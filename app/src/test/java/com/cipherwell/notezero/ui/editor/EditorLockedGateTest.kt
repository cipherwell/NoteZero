package com.cipherwell.notezero.ui.editor

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.locks.LockBiometricPrompt
import com.cipherwell.notezero.ui.notes.FakeNoteRepository
import com.cipherwell.notezero.ui.theme.NoteZeroTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose tests for the editor's locked-note gate (decision #15).
 *
 * The gate must be decided before the [EditorViewModel] exists: a locked, unverified note
 * renders no title, body, counts or attachment names, and the full editor only appears
 * after a successful re-authentication. Clearing verifications re-shows the gate at once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EditorLockedGateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var repository: FakeNoteRepository
    private var closedCount = 0

    @Before
    fun setUp() {
        repository = FakeNoteRepository()
        closedCount = 0
    }

    private fun seed(locked: Boolean) {
        repository.setItems(
            listOf(
                Item(
                    id = "n1",
                    kind = ItemKind.NOTE,
                    title = "Secret title",
                    body = "secret body",
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                    locked = locked,
                ),
            ),
        )
    }

    private fun openEditor() {
        composeRule.setContent {
            NoteZeroTheme {
                NoteEditorScreen(
                    repository = repository,
                    itemId = "n1",
                    onClose = { closedCount++ },
                    biometricPrompt = LockBiometricPrompt.Unavailable,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `locked unverified note renders only the gate`() {
        seed(locked = true)
        openEditor()

        composeRule.onNodeWithTag("editor_locked_gate").assertExists()
        composeRule.onNodeWithTag("editor_locked_reveal").assertExists()
        composeRule.onNodeWithTag("editor_title").assertDoesNotExist()
        composeRule.onNodeWithTag("editor_body").assertDoesNotExist()
        composeRule.onNodeWithTag("editor_count").assertDoesNotExist()
        composeRule.onNodeWithText("Secret title").assertDoesNotExist()
        composeRule.onNodeWithText("secret body").assertDoesNotExist()
    }

    @Test
    fun `gate back button closes the editor without revealing content`() {
        seed(locked = true)
        openEditor()

        composeRule.onNodeWithTag("editor_locked_back").performClick()
        composeRule.waitForIdle()

        assertEquals(1, closedCount)
        composeRule.onNodeWithText("Secret title").assertDoesNotExist()
    }

    @Test
    fun `re-authenticating at the gate reveals the editor`() {
        repository.masterPassword = "Correct-Horse-9!"
        seed(locked = true)
        openEditor()

        composeRule.onNodeWithTag("editor_locked_reveal").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reauth_dialog").assertExists()

        composeRule.onNodeWithTag("reauth_password").performTextInput("Correct-Horse-9!")
        composeRule.onNodeWithTag("reauth_confirm").performClick()
        composeRule.waitForIdle()

        assertTrue("n1" in repository.verifiedLockedIds.value)
        composeRule.onNodeWithTag("editor_locked_gate").assertDoesNotExist()
        composeRule.onNodeWithTag("editor_title").assertExists()
        composeRule.onNodeWithTag("editor_title").assertTextContains("Secret title", substring = true)
        composeRule.onNodeWithTag("editor_body").assertTextContains("secret body", substring = true)
    }

    @Test
    fun `cancelling the gate re-auth keeps the editor locked`() {
        repository.masterPassword = "Correct-Horse-9!"
        seed(locked = true)
        openEditor()

        composeRule.onNodeWithTag("editor_locked_reveal").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reauth_cancel").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("editor_locked_gate").assertExists()
        composeRule.onNodeWithTag("editor_title").assertDoesNotExist()
        composeRule.onNodeWithText("Secret title").assertDoesNotExist()
    }

    @Test
    fun `already verified locked note opens the normal editor`() {
        seed(locked = true)
        repository.revealForTest("n1")
        openEditor()

        composeRule.onNodeWithTag("editor_locked_gate").assertDoesNotExist()
        composeRule.onNodeWithTag("editor_title").assertTextContains("Secret title", substring = true)
    }

    @Test
    fun `clearing verifications re-masks an open editor immediately`() {
        seed(locked = true)
        repository.revealForTest("n1")
        openEditor()
        composeRule.onNodeWithTag("editor_title").assertTextContains("Secret title", substring = true)

        composeRule.runOnIdle { repository.clearLockVerifications() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("editor_locked_gate").assertExists()
        composeRule.onNodeWithTag("editor_title").assertDoesNotExist()
        composeRule.onNodeWithText("Secret title").assertDoesNotExist()
    }

    @Test
    fun `unlocked note is unaffected by the gate`() {
        seed(locked = false)
        openEditor()

        composeRule.onNodeWithTag("editor_locked_gate").assertDoesNotExist()
        composeRule.onNodeWithTag("editor_title").assertTextContains("Secret title", substring = true)
    }
}
