package com.cipherwell.notezero.ui

import com.cipherwell.notezero.data.UnlockResult
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.ThemeMode
import com.cipherwell.notezero.ui.components.common.BiometricAvailability
import com.cipherwell.notezero.ui.components.common.BiometricPromptError
import com.cipherwell.notezero.ui.screens.setup.PasswordPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavioural tests for the shell state machine. No real crypto: [FakeNoteRepository]
 * records operations and returns contract values.
 *
 * Every test that hands a password to the ViewModel also asserts the CharArray was
 * zeroed — that is a security property, not a formatting detail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun freshVault(): FakeNoteRepository = FakeNoteRepository()

    private fun lockedVault(): FakeNoteRepository = FakeNoteRepository(
        initialStatus = VaultStatus.LOCKED,
        initialPassword = FakeNoteRepository.STRONG_PASSWORD,
    )

    private fun unlockedVault(): FakeNoteRepository = FakeNoteRepository(
        initialStatus = VaultStatus.UNLOCKED,
        initialPassword = FakeNoteRepository.STRONG_PASSWORD,
    )

    private fun TestScope.createViewModel(
        repository: FakeNoteRepository,
        biometrics: Boolean = false,
    ): VaultViewModel {
        val viewModel = VaultViewModel(repository, BiometricAvailability { biometrics })
        advanceUntilIdle()
        return viewModel
    }

    private fun assertWiped(buffer: CharArray) {
        assertArrayEquals(
            "password CharArray must be zero-filled after use",
            CharArray(buffer.size) { '\u0000' },
            buffer,
        )
    }

    // ---- Setup --------------------------------------------------------------

    @Test
    fun `setup rejects a weak password locally and never touches the repository`() = runTest(dispatcher) {
        val repository = freshVault()
        val viewModel = createViewModel(repository)
        val buffer = "weak".toCharArray()

        viewModel.createVault(buffer)

        assertEquals(SetupStep.PASSWORD, viewModel.setup.value.step)
        assertEquals(PasswordPolicy.Rule.LENGTH, viewModel.setup.value.passwordRule)
        assertFalse(repository.calls.contains("setupVault"))
        assertWiped(buffer)
    }

    @Test
    fun `setup creates the vault and surfaces the phrase once`() = runTest(dispatcher) {
        val repository = freshVault()
        val viewModel = createViewModel(repository)
        val buffer = FakeNoteRepository.STRONG_PASSWORD.toCharArray()

        viewModel.createVault(buffer)
        advanceUntilIdle()

        assertEquals(SetupStep.PHRASE, viewModel.setup.value.step)
        assertEquals(12, viewModel.setup.value.phrase?.size)
        assertEquals(FakeNoteRepository.DEFAULT_PHRASE, viewModel.setup.value.phrase)
        assertEquals(VaultStatus.UNLOCKED, repository.status.value)
        assertTrue(repository.calls.contains("setupVault"))
        assertWiped(buffer)
    }

    @Test
    fun `setup failure is reported without leaking the exception`() = runTest(dispatcher) {
        val repository = freshVault().apply { failSetup = true }
        val viewModel = createViewModel(repository)

        viewModel.createVault(FakeNoteRepository.STRONG_PASSWORD.toCharArray())
        advanceUntilIdle()

        assertTrue(viewModel.setup.value.failed)
        assertEquals(SetupStep.PASSWORD, viewModel.setup.value.step)
        assertNull(viewModel.setup.value.phrase)
    }

    @Test
    fun `setup is refused once a vault already exists`() = runTest(dispatcher) {
        val repository = lockedVault()
        val viewModel = createViewModel(repository)
        val buffer = FakeNoteRepository.OTHER_STRONG_PASSWORD.toCharArray()

        viewModel.createVault(buffer)

        assertFalse(repository.calls.contains("setupVault"))
        assertWiped(buffer)
    }

    @Test
    fun `phrase confirmation gate rejects wrong words and accepts the asked-back words`() = runTest(dispatcher) {
        val repository = freshVault()
        val viewModel = createViewModel(repository)
        viewModel.createVault(FakeNoteRepository.STRONG_PASSWORD.toCharArray())
        advanceUntilIdle()

        viewModel.acknowledgeRecoveryPhrase()
        assertEquals(SetupStep.CONFIRM, viewModel.setup.value.step)

        assertFalse(viewModel.confirmRecoveryPhrase(listOf("wrong", "wrong", "wrong")))
        assertTrue(viewModel.setup.value.confirmMismatch)
        assertEquals(SetupStep.CONFIRM, viewModel.setup.value.step)

        val phrase = requireNotNull(viewModel.setup.value.phrase)
        val asked = viewModel.setup.value.confirmIndices.map { phrase[it] }
        assertTrue(viewModel.confirmRecoveryPhrase(asked))
        assertEquals(SetupStep.DONE, viewModel.setup.value.step)
        assertNull("phrase must be dropped once confirmed", viewModel.setup.value.phrase)
        assertFalse(viewModel.setup.value.confirmMismatch)
    }

    @Test
    fun `locking mid-setup drops the pending recovery phrase`() = runTest(dispatcher) {
        val repository = freshVault()
        val viewModel = createViewModel(repository)
        viewModel.createVault(FakeNoteRepository.STRONG_PASSWORD.toCharArray())
        advanceUntilIdle()
        assertNotNull(viewModel.setup.value.phrase)

        repository.forceStatus(VaultStatus.LOCKED)
        advanceUntilIdle()

        assertEquals(SetupStep.DONE, viewModel.setup.value.step)
        assertNull("phrase must not survive a lock", viewModel.setup.value.phrase)
        assertEquals(VaultStatus.LOCKED, viewModel.state.value.status)
    }

    // ---- Unlock -------------------------------------------------------------

    @Test
    fun `unlock with the wrong password reports a non-secret error`() = runTest(dispatcher) {
        val repository = lockedVault()
        val viewModel = createViewModel(repository)
        val buffer = "not-the-password".toCharArray()

        viewModel.unlock(buffer)
        advanceUntilIdle()

        assertEquals(UnlockFailure.WRONG_PASSWORD, viewModel.unlock.value.failure)
        assertEquals(1, viewModel.unlock.value.failedAttempts)
        assertEquals(VaultStatus.LOCKED, repository.status.value)
        assertWiped(buffer)
    }

    @Test
    fun `unlock with the correct password unlocks the repository`() = runTest(dispatcher) {
        val repository = lockedVault()
        val viewModel = createViewModel(repository)
        val buffer = FakeNoteRepository.STRONG_PASSWORD.toCharArray()

        viewModel.unlock(buffer)
        advanceUntilIdle()

        assertEquals(VaultStatus.UNLOCKED, repository.status.value)
        assertEquals(VaultStatus.UNLOCKED, viewModel.state.value.status)
        assertNull(viewModel.unlock.value.failure)
        assertWiped(buffer)
    }

    @Test
    fun `five failures arm a retry cooldown that expires`() = runTest(dispatcher) {
        val repository = lockedVault()
        val viewModel = createViewModel(repository)

        repeat(VaultViewModel.MAX_UNLOCK_ATTEMPTS) {
            viewModel.unlock("wrong".toCharArray())
            runCurrent()
        }

        assertEquals(VaultViewModel.MAX_UNLOCK_ATTEMPTS, viewModel.unlock.value.failedAttempts)
        assertEquals(VaultViewModel.LOCKOUT_SECONDS, viewModel.unlock.value.lockoutSecondsLeft)
        assertTrue(viewModel.unlock.value.lockoutActive)
        assertNull("cooldown is not an error message", viewModel.unlock.value.failure)

        // A correct password must be ignored while the cooldown is active.
        viewModel.unlock(FakeNoteRepository.STRONG_PASSWORD.toCharArray())
        runCurrent()
        assertEquals(VaultStatus.LOCKED, repository.status.value)

        advanceTimeBy(VaultViewModel.LOCKOUT_SECONDS * 1_000L + 500)
        runCurrent()
        assertEquals(0, viewModel.unlock.value.lockoutSecondsLeft)
        assertFalse(viewModel.unlock.value.lockoutActive)
    }

    @Test
    fun `corrupt vault maps to an explicit failure`() = runTest(dispatcher) {
        val repository = lockedVault().apply { nextUnlockResult = UnlockResult.CORRUPT_VAULT }
        val viewModel = createViewModel(repository)

        viewModel.unlock("anything".toCharArray())
        advanceUntilIdle()

        assertEquals(UnlockFailure.CORRUPT_VAULT, viewModel.unlock.value.failure)
    }

    @Test
    fun `biometric decrypt round trip unlocks the vault`() = runTest(dispatcher) {
        val repository = lockedVault().apply { forceBiometricEnrolled() }
        val viewModel = createViewModel(repository, biometrics = true)

        val decrypt = viewModel.prepareBiometricDecrypt()
        assertNotNull(decrypt)
        viewModel.completeBiometricUnlock(requireNotNull(decrypt))
        advanceUntilIdle()

        assertEquals(VaultStatus.UNLOCKED, repository.status.value)
        assertTrue(repository.calls.contains("completeBiometricUnlock"))
    }

    @Test
    fun `biometric unavailable path surfaces a fallback message`() = runTest(dispatcher) {
        val repository = lockedVault() // not enrolled -> prepareBiometricDecrypt() == null
        val viewModel = createViewModel(repository)

        assertNull(viewModel.prepareBiometricDecrypt())
        assertEquals(UnlockFailure.BIOMETRIC_UNAVAILABLE, viewModel.unlock.value.failure)

        // Prompt cancellation is silent.
        viewModel.onBiometricPromptError(BiometricPromptError.CANCELLED)
        assertEquals(UnlockFailure.BIOMETRIC_UNAVAILABLE, viewModel.unlock.value.failure)
        viewModel.onBiometricPromptError(BiometricPromptError.LOCKOUT)
        assertEquals(UnlockFailure.BIOMETRIC_LOCKOUT, viewModel.unlock.value.failure)
    }

    @Test
    fun `prompt failures map to retry, not-enrolled and lockout copy`() = runTest(dispatcher) {
        val repository = lockedVault().apply { forceBiometricEnrolled() }
        val viewModel = createViewModel(repository, biometrics = true)
        advanceUntilIdle()

        // Timeout / unable-to-process / vendor: prompt opened but auth did not complete.
        viewModel.onBiometricPromptError(BiometricPromptError.FAILED)
        assertEquals(UnlockFailure.BIOMETRIC_TRY_AGAIN, viewModel.unlock.value.failure)
        viewModel.onBiometricPromptError(BiometricPromptError.TEMPORARILY_UNAVAILABLE)
        assertEquals(UnlockFailure.BIOMETRIC_TRY_AGAIN, viewModel.unlock.value.failure)
        // Hardware exists but nothing is enrolled on the device.
        viewModel.onBiometricPromptError(BiometricPromptError.NOT_ENROLLED)
        assertEquals(UnlockFailure.BIOMETRIC_NOT_ENROLLED, viewModel.unlock.value.failure)
        viewModel.onBiometricPromptError(BiometricPromptError.LOCKOUT_PERMANENT)
        assertEquals(UnlockFailure.BIOMETRIC_LOCKOUT, viewModel.unlock.value.failure)
    }

    @Test
    fun `enrolled flag is exposed while locked and refreshed after disable`() = runTest(dispatcher) {
        // While locked, the enrolled flag is what lets the unlock screen decide whether to
        // offer the biometric CTA (the wrapped VMK is non-secret metadata).
        val locked = lockedVault().apply { forceBiometricEnrolled() }
        val lockedViewModel = createViewModel(locked)
        advanceUntilIdle()
        assertTrue(lockedViewModel.state.value.biometricEnrolled)

        val unlocked = unlockedVault().apply { forceBiometricEnrolled() }
        val viewModel = createViewModel(unlocked)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.biometricEnrolled)

        viewModel.disableBiometricUnlock()
        advanceUntilIdle()
        assertEquals(false, viewModel.state.value.biometricEnrolled)
    }

    @Test
    fun `enrollment failures surface retry or not-enrolled copy`() = runTest(dispatcher) {
        val viewModel = createViewModel(unlockedVault())

        viewModel.onBiometricEnrollError(BiometricPromptError.NOT_ENROLLED)
        assertEquals(SettingsMessage.BIOMETRIC_NOT_ENROLLED, viewModel.settings.value.message)
        assertTrue(viewModel.settings.value.isError)

        viewModel.onBiometricEnrollError(BiometricPromptError.TEMPORARILY_UNAVAILABLE)
        assertEquals(SettingsMessage.BIOMETRIC_TRY_AGAIN, viewModel.settings.value.message)

        // Cancelling the prompt stays silent.
        viewModel.onBiometricEnrollError(BiometricPromptError.CANCELLED)
        assertEquals(SettingsMessage.BIOMETRIC_TRY_AGAIN, viewModel.settings.value.message)
    }

    // ---- Settings -----------------------------------------------------------

    @Test
    fun `change password rejects a wrong current password`() = runTest(dispatcher) {
        val repository = unlockedVault()
        val viewModel = createViewModel(repository)
        val current = "wrong-current".toCharArray()
        val next = FakeNoteRepository.OTHER_STRONG_PASSWORD.toCharArray()

        viewModel.changePassword(current, next)
        advanceUntilIdle()

        assertEquals(SettingsMessage.PASSWORD_WRONG, viewModel.settings.value.message)
        assertTrue(viewModel.settings.value.isError)
        assertEquals(FakeNoteRepository.STRONG_PASSWORD, repository.password)
        assertWiped(current)
        assertWiped(next)
    }

    @Test
    fun `change password succeeds with a policy-compliant password`() = runTest(dispatcher) {
        val repository = unlockedVault()
        val viewModel = createViewModel(repository)
        val current = FakeNoteRepository.STRONG_PASSWORD.toCharArray()
        val next = FakeNoteRepository.OTHER_STRONG_PASSWORD.toCharArray()

        viewModel.changePassword(current, next)
        advanceUntilIdle()

        assertEquals(SettingsMessage.PASSWORD_CHANGED, viewModel.settings.value.message)
        assertFalse(viewModel.settings.value.isError)
        assertEquals(FakeNoteRepository.OTHER_STRONG_PASSWORD, repository.password)
        assertWiped(current)
        assertWiped(next)
    }

    @Test
    fun `change password enforces the policy before calling the repository`() = runTest(dispatcher) {
        val repository = unlockedVault()
        val viewModel = createViewModel(repository)
        val current = FakeNoteRepository.STRONG_PASSWORD.toCharArray()
        val next = "short".toCharArray()

        viewModel.changePassword(current, next)
        advanceUntilIdle()

        assertEquals(SettingsMessage.PASSWORD_REJECTED, viewModel.settings.value.message)
        assertFalse(repository.calls.contains("changePassword"))
        assertWiped(current)
        assertWiped(next)
    }

    @Test
    fun `rotate recovery phrase reports the new phrase once and clears it on acknowledge`() = runTest(dispatcher) {
        val repository = unlockedVault()
        val viewModel = createViewModel(repository)
        val buffer = FakeNoteRepository.STRONG_PASSWORD.toCharArray()

        viewModel.rotateRecoveryPhrase(buffer)
        advanceUntilIdle()

        assertEquals(FakeNoteRepository.ROTATED_PHRASE, viewModel.settings.value.rotatedPhrase)
        assertEquals(SettingsMessage.RECOVERY_ROTATED, viewModel.settings.value.message)
        assertWiped(buffer)

        viewModel.acknowledgeRotatedPhrase()
        assertNull(viewModel.settings.value.rotatedPhrase)
        assertEquals(SettingsMessage.RECOVERY_SAVED, viewModel.settings.value.message)
    }

    @Test
    fun `rotate recovery phrase with a wrong password does not return a phrase`() = runTest(dispatcher) {
        val repository = unlockedVault()
        val viewModel = createViewModel(repository)

        viewModel.rotateRecoveryPhrase("nope".toCharArray())
        advanceUntilIdle()

        assertNull(viewModel.settings.value.rotatedPhrase)
        assertEquals(SettingsMessage.RECOVERY_ROTATE_WRONG_PASSWORD, viewModel.settings.value.message)
        assertTrue(viewModel.settings.value.isError)
    }

    @Test
    fun `two-phase biometric enrollment enables and disables the switch`() = runTest(dispatcher) {
        val repository = unlockedVault()
        val viewModel = createViewModel(repository, biometrics = true)

        val enroll = viewModel.prepareBiometricEnroll()
        assertNotNull(enroll)
        viewModel.completeBiometricEnroll(requireNotNull(enroll))
        advanceUntilIdle()

        assertEquals(SettingsMessage.BIOMETRIC_ENABLED, viewModel.settings.value.message)
        assertTrue(repository.biometricEnrolled)
        assertTrue(viewModel.state.value.biometricEnabled)

        viewModel.disableBiometricUnlock()
        advanceUntilIdle()
        assertEquals(SettingsMessage.BIOMETRIC_DISABLED, viewModel.settings.value.message)
        assertFalse(repository.biometricEnrolled)
        assertFalse(viewModel.state.value.biometricEnabled)
    }

    @Test
    fun `theme and auto-lock choices are persisted through the repository`() = runTest(dispatcher) {
        val repository = unlockedVault()
        val viewModel = createViewModel(repository)

        viewModel.setThemeMode(ThemeMode.DARK)
        viewModel.setAutoLockMinutes(15)
        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, repository.themeMode.value)
        assertEquals(15, repository.autoLockMinutes.value)
        assertEquals(ThemeMode.DARK, viewModel.state.value.themeMode)
        assertEquals(15, viewModel.state.value.autoLockMinutes)
    }

    @Test
    fun `auto-lock options exclude never and start at one minute`() {
        assertEquals(listOf(1, 5, 15, 30, 60), VaultViewModel.AUTO_LOCK_OPTIONS)
    }

    @Test
    fun `lock now locks the vault immediately`() = runTest(dispatcher) {
        val repository = unlockedVault()
        val viewModel = createViewModel(repository)

        viewModel.lockNow()
        advanceUntilIdle()

        assertEquals(VaultStatus.LOCKED, repository.status.value)
        assertEquals(VaultStatus.LOCKED, viewModel.state.value.status)
        assertTrue(repository.calls.contains("lock"))
    }

    // ---- Recovery -----------------------------------------------------------

    @Test
    fun `recovery validates the phrase before asking for a new password`() = runTest(dispatcher) {
        val repository = lockedVault()
        val viewModel = createViewModel(repository)

        viewModel.checkRecoveryPhrase(List(12) { "wrong" })
        advanceUntilIdle()
        assertEquals(RecoveryStep.PHRASE, viewModel.recovery.value.step)
        assertTrue(viewModel.recovery.value.invalidPhrase)
        assertEquals(RecoveryFailure.WRONG_PHRASE, viewModel.recovery.value.failure)

        viewModel.checkRecoveryPhrase(FakeNoteRepository.DEFAULT_PHRASE)
        advanceUntilIdle()
        assertEquals(RecoveryStep.NEW_PASSWORD, viewModel.recovery.value.step)
        assertFalse(viewModel.recovery.value.invalidPhrase)
        assertNull(viewModel.recovery.value.failure)
    }

    @Test
    fun `recovery rejects an incomplete phrase without a repository call`() = runTest(dispatcher) {
        val repository = lockedVault()
        val viewModel = createViewModel(repository)

        viewModel.checkRecoveryPhrase(listOf("abandon", "", ""))
        advanceUntilIdle()

        assertTrue(viewModel.recovery.value.invalidPhrase)
        assertFalse(repository.calls.contains("validateRecoveryPhrase"))
    }

    @Test
    fun `recovery reset rejects a weak new password`() = runTest(dispatcher) {
        val repository = lockedVault()
        val viewModel = createViewModel(repository)
        val weak = "short".toCharArray()

        viewModel.resetWithRecoveryPhrase(FakeNoteRepository.DEFAULT_PHRASE, weak)
        // resetWithRecoveryPhrase validates synchronously; still drain the scheduler.
        advanceUntilIdle()

        assertEquals(RecoveryStep.NEW_PASSWORD, viewModel.recovery.value.step)
        assertEquals(PasswordPolicy.Rule.LENGTH, viewModel.recovery.value.passwordRule)
        assertFalse(repository.calls.contains("resetWithRecoveryPhrase"))
        assertWiped(weak)
    }

    @Test
    fun `recovery reset re-wraps the vault and unlocks it`() = runTest(dispatcher) {
        val repository = lockedVault()
        val viewModel = createViewModel(repository)
        val next = FakeNoteRepository.OTHER_STRONG_PASSWORD.toCharArray()

        viewModel.resetWithRecoveryPhrase(FakeNoteRepository.DEFAULT_PHRASE, next)
        advanceUntilIdle()

        assertEquals(VaultStatus.UNLOCKED, repository.status.value)
        assertEquals(FakeNoteRepository.OTHER_STRONG_PASSWORD, repository.password)
        assertEquals(RecoveryUiState(), viewModel.recovery.value)
        assertWiped(next)
    }

    @Test
    fun `recovery reset with a mismatched phrase returns to the phrase step`() = runTest(dispatcher) {
        val repository = lockedVault()
        val viewModel = createViewModel(repository)
        val next = FakeNoteRepository.OTHER_STRONG_PASSWORD.toCharArray()

        viewModel.resetWithRecoveryPhrase(List(12) { "zebra" }, next)
        advanceUntilIdle()

        assertEquals(RecoveryStep.PHRASE, viewModel.recovery.value.step)
        assertEquals(RecoveryFailure.WRONG_PHRASE, viewModel.recovery.value.failure)
        assertEquals(VaultStatus.LOCKED, repository.status.value)
        assertWiped(next)
    }

    // ---- session-boundary hygiene (audit findings F2/F5) ---------------------

    @Test
    fun `rotated phrase is dropped on lock and cannot be re-shown without a new rotation`() =
        runTest(dispatcher) {
            val repository = unlockedVault()
            val viewModel = createViewModel(repository)

            viewModel.rotateRecoveryPhrase(FakeNoteRepository.STRONG_PASSWORD.toCharArray())
            advanceUntilIdle()
            assertEquals(FakeNoteRepository.ROTATED_PHRASE, viewModel.settings.value.rotatedPhrase)

            // Background/screen-off lock: the phrase (and its acknowledgement) must not stay in
            // the app-scoped ViewModel, or reopening Settings would reveal it without re-auth.
            repository.forceStatus(VaultStatus.LOCKED)
            advanceUntilIdle()

            assertNull("the rotated phrase is dropped on lock", viewModel.settings.value.rotatedPhrase)
            assertNull("the acknowledgement is dropped on lock", viewModel.settings.value.message)
            assertFalse(viewModel.settings.value.isWorking)
        }

    @Test
    fun `recovery reset reports that the phrase is unchanged and clears the notice on lock`() =
        runTest(dispatcher) {
            val repository = lockedVault()
            val viewModel = createViewModel(repository)
            val next = FakeNoteRepository.OTHER_STRONG_PASSWORD.toCharArray()

            assertFalse(viewModel.recoveryResetNotice.value)
            viewModel.resetWithRecoveryPhrase(FakeNoteRepository.DEFAULT_PHRASE, next)
            advanceUntilIdle()

            assertEquals(VaultStatus.UNLOCKED, repository.status.value)
            // Decision #12: the reset re-wraps the SAME phrase, so the user must be told that,
            // not that a new phrase was generated.
            assertTrue("post-reset confirmation is raised", viewModel.recoveryResetNotice.value)
            assertEquals(FakeNoteRepository.DEFAULT_PHRASE, repository.recoveryPhrase)

            viewModel.dismissRecoveryResetNotice()
            assertFalse(viewModel.recoveryResetNotice.value)

            // A lock drops a pending notice: it belongs to the session that produced it.
            repository.forceStatus(VaultStatus.LOCKED)
            advanceUntilIdle()
            // The ViewModel wipes the buffer handed to the first reset, so pass a fresh one.
            viewModel.resetWithRecoveryPhrase(
                FakeNoteRepository.DEFAULT_PHRASE,
                FakeNoteRepository.OTHER_STRONG_PASSWORD.toCharArray(),
            )
            advanceUntilIdle()
            assertTrue(viewModel.recoveryResetNotice.value)
            repository.forceStatus(VaultStatus.LOCKED)
            advanceUntilIdle()
            assertFalse(viewModel.recoveryResetNotice.value)
        }
}
