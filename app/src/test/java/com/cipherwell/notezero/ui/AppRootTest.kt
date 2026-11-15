package com.cipherwell.notezero.ui

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.ui.components.common.BiometricAvailability
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end shell routing on Robolectric: setup -> locked -> notes -> settings, plus
 * the re-lock overlay and the stable tags the Lead's emulator smoke test drives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AppRootTest : UiTestBase() {

    private fun viewModel(repository: FakeNoteRepository): VaultViewModel =
        VaultViewModel(repository, BiometricAvailability { false })

    @Test
    fun `fresh install routes to setup`() {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)
        setThemedContent { AppRoot(repository = repository, viewModel = viewModel) }

        composeRule.onNodeWithTag("app_root").assertExists()
        composeRule.onNodeWithTag("setup_password").assertExists()
        composeRule.onNodeWithTag("setup_create_button").assertExists()
        composeRule.onNodeWithTag("locked_overlay").assertDoesNotExist()
    }

    @Test
    fun `locked vault shows unlock, a wrong-password error, then the notes list`() {
        val repository = FakeNoteRepository(
            initialStatus = VaultStatus.LOCKED,
            initialPassword = FakeNoteRepository.STRONG_PASSWORD,
        )
        val viewModel = viewModel(repository)
        setThemedContent { AppRoot(repository = repository, viewModel = viewModel) }

        composeRule.onNodeWithTag("unlock_password").assertExists()
        composeRule.onNodeWithTag("unlock_biometric").assertDoesNotExist()

        composeRule.onNodeWithTag("unlock_password").performTextInput("wrong-password")
        composeRule.onNodeWithTag("unlock_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("unlock_error").assertExists()
        assertEquals(UnlockFailure.WRONG_PASSWORD, viewModel.unlock.value.failure)

        composeRule.onNodeWithTag("unlock_password")
            .performTextInput(FakeNoteRepository.STRONG_PASSWORD)
        composeRule.onNodeWithTag("unlock_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("notes_list").assertExists()
        composeRule.onNodeWithTag("locked_overlay").assertDoesNotExist()
    }

    @Test
    fun `immediate re-lock shows the locked overlay`() {
        val repository = FakeNoteRepository(
            initialStatus = VaultStatus.UNLOCKED,
            initialPassword = FakeNoteRepository.STRONG_PASSWORD,
        )
        val viewModel = viewModel(repository)
        setThemedContent { AppRoot(repository = repository, viewModel = viewModel) }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("notes_list").assertExists()
        composeRule.onNodeWithTag("locked_overlay").assertDoesNotExist()

        viewModel.lockNow()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("locked_overlay").assertExists()
        composeRule.onNodeWithTag("unlock_password").assertExists()
        assertEquals(VaultStatus.LOCKED, repository.status.value)
    }

    @Test
    fun `settings route is reachable from the notes pill navigation and returns`() {
        val repository = FakeNoteRepository(
            initialStatus = VaultStatus.UNLOCKED,
            initialPassword = FakeNoteRepository.STRONG_PASSWORD,
        )
        val viewModel = viewModel(repository)
        setThemedContent { AppRoot(repository = repository, viewModel = viewModel) }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_screen").assertExists()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_screen").assertDoesNotExist()
        composeRule.onNodeWithTag("notes_list").assertExists()
    }

    @Test
    fun `a recovery reset confirms the phrase is unchanged and can be dismissed`() {
        val repository = FakeNoteRepository(
            initialStatus = VaultStatus.LOCKED,
            initialPassword = FakeNoteRepository.STRONG_PASSWORD,
        )
        val viewModel = viewModel(repository)
        setThemedContent { AppRoot(repository = repository, viewModel = viewModel) }
        composeRule.onNodeWithTag("unlock_password").assertExists()

        // Drive the reset through the real VaultViewModel state machine (the 12-word UI entry
        // itself is covered by RecoveryScreenTest); the shell must surface the non-secret
        // "your phrase is unchanged" confirmation once the vault unlocks (audit finding F2).
        composeRule.runOnIdle {
            viewModel.resetWithRecoveryPhrase(
                FakeNoteRepository.DEFAULT_PHRASE,
                FakeNoteRepository.OTHER_STRONG_PASSWORD.toCharArray(),
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("notes_list").assertExists()
        composeRule.onNodeWithTag("recovery_reset_notice").assertExists()
        composeRule.onNodeWithTag("recovery_reset_notice_dismiss").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("recovery_reset_notice").assertDoesNotExist()
        composeRule.onNodeWithTag("notes_list").assertExists()
    }

    @Test
    fun `biometric CTA is hidden when unlock was never enabled`() {
        // Available hardware, but biometric unlock was never enabled -> no CTA. Device QA:
        // the button used to be shown and could only fail with an "unavailable" message.
        val repository = FakeNoteRepository(
            initialStatus = VaultStatus.LOCKED,
            initialPassword = FakeNoteRepository.STRONG_PASSWORD,
        )
        val vm = VaultViewModel(repository, BiometricAvailability { true })
        setThemedContent { AppRoot(repository = repository, viewModel = vm) }
        composeRule.onNodeWithTag("unlock_password").assertExists()
        composeRule.onNodeWithTag("unlock_biometric").assertDoesNotExist()
    }

    @Test
    fun `biometric CTA is offered when a wrapped VMK is enrolled`() {
        val repository = FakeNoteRepository(
            initialStatus = VaultStatus.LOCKED,
            initialPassword = FakeNoteRepository.STRONG_PASSWORD,
        ).apply { forceBiometricEnrolled() }
        val vm = VaultViewModel(repository, BiometricAvailability { true })
        setThemedContent { AppRoot(repository = repository, viewModel = vm) }
        composeRule.onNodeWithTag("unlock_biometric").assertExists()
    }
}
