package com.cipherwell.notezero.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.book.EpubParser
import com.cipherwell.notezero.data.AttachmentImport
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.UiTestBase
import com.cipherwell.notezero.ui.locks.LockBiometricPrompt
import com.cipherwell.notezero.ui.notes.FakeNoteRepository
import com.cipherwell.notezero.ui.reader.BookReaderScreen
import com.cipherwell.notezero.ui.reader.EpubTestData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * Robolectric + Compose tests for the universal file workspace.
 *
 * The screen is mounted against the lock-aware [FakeNoteRepository] (no crypto, no Room, no
 * biometrics) with records seeded across every [ItemKind], a folder, a locked+unverified item
 * and an item inside a hidden locked folder. Assertions go through the UI semantics and the
 * repository index only — never through content logs.
 *
 * A test-only seam ([WorkspaceScreenContent]) injects lightweight hosts for the PDF/sketch
 * routing tests, exactly like the existing reader tests inject their renderers; the EPUB reader
 * integration uses a real in-memory book. [Config] pins sdk 34 with no display qualifiers (the
 * Robolectric-safe combination for dialogs and text fields).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WorkspaceScreenTest : UiTestBase() {

    private lateinit var repository: FakeNoteRepository
    private val openedNotes = mutableListOf<String>()

    @Before
    fun setUpRepository() {
        repository = FakeNoteRepository()
        openedNotes.clear()
    }

    // ---- Fixtures ------------------------------------------------------------

    private fun attachment(
        id: String,
        name: String,
        mime: String = "application/octet-stream",
        size: Long = 1_024L,
    ) = AttachmentRef(
        id = id,
        displayName = name,
        mimeType = mime,
        sizeBytes = size,
        createdAt = 1L,
        sha256 = "0".repeat(64),
    )

    private fun item(
        id: String,
        kind: ItemKind = ItemKind.NOTE,
        title: String = "Title $id",
        body: String = "",
        tags: List<String> = emptyList(),
        folderId: String? = null,
        trashed: Boolean = false,
        locked: Boolean = false,
        updatedAt: Long = 1_000L,
        attachments: List<AttachmentRef> = emptyList(),
    ) = Item(
        id = id,
        kind = kind,
        title = title,
        body = body,
        tags = tags,
        folderId = folderId,
        trashed = trashed,
        locked = locked,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        version = 1,
        attachments = attachments,
    )

    private fun folder(id: String, name: String, locked: Boolean = false) =
        Folder(id = id, name = name, sortOrder = 0, createdAt = 1L, locked = locked)

    // ---- Harness -------------------------------------------------------------

    private fun showScreen(repository: NoteRepository = this.repository) {
        setThemedContent {
            WorkspaceScreen(
                repository = repository,
                onBack = {},
                onOpenNote = { openedNotes += it },
            )
        }
        composeRule.waitForIdle()
    }

    /**
     * Mounts the internal seam with lightweight PDF/sketch hosts: this verifies the workspace
     * routing state machine without inflating the platform PDF renderer or the drawing canvas.
     */
    private fun showScreenWithFakeHosts(repository: NoteRepository = this.repository) {
        setThemedContent {
            WorkspaceScreenContent(
                repository = repository,
                onBack = {},
                onOpenNote = { openedNotes += it },
                pdfHost = { id, onClose ->
                    Column {
                        Text("pdf-host:$id")
                        TextButton(
                            onClick = onClose,
                            modifier = Modifier.testTag("fake_host_close"),
                        ) {
                            Text("close")
                        }
                    }
                },
                sketchHost = { id, onClose ->
                    Column {
                        Text("sketch-host:$id")
                        TextButton(
                            onClick = onClose,
                            modifier = Modifier.testTag("fake_host_close"),
                        ) {
                            Text("close")
                        }
                    }
                },
                bookHost = { id, onClose ->
                    BookReaderScreen(repository = repository, itemId = id, onClose = onClose)
                },
                biometrics = LockBiometricPrompt.Unavailable,
            )
        }
        composeRule.waitForIdle()
    }

    private fun longPressFirstRow() {
        composeRule.onAllNodesWithTag("workspace_item")[0].performTouchInput { longClick() }
        composeRule.waitForIdle()
    }

    private fun rowCount(): Int =
        composeRule.onAllNodesWithTag("workspace_item").fetchSemanticsNodes().size

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForNoTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForRepository(condition: () -> Boolean) {
        composeRule.waitUntil(5_000) { condition() }
    }

    private fun clickFilter(tag: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun clickBulk(tag: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun seedRealBook(title: String = EpubTestData.BOOK_TITLE): String = runBlocking {
        val created = repository.createItem(ItemKind.BOOK_FILE)
        val saved = repository.saveItem(created.copy(title = title))
        val imported = repository.importAttachment(
            noteId = saved.id,
            displayName = "book.epub",
            mimeType = EpubParser.MIME,
            source = ByteArrayInputStream(EpubTestData.validEpub(title = title)),
        )
        check(imported is AttachmentImport.Success) { "test EPUB import failed" }
        saved.id
    }

    // ---- Rows / labels / folder ---------------------------------------------

    @Test
    fun `shows note code and pdf rows with type labels folder names and the title bar`() {
        repository.setFolders(listOf(folder("f1", "Projects")))
        repository.setItems(
            listOf(
                item("note", ItemKind.NOTE, title = "Team plan", folderId = "f1", updatedAt = 3_000L),
                item("code", ItemKind.CODE_FILE, title = "script.kt", updatedAt = 2_000L),
                item("pdf", ItemKind.PDF_FILE, title = "Manual", updatedAt = 1_000L),
            ),
        )
        showScreen()

        composeRule.onNodeWithTag("workspace_screen").assertExists()
        composeRule.onNodeWithTag("workspace_title").assertTextContains("Files")
        composeRule.onNodeWithTag("workspace_back").assertExists()
        composeRule.onNodeWithText("Team plan").assertExists()
        composeRule.onNodeWithTag("workspace_type_note", useUnmergedTree = true).assertExists()
        // Folder name resolved for the folder item; root items render the no-folder fallback.
        composeRule.onNodeWithText("Projects").assertExists()
        assertTrue(
            composeRule.onAllNodesWithText("No folder").fetchSemanticsNodes().isNotEmpty(),
        )
        // The LazyColumn only composes the viewport, so scroll to the remaining rows.
        composeRule.onNodeWithTag("workspace_list").performScrollToNode(hasText("script.kt"))
        composeRule.onNodeWithText("script.kt").assertExists()
        composeRule.onNodeWithTag("workspace_type_code", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("workspace_list").performScrollToNode(hasText("Manual"))
        composeRule.onNodeWithText("Manual").assertExists()
        composeRule.onNodeWithTag("workspace_type_pdf", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("workspace_storage_summary").assertExists()
    }

    @Test
    fun `each type filter shows only its kind including books and drawings`() {
        repository.setItems(
            listOf(
                item("note", ItemKind.NOTE, title = "A note", updatedAt = 5_000L),
                item("code", ItemKind.CODE_FILE, title = "A code file", updatedAt = 4_000L),
                item("pdf", ItemKind.PDF_FILE, title = "A pdf", updatedAt = 3_000L),
                item("book", ItemKind.BOOK_FILE, title = "A book", updatedAt = 2_000L),
                item("sketch", ItemKind.SKETCH_FILE, title = "A sketch", updatedAt = 1_000L),
            ),
        )
        showScreen()

        clickFilter("workspace_filter_books")
        assertEquals(1, rowCount())
        composeRule.onNodeWithTag("workspace_type_book", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("A book").assertExists()

        clickFilter("workspace_filter_drawings")
        assertEquals(1, rowCount())
        composeRule.onNodeWithTag("workspace_type_sketch", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("A sketch").assertExists()

        clickFilter("workspace_filter_code")
        assertEquals(1, rowCount())
        composeRule.onNodeWithText("A code file").assertExists()

        clickFilter("workspace_filter_pdfs")
        composeRule.onNodeWithText("A pdf").assertExists()

        clickFilter("workspace_filter_notes")
        composeRule.onNodeWithText("A note").assertExists()

        clickFilter("workspace_filter_all")
        composeRule.onNodeWithText("A note").assertExists()
    }

    // ---- Masking / hidden folders -------------------------------------------

    @Test
    fun `masked and hidden-folder items are absent from rows search and storage summary`() {
        repository.setFolders(
            listOf(
                folder("f-open", "Projects"),
                folder("f-locked", "Secret folder", locked = true),
            ),
        )
        repository.setItems(
            listOf(
                item(
                    "visible",
                    title = "Visible note",
                    folderId = "f-open",
                    attachments = listOf(attachment("a1", "visible.pdf", size = 100L)),
                ),
                item(
                    "masked",
                    title = "Passport scan",
                    body = "number 12345",
                    locked = true,
                    attachments = listOf(attachment("a2", "hidden.pdf", size = 8_888L)),
                ),
                item(
                    "hidden",
                    title = "Inside secret folder",
                    folderId = "f-locked",
                    attachments = listOf(attachment("a3", "hidden2.pdf", size = 7_777L)),
                ),
            ),
        )
        showScreen()

        assertEquals(1, rowCount())
        composeRule.onNodeWithText("Visible note").assertExists()
        composeRule.onNodeWithText("Passport scan").assertDoesNotExist()
        composeRule.onNodeWithText("number 12345").assertDoesNotExist()
        composeRule.onNodeWithText("Inside secret folder").assertDoesNotExist()
        composeRule.onNodeWithText("Secret folder").assertDoesNotExist()
        // Only the visible item's attachment bytes are counted.
        composeRule.onNodeWithTag("workspace_storage_summary")
            .assertTextContains("1 items · 1 attachments · 100 B")

        // Searching for masked content can never match it.
        composeRule.onNodeWithTag("workspace_search").performTextInput("Passport")
        composeRule.waitForIdle()
        assertEquals(0, rowCount())
        waitForTag("workspace_no_matches")
        composeRule.onNodeWithText("Passport scan").assertDoesNotExist()
    }

    @Test
    fun `verified locked item is visible with a lock glyph`() {
        repository.setItems(
            listOf(
                item("locked", title = "Revealed note", locked = true, updatedAt = 2_000L),
                item("open", title = "Open note", updatedAt = 1_000L),
            ),
        )
        repository.revealForTest("locked")
        showScreen()

        assertEquals(2, rowCount())
        composeRule.onNodeWithText("Revealed note").assertExists()
        composeRule.onNodeWithTag("workspace_locked_glyph", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Locked file").assertExists()
    }

    // ---- Search / sort -------------------------------------------------------

    @Test
    fun `search filters rows case insensitively and a body match shows a snippet`() {
        repository.setItems(
            listOf(
                item("target", title = "Shopping", body = "remember the distinctive searchphrase"),
                item("other", title = "Unrelated", body = "nothing to see"),
            ),
        )
        showScreen()

        composeRule.onNodeWithTag("workspace_search").performTextInput("SEARCHPHRASE")
        composeRule.waitForIdle()

        assertEquals(1, rowCount())
        composeRule.onNodeWithText("Shopping").assertExists()
        composeRule.onNodeWithTag("workspace_snippet", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Unrelated").assertDoesNotExist()

        composeRule.onNodeWithTag("workspace_clear_search").performClick()
        composeRule.waitForIdle()
        assertEquals(2, rowCount())
    }

    @Test
    fun `search matches attachment file names and shows no-matches state`() {
        repository.setItems(
            listOf(
                item(
                    "pdf",
                    ItemKind.PDF_FILE,
                    title = "Scanned",
                    attachments = listOf(attachment("a", "passport-2026.pdf", "application/pdf")),
                ),
            ),
        )
        showScreen()

        composeRule.onNodeWithTag("workspace_search").performTextInput("passport")
        composeRule.waitForIdle()
        assertEquals(1, rowCount())
        composeRule.onNodeWithText("Scanned").assertExists()

        composeRule.onNodeWithTag("workspace_search").performTextInput("zzz")
        composeRule.waitForIdle()
        assertEquals(0, rowCount())
        waitForTag("workspace_no_matches")
        assertEquals(0, rowCount())
        // Matched rows render no snippet when the title/file name (not the body) matched.
        composeRule.onNodeWithTag("workspace_clear_search").performClick()
        composeRule.waitForIdle()
        assertEquals(1, rowCount())
        composeRule.onNodeWithTag("workspace_snippet", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `sort menu switches between last updated and name order`() {
        repository.setItems(
            listOf(
                item("zulu", title = "Zulu", updatedAt = 3_000L),
                item("alpha", title = "alpha", updatedAt = 2_000L),
                item("beta", title = "Beta", updatedAt = 1_000L),
            ),
        )
        showScreen()

        composeRule.onAllNodesWithTag("workspace_item")[0].assert(hasText("Zulu", substring = true))

        composeRule.onNodeWithTag("workspace_sort").performClick()
        composeRule.waitForIdle()
        waitForTag("workspace_sort_menu")
        composeRule.onNodeWithTag("workspace_sort_name").performClick()
        composeRule.waitForIdle()

        // LazyColumn anchors the previously visible key across a reorder, so return to the top.
        composeRule.onNodeWithTag("workspace_list").performScrollToIndex(0)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("workspace_item")[0].assert(hasText("alpha", substring = true))

        composeRule.onNodeWithTag("workspace_sort").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("workspace_sort_updated").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("workspace_list").performScrollToIndex(0)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("workspace_item")[0].assert(hasText("Zulu", substring = true))
    }

    @Test
    fun `storage summary aggregates attachments of the visible index only`() {
        repository.setFolders(listOf(folder("f-locked", "Secret", locked = true)))
        repository.setItems(
            listOf(
                item("note", title = "Plain note", updatedAt = 4_000L),
                item(
                    "pdf",
                    ItemKind.PDF_FILE,
                    title = "Document",
                    updatedAt = 3_000L,
                    attachments = listOf(
                        attachment("a1", "one.pdf", "application/pdf", size = 100L),
                        attachment("a2", "two.pdf", "application/pdf", size = 200L),
                    ),
                ),
                item(
                    "masked",
                    locked = true,
                    updatedAt = 2_000L,
                    attachments = listOf(attachment("a3", "hidden.pdf", size = 9_999L)),
                ),
                item(
                    "hidden",
                    folderId = "f-locked",
                    updatedAt = 1_000L,
                    attachments = listOf(attachment("a4", "hidden2.pdf", size = 8_888L)),
                ),
            ),
        )
        showScreen()

        composeRule.onNodeWithTag("workspace_storage_summary")
            .assertTextContains("2 items · 2 attachments · 300 B")

        // Filtering/searching does not change the vault-level summary.
        clickFilter("workspace_filter_notes")
        composeRule.onNodeWithTag("workspace_storage_summary")
            .assertTextContains("2 items · 2 attachments · 300 B")
    }

    @Test
    fun `empty vault shows the empty title and message`() {
        showScreen()

        waitForTag("workspace_empty")
        composeRule.onNodeWithText("No files yet").assertExists()
        composeRule.onNodeWithTag("workspace_storage_summary")
            .assertTextContains("0 items · 0 attachments · 0 B")
    }

    // ---- Multi-select and bulk operations -----------------------------------

    @Test
    fun `long press enters selection select-all counts every visible row and clear exits`() {
        repository.setItems(
            listOf(
                item("a", title = "Alpha", updatedAt = 3_000L),
                item("b", title = "Beta", updatedAt = 2_000L),
                item("c", title = "Gamma", updatedAt = 1_000L),
            ),
        )
        showScreen()

        composeRule.onNodeWithTag("workspace_title").assertExists()
        longPressFirstRow()

        composeRule.onNodeWithTag("workspace_selected_count").assertTextContains("1 selected")
        composeRule.onAllNodesWithTag("workspace_item_checkbox").assertCountEquals(3)

        clickBulk("cd_workspace_select_all")
        composeRule.onNodeWithTag("workspace_selected_count").assertTextContains("3 selected")

        clickBulk("workspace_clear_selection")
        waitForNoTag("workspace_selected_count")
        composeRule.onNodeWithTag("workspace_title").assertExists()
        composeRule.onAllNodesWithTag("workspace_item_checkbox").assertCountEquals(0)
    }

    @Test
    fun `overflow select all enters selection for every visible row`() {
        repository.setItems(
            listOf(
                item("a", title = "Alpha", updatedAt = 2_000L),
                item("b", title = "Beta", updatedAt = 1_000L),
            ),
        )
        showScreen()

        composeRule.onNodeWithTag("workspace_more_actions").performClick()
        composeRule.waitForIdle()
        waitForTag("workspace_actions_menu")
        composeRule.onNodeWithTag("workspace_select_all").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("workspace_selected_count").assertTextContains("2 selected")
        composeRule.onAllNodesWithTag("workspace_item_checkbox").assertCountEquals(2)
    }

    @Test
    fun `bulk move moves every selected row to the chosen folder and clears selection`() {
        repository.setFolders(listOf(folder("f1", "Projects")))
        repository.setItems(
            listOf(
                item("a", title = "Alpha", updatedAt = 2_000L),
                item("b", title = "Beta", updatedAt = 1_000L),
            ),
        )
        showScreen()

        longPressFirstRow()
        composeRule.onAllNodesWithTag("workspace_item")[1].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("workspace_selected_count").assertTextContains("2 selected")

        clickBulk("workspace_move")
        waitForTag("move_target_folder")
        composeRule.onAllNodesWithTag("move_target_folder")[0].performClick()

        waitForRepository {
            repository.items.value.filter { !it.trashed }.all { it.folderId == "f1" }
        }
        waitForNoTag("workspace_selected_count")
        assertEquals(2, repository.items.value.count { it.folderId == "f1" })
    }

    @Test
    fun `bulk lock locks every selected row hides them as masked and clears selection`() {
        repository.setItems(
            listOf(
                item("a", title = "Alpha", updatedAt = 2_000L),
                item("b", title = "Beta", updatedAt = 1_000L),
            ),
        )
        showScreen()

        longPressFirstRow()
        composeRule.onAllNodesWithTag("workspace_item")[1].performClick()
        composeRule.waitForIdle()
        clickBulk("workspace_lock")

        waitForRepository { repository.items.value.all { it.locked } }
        waitForNoTag("workspace_selected_count")
        // Locked+unverified rows are masked out entirely (fail closed).
        waitForNoTag("workspace_item")
        composeRule.onNodeWithText("Alpha").assertDoesNotExist()
        composeRule.onNodeWithText("Beta").assertDoesNotExist()
    }

    @Test
    fun `bulk unlock unlocks every selected verified locked row`() {
        repository.setItems(
            listOf(
                item("a", title = "Alpha", locked = true, updatedAt = 2_000L),
                item("b", title = "Beta", locked = true, updatedAt = 1_000L),
            ),
        )
        repository.revealForTest("a")
        repository.revealForTest("b")
        showScreen()

        assertEquals(2, rowCount())
        longPressFirstRow()
        composeRule.onAllNodesWithTag("workspace_item")[1].performClick()
        composeRule.waitForIdle()
        clickBulk("workspace_unlock")

        waitForRepository { repository.items.value.none { it.locked } }
        waitForNoTag("workspace_selected_count")
        assertEquals(2, rowCount())
        composeRule.onNodeWithText("Alpha").assertExists()
    }

    @Test
    fun `trash asks for confirmation cancel keeps the selection and rows`() {
        repository.setItems(listOf(item("a", title = "Trash me", updatedAt = 2_000L)))
        showScreen()

        longPressFirstRow()
        clickBulk("workspace_trash")
        waitForTag("workspace_trash_dialog")
        composeRule.onNodeWithText("Trash me").assertExists()

        composeRule.onNodeWithTag("workspace_cancel").performClick()
        composeRule.waitForIdle()
        waitForNoTag("workspace_trash_dialog")
        assertFalse(repository.itemById("a")!!.trashed)
        composeRule.onNodeWithTag("workspace_selected_count").assertExists()
        assertEquals(1, rowCount())
    }

    @Test
    fun `trash confirmation trashes selected rows and clears the selection`() {
        repository.setItems(
            listOf(
                item("a", title = "Alpha", updatedAt = 2_000L),
                item("b", title = "Beta", updatedAt = 1_000L),
            ),
        )
        showScreen()

        longPressFirstRow()
        composeRule.onAllNodesWithTag("workspace_item")[1].performClick()
        composeRule.waitForIdle()
        clickBulk("workspace_trash")
        waitForTag("workspace_trash_dialog")
        composeRule.onNodeWithTag("workspace_trash_confirm").performClick()

        waitForRepository { repository.items.value.all { it.trashed } }
        waitForNoTag("workspace_selected_count")
        waitForNoTag("workspace_item")
        composeRule.onNodeWithText("Alpha").assertDoesNotExist()
    }

    // ---- Failure handling ----------------------------------------------------

    @Test
    fun `repository failure shows the non-secret bulk notice and clears selection`() {
        repository.setItems(listOf(item("a", title = "Alpha")))
        val failing = object : NoteRepository by repository {
            override suspend fun setItemLocked(id: String, locked: Boolean): Boolean = false
        }
        showScreen(failing)

        longPressFirstRow()
        clickBulk("workspace_lock")

        waitForTag("workspace_bulk_failed")
        assertFalse(repository.itemById("a")!!.locked)
        waitForNoTag("workspace_selected_count")
        composeRule.onNodeWithText("Alpha").assertExists()
    }

    @Test
    fun `repository exception mid-bulk does not crash and reports the notice`() {
        repository.setItems(listOf(item("a", title = "Alpha")))
        val failing = object : NoteRepository by repository {
            override suspend fun trashItem(id: String) {
                throw IllegalStateException("vault locked mid-flight")
            }
        }
        showScreen(failing)

        longPressFirstRow()
        clickBulk("workspace_trash")
        waitForTag("workspace_trash_dialog")
        composeRule.onNodeWithTag("workspace_trash_confirm").performClick()

        waitForTag("workspace_bulk_failed")
        assertFalse(repository.itemById("a")!!.trashed)
        composeRule.onNodeWithText("Alpha").assertExists()
    }

    // ---- Open routing --------------------------------------------------------

    @Test
    fun `note and code row taps call onOpenNote with the item id`() {
        repository.setItems(
            listOf(
                item("note", ItemKind.NOTE, title = "Alpha", updatedAt = 2_000L),
                item("code", ItemKind.CODE_FILE, title = "Beta", updatedAt = 1_000L),
            ),
        )
        showScreen()

        composeRule.onNodeWithText("Alpha").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Beta").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("note", "code"), openedNotes)
    }

    @Test
    fun `verified locked note opens through the re-auth controller`() {
        repository.setItems(listOf(item("locked", title = "Revealed note", locked = true)))
        repository.revealForTest("locked")
        showScreen()

        composeRule.onNodeWithText("Revealed note").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("locked"), openedNotes)
        composeRule.onNodeWithTag("reauth_dialog").assertDoesNotExist()
    }

    @Test
    fun `pdf row opens the hosted pdf screen and closes back to the list`() {
        repository.setItems(listOf(item("pdf", ItemKind.PDF_FILE, title = "Manual")))
        showScreenWithFakeHosts()

        composeRule.onNodeWithText("Manual").performClick()
        composeRule.waitForIdle()
        waitForText("pdf-host:pdf")
        composeRule.onNodeWithText("pdf-host:pdf").assertExists()
        composeRule.onNodeWithTag("workspace_search").assertDoesNotExist()

        composeRule.onNodeWithTag("fake_host_close").performClick()
        composeRule.waitForIdle()
        waitForTag("workspace_search")
        composeRule.onNodeWithText("Manual").assertExists()
    }

    @Test
    fun `book row opens the real reader and back returns to the list`() {
        seedRealBook()
        showScreen()

        assertEquals(1, rowCount())
        composeRule.onAllNodesWithTag("workspace_item")[0].performClick()
        composeRule.waitForIdle()
        waitForTag("book_reader")
        composeRule.onNodeWithTag("book_reader").assertExists()

        composeRule.onNodeWithTag("book_back").performClick()
        composeRule.waitForIdle()
        waitForTag("workspace_search")
        assertEquals(1, rowCount())
    }

    @Test
    fun `sketch row opens the hosted studio and closes back to the list`() {
        repository.setItems(listOf(item("sketch", ItemKind.SKETCH_FILE, title = "Doodle")))
        showScreenWithFakeHosts()

        composeRule.onNodeWithText("Doodle").performClick()
        composeRule.waitForIdle()
        waitForText("sketch-host:sketch")
        composeRule.onNodeWithTag("workspace_search").assertDoesNotExist()

        composeRule.onNodeWithTag("fake_host_close").performClick()
        composeRule.waitForIdle()
        waitForTag("workspace_search")
    }

    // ---- Vault lock mid-session ----------------------------------------------

    @Test
    fun `vault lock closes a hosted screen and clears the selection`() {
        repository.setItems(listOf(item("pdf", ItemKind.PDF_FILE, title = "Manual")))
        showScreenWithFakeHosts()

        composeRule.onNodeWithText("Manual").performClick()
        composeRule.waitForIdle()
        waitForText("pdf-host:pdf")

        repository.setStatus(VaultStatus.LOCKED)
        composeRule.waitForIdle()

        waitForNoTag("fake_host_close")
        composeRule.onNodeWithTag("workspace_empty").assertExists()
    }

    @Test
    fun `vault lock mid-selection clears the selection and shows no rows`() {
        repository.setItems(
            listOf(
                item("a", title = "Alpha", updatedAt = 2_000L),
                item("b", title = "Beta", updatedAt = 1_000L),
            ),
        )
        showScreen()

        longPressFirstRow()
        composeRule.onNodeWithTag("workspace_selected_count").assertExists()

        repository.setStatus(VaultStatus.LOCKED)
        composeRule.waitForIdle()

        waitForNoTag("workspace_selected_count")
        waitForNoTag("workspace_item")
        composeRule.onNodeWithTag("workspace_empty").assertExists()
    }
}
