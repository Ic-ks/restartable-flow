import androidx.compose.material.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application


fun main() = application {
    val viewModel = ProductScreenViewModel()
    Window(onCloseRequest = ::exitApplication) {
        MaterialTheme {
            ProductScreen(viewModel)
        }
    }
}
