package com.cipherwell.notezero.ui.locks

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Presence-only biometric prompt used to re-reveal a locked note/folder.
 *
 * Deliberately **no CryptoObject**: content is already sealed under the vault VMK and this
 * prompt only gates UI visibility (decision #15). It must never be mistaken for a key
 * operation — no Keystore cipher, no VMK, no key material is passed here.
 *
 * [authenticate] returns false when no prompt could be shown (API < 28, no enrolment,
 * hardware missing, or any platform failure); the caller then falls back to the
 * master-password dialog, so devices/emulators without biometrics never crash.
 */
fun interface LockBiometricPrompt {

    /**
     * Shows the prompt when possible and invokes [onResult] exactly once with the presence
     * outcome. Returns false when the prompt is unavailable, in which case [onResult] is
     * never called.
     */
    fun authenticate(onResult: (Boolean) -> Unit): Boolean

    companion object {
        /** No biometric capability: always fall back to the password dialog. */
        val Unavailable: LockBiometricPrompt = LockBiometricPrompt { _ -> false }
    }
}

/** Platform implementation (API 28+), presence-only. */
class AndroidLockBiometricPrompt(private val activity: Activity) : LockBiometricPrompt {

    override fun authenticate(onResult: (Boolean) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val usable = try {
            BiometricManager.from(activity)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: Throwable) {
            false
        }
        if (!usable) return false
        return try {
            showPresencePrompt(onResult)
            true
        } catch (_: Throwable) {
            // Prompt unavailable/invalidated: caller falls back to the password dialog.
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun showPresencePrompt(onResult: (Boolean) -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        // The platform callback can fire more than once (error then cancel); the caller
        // must see exactly one outcome per prompt.
        val finished = AtomicBoolean(false)
        fun finishOnce(value: Boolean) {
            if (finished.compareAndSet(false, true)) onResult(value)
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult,
            ) {
                finishOnce(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                finishOnce(false)
            }
        }
        // Presence-only: authenticate without a CryptoObject (no key is unlocked here).
        BiometricPrompt.Builder(activity)
            .setTitle("Reveal locked content")
            .setSubtitle("Confirm it's you to show this locked item")
            .setNegativeButton("Use password", executor) { _, _ -> finishOnce(false) }
            .build()
            .authenticate(CancellationSignal(), executor, callback)
    }
}

/** Device prompt for Compose; [LockBiometricPrompt.Unavailable] outside an Activity. */
@Composable
fun rememberLockBiometricPrompt(): LockBiometricPrompt {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivityOrNull() }
    return remember(activity) {
        activity?.let { AndroidLockBiometricPrompt(it) } ?: LockBiometricPrompt.Unavailable
    }
}

private fun Context.findActivityOrNull(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
