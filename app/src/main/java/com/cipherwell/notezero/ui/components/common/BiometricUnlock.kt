package com.cipherwell.notezero.ui.components.common

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
import javax.crypto.Cipher

/**
 * Device capability check, injected into [com.cipherwell.notezero.ui.VaultViewModel] so
 * the UI state machine stays unit-testable (Robolectric/CI has no biometric hardware).
 */
fun interface BiometricAvailability {
    fun canAuthenticate(): Boolean
}

/**
 * Real implementation backed by androidx.biometric's [BiometricManager].
 *
 * The prompt itself is the platform [BiometricPrompt] (API 28+): the seeded Activity is
 * a plain ComponentActivity and androidx.biometric's prompt requires a FragmentActivity,
 * which would pull a legacy fragment runtime into an otherwise Compose-only app. On
 * API 24-27 the CTA is simply not offered (password unlock always works); the Keystore
 * wrapped key and two-phase enrollment contract are unchanged.
 */
class AndroidBiometricAvailability(private val context: Context) : BiometricAvailability {
    override fun canAuthenticate(): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    } catch (_: Throwable) {
        false
    }
}

/** Outcome of a prompt, mapped to non-secret user-facing copy in the ViewModel. */
enum class BiometricPromptError {
    /** No hardware/enrolment, or the Keystore key was invalidated. */
    UNAVAILABLE,

    /** Too many attempts; the user must fall back to the password. */
    LOCKOUT,

    /** Lockout that requires unlocking the device first. */
    LOCKOUT_PERMANENT,

    /** The user dismissed the prompt. Not an error worth showing. */
    CANCELLED,

    /** Anything else. */
    FAILED,
}

/**
 * Thin wrapper over the platform biometric prompt. The caller passes the Keystore
 * cipher prepared by the repository ([com.cipherwell.notezero.data.NoteRepository]) so
 * the VMK is only released by the TEE after a successful auth.
 */
class BiometricPromptLauncher(private val activity: Activity) {

    fun authenticateDecrypt(
        cipher: Cipher,
        onAuthenticated: () -> Unit,
        onError: (BiometricPromptError) -> Unit,
    ) {
        showPrompt(
            cipher = cipher,
            title = "Unlock NoteZero",
            subtitle = "Confirm it's you to unlock your vault",
            negativeButton = "Use password",
            onAuthenticated = onAuthenticated,
            onError = onError,
        )
    }

    fun authenticateEnroll(
        cipher: Cipher,
        onAuthenticated: () -> Unit,
        onError: (BiometricPromptError) -> Unit,
    ) {
        showPrompt(
            cipher = cipher,
            title = "Enable biometric unlock",
            subtitle = "Confirm it's you to protect your vault key",
            negativeButton = "Cancel",
            onAuthenticated = onAuthenticated,
            onError = onError,
        )
    }

    private fun showPrompt(
        cipher: Cipher,
        title: String,
        subtitle: String,
        negativeButton: String,
        onAuthenticated: () -> Unit,
        onError: (BiometricPromptError) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onError(BiometricPromptError.UNAVAILABLE)
            return
        }
        try {
            showPlatformPrompt(
                cipher = cipher,
                title = title,
                subtitle = subtitle,
                negativeButton = negativeButton,
                onAuthenticated = onAuthenticated,
                onError = onError,
            )
        } catch (_: Throwable) {
            // Cipher invalidated / prompt unavailable: fall back to the password path.
            onError(BiometricPromptError.UNAVAILABLE)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun showPlatformPrompt(
        cipher: Cipher,
        title: String,
        subtitle: String,
        negativeButton: String,
        onAuthenticated: () -> Unit,
        onError: (BiometricPromptError) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        // The platform callback can fire more than once (error then cancel); the VM must
        // see exactly one outcome per prompt.
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finishOnce(action: () -> Unit) {
            if (finished.compareAndSet(false, true)) action()
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult,
            ) {
                finishOnce(onAuthenticated)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                finishOnce { onError(mapError(errorCode)) }
            }
        }
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButton(negativeButton, executor) { _, _ ->
                finishOnce { onError(BiometricPromptError.CANCELLED) }
            }
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            executor,
            callback,
        )
    }

    private fun mapError(errorCode: Int): BiometricPromptError = when (errorCode) {
        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
        BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
        -> BiometricPromptError.CANCELLED
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT -> BiometricPromptError.LOCKOUT
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> BiometricPromptError.LOCKOUT_PERMANENT
        BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS,
        BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
        BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
        BiometricPrompt.BIOMETRIC_ERROR_NOT_ENABLED_FOR_APPS,
        -> BiometricPromptError.UNAVAILABLE
        else -> BiometricPromptError.FAILED
    }
}

@Composable
fun rememberBiometricAvailability(): BiometricAvailability {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) { AndroidBiometricAvailability(appContext) }
}

/** Returns null when the current context is not hosted by an Activity (e.g. previews). */
@Composable
fun rememberBiometricPromptLauncher(): BiometricPromptLauncher? {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    return remember(activity) { activity?.let(::BiometricPromptLauncher) }
}

internal fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
