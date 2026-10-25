package app.clippr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.clippr.core.ui.theme.ClipprTheme
import app.clippr.feature.editor.AppRestart

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as ClipprApplication).container
        // Set when Clippr restarted itself from the editor (to free stuck video decoders).
        val reopenProject = if (savedInstanceState == null) intent.getStringExtra(AppRestart.EXTRA_PROJECT_ID) else null
        setContent {
            ClipprTheme {
                ClipprNavHost(container, reopenProject)
            }
        }
    }
}
