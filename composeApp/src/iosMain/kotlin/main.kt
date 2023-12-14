import androidx.compose.ui.window.ComposeUIViewController
import org.company.sample.App
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController { App() }
