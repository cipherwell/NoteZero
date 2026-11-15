package com.cipherwell.notezero.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cipherwell.notezero.crypto.BiometricDecrypt
import com.cipherwell.notezero.crypto.BiometricEnroll
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.UnlockResult
import com.cipherwell.notezero.data.VaultInfo
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.ThemeMode
import com.cipherwell.notezero.ui.components.common.BiometricAvailability
import com.cipherwell.notezero.ui.components.common.BiometricPromptError
import com.cipherwell.notezero.ui.screens.setup.PasswordPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------
// UI state models. All of them are free of secret material except
// [SetupUiState.phrase] / [SettingsUiState.rotatedPhrase], which hold the freshly
// generated recovery words only until the user acknowledges them — and never past a
// lock: the status collector drops both on every session boundary (decision #12,
// audit findings F2/F5).
// ---------------------------------------------------------------------------------

/** Combined vault/session state used by the app shell and settings. */
data class VaultUiState(
    val status: VaultStatus = VaultStatus.SETUP_REQUIRED,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val autoLockMinutes: Int = VaultViewModel.DEFAULT_AUTO_LOCK_MINUTES,
    val vaultInfo: VaultInfo? = null,
    val biometricAvailable: Boolean = false,
    val biometricEnabled: Boolean = false,
    /** A wrapped VMK exists for biometric unlock (known even while the vault is locked). */
    val biometricEnrolled: Boolean = false,
)

enum class SetupStep { PASSWORD, PHRASE, CONFIRM, DONE }

data class SetupUiState(
    val step: SetupStep = SetupStep.PASSWORD,
    val isWorking: Boolean = false,
    /** Last password-rule violation; rendered as a non-secret message. */
    val passwordRule: PasswordPolicy.Rule? = null,
    /** Generic storage/crypto failure — never carries an exception message. */
    val failed: Boolean = false,
    /** 12 recovery words, shown once during the PHRASE/CONFIRM steps. */
    val phrase: List<String>? = null,
    /** 0-based word positions asked back in the confirmation gate. */
    val confirmIndices: List<Int> = DEFAULT_CONFIRM_INDICES,
    val confirmMismatch: Boolean = false,
) {
    companion object {
        val DEFAULT_CONFIRM_INDICES = listOf(2, 6, 10)
    }
}

/** Non-secret unlock failure reasons. */
enum class UnlockFailure {
    WRONG_PASSWORD,
    CORRUPT_VAULT,
    BIOMETRIC_FAILED,
    /** Prompt opened but authentication did not complete (timeout/vendor/unable). */
    BIOMETRIC_TRY_AGAIN,
    BIOMETRIC_UNAVAILABLE,
    /** Hardware exists but the device has no fingerprint/face enrolled. */
    BIOMETRIC_NOT_ENROLLED,
    BIOMETRIC_LOCKOUT,
}

data class UnlockUiState(
    val isWorking: Boolean = false,
    val failure: UnlockFailure? = null,
    val failedAttempts: Int = 0,
    /** > 0 while the local (UI-level) retry cooldown is counting down. */
    val lockoutSecondsLeft: Int = 0,
) {
    val lockoutActive: Boolean get() = lockoutSecondsLeft > 0
}

enum class RecoveryStep { PHRASE, NEW_PASSWORD }

enum class RecoveryFailure { WRONG_PHRASE, CORRUPT_VAULT, FAILED }

data class RecoveryUiState(
    val step: RecoveryStep = RecoveryStep.PHRASE,
    val isWorking: Boolean = false,
    val invalidPhrase: Boolean = false,
    val failure: RecoveryFailure? = null,
    val passwordRule: PasswordPolicy.Rule? = null,
)

enum class SettingsMessage {
    PASSWORD_CHANGED,
    PASSWORD_WRONG,
    PASSWORD_REJECTED,
    RECOVERY_ROTATED,
    RECOVERY_ROTATE_WRONG_PASSWORD,
    RECOVERY_SAVED,
    BIOMETRIC_ENABLED,
    BIOMETRIC_DISABLED,
    BIOMETRIC_UNAVAILABLE,
    BIOMETRIC_NOT_ENROLLED,
    BIOMETRIC_TRY_AGAIN,
    BIOMETRIC_FAILED,
    AUTO_LOCK_UPDATED,
    THEME_UPDATED,
    LOCKED,
    FAILED,
}

data class SettingsUiState(
    val isWorking: Boolean = false,
    val message: SettingsMessage? = null,
    val isError: Boolean = false,
    /**
     * New recovery words after an explicit rotation; cleared as soon as they are
     * acknowledged **and** on every lock/background, so they are never re-shown from a
     * previous session without a fresh rotate action (audit F5).
     */
    val rotatedPhrase: List<String>? = null,
) {
    fun messageFor(m: SettingsMessage): String = when (m) {
        SettingsMessage.PASSWORD_CHANGED -> "Master password changed."
        SettingsMessage.PASSWORD_WRONG -> "The current password is incorrect."
        SettingsMessage.PASSWORD_REJECTED -> "The new password does not meet the requirements."
        SettingsMessage.RECOVERY_ROTATED -> "Recovery kit rotated. Write down the new phrase."
        SettingsMessage.RECOVERY_ROTATE_WRONG_PASSWORD -> "The current password is incorrect."
        SettingsMessage.RECOVERY_SAVED -> "New recovery phrase confirmed."
        SettingsMessage.BIOMETRIC_ENABLED -> "Biometric unlock enabled."
        SettingsMessage.BIOMETRIC_DISABLED -> "Biometric unlock disabled."
        SettingsMessage.BIOMETRIC_UNAVAILABLE -> "Biometrics are not available on this device."
        SettingsMessage.BIOMETRIC_NOT_ENROLLED ->
            "No fingerprint or face enrolled. Add biometrics in system settings."
        SettingsMessage.BIOMETRIC_TRY_AGAIN ->
            "Biometric authentication didn't complete. Try again."
        SettingsMessage.BIOMETRIC_FAILED -> "Biometric authentication failed. Try again."
        SettingsMessage.AUTO_LOCK_UPDATED -> "Auto-lock updated."
        SettingsMessage.THEME_UPDATED -> "Appearance updated."
        SettingsMessage.LOCKED -> "Vault locked."
        SettingsMessage.FAILED -> "Something went wrong. Your vault is unchanged."
    }
}

/**
 * App-scoped vault/session ViewModel. Owns no crypto: every operation is delegated to
 * [NoteRepository], which owns the VMK and the encrypted storage. It only keeps
 * transient UI state (never logs, never persists) and wipes password CharArrays as
 * soon as the suspend call returns.
 *
 * Testability: [BiometricAvailability] is injected so the state machine can be tested
 * on a JVM/Robolectric without biometric hardware.
 */
class VaultViewModel(
    private val repository: NoteRepository,
    private val biometricAvailability: BiometricAvailability = BiometricAvailability { false },
) : ViewModel() {

    private val biometricAvailableFlow =
        MutableStateFlow(runCatching { biometricAvailability.canAuthenticate() }.getOrDefault(false))

    /**
     * Whether a wrapped VMK for biometric unlock exists. Known without the vault being
     * unlocked (the wrap is ciphertext), so the locked screen can decide whether to offer
     * the biometric CTA at all instead of showing a button that can only fail.
     */
    private val biometricEnrolledFlow = MutableStateFlow(
        runCatching { repository.isBiometricUnlockEnrolled() }.getOrDefault(false),
    )

    private data class SessionMeta(
        val status: VaultStatus,
        val themeMode: ThemeMode,
        val autoLockMinutes: Int,
        val vaultInfo: VaultInfo?,
    )

    private val sessionMeta: Flow<SessionMeta> = combine(
        repository.status,
        repository.themeMode,
        repository.autoLockMinutes,
        repository.vaultInfo,
    ) { status, themeMode, autoLockMinutes, vaultInfo ->
        SessionMeta(status, themeMode, autoLockMinutes, vaultInfo)
    }

    /** Combined vault state (status, theme, auto-lock, non-secret vault info + biometric flags). */
    val state: StateFlow<VaultUiState> = combine(
        sessionMeta,
        biometricAvailableFlow,
        biometricEnrolledFlow,
    ) { meta, biometricAvailable, biometricEnrolled ->
        VaultUiState(
            status = meta.status,
            themeMode = meta.themeMode,
            autoLockMinutes = meta.autoLockMinutes,
            vaultInfo = meta.vaultInfo,
            biometricAvailable = biometricAvailable,
            biometricEnabled = meta.vaultInfo?.biometricUnlockEnabled == true,
            biometricEnrolled = biometricEnrolled,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = VaultUiState(
            biometricAvailable = biometricAvailableFlow.value,
            biometricEnrolled = biometricEnrolledFlow.value,
        ),
    )

    /** Refreshes the enrolled flag from the repository (Keystore/prefs-backed, non-secret). */
    private fun refreshBiometricEnrolled() {
        biometricEnrolledFlow.value =
            runCatching { repository.isBiometricUnlockEnrolled() }.getOrDefault(false)
    }

    // ---- Setup -------------------------------------------------------------

    private val _setup = MutableStateFlow(SetupUiState())
    val setup: StateFlow<SetupUiState> = _setup

    // ---- Unlock ------------------------------------------------------------

    private val _unlock = MutableStateFlow(UnlockUiState())
    val unlock: StateFlow<UnlockUiState> = _unlock

    private var lockoutJob: Job? = null

    // ---- Recovery ----------------------------------------------------------

    private val _recovery = MutableStateFlow(RecoveryUiState())
    val recovery: StateFlow<RecoveryUiState> = _recovery

    // ---- Settings ----------------------------------------------------------

    private val _settings = MutableStateFlow(SettingsUiState())
    val settings: StateFlow<SettingsUiState> = _settings

    private val _recoveryResetNotice = MutableStateFlow(false)

    /**
     * One-shot, non-secret confirmation shown after a successful recovery reset: the 12-word
     * phrase was **not** rotated, so the one the user already wrote down keeps working
     * (decision #12). Dismissed by the user, and dropped on every lock.
     */
    val recoveryResetNotice: StateFlow<Boolean> = _recoveryResetNotice.asStateFlow()

    init {
        refreshBiometricEnrolled()
        viewModelScope.launch {
            repository.status.collect { status ->
                refreshBiometricEnrolled()
                when (status) {
                    VaultStatus.UNLOCKED -> {
                        lockoutJob?.cancel()
                        _unlock.value = UnlockUiState()
                        _recovery.value = RecoveryUiState()
                    }
                    VaultStatus.LOCKED -> {
                        lockoutJob?.cancel()
                        _unlock.value = UnlockUiState()
                        // A rotated phrase and its acknowledgement are session secrets: they
                        // must not survive a lock/background, otherwise reopening Settings in
                        // the same process would re-display them without re-authentication
                        // (audit F5). Same for the post-reset notice.
                        _settings.value = SettingsUiState()
                        _recoveryResetNotice.value = false
                        // If the app locked mid-setup (backgrounded on the phrase screen),
                        // the phrase can no longer be recovered into the UI. The vault
                        // exists and is unlockable with the password; the kit can be
                        // rotated from settings ("requires current password").
                        _setup.update { current ->
                            if (current.step != SetupStep.DONE) {
                                current.copy(step = SetupStep.DONE, phrase = null)
                            } else {
                                current
                            }
                        }
                    }
                    VaultStatus.SETUP_REQUIRED -> {
                        // Fresh process / fresh vault.
                        _unlock.value = UnlockUiState()
                        _setup.value = SetupUiState()
                        _recovery.value = RecoveryUiState()
                        _settings.value = SettingsUiState()
                        _recoveryResetNotice.value = false
                    }
                }
            }
        }
    }

    // =========================================================================
    // Setup
    // =========================================================================

    /** Clears the last password-rule error (call when the user edits the field). */
    fun clearSetupErrors() {
        if (_setup.value.passwordRule != null || _setup.value.failed) {
            _setup.update { it.copy(passwordRule = null, failed = false) }
        }
    }

    /**
     * First-run creation. The password buffer is wiped by this method once the
     * repository call returns, regardless of success.
     */
    fun createVault(password: CharArray) {
        if (_setup.value.isWorking) {
            password.fill('\u0000')
            return
        }
        if (repository.status.value != VaultStatus.SETUP_REQUIRED) {
            password.fill('\u0000')
            return
        }
        val policy = PasswordPolicy.evaluate(password)
        if (!policy.isValid) {
            _setup.update { it.copy(passwordRule = policy.unmet.firstOrNull(), failed = false) }
            password.fill('\u0000')
            return
        }
        viewModelScope.launch {
            _setup.update { it.copy(isWorking = true, passwordRule = null, failed = false) }
            var phrase: List<String>? = null
            try {
                phrase = repository.setupVault(password)
            } catch (_: Throwable) {
                // Never surface crypto/storage exception text: it can carry key material.
                _setup.update { it.copy(isWorking = false, failed = true) }
            } finally {
                password.fill('\u0000')
            }
            if (phrase != null) {
                _setup.update {
                    it.copy(
                        isWorking = false,
                        step = SetupStep.PHRASE,
                        phrase = phrase,
                        confirmIndices = SetupUiState.DEFAULT_CONFIRM_INDICES,
                    )
                }
            }
        }
    }

    /** The user says the phrase is written down: advance to the confirmation gate. */
    fun acknowledgeRecoveryPhrase() {
        _setup.update { current ->
            if (current.step == SetupStep.PHRASE && current.phrase != null) {
                current.copy(step = SetupStep.CONFIRM, confirmMismatch = false)
            } else {
                current
            }
        }
    }

    /**
     * Confirmation gate: the user must type the words at [SetupUiState.confirmIndices].
     * Returns true when they match (setup completes and the phrase is dropped from
     * memory); false otherwise.
     */
    fun confirmRecoveryPhrase(entered: List<String>): Boolean {
        val current = _setup.value
        val phrase = current.phrase ?: return false
        if (current.step != SetupStep.CONFIRM) return false
        val matches = current.confirmIndices.withIndex().all { (position, wordIndex) ->
            val typed = entered.getOrNull(position)?.trim()?.lowercase()
            val expected = phrase.getOrNull(wordIndex)?.lowercase()
            typed != null && typed.isNotEmpty() && typed == expected
        }
        if (matches) {
            _setup.update {
                it.copy(step = SetupStep.DONE, phrase = null, confirmMismatch = false, isWorking = false)
            }
        } else {
            _setup.update { it.copy(confirmMismatch = true) }
        }
        return matches
    }

    // =========================================================================
    // Unlock
    // =========================================================================

    fun clearUnlockError() {
        if (_unlock.value.failure != null) {
            _unlock.update { it.copy(failure = null) }
        }
    }

    fun unlock(password: CharArray) {
        val current = _unlock.value
        if (current.isWorking || current.lockoutActive) {
            password.fill('\u0000')
            return
        }
        if (repository.status.value != VaultStatus.LOCKED) {
            password.fill('\u0000')
            return
        }
        viewModelScope.launch {
            _unlock.update { it.copy(isWorking = true, failure = null) }
            var result = UnlockResult.CORRUPT_VAULT
            try {
                result = repository.unlock(password)
            } catch (_: Throwable) {
                result = UnlockResult.CORRUPT_VAULT
            } finally {
                password.fill('\u0000')
            }
            when (result) {
                UnlockResult.SUCCESS -> _unlock.value = UnlockUiState()
                UnlockResult.WRONG_PASSWORD -> recordFailedUnlockAttempt()
                UnlockResult.CORRUPT_VAULT ->
                    _unlock.update { it.copy(isWorking = false, failure = UnlockFailure.CORRUPT_VAULT) }
                UnlockResult.WRONG_RECOVERY_PHRASE,
                UnlockResult.BIOMETRIC_FAILED,
                -> _unlock.update {
                    it.copy(isWorking = false, failure = UnlockFailure.BIOMETRIC_FAILED)
                }
            }
        }
    }

    private fun recordFailedUnlockAttempt() {
        val attempts = _unlock.value.failedAttempts + 1
        if (attempts >= MAX_UNLOCK_ATTEMPTS) {
            _unlock.update {
                it.copy(
                    isWorking = false,
                    failure = null,
                    failedAttempts = attempts,
                    lockoutSecondsLeft = LOCKOUT_SECONDS,
                )
            }
            startLockoutCountdown()
        } else {
            _unlock.update {
                it.copy(
                    isWorking = false,
                    failure = UnlockFailure.WRONG_PASSWORD,
                    failedAttempts = attempts,
                )
            }
        }
    }

    private fun startLockoutCountdown() {
        lockoutJob?.cancel()
        lockoutJob = viewModelScope.launch {
            while (_unlock.value.lockoutSecondsLeft > 0) {
                delay(1_000)
                _unlock.update { it.copy(lockoutSecondsLeft = (it.lockoutSecondsLeft - 1).coerceAtLeast(0)) }
            }
        }
    }

    // ---- Biometric unlock --------------------------------------------------

    /** Cipher to wrap in a BiometricPrompt CryptoObject; null when unavailable. */
    fun prepareBiometricDecrypt(): BiometricDecrypt? {
        if (repository.status.value != VaultStatus.LOCKED) return null
        val decrypt = runCatching { repository.prepareBiometricDecrypt() }.getOrNull()
        if (decrypt == null) {
            _unlock.update { it.copy(failure = UnlockFailure.BIOMETRIC_UNAVAILABLE) }
        }
        return decrypt
    }

    fun completeBiometricUnlock(decrypt: BiometricDecrypt) {
        viewModelScope.launch {
            _unlock.update { it.copy(isWorking = true, failure = null) }
            var result = UnlockResult.BIOMETRIC_FAILED
            try {
                result = repository.completeBiometricUnlock(decrypt)
            } catch (_: Throwable) {
                result = UnlockResult.BIOMETRIC_FAILED
            }
            if (result == UnlockResult.SUCCESS) {
                _unlock.value = UnlockUiState()
            } else {
                _unlock.update { it.copy(isWorking = false, failure = UnlockFailure.BIOMETRIC_FAILED) }
            }
        }
    }

    /** Maps a prompt error to non-secret UI copy; cancellation is silent. */
    fun onBiometricPromptError(error: BiometricPromptError) {
        val failure = when (error) {
            BiometricPromptError.CANCELLED -> return
            BiometricPromptError.UNAVAILABLE -> UnlockFailure.BIOMETRIC_UNAVAILABLE
            BiometricPromptError.NOT_ENROLLED -> UnlockFailure.BIOMETRIC_NOT_ENROLLED
            BiometricPromptError.LOCKOUT,
            BiometricPromptError.LOCKOUT_PERMANENT,
            -> UnlockFailure.BIOMETRIC_LOCKOUT
            BiometricPromptError.TEMPORARILY_UNAVAILABLE,
            BiometricPromptError.FAILED,
            -> UnlockFailure.BIOMETRIC_TRY_AGAIN
        }
        _unlock.update { it.copy(isWorking = false, failure = failure) }
    }

    // =========================================================================
    // Recovery (phrase reset from the locked screen)
    // =========================================================================

    fun resetRecoveryState() {
        _recovery.value = RecoveryUiState()
    }

    fun clearRecoveryErrors() {
        _recovery.update { it.copy(invalidPhrase = false, failure = null, passwordRule = null) }
    }

    /** Checks the 12-word checksum; on success advances to the new-password step. */
    fun checkRecoveryPhrase(words: List<String>) {
        if (_recovery.value.isWorking) return
        if (repository.status.value != VaultStatus.LOCKED) return
        val normalized = words.map { it.trim().lowercase() }
        if (normalized.size != RECOVERY_WORD_COUNT || normalized.any { it.isEmpty() }) {
            _recovery.update { it.copy(invalidPhrase = true, failure = null) }
            return
        }
        viewModelScope.launch {
            _recovery.update { it.copy(isWorking = true, invalidPhrase = false, failure = null) }
            var valid = false
            try {
                valid = repository.validateRecoveryPhrase(normalized)
            } catch (_: Throwable) {
                valid = false
            }
            _recovery.update {
                it.copy(
                    isWorking = false,
                    step = if (valid) RecoveryStep.NEW_PASSWORD else RecoveryStep.PHRASE,
                    invalidPhrase = !valid,
                    failure = if (valid) null else RecoveryFailure.WRONG_PHRASE,
                )
            }
        }
    }

    fun backToPhraseStep() {
        _recovery.update { it.copy(step = RecoveryStep.PHRASE, passwordRule = null) }
    }

    /**
     * Recovery reset: re-wraps the VMK under [newPassword] and re-wraps the **same** 12-word
     * phrase under a fresh salt — the phrase is *not* rotated (decision #12; the frozen
     * repository interface returns no new phrase). Rotation is the explicit Settings action
     * [rotateRecoveryPhrase], which shows the new phrase once.
     *
     * On success it raises [recoveryResetNotice] so the unlocked surface can confirm that the
     * phrase the user already wrote down still works. The new password buffer is wiped when
     * the call returns.
     */
    fun resetWithRecoveryPhrase(words: List<String>, newPassword: CharArray) {
        if (_recovery.value.isWorking) {
            newPassword.fill('\u0000')
            return
        }
        if (repository.status.value != VaultStatus.LOCKED) {
            newPassword.fill('\u0000')
            return
        }
        val policy = PasswordPolicy.evaluate(newPassword)
        if (!policy.isValid) {
            _recovery.update {
                it.copy(passwordRule = policy.unmet.firstOrNull(), step = RecoveryStep.NEW_PASSWORD)
            }
            newPassword.fill('\u0000')
            return
        }
        val normalized = words.map { it.trim().lowercase() }
        viewModelScope.launch {
            _recovery.update { it.copy(isWorking = true, passwordRule = null, failure = null) }
            var result = UnlockResult.CORRUPT_VAULT
            try {
                result = repository.resetWithRecoveryPhrase(normalized, newPassword)
            } catch (_: Throwable) {
                result = UnlockResult.CORRUPT_VAULT
            } finally {
                newPassword.fill('\u0000')
            }
            when (result) {
                UnlockResult.SUCCESS -> {
                    _recovery.value = RecoveryUiState()
                    // The phrase is unchanged by a reset (decision #12): tell the user instead
                    // of letting them believe a new one was generated (audit F2).
                    _recoveryResetNotice.value = true
                }
                UnlockResult.WRONG_RECOVERY_PHRASE -> _recovery.update {
                    it.copy(
                        isWorking = false,
                        step = RecoveryStep.PHRASE,
                        failure = RecoveryFailure.WRONG_PHRASE,
                    )
                }
                UnlockResult.CORRUPT_VAULT ->
                    _recovery.update { it.copy(isWorking = false, failure = RecoveryFailure.CORRUPT_VAULT) }
                else ->
                    _recovery.update { it.copy(isWorking = false, failure = RecoveryFailure.FAILED) }
            }
        }
    }

    // =========================================================================
    // Settings
    // =========================================================================

    fun clearSettingsMessage() {
        if (_settings.value.message != null) {
            _settings.update { it.copy(message = null, isError = false) }
        }
    }

    fun changePassword(current: CharArray, next: CharArray) {
        if (!isUnlocked()) {
            current.fill('\u0000')
            next.fill('\u0000')
            return
        }
        val policy = PasswordPolicy.evaluate(next)
        if (!policy.isValid) {
            current.fill('\u0000')
            next.fill('\u0000')
            _settings.update { it.copy(message = SettingsMessage.PASSWORD_REJECTED, isError = true) }
            return
        }
        viewModelScope.launch {
            _settings.update { it.copy(isWorking = true, message = null, isError = false) }
            var changed = false
            try {
                changed = repository.changePassword(current, next)
            } catch (_: Throwable) {
                changed = false
            } finally {
                current.fill('\u0000')
                next.fill('\u0000')
            }
            _settings.update {
                it.copy(
                    isWorking = false,
                    message = if (changed) SettingsMessage.PASSWORD_CHANGED else SettingsMessage.PASSWORD_WRONG,
                    isError = !changed,
                )
            }
        }
    }

    /**
     * Rotates the recovery kit using the current password; the new phrase stays in memory only
     * until acknowledged (and is dropped on the next lock). This is the **only** action that
     * replaces the phrase: a recovery reset re-wraps the existing one (decision #12).
     */
    fun rotateRecoveryPhrase(currentPassword: CharArray) {
        if (!isUnlocked()) {
            currentPassword.fill('\u0000')
            return
        }
        viewModelScope.launch {
            _settings.update { it.copy(isWorking = true, message = null, isError = false) }
            var phrase: List<String>? = null
            try {
                phrase = repository.rotateRecoveryPhrase(currentPassword)
            } catch (_: Throwable) {
                phrase = null
            } finally {
                currentPassword.fill('\u0000')
            }
            if (phrase == null) {
                _settings.update {
                    it.copy(
                        isWorking = false,
                        message = SettingsMessage.RECOVERY_ROTATE_WRONG_PASSWORD,
                        isError = true,
                    )
                }
            } else {
                _settings.update {
                    it.copy(isWorking = false, rotatedPhrase = phrase, message = SettingsMessage.RECOVERY_ROTATED)
                }
            }
        }
    }

    /** Call when the user confirmed they saved the rotated phrase: drops it from memory. */
    fun acknowledgeRotatedPhrase() {
        _settings.update {
            it.copy(rotatedPhrase = null, message = SettingsMessage.RECOVERY_SAVED, isError = false)
        }
    }

    /** Dismisses the post-recovery-reset confirmation ("your phrase is unchanged"). */
    fun dismissRecoveryResetNotice() {
        _recoveryResetNotice.value = false
    }

    // ---- Biometric enrollment (two-phase; see data/NoteRepository) ----------

    fun prepareBiometricEnroll(): BiometricEnroll? {
        if (!isUnlocked()) return null
        val enroll = runCatching { repository.prepareBiometricEnroll() }.getOrNull()
        if (enroll == null) {
            val message = if (biometricAvailableFlow.value) {
                SettingsMessage.BIOMETRIC_TRY_AGAIN
            } else {
                SettingsMessage.BIOMETRIC_UNAVAILABLE
            }
            _settings.update { it.copy(message = message, isError = true) }
        }
        return enroll
    }

    fun completeBiometricEnroll(enroll: BiometricEnroll) {
        viewModelScope.launch {
            _settings.update { it.copy(isWorking = true, message = null, isError = false) }
            var enabled = false
            try {
                enabled = repository.completeBiometricEnroll(enroll)
            } catch (_: Throwable) {
                enabled = false
            }
            refreshBiometricEnrolled()
            _settings.update {
                it.copy(
                    isWorking = false,
                    message = if (enabled) SettingsMessage.BIOMETRIC_ENABLED else SettingsMessage.BIOMETRIC_TRY_AGAIN,
                    isError = !enabled,
                )
            }
        }
    }

    fun onBiometricEnrollError(error: BiometricPromptError) {
        val message = when (error) {
            BiometricPromptError.CANCELLED -> return
            BiometricPromptError.UNAVAILABLE -> SettingsMessage.BIOMETRIC_UNAVAILABLE
            BiometricPromptError.NOT_ENROLLED -> SettingsMessage.BIOMETRIC_NOT_ENROLLED
            BiometricPromptError.TEMPORARILY_UNAVAILABLE,
            BiometricPromptError.FAILED,
            BiometricPromptError.LOCKOUT,
            BiometricPromptError.LOCKOUT_PERMANENT,
            -> SettingsMessage.BIOMETRIC_TRY_AGAIN
        }
        _settings.update { it.copy(isWorking = false, message = message, isError = true) }
    }

    fun disableBiometricUnlock() {
        if (!isUnlocked()) return
        viewModelScope.launch {
            _settings.update { it.copy(isWorking = true, message = null, isError = false) }
            try {
                repository.disableBiometricUnlock()
                refreshBiometricEnrolled()
                _settings.update { it.copy(isWorking = false, message = SettingsMessage.BIOMETRIC_DISABLED, isError = false) }
            } catch (_: Throwable) {
                _settings.update { it.copy(isWorking = false, message = SettingsMessage.FAILED, isError = true) }
            }
        }
    }

    fun setAutoLockMinutes(minutes: Int) {
        if (!isUnlocked()) return
        if (minutes !in AUTO_LOCK_OPTIONS) return
        viewModelScope.launch { runCatching { repository.setAutoLockMinutes(minutes) } }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { runCatching { repository.setThemeMode(mode) } }
    }

    /** Immediate re-lock from the settings screen. */
    fun lockNow() {
        if (!isUnlocked()) return
        viewModelScope.launch {
            runCatching { repository.lock() }
            _settings.update { it.copy(message = SettingsMessage.LOCKED, isError = false) }
        }
    }

    private fun isUnlocked(): Boolean = repository.status.value == VaultStatus.UNLOCKED

    // =========================================================================

    class Factory(
        private val repository: NoteRepository,
        private val biometricAvailability: BiometricAvailability,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(VaultViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return VaultViewModel(repository, biometricAvailability) as T
        }
    }

    companion object {
        const val MAX_UNLOCK_ATTEMPTS = 5
        const val LOCKOUT_SECONDS = 30
        const val RECOVERY_WORD_COUNT = 12
        const val DEFAULT_AUTO_LOCK_MINUTES = 5

        /** Allowed auto-lock idle minutes (1 = tightest available in the UI). */
        val AUTO_LOCK_OPTIONS = listOf(1, 5, 15, 30, 60)

        fun factory(repository: NoteRepository, biometricAvailability: BiometricAvailability): ViewModelProvider.Factory =
            Factory(repository, biometricAvailability)
    }
}
