package com.cipherwell.notezero

import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.ui.AppRoot

/**
 * Single-activity host for the Compose app.
 *
 * FLAG_SECURE is applied unconditionally in production builds so screenshots and
 * recents thumbnails show a blank card. `-PallowScreenshots=true` is the only escape
 * hatch and exists solely for automated UI verification.
 *
 * The vault repository is process-scoped ([NoteZeroApp]) so rotation, task restore and
 * recents never drop or duplicate the unlocked vault. Lifecycle is forwarded to the
 * repository so background/screen-off arming and foreground returns are handled by the
 * data layer (which owns the auto-lock timer).
 */
class MainActivity : ComponentActivity() {

    private val repository: NoteRepository
        get() = (application as NoteZeroApp).repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.ALLOW_SCREENSHOTS) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdge()

        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> repository.onAppForegrounded()
                    Lifecycle.Event.ON_STOP -> repository.onAppBackgrounded()
                    else -> Unit
                }
            },
        )

        setContent {
            // AppRoot owns the persisted light/dark/system theme.
            AppRoot(repository = repository)
        }
    }

    /**
     * Real user input resets the repository's idle auto-lock timer. Only ACTION_DOWN is
     * forwarded: it marks the start of an interaction and avoids per-move noise.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            repository.registerInteraction()
        }
        return super.dispatchTouchEvent(ev)
    }
}
