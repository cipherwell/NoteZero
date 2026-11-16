package com.cipherwell.notezero.ui.notes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
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
 * Robolectric + Compose semantics tests for the notes list screen. No real crypto or
 * Room: the screen runs against [FakeNoteRepository], exactly as it does against the
 * encrypted repository in production (same frozen interface).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotesListScreenTest {

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
        pinned: Boolean = false,
        favorite: Boolean = false,
        trashed: Boolean = false,
        updatedAt: Long = 1_000L,
    ) = Item(
        id = id,
        kind = ItemKind.NOTE,
        title = title,
        body = body,
        tags = tags,
        folderId = folderId,
        pinned = pinned,
        favorite = favorite,
        trashed = trashed,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    private fun setScreen(onOpenSettings: () -> Unit = {}) {
        composeRule.setContent {
            NoteZeroTheme {
                NotesListScreen(repository = repository, onOpenSettings = onOpenSettings)
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `renders notes sections with stable test tags`() {
        repository.setItems(
            listOf(
                note("a", "Alpha", body = "first body", pinned = true, updatedAt = 300),
                note("b", "Beta", body = "second body", updatedAt = 200),
            ),
        )
        setScreen()

        composeRule.onNodeWithTag("notes_list").assertExists()
        composeRule.onAllNodesWithTag("note_row").assertCountEquals(2)
        composeRule.onNodeWithText("Pinned").assertExists()
        composeRule.onNodeWithText("Alpha").assertExists()
        composeRule.onNodeWithText("Beta").assertExists()
        composeRule.onNodeWithTag("new_note_button").assertExists()
        composeRule.onNodeWithTag("trash_button").assertExists()
        composeRule.onNodeWithTag("settings_button").assertExists()
    }

    @Test
    fun `settings pill delegates to the shell callback`() {
        var opened = 0
        setScreen(onOpenSettings = { opened++ })

        composeRule.onNodeWithTag("settings_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun `plus button opens the create sheet with the four item types`() {
        setScreen()

        composeRule.onNodeWithTag("new_note_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("create_sheet").assertExists()
        composeRule.onNodeWithTag("create_note").assertExists()
        composeRule.onNodeWithTag("create_drawing").assertExists()
        composeRule.onNodeWithTag("create_code").assertExists()
        composeRule.onNodeWithTag("create_pdf").assertExists()
    }

    @Test
    fun `create note from the sheet creates a note and opens the editor`() {
        setScreen()

        composeRule.onNodeWithTag("new_note_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("create_note").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("editor_title").assertExists()
        composeRule.onNodeWithTag("editor_body").assertExists()
        composeRule.onNodeWithTag("editor_back").assertExists()
        assertEquals(1, repository.items.value.size)
        assertEquals(ItemKind.NOTE, repository.items.value.single().kind)
    }

    @Test
    fun `create drawing opens a note with the drawing canvas already visible`() {
        setScreen()

        composeRule.onNodeWithTag("new_note_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("create_drawing").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("drawing_canvas").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("editor_title").assertExists()
        composeRule.onNodeWithTag("drawing_canvas").assertExists()
        assertEquals(ItemKind.NOTE, repository.items.value.single().kind)
    }

    @Test
    fun `create code picks a format and creates the code file`() {
        setScreen()

        composeRule.onNodeWithTag("new_note_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("create_code").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("code_new_file_format_row").assertExists()

        composeRule.onNodeWithTag("code_format_py").performScrollTo().performClick()
        composeRule.onNodeWithTag("code_dialog_confirm").performClick()
        composeRule.waitForIdle()

        // Code files open the dedicated code editor (monospace + lexer), not the markdown editor.
        composeRule.onNodeWithTag("code_editor").assertExists()
        val created = repository.items.value.single()
        assertEquals(ItemKind.CODE_FILE, created.kind)
        assertEquals("untitled.py", created.title)
    }

    @Test
    fun `create pdf stores an encrypted pdf attachment and opens the viewer`() {
        setScreen()

        composeRule.onNodeWithTag("new_note_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("create_pdf").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("pdf_viewer").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("pdf_viewer").assertExists()
        val created = repository.items.value.single { it.kind == ItemKind.PDF_FILE }
        assertEquals(1, created.attachments.size)
        assertEquals("application/pdf", created.attachments.single().mimeType)
        assertTrue(created.title.endsWith(".pdf"))
        assertEquals(BlankPdf.MIME_TYPE, created.attachments.single().mimeType)
    }

    @Test
    fun `search tab filters notes by body text`() {
        repository.setItems(
            listOf(
                note("milk", "Groceries", body = "milk and eggs", updatedAt = 200),
                note("code", "Kotlin", body = "coroutines", updatedAt = 100),
            ),
        )
        setScreen()

        composeRule.onNodeWithTag("nav_search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("notes_search").assertExists()
        composeRule.onNodeWithTag("search_field").performTextInput("milk")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("search_results").assertExists()
        composeRule.onAllNodesWithTag("note_row").assertCountEquals(1)
        composeRule.onNodeWithText("Groceries").assertExists()
    }

    @Test
    fun `trash button shows trash and restore brings a note back`() {
        repository.setItems(
            listOf(
                note("live", "Live note", updatedAt = 200),
                note("gone", "Deleted note", trashed = true, updatedAt = 100),
            ),
        )
        setScreen()

        composeRule.onNodeWithTag("trash_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("trash_list").assertExists()
        composeRule.onAllNodesWithTag("trash_row").assertCountEquals(1)

        composeRule.onNodeWithTag("trash_restore").performClick()
        composeRule.waitForIdle()
        assertFalse(repository.itemById("gone")!!.trashed)
    }

    @Test
    fun `long press opens context actions and pin persists`() {
        repository.setItems(listOf(note("a", "Alpha", updatedAt = 100)))
        setScreen()

        composeRule.onAllNodesWithTag("note_row")[0].performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Pin note").performClick()
        composeRule.waitForIdle()

        assertTrue(repository.itemById("a")!!.pinned)
    }

    @Test
    fun `folders tab renders nested folders and opens them`() {
        repository.setFolders(
            listOf(
                Folder(id = "root", name = "Projects"),
                Folder(id = "child", name = "NoteZero", parentId = "root"),
            ),
        )
        repository.setItems(listOf(note("a", "Root note", folderId = "root", updatedAt = 100)))
        setScreen()

        composeRule.onNodeWithTag("folder_nav").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("folders_list").assertExists()
        composeRule.onAllNodesWithTag("folder_row").assertCountEquals(1)

        composeRule.onAllNodesWithTag("folder_row")[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Root note").assertExists()
        composeRule.onAllNodesWithTag("folder_row").assertCountEquals(1)
    }

    @Test
    fun `favourites filter shows only favourites`() {
        repository.setItems(
            listOf(
                note("fav", "Favourite note", favorite = true, updatedAt = 200),
                note("plain", "Plain note", updatedAt = 100),
            ),
        )
        setScreen()

        composeRule.onNodeWithTag("favorites_filter").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Favourite note").assertExists()
        composeRule.onAllNodesWithTag("note_row").assertCountEquals(1)
    }
}
