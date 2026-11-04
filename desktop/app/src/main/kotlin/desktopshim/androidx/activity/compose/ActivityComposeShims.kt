package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

fun ComponentActivity.setContent(
    parent: Any? = null,
    content: @Composable () -> Unit,
) {
    this.composeContent = content
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {}

class ManagedActivityResultLauncher<I, O>(
    private val contract: ActivityResultContract<I, O>,
    private val onResult: (O) -> Unit,
) : ActivityResultLauncher<I>() {
    override fun launch(input: I) {
        contract.executeDesktop(input, onResult)
    }
}

@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> {
    return remember(contract, onResult) {
        ManagedActivityResultLauncher(contract, onResult)
    }
}

