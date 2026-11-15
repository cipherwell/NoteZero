package com.cipherwell.notezero.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.ui.components.common.BiometricAvailability
import com.cipherwell.notezero.ui.screens.recovery.RecoveryScreen
import com.cipherwell.notezero.ui.screens.setup.PasswordPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class RecoveryScreenTest : UiTestBase() {

    private fun lockedVault() = FakeNoteRepository(
        initialStatus = VaultStatus.LOCKED,
        initialPassword = FakeNoteRepository.STRONG_PASSWORD,
    )

    private fun render(viewModel: VaultViewModel) {
        setThemedContent {
            val recovery by viewModel.recovery.collectAsState()
            RecoveryScreen(
                recovery = recovery,
                onSubmitPhrase = viewModel::checkRecoveryPhrase,
                onSubmitNewPassword = viewModel::resetWithRecoveryPhrase,
                onBack = {},
                onBackToPhrase = viewModel::backToPhraseStep,
            )
        }
    }

    private fun fillPhrase(words: List<String>) {
        words.forEachIndexed { index, word ->
            composeRule.onNodeWithTag("recovery_word_$index").performTextInput(word)
        }
    }

    @Test
    fun `valid phrase advances to the new-password step`() {
        val repository = lockedVault()
        val viewModel = VaultViewModel(repository, BiometricAvailability { false })
        render(viewModel)

        composeRule.onNodeWithTag("recovery_word_0").assertExists()
        composeRule.onNodeWithTag("recovery_word_11").assertExists()
        fillPhrase(FakeNoteRepository.DEFAULT_PHRASE)
        composeRule.onNodeWithTag("recovery_continue_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("recovery_new_password").assertExists()
        assertEquals(RecoveryStep.NEW_PASSWORD, viewModel.recovery.value.step)
    }

    @Test
    fun `weak new password is rejected with a rule message and no reset call`() {
        val repository = lockedVault()
        val viewModel = VaultViewModel(repository, BiometricAvailability { false })
        render(viewModel)

        fillPhrase(FakeNoteRepository.DEFAULT_PHRASE)
        composeRule.onNodeWithTag("recovery_continue_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("recovery_new_password").performTextInput("weak")
        composeRule.onNodeWithTag("recovery_confirm_password").performTextInput("weak")
        composeRule.onNodeWithTag("recovery_reset_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("recovery_error").assertExists()
        composeRule.onNodeWithText("The password is missing: At least 12 characters.").assertExists()
        assertEquals(PasswordPolicy.Rule.LENGTH, viewModel.recovery.value.passwordRule)
        assertFalse(repository.calls.contains("resetWithRecoveryPhrase"))
        assertEquals(VaultStatus.LOCKED, repository.status.value)
    }

    @Test
    fun `complete recovery reset unlocks the vault`() {
        val repository = lockedVault()
        val viewModel = VaultViewModel(repository, BiometricAvailability { false })
        render(viewModel)

        fillPhrase(FakeNoteRepository.DEFAULT_PHRASE)
        composeRule.onNodeWithTag("recovery_continue_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("recovery_new_password")
            .performTextInput(FakeNoteRepository.OTHER_STRONG_PASSWORD)
        composeRule.onNodeWithTag("recovery_confirm_password")
            .performTextInput(FakeNoteRepository.OTHER_STRONG_PASSWORD)
        composeRule.onNodeWithTag("recovery_reset_button").performClick()
        composeRule.waitForIdle()

        assertEquals(VaultStatus.UNLOCKED, repository.status.value)
        assertEquals(FakeNoteRepository.OTHER_STRONG_PASSWORD, repository.password)
    }
}
