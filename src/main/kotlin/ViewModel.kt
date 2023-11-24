import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

abstract class ViewModel {
    protected val viewModelScope = CoroutineScope(SupervisorJob())

    fun  onClear() {
        viewModelScope.cancel()
    }
}

@Composable
inline fun <reified T: ViewModel> rememberViewModel(factory: () -> T): T {
    val vm = factory()
    DisposableEffect(T::class) {
        onDispose { vm.onClear() }
    }
    return vm
}