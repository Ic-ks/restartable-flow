<!--suppress HtmlDeprecatedAttribute -->
<p align="center">
    <img src="restartable-flow.jpg" alt="title image">
</p>

# Restartable StateFlows (in Compose) #

If `StateFlows<T>`, `SharingStarted.WhileSubscribed(4711)`, and `ViewModels` are your daily bread,
feel free to jump straight to the <a href="#intro">restart implementation</a> below and skip the intro 🧐
If you're a code enthusiast who prefers to skip the written clutter, open the minimal working example on
<a href="https://github.com/Ic-ks/restartable-flow">GitHub</a>.

### Intro ###

Compose screens often display asynchronously loaded data provided as `StateFlow<ScreenState>` by a `ViewModel`.
It's inevitable that exceptions might occur during data retrieval, prompting the question of how to recover from errors.
While a user-triggered retry provides a simple solution, implementing it with `Flow.retryWhen {...}` becomes less satisfying, especially when dealing with it for every screen.
This dissatisfaction grows further when you need to handle a second case: a user-triggered refresh without an error.
If you're tired of writing boilerplate code repeatedly, you've come to the right place.
Solving this problem requires only a few lines of code, but first, let's explore some minimal working example code to gain a better understanding of the issue.

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
**Currently, there isn't a built-in mechanism for a user-triggered restart of the flow in both cases: in the event of fetching failures or when the user wishes to refresh the screen after a while.**

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

Examining the source code of `SharingStarted.WhileSubscribed(5000)`, we find that the logic is implemented in the following class:

```kotlin
private class StartedWhileSubscribed: SharingStarted {
    // ...
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
    }
    //...
}
```

So the `emit(SharingCommand.STOP_AND_RESET_REPLAY_CACHE)` stops the flow and discards the current state.
Unfortunately, there is no public access to trigger this action manually.
But wait, we can implement our own `SharingStarted` — it is a public interface.
Even better would be an extension for all existing `SharingStarted` implementations (`StartedEagerly`, `StartedLazy`, and `StartedWhileSubscribed`).
Alright, let's define our extension as an interface that can be used as wrapper for the existing `SharingStarted` implementations.

### Make SharingStarted restartable 🚂🌊🌊🌊🌊 <a id='intro'></a> ###

```kotlin
// SharingRestartable.kt

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

To leverage our new `private` implementation, we need some syntactic sugar in the form of an extension function:

```kotlin
// SharingRestartable.kt

fun SharingStarted.makeRestartable(): SharingRestartable {
    return SharingRestartableImpl(this)
}
```

Now, we can define our flow and access our new restart mechanism:

```kotlin
class ProductScreenViewModel : ViewModel() {
    private val restarter = SharingStarted.WhileSubscribed(5000).makeRestartable()
    
    val productStream = flow {
        //...
    }.catch {
        emit(ProductScreenState.Error)
    }.stateIn(
        started = restarter,
        //...
    )

    fun restart() = restarter.restart()
    
    //...
}
```

### Finally, Reduce the Boilerplate Even More by Providing a Restartable State Flow ###

For this purpose, we need an interface that extends the existing `StateFlow<T>` by adding a restart function:

```kotlin
// RestartableStateFlow.kt

interface RestartableStateFlow<out T> : StateFlow<T> {
    fun restart()
}

```

Then we can utilize our `SharingRestartable` implementation in conjunction with a custom `stateIn()` extension function:

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
This leads to our final implementation. 

```kotlin
class ProductScreenViewModel : ViewModel() {
    val productStream = flow {
        emit(ProductScreenState.Loading)
        emit(ProductScreenState.Description(fetchDescription()))
    }.catch {
        emit(ProductScreenState.Error)
    }.restartableStateIn( // <<< Switching to restartableStateIn() is everything we have to change
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ProductScreenState.Loading
    )

    private suspend fun fetchDescription(): String {
        // ...
    }
}
```

Even the `viewModel.restart()` function was removed, because `RestartableStateFlow.restart()` function is already exposed to the UI layer:

```kotlin
@Composable
private fun ScreenContent(viewModel: ProductScreenViewModel) {
    val state = viewModel.productStream.collectAsState()
    when (val stateValue = state.value) {
        ProductScreenState.Loading -> LoadingPanel()

        is ProductScreenState.Description -> DescriptionPanel(
            description = stateValue.description,
            onClickRefresh = viewModel.productStream::restart // 🤩
        )

        ProductScreenState.Error -> ErrorPanel(
            onClickRetry = viewModel.productStream::restart   // 🤩
        )
    }
}
```

Your feedback is highly appreciated, especially if there are any flaws.