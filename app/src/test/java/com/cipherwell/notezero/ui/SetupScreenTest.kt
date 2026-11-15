package com.cipherwell.notezero.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.ui.components.common.BiometricAvailability
import com.cipherwell.notezero.ui.components.common.SensitiveClipboard
import com.cipherwell.notezero.ui.screens.setup.SetupScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose tests for first-run setup: validation, the one-time phrase, the
 * guarded clipboard and the confirmation gate. Uses [FakeNoteRepository] — no crypto.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SetupScreenTest : UiTestBase() {

    private fun viewModel(repository: FakeNoteRepository = FakeNoteRepository()): VaultViewModel =
        VaultViewModel(repository, BiometricAvailability { false })

    private fun setScreen(viewModel: VaultViewModel) {
        setThemedContent {
            val setup by viewModel.setup.collectAsState()
            SetupScreen(
                setup = setup,
                onCreateVault = viewModel::createVault,
                onAcknowledgePhrase = viewModel::acknowledgeRecoveryPhrase,
                onConfirmPhrase = viewModel::confirmRecoveryPhrase,
            )
        }
    }

    @Test
    fun `setup exposes the stable tags and gates the create button on both fields`() {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)
        setScreen(viewModel)

        composeRule.onNodeWithTag("setup_screen").assertExists()
        composeRule.onNodeWithTag("setup_password").assertExists()
        composeRule.onNodeWithTag("setup_confirm").assertExists()
        composeRule.onNodeWithTag("setup_create_button").assertIsNotEnabled()

        composeRule.onNodeWithTag("setup_password")
            .performTextInput(FakeNoteRepository.STRONG_PASSWORD)
        composeRule.onNodeWithTag("setup_confirm")
            .performTextInput(FakeNoteRepository.STRONG_PASSWORD)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("setup_create_button").assertIsEnabled()
    }

    @Test
    fun `weak password shows the rule error and never creates a vault`() {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)
        setScreen(viewModel)

        composeRule.onNodeWithTag("setup_password").performTextInput("weak")
        composeRule.onNodeWithTag("setup_confirm").performTextInput("weak")
        composeRule.onNodeWithTag("setup_create_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("setup_error").assertExists()
        composeRule.onNodeWithText("The password is missing: At least 12 characters.").assertExists()
        assertEquals(VaultStatus.SETUP_REQUIRED, repository.status.value)
        assertFalse(repository.calls.contains("setupVault"))
    }

    @Test
    fun `mismatched confirmation is reported without creating a vault`() {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)
        setScreen(viewModel)

        composeRule.onNodeWithTag("setup_password")
            .performTextInput(FakeNoteRepository.STRONG_PASSWORD)
        composeRule.onNodeWithTag("setup_confirm").performTextInput("Different-Pass-1!")
        composeRule.onNodeWithTag("setup_create_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("setup_error").assertExists()
        composeRule.onNodeWithText("do not match", substring = true).assertExists()
        assertFalse(repository.calls.contains("setupVault"))
    }

    @Test
    fun `phrase is shown once behind the acknowledgement gate and clipboard copy is guarded`() {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)
        viewModel.createVault(FakeNoteRepository.STRONG_PASSWORD.toCharArray())
        setScreen(viewModel)

        composeRule.onNodeWithTag("recovery_phrase_words").assertExists()
        FakeNoteRepository.DEFAULT_PHRASE.forEach { word ->
            composeRule.onNodeWithText(word).assertExists()
        }

        // Copy requires the in-app warning first.
        composeRule.onNodeWithText("Copy phrase").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Copy recovery phrase?").assertExists()
        composeRule.onNodeWithText("Copy anyway").performClick()
        composeRule.waitForIdle()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = requireNotNull(clipboard.primaryClip) { "expected the phrase on the clipboard" }
        assertEquals(
            FakeNoteRepository.DEFAULT_PHRASE.joinToString(" "),
            clip.getItemAt(0).text.toString(),
        )
        assertTrue(
            "clip must be marked sensitive for clipboard previews/history",
            clip.description.extras.getBoolean(SensitiveClipboard.SENSITIVE_EXTRA),
        )

        composeRule.onNodeWithTag("recovery_confirmed_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("setup_confirm_word_0").assertExists()
        composeRule.onNodeWithTag("setup_confirm_word_2").assertExists()
    }

    @Test
    fun `confirmation gate rejects wrong words and completes with the requested words`() {
        val repository = FakeNoteRepository()
        val viewModel = viewModel(repository)
        viewModel.createVault(FakeNoteRepository.STRONG_PASSWORD.toCharArray())
        setScreen(viewModel)

        composeRule.onNodeWithTag("recovery_confirmed_button").performClick()
        composeRule.waitForIdle()

        repeat(3) { position ->
            composeRule.onNodeWithTag("setup_confirm_word_$position")
                .performTextInput("notaword")
        }
        composeRule.onNodeWithTag("setup_finish_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("setup_error").assertExists()
        assertEquals(SetupStep.CONFIRM, viewModel.setup.value.step)

        val phrase = requireNotNull(viewModel.setup.value.phrase)
        val asked = viewModel.setup.value.confirmIndices.map { phrase[it] }
        asked.forEachIndexed { position, word ->
            composeRule.onNodeWithTag("setup_confirm_word_$position").performTextClearance()
            composeRule.onNodeWithTag("setup_confirm_word_$position").performTextInput(word)
        }
        composeRule.onNodeWithTag("setup_finish_button").performClick()
        composeRule.waitForIdle()

        assertEquals(SetupStep.DONE, viewModel.setup.value.step)
        assertEquals(null, viewModel.setup.value.phrase)
    }
}
