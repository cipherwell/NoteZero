package com.cipherwell.notezero.ui.code

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.ui.locks.LockBiometricPrompt
import com.cipherwell.notezero.ui.theme.NoteZeroTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose tests for the code editor's locked-file gate (decision #16, audit
 * finding F7).
 *
 * The workspace tree already filters locked code files; this gate is defence in depth for
 * every other route (restored id, deep link, task restore, a future preview). A locked and
 * unverified file must render no name, no source and no counts, and the editor ViewModel must
 * only be created after a successful re-authentication.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CodeEditorLockedGateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var repository: FakeCodeRepository
    private var backCount = 0

    @Before
    fun setUp() {
        repository = FakeCodeRepository()
        backCount = 0
    }

    private fun seed(locked: Boolean) {
        repository.seedCodeFile(
            id = "f1",
            title = "Secret.kt",
            body = "val secret = \"must not render\"",
            locked = locked,
        )
    }

    private fun openEditor() {
        composeRule.setContent {
            NoteZeroTheme {
                CodeEditorScreen(
                    repository = repository,
                    itemId = "f1",
                    onBack = { backCount++ },
                    biometricPrompt = LockBiometricPrompt.Unavailable,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `locked unverified file renders only the gate`() {
        seed(locked = true)
        openEditor()

        composeRule.onNodeWithTag("editor_locked_gate").assertExists()
        composeRule.onNodeWithTag("editor_locked_reveal").assertExists()
        composeRule.onNodeWithTag(CodeTags.EDITOR).assertDoesNotExist()
        composeRule.onNodeWithTag(CodeTags.LINE_NUMBERS).assertDoesNotExist()
        composeRule.onNodeWithText("Secret.kt").assertDoesNotExist()
        composeRule.onNodeWithText("val secret = \"must not render\"").assertDoesNotExist()
    }

    @Test
    fun `gate back button closes the editor without revealing the file`() {
        seed(locked = true)
        openEditor()

        composeRule.onNodeWithTag("editor_locked_back").performClick()
        composeRule.waitForIdle()

        assertEquals(1, backCount)
        composeRule.onNodeWithText("Secret.kt").assertDoesNotExist()
    }

    @Test
    fun `re-authenticating at the gate reveals the code editor`() {
        repository.masterPassword = "Correct-Horse-9!"
        seed(locked = true)
        openEditor()

        composeRule.onNodeWithTag("editor_locked_reveal").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reauth_dialog").assertExists()

        composeRule.onNodeWithTag("reauth_password").performTextInput("Correct-Horse-9!")
        composeRule.onNodeWithTag("reauth_confirm").performClick()
        composeRule.waitForIdle()

        assertTrue("f1" in repository.verifiedLockedIds.value)
        composeRule.onNodeWithTag("editor_locked_gate").assertDoesNotExist()
        composeRule.onNodeWithTag(CodeTags.EDITOR).assertExists()
        composeRule.onNodeWithTag(CodeTags.EDITOR)
            .assertTextContains("val secret = \"must not render\"", substring = true)
    }

    @Test
    fun `cancelling the gate re-auth keeps the file locked`() {
        repository.masterPassword = "Correct-Horse-9!"
        seed(locked = true)
        openEditor()

        composeRule.onNodeWithTag("editor_locked_reveal").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reauth_cancel").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("editor_locked_gate").assertExists()
        composeRule.onNodeWithTag(CodeTags.EDITOR).assertDoesNotExist()
        composeRule.onNodeWithText("Secret.kt").assertDoesNotExist()
    }

    @Test
    fun `already verified locked file opens the normal editor`() {
        seed(locked = true)
        repository.revealForTest("f1")
        openEditor()

        composeRule.onNodeWithTag("editor_locked_gate").assertDoesNotExist()
        composeRule.onNodeWithTag(CodeTags.EDITOR).assertExists()
        composeRule.onNodeWithTag(CodeTags.STATUS).assertTextContains("Kotlin", substring = true)
    }

    @Test
    fun `clearing verifications re-masks an open code editor immediately`() {
        seed(locked = true)
        repository.revealForTest("f1")
        openEditor()
        composeRule.onNodeWithTag(CodeTags.EDITOR).assertExists()

        composeRule.runOnIdle { repository.clearLockVerifications() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("editor_locked_gate").assertExists()
        composeRule.onNodeWithTag(CodeTags.EDITOR).assertDoesNotExist()
        composeRule.onNodeWithText("val secret = \"must not render\"").assertDoesNotExist()
    }

    @Test
    fun `unlocked file is unaffected by the gate`() {
        seed(locked = false)
        openEditor()

        composeRule.onNodeWithTag("editor_locked_gate").assertDoesNotExist()
        composeRule.onNodeWithTag(CodeTags.EDITOR).assertExists()
        composeRule.onNodeWithTag(CodeTags.EDITOR)
            .assertTextContains("val secret = \"must not render\"", substring = true)
    }
}
