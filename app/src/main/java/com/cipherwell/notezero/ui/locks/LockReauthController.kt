package com.cipherwell.notezero.ui.locks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.cipherwell.notezero.data.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a re-authentication request is for. Notes and folders share the same id set. */
enum class LockTargetKind { NOTE, FOLDER }

/** One locked record the user asked to reveal (or un-lock) for this session. */
data class LockedTarget(val id: String, val kind: LockTargetKind)

/**
 * State machine for revealing locked content (decision #15).
 *
 * Flow: [request] tries the presence-only [LockBiometricPrompt] when one is available; on
 * cancel/failure (or when no prompt exists) it shows the master-password dialog. A correct
 * password — checked by [NoteRepository.verifyMasterPassword] without touching the session —
 * calls [NoteRepository.markLockVerified] and only then runs the caller's follow-up action
 * (open the note/folder, remove the lock, …). Cancel/failure closes nothing at the data
 * layer: the content stays masked.
 *
 * Security notes: the password draft lives only in this in-memory state, is handed to the
 * repository as a CharArray (which wipes it on every path) and is never logged or persisted.
 * The controller never caches note/folder content.
 */
class LockReauthController internal constructor(
    private val repository: NoteRepository,
    private val biometrics: LockBiometricPrompt,
    private val scope: CoroutineScope,
) {

    /** Immutable snapshot of the re-auth UI. */
    data class State(
        val target: LockedTarget? = null,
        val dialogVisible: Boolean = false,
        val password: String = "",
        val verifying: Boolean = false,
        val biometricInFlight: Boolean = false,
        /** Consecutive failed password attempts in this dialog (resets when it closes). */
        val failures: Int = 0,
        /** True once the repository rate limiter's 30 s cooldown has been reached. */
        val cooldown: Boolean = false,
        val error: String? = null,
    )

    var state by mutableStateOf(State())
        private set

    private var afterReveal: (() -> Unit)? = null

    /**
     * Starts a re-auth for [target]. [afterReveal] runs after the id has been marked
     * verified, so it can safely open the now-revealed note/folder or remove its lock.
     */
    fun request(target: LockedTarget, afterReveal: () -> Unit = {}) {
        if (target.id.isBlank()) return
        if (state.target != null) return // One re-auth at a time.
        if (target.id in repository.verifiedLockedIds.value) {
            afterReveal()
            return
        }
        this.afterReveal = afterReveal
        state = State(target = target, biometricInFlight = true)
        val launched = try {
            biometrics.authenticate { success -> onBiometricResult(target, success) }
        } catch (_: Throwable) {
            false
        }
        if (!launched) {
            // Graceful degradation: no biometric prompt -> master-password dialog.
            state = state.copy(biometricInFlight = false, dialogVisible = true)
        }
    }

    fun updatePassword(value: String) {
        if (state.verifying) return
        state = state.copy(password = value, error = null)
    }

    /** Verifies the drafted master password against the repository. */
    fun submitPassword() {
        val target = state.target ?: return
        if (state.verifying || state.cooldown) return
        val password = state.password
        if (password.isBlank()) {
            state = state.copy(error = BLANK_PASSWORD_MESSAGE)
            return
        }
        state = state.copy(verifying = true, error = null)
        scope.launch {
            val chars = password.toCharArray()
            val verified = try {
                repository.verifyMasterPassword(chars)
            } finally {
                chars.fill('\u0000')
            }
            if (state.target?.id != target.id) return@launch
            if (verified) {
                reveal(target)
            } else {
                val failures = state.failures + 1
                val cooldown = failures >= MAX_FAILURES
                state = state.copy(
                    verifying = false,
                    password = "",
                    failures = failures,
                    cooldown = cooldown,
                    error = if (cooldown) COOLDOWN_MESSAGE else WRONG_PASSWORD_MESSAGE,
                )
            }
        }
    }

    /** Dismisses the dialog without revealing anything: content stays masked. */
    fun cancel() {
        if (state.verifying) return
        afterReveal = null
        state = State()
    }

    private fun onBiometricResult(target: LockedTarget, success: Boolean) {
        if (state.target?.id != target.id) return
        if (success) {
            reveal(target)
        } else {
            state = state.copy(
                biometricInFlight = false,
                dialogVisible = true,
                password = "",
                error = BIOMETRIC_CANCELLED_MESSAGE,
            )
        }
    }

    private fun reveal(target: LockedTarget) {
        repository.markLockVerified(target.id)
        val followUp = afterReveal
        afterReveal = null
        state = State()
        followUp?.invoke()
    }

    companion object {
        /** Consecutive password failures before the UI reports the rate-limit cooldown. */
        const val MAX_FAILURES: Int = 5

        const val BLANK_PASSWORD_MESSAGE: String = "Enter your master password."
        const val WRONG_PASSWORD_MESSAGE: String = "Incorrect master password."
        const val COOLDOWN_MESSAGE: String = "Too many attempts. Try again in 30 seconds."
        const val BIOMETRIC_CANCELLED_MESSAGE: String =
            "Biometric check was cancelled. Enter your master password."
    }
}

/**
 * Remembers a [LockReauthController] for a screen. [biometrics] is injectable so Robolectric
 * tests never touch the platform prompt; production passes null and gets the device prompt
 * (or [LockBiometricPrompt.Unavailable] when there is no Activity/biometric hardware).
 */
@Composable
fun rememberLockReauthController(
    repository: NoteRepository,
    biometrics: LockBiometricPrompt? = null,
): LockReauthController {
    val scope = rememberCoroutineScope()
    val systemPrompt = rememberLockBiometricPrompt()
    val prompt = biometrics ?: systemPrompt
    return remember(repository, prompt, scope) {
        LockReauthController(repository, prompt, scope)
    }
}
