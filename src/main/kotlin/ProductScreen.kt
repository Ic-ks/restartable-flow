import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Preview
@Composable
fun ProductScreen(
    viewModel: ProductScreenViewModel
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        ScreenContent(viewModel)
    }
}

@Composable
private fun ScreenContent(viewModel: ProductScreenViewModel) {
    val state = viewModel.productStream.collectAsState()
    when (val stateValue = state.value) {
        ProductScreenState.Loading -> LoadingPanel()
        is ProductScreenState.Description -> DescriptionPanel(stateValue.description)
        ProductScreenState.Error -> ErrorPanel(onClickRestart = viewModel.productStream::restart)
    }
}

@Composable
private fun ErrorPanel(onClickRestart: () -> Unit) {
    Button(onClickRestart) {
        Text("Restart")
    }
}

@Composable
private fun DescriptionPanel(description: String) {
    Text(description)
}

@Composable
private fun LoadingPanel() {
    CircularProgressIndicator()
}