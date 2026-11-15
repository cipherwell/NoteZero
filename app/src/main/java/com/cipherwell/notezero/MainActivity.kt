package com.cipherwell.notezero

import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    /**
     * Quick-capture request from the static launcher shortcut ("New note"). It stays pending
     * across the unlock screen: AppRoot honours it only once the vault is unlocked and the
     * notes surface is visible (the note is always created through the encrypted repository).
     */
    private var newNoteRequest by mutableStateOf(false)

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

        newNoteRequest = intent?.action == ACTION_SHORTCUT_NEW_NOTE
        setContent {
            // AppRoot owns the persisted light/dark/system theme.
            AppRoot(
                repository = repository,
                newNoteRequest = newNoteRequest,
                onNewNoteRequestConsumed = { newNoteRequest = false },
            )
        }
    }

    /**
     * Real user input resets the repository's idle auto-lock timer. Only ACTION_DOWN is
     * forwarded: it marks the start of an interaction and avoids per-move noise.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_SHORTCUT_NEW_NOTE) newNoteRequest = true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            repository.registerInteraction()
        }
        return super.dispatchTouchEvent(ev)
    }
}

private const val ACTION_SHORTCUT_NEW_NOTE = "com.cipherwell.notezero.SHORTCUT_NEW_NOTE"
