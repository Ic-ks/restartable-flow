# Restartable StateFlows (in Compose) #
If `StateFlows<T>`, `SharingStarted.WhileSubscibed(1337)` and `ViewModels` is your daily bread, 
then jump straight to the <a href="#intro">restart implementation</a> below and skip the intro 🧐 

### Intro ###
Often there are compose screens which display asynchronously loaded data. The data is provided as `StateFlow<ScreenState>` by a `ViewModel`. 
Needless to say that exceptions could be thrown during the data retrieval. This leads to the question of how to recover from errors. 
A simple retry could be the solution. So let's have a look at the Code:

#### Defining screen states ####
We need 3 states, a loading state, an error state and finally a state which contains our fetched data:
```kotlin
sealed interface ProductScreenState {
    object Loading: ProductScreenState
    object Error: ProductScreenState
    @Immutable
    data class Description(val description: String): ProductScreenState
}
```
#### Implementing the ViewModel ####
Our ViewModel consist of a StateFlow which reflects fetching process with the help of the 3 states.  
```kotlin
class ProductScreenViewModel : ViewModel() {
    val productStream = flow {
        emit(ProductScreenState.Loading)
        emit(ProductScreenState.Description(fetchDescription()))
    }.catch {
        emit(ProductScreenState.Error)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ProductScreenState.Loading
    )

    private suspend fun fetchDescription(): String {
        delay(1500)
        require(Math.random() > 0.5) { "Random error" }
        return "My Product Description"
    }
}
```
**Right now, there is no out-of-the-box way to restart the flow if the fetching fails.**
<p align="center">
    <img src="angry.svg" alt="Angry Meme">
</p>

#### Wait, we missed an important detail  ####
Let's have a look at this line: `started = SharingStarted.WhileSubscribed(5000)` .
It tells the StateFlow to start the state emission as soon there is at least one subscriber.
But it is also responsible to **restart** 🎉 the flow as soon there is no subscription left and a new subscription arrives.
Given that time between zero subscriptions and the first new subscription is greater than 5 seconds.
Sounds interesting...
<p align="center">
    <img src="thinking.svg" alt="Angry Meme">
</p>

Let's have a look at the source code of `SharingStarted.WhileSubscribed(5000)` 
There is only one function which implements the described logic:

```kotlin
private class StartedWhileSubscribed: SharingStarted {
    [...]
    override fun command(subscriptionCount: StateFlow<Int>): Flow<SharingCommand> = subscriptionCount
        .transformLatest { count ->
            if (count > 0) {
                emit(SharingCommand.START)
            } else {
                delay(stopTimeout)
                if (replayExpiration > 0) {
                    emit(SharingCommand.STOP)
                    delay(replayExpiration)
                }
                emit(SharingCommand.STOP_AND_RESET_REPLAY_CACHE)
            }
        }
        .dropWhile { it != SharingCommand.START } // don't emit any STOP/RESET_BUFFER to start with, only START
        .distinctUntilChanged()
    [...]
```

So the `emit(SharingCommand.STOP_AND_RESET_REPLAY_CACHE)` seems to stop the flow and to drop the current state. 
Unfortunately, there is no public access to trigger this action manually. 
But wait, we can implement our own `SharingStarted`, it is a public interface. 
Even better would be an extension for all existing `SharingStarted` implementations (`StartedEagerly`, `StartedLazy` and `StartedWhileSubscribed`).
Ok then let's define our extension as interface which can be used to wrap an existing `SharingStarted` implementation.

### Make SharingStarted restartable 🚂🌊🌊🌊🌊 ###
<a id='intro'></a>

```kotlin
interface SharingRestartable: SharingStarted {
    fun restart()
}
```

Ok now we need an implementation of our interface. 
The only thing we have to do, is to emit a `SharingCommand.STOP_AND_RESET_REPLAY_CACHE` and a `SharingCommand.START` command as soon `restart()` is called.
Everything else should be handled by the existing `SharingStarted` instance. 
This can be done by merging our restart flow with the existing flow of the wrapped instance:

```kotlin
// SharingRestartable.kt

private data class SharingRestartableImpl(
    private val sharingStarted: SharingStarted,
): SharingRestartable {

    private val restartFlow = MutableSharedFlow<SharingCommand>(extraBufferCapacity = 2)

    override fun command(subscriptionCount: StateFlow<Int>): Flow<SharingCommand> {
        return merge(restartFlow, sharingStarted.command(subscriptionCount))
    }

    override fun restart() {
        restartFlow.tryEmit(SharingCommand.STOP_AND_RESET_REPLAY_CACHE)
        restartFlow.tryEmit(SharingCommand.START)
    }
}
```

To use our new implementation, we need some syntactic sugar in the form of an extension function: 

```kotlin
// SharingRestartable.kt

fun SharingStarted.makeRestartable(): SharingRestartable {
    return SharingRestartableImpl(this)
}
```

Now we can define our flow and can access the built-in restart mechanism:

```kotlin
    private val restarter = SharingStarted.WhileSubscribed(5000).makeRestartable()
    val productStream = flow {
        [..]
    }.stateIn(
        started = restarter,
        [..]
    )
    
    fun restart() = restarter.restart()
    
```
### Finally, reduce the boilerplate even more by providing a restartable state flow ###

Therefore, we need an interface which extends the existing `StateFlow<T>` by a restart function:
```kotlin
// RestartableStateFlow.kt

interface RestartableStateFlow<out T> : StateFlow<T> {
    fun restart()
}
```
Then we can use our `SharingRestartable` implementation in combination our own `stateIn()` extension function:
```kotlin
// RestartableStateFlow.kt

fun <T> Flow<T>.restartableStateIn(
    scope: CoroutineScope,
    started: SharingStarted,
    initialValue: T
): RestartableStateFlow<T> {
    val sharingRestartable = started.makeRestartable()
    val stateFlow = stateIn(scope, sharingRestartable, initialValue)
    return object : RestartableStateFlow<T>, StateFlow<T> by stateFlow {
        override fun restart() = sharingRestartable.restart()
    }
}
```
This leads to our final implementation:

```kotlin
    val productStream = flow {
        [..]
    }.restartableStateIn(
        started = SharingStarted.WhileSubscribed(5000),
        [..]
    )
    
    fun restart() = productStream.restart()
```

Even the `restart()` function of the ViewModel can be removed, because the flows restart function is already exposed to the UI layer:

```kotlin
@Composable
private fun ScreenContent(viewModel: ProductScreenViewModel) {
    val state = viewModel.productStream.collectAsState()
    when (val stateValue = state.value) {
        ProductScreenState.Loading -> LoadingPanel()
        is ProductScreenState.Description -> DescriptionPanel(stateValue.description)
        ProductScreenState.Error -> ErrorPanel(
            onClickRestart = viewModel.productStream::restart // There we go :)
        ) 
    }
}
```