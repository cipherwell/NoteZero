package com.cipherwell.notezero.ui.notes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.locks.LockBiometricPrompt
import com.cipherwell.notezero.ui.theme.NoteZeroTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose tests for the locked notes/folders visibility gate in the notes
 * list (decision #15): masked rows leak no content, tapping re-authenticates instead of
 * opening the editor, a revealed id re-masks the moment the repository clears it, and the
 * password dialog is the graceful fallback when no biometric prompt is available.
 *
 * Runs against [FakeNoteRepository]; no real crypto, Room, biometrics or sleeps.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LockedNotesListTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var repository: FakeNoteRepository

    @Before
    fun setUp() {
        repository = FakeNoteRepository()
    }

    private fun note(
        id: String,
        title: String,
        body: String = "",
        folderId: String? = null,
        tags: List<String> = emptyList(),
        trashed: Boolean = false,
        locked: Boolean = false,
        updatedAt: Long = 1_000L,
    ) = Item(
        id = id,
        kind = ItemKind.NOTE,
        title = title,
        body = body,
        tags = tags,
        folderId = folderId,
        trashed = trashed,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        locked = locked,
    )

    private fun setScreen(
        biometricPrompt: LockBiometricPrompt = LockBiometricPrompt.Unavailable,
    ) {
        composeRule.setContent {
            NoteZeroTheme {
                NotesListScreen(
                    repository = repository,
                    onOpenSettings = {},
                    biometricPrompt = biometricPrompt,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun submitPassword(password: String) {
        composeRule.onNodeWithTag("reauth_password").performTextInput(password)
        composeRule.onNodeWithTag("reauth_confirm").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `locked unverified row renders a mask without content`() {
        repository.setItems(
            listOf(
                note(
                    "a",
                    title = "Passport scan",
                    body = "number 12345",
                    tags = listOf("identity"),
                    locked = true,
                ),
            ),
        )
        setScreen()

        composeRule.onNodeWithTag("locked_note_row").assertExists()
        composeRule.onAllNodesWithTag("note_row").assertCountEquals(0)
        composeRule.onNodeWithText("Locked note").assertExists()
        composeRule.onNodeWithContentDescription("Locked").assertExists()
        composeRule.onNodeWithText("Passport scan").assertDoesNotExist()
        composeRule.onNodeWithText("number 12345").assertDoesNotExist()
        composeRule.onNodeWithText("identity").assertDoesNotExist()
    }

    @Test
    fun `tapping a masked row opens the re-auth dialog instead of the editor`() {
        repository.setItems(listOf(note("a", title = "Secret", locked = true)))
        setScreen()

        composeRule.onNodeWithTag("locked_note_row").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reauth_dialog").assertExists()
        composeRule.onNodeWithTag("reauth_password").assertExists()
        composeRule.onNodeWithTag("reauth_confirm").assertExists()
        composeRule.onNodeWithTag("reauth_cancel").assertExists()
        composeRule.onNodeWithTag("editor_title").assertDoesNotExist()
        composeRule.onNodeWithTag("editor_body").assertDoesNotExist()
    }

    @Test
    fun `correct master password reveals the note and opens the editor`() {
        repository.masterPassword = "Correct-Horse-9!"
        repository.setItems(listOf(note("a", title = "Secret title", body = "secret body", locked = true)))
        setScreen()

        composeRule.onNodeWithTag("locked_note_row").performClick()
        composeRule.waitForIdle()
        submitPassword("Correct-Horse-9!")

        composeRule.onNodeWithTag("reauth_dialog").assertDoesNotExist()
        assertTrue("a" in repository.verifiedLockedIds.value)
        composeRule.onNodeWithTag("editor_title").assertExists()
        composeRule.onNodeWithTag("editor_title").assertTextContains("Secret title", substring = true)
        composeRule.onNodeWithTag("editor_body").assertTextContains("secret body", substring = true)
    }

    @Test
    fun `cancelling re-auth keeps the note masked`() {
        repository.masterPassword = "Correct-Horse-9!"
        repository.setItems(listOf(note("a", title = "Secret", locked = true)))
        setScreen()

        composeRule.onNodeWithTag("locked_note_row").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reauth_cancel").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reauth_dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("locked_note_row").assertExists()
        composeRule.onNodeWithText("Secret").assertDoesNotExist()
        composeRule.onNodeWithTag("editor_title").assertDoesNotExist()
        assertTrue(repository.verifiedLockedIds.value.isEmpty())
    }

    @Test
    fun `wrong password shows an error and keeps the note masked`() {
        repository.masterPassword = "Correct-Horse-9!"
        repository.setItems(listOf(note("a", title = "Secret", locked = true)))
        setScreen()

        composeRule.onNodeWithTag("locked_note_row").performClick()
        composeRule.waitForIdle()
        submitPassword("wrong")

        composeRule.onNodeWithTag("reauth_error")
            .assertTextContains("Incorrect master password", substring = true)
        composeRule.onNodeWithTag("locked_note_row").assertExists()
        composeRule.onNodeWithText("Secret").assertDoesNotExist()
        assertTrue(repository.verifiedLockedIds.value.isEmpty())
    }

    @Test
    fun `five failed attempts report the cooldown and keep content hidden`() {
        repository.masterPassword = "Correct-Horse-9!"
        repository.setItems(listOf(note("a", title = "Secret", locked = true)))
        setScreen()

        composeRule.onNodeWithTag("locked_note_row").performClick()
        composeRule.waitForIdle()
        repeat(5) { attempt ->
            submitPassword("wrong-$attempt")
        }

        composeRule.onNodeWithTag("reauth_dialog").assertExists()
        composeRule.onNodeWithTag("reauth_error")
            .assertTextContains("Too many attempts. Try again in 30 seconds.", substring = true)
        composeRule.onNodeWithTag("reauth_confirm").assertIsNotEnabled()
        composeRule.onNodeWithText("Secret").assertDoesNotExist()
        assertTrue(repository.verifiedLockedIds.value.isEmpty())
    }

    @Test
    fun `search excludes the title and body of a masked note`() {
        repository.setItems(
            listOf(
                note("s", title = "Passport scan", body = "number 12345", locked = true),
                note("p", title = "Kotlin", body = "coroutines"),
            ),
        )
        setScreen()

        composeRule.onNodeWithTag("nav_search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search_field").performTextInput("Passport")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("No matches").assertExists()
        composeRule.onAllNodesWithTag("note_row").assertCountEquals(0)
        composeRule.onAllNodesWithTag("locked_note_row").assertCountEquals(0)
        composeRule.onNodeWithText("Passport scan").assertDoesNotExist()

        composeRule.onNodeWithTag("search_clear").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search_field").performTextInput("coroutines")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Kotlin").assertExists()
    }

    @Test
    fun `tag chips exclude tags that only exist on masked notes`() {
        repository.setItems(
            listOf(
                note("s", title = "Hidden", tags = listOf("identity"), locked = true),
                note("p", title = "Plain", tags = listOf("errand")),
            ),
        )
        setScreen()

        composeRule.onNodeWithText("errand").assertExists()
        composeRule.onNodeWithText("identity").assertDoesNotExist()
    }

    @Test
    fun `locked folder is masked and entering it requires re-auth`() {
        repository.masterPassword = "Correct-Horse-9!"
        repository.setFolders(
            listOf(
                Folder(id = "locked", name = "Secret folder", locked = true),
                Folder(id = "child", name = "Secret child", parentId = "locked"),
            ),
        )
        repository.setItems(
            listOf(
                note("inside", title = "Hidden note", folderId = "locked"),
                note("root", title = "Root note"),
            ),
        )
        setScreen()

        composeRule.onNodeWithTag("folder_nav").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("locked_folder_row").assertExists()
        composeRule.onNodeWithText("Locked folder").assertExists()
        composeRule.onNodeWithText("Secret folder").assertDoesNotExist()
        composeRule.onNodeWithText("Secret child").assertDoesNotExist()
        composeRule.onNodeWithText("Hidden note").assertDoesNotExist()

        composeRule.onNodeWithTag("locked_folder_row").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reauth_dialog").assertExists()
        submitPassword("Correct-Horse-9!")

        composeRule.onNodeWithTag("reauth_dialog").assertDoesNotExist()
        assertTrue("locked" in repository.verifiedLockedIds.value)
        composeRule.onNodeWithText("Hidden note").assertExists()
        composeRule.onNodeWithText("Secret child").assertExists()
    }

    @Test
    fun `folder menu locks the folder and masks it immediately`() {
        repository.setFolders(listOf(Folder(id = "f", name = "Projects")))
        setScreen()

        composeRule.onNodeWithTag("folder_nav").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("folder_row_menu").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Lock folder").assertExists()
        composeRule.onNodeWithTag("folder_lock_action").performClick()
        composeRule.waitForIdle()

        assertTrue(repository.folderById("f")!!.locked)
        composeRule.onNodeWithTag("locked_folder_row").assertExists()
        composeRule.onNodeWithText("Projects").assertDoesNotExist()
    }

    @Test
    fun `note menu locks the note and masks it immediately`() {
        repository.setItems(listOf(note("a", title = "Alpha")))
        setScreen()

        composeRule.onNodeWithTag("note_row").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Lock note").assertExists()
        composeRule.onNodeWithTag("note_lock_action").performClick()
        composeRule.waitForIdle()

        assertTrue(repository.itemById("a")!!.locked)
        composeRule.onNodeWithTag("locked_note_row").assertExists()
        composeRule.onNodeWithText("Alpha").assertDoesNotExist()
    }

    @Test
    fun `removing a lock while masked requires re-auth`() {
        repository.masterPassword = "Correct-Horse-9!"
        repository.setItems(listOf(note("a", title = "Secret", locked = true)))
        setScreen()

        composeRule.onNodeWithTag("locked_note_row").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Remove lock").assertExists()
        composeRule.onNodeWithTag("note_lock_action").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reauth_dialog").assertExists()

        composeRule.onNodeWithTag("reauth_cancel").performClick()
        composeRule.waitForIdle()
        assertTrue(repository.itemById("a")!!.locked)
        composeRule.onNodeWithTag("locked_note_row").assertExists()

        // Retry and verify: only then may the lock be removed.
        composeRule.onNodeWithTag("locked_note_row").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("note_lock_action").performClick()
        composeRule.waitForIdle()
        submitPassword("Correct-Horse-9!")

        assertFalse(repository.itemById("a")!!.locked)
        assertEquals(emptySet<String>(), repository.verifiedLockedIds.value)
        composeRule.onNodeWithTag("note_row").assertExists()
        composeRule.onNodeWithText("Secret").assertExists()
    }

    @Test
    fun `re-mask happens immediately when the repository clears verifications`() {
        repository.setItems(listOf(note("a", title = "Secret", locked = true)))
        repository.revealForTest("a")
        setScreen()

        composeRule.onNodeWithText("Secret").assertExists()
        composeRule.onAllNodesWithTag("locked_note_row").assertCountEquals(0)

        composeRule.runOnIdle { repository.clearLockVerifications() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("locked_note_row").assertExists()
        composeRule.onNodeWithText("Secret").assertDoesNotExist()
    }

    @Test
    fun `biometric success reveals without a password dialog`() {
        repository.setItems(listOf(note("a", title = "Secret", locked = true)))
        val prompt = LockBiometricPrompt { onResult ->
            onResult(true)
            true
        }
        setScreen(biometricPrompt = prompt)

        composeRule.onNodeWithTag("locked_note_row").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reauth_dialog").assertDoesNotExist()
        assertTrue("a" in repository.verifiedLockedIds.value)
        composeRule.onNodeWithTag("editor_title").assertExists()
    }

    @Test
    fun `biometric cancel falls back to the password dialog and stays hidden`() {
        repository.setItems(listOf(note("a", title = "Secret", locked = true)))
        val prompt = LockBiometricPrompt { onResult ->
            onResult(false)
            true
        }
        setScreen(biometricPrompt = prompt)

        composeRule.onNodeWithTag("locked_note_row").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reauth_dialog").assertExists()
        composeRule.onNodeWithTag("reauth_error")
            .assertTextContains("cancelled", substring = true)
        composeRule.onNodeWithTag("locked_note_row").assertExists()
        composeRule.onNodeWithText("Secret").assertDoesNotExist()
    }
}
