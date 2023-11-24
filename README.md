# Restartable StateFlows (in Compose) #

If `StateFlows<T>`, `SharingStarted.WhileSubscribed(1337)`, and `ViewModels` are your daily bread,
feel free to jump straight to the <a href="#intro">restart implementation</a> below and skip the intro 🧐
If you're a code enthusiast who prefers to skip the clutter, open the minimal working example on
<a href="https://github.com/Ic-ks/restartable-flow">GitHub</a>.

### Intro ###

Compose screens often display asynchronously loaded data provided as `StateFlow<ScreenState>` by a `ViewModel`.
It's inevitable that exceptions might occur during data retrieval, prompting the question of how to recover from errors.
A user-triggered retry provides a simple solution, but implementing it becomes less satisfying when faced with a cumbersome `flow.retryWhen {}`.
Now, let's dive into some example code.

#### Defining Screen States ####

To model clear states for the screen, we require three: a loading state, an error state, and, finally, a state containing the fetched data:

```kotlin
sealed interface ProductScreenState {
    object Loading: ProductScreenState
    object Error: ProductScreenState
    @Immutable
    data class Description(val description: String): ProductScreenState
}
```

#### Implementing the ViewModel ####

Our `ViewModel` consists of a `StateFlow` that reflects the fetching process with the help of the three states.

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
**Currently, there is no out-of-the-box way to restart the flow if fetching fails.**

<!--suppress HtmlDeprecatedAttribute -->
<p align="center">
    <img src="angry.svg" alt="Angry Meme">
</p>

#### Wait, We Missed an Important Detail ####

Let's take a closer look at this line: `started = SharingStarted.WhileSubscribed(5000)`.
This line instructs the StateFlow to initiate state emission as soon as there is at least one subscriber.
Moreover, it plays a crucial role in **restarting** 🎉 the flow when there are no subscriptions and a new subscription occurs.
This happens when the time between zero subscriptions and the arrival of a new subscription exceeds 5 seconds. 
Quite intriguing, isn't it...

<!--suppress HtmlDeprecatedAttribute -->
<p align="center">
    <img src="thinking.svg" alt="Angry Meme">
</p>

Examining the source code of `SharingStarted.WhileSubscribed(5000)`, we find that the logic is implemented in the following function:

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

So the `emit(SharingCommand.STOP_AND_RESET_REPLAY_CACHE)` stops the flow and discards the current state.
Unfortunately, there is no public access to trigger this action manually.
But wait, we can implement our own `SharingStarted` — it is a public interface.
Even better would be an extension for all existing `SharingStarted` implementations (`StartedEagerly`, `StartedLazy`, and `StartedWhileSubscribed`).
Alright, let's define our extension as an interface that can be used as wrapper for the existing `SharingStarted` implementations.

### Make SharingStarted restartable 🚂🌊🌊🌊🌊 <a id='intro'></a> ###

```kotlin
interface SharingRestartable: SharingStarted {
    fun restart()
}
```

Now, we require an implementation of our interface.
The only task is to emit a `SharingCommand.STOP_AND_RESET_REPLAY_CACHE` and a `SharingCommand.START` command as soon as our defined `restart()` is called.
Everything else should be handled by the existing `SharingStarted` instance.
This can be achieved by merging a new `MutableSharedFlow<SharingCommand>` which is responsible to trigger the restart and the existing flow of the wrapped instance:


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

To leverage our new implementation, we need some syntactic sugar in the form of an extension function:

```kotlin
// SharingRestartable.kt

fun SharingStarted.makeRestartable(): SharingRestartable {
    return SharingRestartableImpl(this)
}
```

Now, we can define our flow and access the built-in restart mechanism:

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

### Finally, Reduce the Boilerplate Even More by Providing a Restartable State Flow ###

For this purpose, we need an interface that extends the existing `StateFlow<T>` by adding a restart function:

```kotlin
// RestartableStateFlow.kt

interface RestartableStateFlow<out T> : StateFlow<T> {
    fun restart()
}

```

Then we can utilize our `SharingRestartable` implementation in conjunction with our custom `stateIn()` extension function:

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

Your feedback is highly appreciated, especially if there are any flaws.