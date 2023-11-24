import androidx.compose.runtime.Immutable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

class ProductScreenViewModel : ViewModel() {
    val productStream = flow {
        emit(ProductScreenState.Loading)
        emit(ProductScreenState.Description(fetchDescription()))
    }.catch {
        emit(ProductScreenState.Error)
    }.restartableStateIn(
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

sealed interface ProductScreenState {
    object Loading: ProductScreenState
    object Error: ProductScreenState
    @Immutable
    data class Description(val description: String): ProductScreenState
}
