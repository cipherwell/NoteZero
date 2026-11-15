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
import com.cipherwell.notezero.R
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
    /** No biometric hardware, not enabled for apps, or the Keystore key was invalidated. */
    UNAVAILABLE,

    /** Hardware exists but no fingerprint/face is enrolled on this device. */
    NOT_ENROLLED,

    /** Hardware temporarily busy/unavailable (e.g. sensor in use) — retrying works. */
    TEMPORARILY_UNAVAILABLE,

    /** Too many attempts; the user must fall back to the password. */
    LOCKOUT,

    /** Lockout that requires unlocking the device first. */
    LOCKOUT_PERMANENT,

    /** The user dismissed the prompt (negative button / system cancel). Not worth showing. */
    CANCELLED,

    /** Timeout, unable-to-process, vendor error or anything else: authentication did not
     *  complete even though the prompt opened. */
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
            title = activity.getString(R.string.unlock_title),
            subtitle = activity.getString(R.string.common_biometric_unlock_subtitle),
            negativeButton = activity.getString(R.string.common_biometric_use_password),
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
            title = activity.getString(R.string.common_biometric_enable_title),
            subtitle = activity.getString(R.string.common_biometric_enable_subtitle),
            negativeButton = activity.getString(R.string.common_cancel),
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

    private companion object {
        /** `BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON` (stable platform code). */
        const val BIOMETRIC_ERROR_NEGATIVE_BUTTON_CODE = 12
    }

    private fun mapError(errorCode: Int): BiometricPromptError = when (errorCode) {
        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
        BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
        // The platform constant for the negative button (12) is not exposed on every SDK
        // level this app compiles against; the value is stable in the platform API contract.
        BIOMETRIC_ERROR_NEGATIVE_BUTTON_CODE,
        -> BiometricPromptError.CANCELLED
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT -> BiometricPromptError.LOCKOUT
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> BiometricPromptError.LOCKOUT_PERMANENT
        // Hardware present but nothing enrolled on the device.
        BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS -> BiometricPromptError.NOT_ENROLLED
        // No hardware / not usable by apps / key invalidated.
        BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
        BiometricPrompt.BIOMETRIC_ERROR_NOT_ENABLED_FOR_APPS,
        -> BiometricPromptError.UNAVAILABLE
        // Sensor busy: the prompt opened but the TEE/biometric service could not serve it.
        BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricPromptError.TEMPORARILY_UNAVAILABLE
        // Timeout / unable-to-process / vendor / no-space: the user gets a retry prompt.
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
