import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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

        is ProductScreenState.Description -> DescriptionPanel(
            description = stateValue.description,
            onClickRefresh = viewModel.productStream::restart // 🤩
        )

        ProductScreenState.Error -> ErrorPanel(
            onClickRetry = viewModel.productStream::restart   // 🤩
        )
    }
}

@Composable
private fun ErrorPanel(onClickRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Error!",
            color = MaterialTheme.colors.error
        )
        Button(onClickRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun DescriptionPanel(
    description: String,
    onClickRefresh: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClickRefresh,
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh"
            )
        }
        Text(description)
    }
}

@Composable
private fun LoadingPanel() {
    CircularProgressIndicator()
}