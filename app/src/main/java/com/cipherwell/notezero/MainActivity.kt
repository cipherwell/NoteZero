package com.cipherwell.notezero

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.cipherwell.notezero.ui.theme.NoteZeroTheme

/**
 * Single-activity host. The real navigation graph (setup -> unlock -> notes/editor)
 * lives in ui/ once the security core is wired; this shell exists so the module
 * builds from commit #1.
 *
 * FLAG_SECURE is applied unconditionally in production builds so screenshots and
 * recents thumbnails show a blank card. `-PallowScreenshots=true` is the only escape
 * hatch and exists solely for automated UI verification.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.ALLOW_SCREENSHOTS) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdge()
        setContent {
            NoteZeroTheme {
                BootPlaceholder()
            }
        }
    }
}

@Composable
private fun BootPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "NoteZero",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
