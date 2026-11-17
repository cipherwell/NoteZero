package com.cipherwell.notezero.ui.library

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.book.EpubParser
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.ui.FakeNoteRepository
import com.cipherwell.notezero.ui.UiTestBase
import com.cipherwell.notezero.ui.reader.EpubTestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose tests for the Library shelf.
 *
 * Documents are seeded as plain [Item]s carrying encrypted-attachment references, exactly
 * the shape the repository exposes after decrypting the index; the book-open test imports
 * a real in-memory EPUB through the same attachment path as the app.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LibraryScreenTest : UiTestBase() {

    private fun attachment(id: String, displayName: String, mimeType: String): AttachmentRef =
        AttachmentRef(
            id = id,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = 1024L,
            createdAt = 1L,
            sha256 = "0".repeat(64),
        )

    private fun pdfItem(
        id: String,
        title: String,
        updatedAt: Long = 1_000L,
        readingState: ReadingState? = null,
        locked: Boolean = false,
    ): Item = Item(
        id = id,
        kind = ItemKind.PDF_FILE,
        title = title,
        body = "",
        createdAt = updatedAt,
        updatedAt = updatedAt,
        version = 1,
        attachments = listOf(attachment("att-$id", "$title.pdf", "application/pdf")),
        readingState = readingState,
        locked = locked,
    )

    private fun bookItem(
        id: String,
        title: String,
        updatedAt: Long = 1_000L,
        readingState: ReadingState? = null,
    ): Item = Item(
        id = id,
        kind = ItemKind.BOOK_FILE,
        title = title,
        body = "",
        createdAt = updatedAt,
        updatedAt = updatedAt,
        version = 1,
        attachments = listOf(attachment("att-$id", "$title.epub", EpubParser.MIME)),
        readingState = readingState,
    )

    private fun drawingItem(id: String, title: String, updatedAt: Long = 1_000L): Item = Item(
        id = id,
        kind = ItemKind.NOTE,
        title = title,
        body = "",
        createdAt = updatedAt,
        updatedAt = updatedAt,
        version = 1,
        attachments = listOf(
            attachment("att-$id", "drawing-20260101-120000.png", "image/png"),
        ),
    )

    private fun showLibrary(repository: FakeNoteRepository, onBack: () -> Unit = {}) {
        setThemedContent {
            LibraryScreen(repository = repository, onBack = onBack)
        }
        composeRule.waitForIdle()
    }

    private fun rowCount(): Int = composeRule.onAllNodesWithTag("library_item").fetchSemanticsNodes().size

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

    @Test
    fun `shows pdf book and drawing rows with type labels and reading progress`() {
        val repository = EpubTestData.unlockedRepository(
            listOf(
                pdfItem(
                    id = "pdf-1",
                    title = "PDF Doc",
                    updatedAt = 3_000L,
                    readingState = ReadingState(position = 2, total = 10),
                ),
                bookItem(
                    id = "book-1",
                    title = "Book Doc",
                    updatedAt = 2_000L,
                    readingState = ReadingState(position = 1, total = 3, author = "An Author"),
                ),
                drawingItem(id = "drawing-1", title = "Sketch note", updatedAt = 1_000L),
            ),
        )
        showLibrary(repository)

        composeRule.onNodeWithTag("library_screen").assertExists()
        assertEquals(3, rowCount())
        composeRule.onNodeWithText("PDF Doc").assertExists()
        composeRule.onNodeWithText("Book Doc").assertExists()
        composeRule.onNodeWithText("Sketch note").assertExists()
        composeRule.onNodeWithText("An Author").assertExists()

        composeRule.onNodeWithText("Page 3 of 10").assertExists()
        composeRule.onNodeWithText("Chapter 2 of 3").assertExists()
        assertEquals(2, composeRule.onAllNodesWithTag("library_progress_percent").fetchSemanticsNodes().size)
        composeRule.onAllNodesWithTag("library_page_progress").fetchSemanticsNodes()
            .let { assertEquals(1, it.size) }
        composeRule.onAllNodesWithTag("library_chapter_progress").fetchSemanticsNodes()
            .let { assertEquals(1, it.size) }
    }

    @Test
    fun `filters show only the chosen document kind`() {
        val repository = EpubTestData.unlockedRepository(
            listOf(
                pdfItem(id = "pdf-1", title = "PDF Doc"),
                bookItem(id = "book-1", title = "Book Doc"),
                drawingItem(id = "drawing-1", title = "Sketch note"),
            ),
        )
        showLibrary(repository)

        composeRule.onNodeWithTag("library_filter_books").performClick()
        composeRule.waitForIdle()
        assertEquals(1, rowCount())
        composeRule.onNodeWithText("Book Doc").assertExists()
        composeRule.onNodeWithText("PDF Doc").assertDoesNotExist()

        composeRule.onNodeWithTag("library_filter_pdfs").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("PDF Doc").assertExists()
        composeRule.onNodeWithText("Book Doc").assertDoesNotExist()

        composeRule.onNodeWithTag("library_filter_drawings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sketch note").assertExists()
        composeRule.onNodeWithText("PDF Doc").assertDoesNotExist()

        composeRule.onNodeWithTag("library_filter_all").performClick()
        composeRule.waitForIdle()
        assertEquals(3, rowCount())
    }

    @Test
    fun `empty shelf shows the empty state`() {
        val repository = EpubTestData.unlockedRepository(
            listOf(
                Item(
                    id = "note-1",
                    kind = ItemKind.NOTE,
                    title = "Just a note",
                    body = "body",
                    updatedAt = 1_000L,
                ),
            ),
        )
        showLibrary(repository)

        assertEquals(0, rowCount())
        composeRule.onNodeWithText("Your library is empty").assertExists()
    }

    @Test
    fun `locked and unverified documents are hidden from the shelf`() {
        val repository = EpubTestData.unlockedRepository(
            listOf(
                pdfItem(id = "pdf-locked", title = "Locked PDF", locked = true),
                pdfItem(id = "pdf-open", title = "Visible PDF"),
            ),
        )
        showLibrary(repository)

        assertEquals(1, rowCount())
        composeRule.onNodeWithText("Visible PDF").assertExists()
        composeRule.onNodeWithText("Locked PDF").assertDoesNotExist()
    }

    @Test
    fun `rows are sorted newest first`() {
        val repository = EpubTestData.unlockedRepository(
            listOf(
                pdfItem(id = "pdf-old", title = "Oldest", updatedAt = 1_000L),
                pdfItem(id = "pdf-new", title = "Newest", updatedAt = 9_000L),
                pdfItem(id = "pdf-mid", title = "Middle", updatedAt = 5_000L),
            ),
        )
        showLibrary(repository)

        composeRule.onAllNodesWithTag("library_item")[0]
            .assert(hasText("Newest", substring = true))
        composeRule.onAllNodesWithTag("library_item")[1]
            .assert(hasText("Middle", substring = true))
        composeRule.onAllNodesWithTag("library_item")[2]
            .assert(hasText("Oldest", substring = true))
    }

    @Test
    fun `overflow actions move a document to the trash`() {
        val repository = EpubTestData.unlockedRepository(
            listOf(pdfItem(id = "pdf-1", title = "Trash me")),
        )
        showLibrary(repository)

        composeRule.onNodeWithTag("library_actions").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Move to trash").performClick()

        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == "pdf-1" }?.trashed == true
        }
        composeRule.waitForIdle()
        assertEquals(0, rowCount())
        composeRule.onNodeWithText("Your library is empty").assertExists()
    }

    @Test
    fun `rename dialog renames the encrypted item title`() {
        val repository = EpubTestData.unlockedRepository(
            listOf(pdfItem(id = "pdf-1", title = "Old name")),
        )
        showLibrary(repository)

        composeRule.onNodeWithTag("library_actions").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Rename note").performClick()
        waitForTag("library_rename_dialog")
        composeRule.onNodeWithTag("library_rename_field").performTextClearance()
        composeRule.onNodeWithTag("library_rename_field").performTextInput("New name")
        composeRule.onNodeWithTag("library_rename_save").performClick()

        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == "pdf-1" }?.title == "New name"
        }
        composeRule.onNodeWithText("New name").assertExists()
    }

    @Test
    fun `opening a book row shows the EPUB reader and closes back to the shelf`() {
        val repository = EpubTestData.unlockedRepository()
        EpubTestData.seedBook(repository)
        showLibrary(repository)

        assertEquals(1, rowCount())
        composeRule.onNodeWithTag("library_item").performClick()
        waitForTag("book_reader")

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("book_chapter").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("book_back").performClick()
        waitForTag("library_item")
    }

    @Test
    fun `drawing row opens an in-memory preview overlay and closes`() {
        val repository = EpubTestData.unlockedRepository(
            listOf(drawingItem(id = "drawing-1", title = "Sketch note")),
        )
        showLibrary(repository)

        composeRule.onNodeWithTag("library_item").performClick()
        waitForTag("library_preview")
        waitForTag("library_no_preview")
        composeRule.onNodeWithTag("library_preview_close").performClick()
        waitForNoTag("library_preview")
    }

    @Test
    fun `import menu offers pdf and epub imports`() {
        val repository = EpubTestData.unlockedRepository()
        showLibrary(repository)

        composeRule.onNodeWithTag("library_import").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("PDF document").assertExists()
        composeRule.onNodeWithText("E-book (EPUB)").assertExists()
    }

    @Test
    fun `back closes the library`() {
        val repository = EpubTestData.unlockedRepository()
        var closed = 0
        showLibrary(repository, onBack = { closed++ })
        composeRule.onNodeWithTag("library_back").performClick()
        composeRule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun `pure shelf builder keeps locked items hidden and filters drawings`() {
        val items = listOf(
            pdfItem(id = "pdf-1", title = "PDF Doc", updatedAt = 3_000L),
            bookItem(id = "book-1", title = "Book Doc", updatedAt = 2_000L),
            drawingItem(id = "drawing-1", title = "Sketch note", updatedAt = 1_000L),
            pdfItem(id = "pdf-locked", title = "Hidden", locked = true),
        )
        val all = buildLibraryEntries(items, emptyList(), emptySet(), LibraryFilter.ALL)
        assertEquals(listOf("PDF Doc", "Book Doc", "Sketch note"), all.map { it.item.title })
        assertTrue(all.none { it.item.title == "Hidden" })

        val drawings = buildLibraryEntries(items, emptyList(), emptySet(), LibraryFilter.DRAWINGS)
        assertEquals(1, drawings.size)
        assertEquals(LibraryEntryType.DRAWING, drawings.single().type)

        val books = buildLibraryEntries(items, emptyList(), emptySet(), LibraryFilter.BOOKS)
        assertEquals(1, books.size)
        assertEquals(LibraryEntryType.BOOK, books.single().type)

        val pdfs = buildLibraryEntries(items, emptyList(), emptySet(), LibraryFilter.PDFS)
        assertEquals(LibraryEntryType.PDF, pdfs.single().type)
    }
}
