package com.cipherwell.notezero.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.ui.components.common.BiometricAvailability
import com.cipherwell.notezero.ui.screens.unlock.UnlockScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class UnlockScreenTest : UiTestBase() {

    private fun lockedVault() = FakeNoteRepository(
        initialStatus = VaultStatus.LOCKED,
        initialPassword = FakeNoteRepository.STRONG_PASSWORD,
    )

    @Test
    fun `unlock screen exposes stable tags and hides biometric when unavailable`() {
        setThemedContent {
            UnlockScreen(
                unlock = UnlockUiState(failure = UnlockFailure.WRONG_PASSWORD),
                showBiometric = false,
                onUnlock = {},
                onBiometricUnlock = {},
                onUseRecoveryPhrase = {},
            )
        }

        composeRule.onNodeWithTag("unlock_password").assertExists()
        composeRule.onNodeWithTag("unlock_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("unlock_error").assertExists()
        composeRule.onNodeWithText("Incorrect password. Try again.").assertExists()
        composeRule.onNodeWithTag("unlock_biometric").assertDoesNotExist()
        composeRule.onNodeWithTag("unlock_use_recovery").assertExists()
    }

    @Test
    fun `wrong password via the view model shows a non-secret error and never echoes the secret`() {
        val repository = lockedVault()
        val viewModel = VaultViewModel(repository, BiometricAvailability { false })
        setThemedContent {
            val unlock by viewModel.unlock.collectAsState()
            UnlockScreen(
                unlock = unlock,
                showBiometric = false,
                onUnlock = viewModel::unlock,
                onBiometricUnlock = {},
                onUseRecoveryPhrase = {},
            )
        }

        composeRule.onNodeWithTag("unlock_password").performTextInput("typed-wrong-password")
        composeRule.onNodeWithTag("unlock_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("unlock_error").assertExists()
        assertEquals(UnlockFailure.WRONG_PASSWORD, viewModel.unlock.value.failure)
        assertEquals(VaultStatus.LOCKED, repository.status.value)
        // The secret must never be rendered back to the user.
        composeRule.onAllNodesWithText("typed-wrong-password", substring = true).assertCountEquals(0)
    }

    @Test
    fun `retry cooldown disables input and shows a countdown`() {
        setThemedContent {
            UnlockScreen(
                unlock = UnlockUiState(failedAttempts = 5, lockoutSecondsLeft = 12),
                showBiometric = false,
                onUnlock = {},
                onBiometricUnlock = {},
                onUseRecoveryPhrase = {},
            )
        }

        composeRule.onNodeWithTag("unlock_password").assertIsNotEnabled()
        composeRule.onNodeWithTag("unlock_button").assertIsNotEnabled()
        composeRule.onNodeWithText("Too many attempts. Try again in 12s.", substring = true)
            .assertExists()
    }

    @Test
    fun `biometric CTA only renders when available and invokes its callback`() {
        var requested = false
        setThemedContent {
            UnlockScreen(
                unlock = UnlockUiState(),
                showBiometric = true,
                onUnlock = {},
                onBiometricUnlock = { requested = true },
                onUseRecoveryPhrase = {},
            )
        }

        composeRule.onNodeWithTag("unlock_biometric").assertExists()
        composeRule.onNodeWithTag("unlock_biometric").performClick()
        assertTrue(requested)
    }
}
