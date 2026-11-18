package com.cipherwell.notezero.ui.comic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.book.ComicBook
import com.cipherwell.notezero.model.ReadingBookmark
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.ui.FakeNoteRepository
import com.cipherwell.notezero.ui.UiTestBase
import com.cipherwell.notezero.ui.reader.BookReaderScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose tests for the CBZ comic reader.
 *
 * Each comic is a real, generated-in-memory CBZ parsed by the real `CbzParser` and seeded
 * through the encrypted-attachment path, so the tests run the same decrypt → parse → render
 * pipeline the device uses. Dialogs use `@Config(sdk = [34])` with no display qualifiers (the
 * Robolectric-safe combination used by the other dialog tests in this project).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ComicReaderScreenTest : UiTestBase() {

    private fun repository(): FakeNoteRepository = ComicTestData.unlockedRepository()

    private fun waitForTag(tag: String, useUnmergedTree: Boolean = false) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = useUnmergedTree)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForNoTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun showComic(
        comic: ComicBook,
        repository: FakeNoteRepository,
        itemId: String,
        onClose: () -> Unit = {},
    ) {
        setThemedContent {
            ComicReaderScreen(
                comic = comic,
                repository = repository,
                itemId = itemId,
                onClose = onClose,
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `renders decoded pages with the page indicator and no text tools`() {
        val repository = repository()
        val bytes = ComicTestData.validCbz()
        val itemId = ComicTestData.seedComic(repository, bytes = bytes)
        showComic(ComicTestData.parseComic(bytes), repository, itemId)

        waitForTag("comic_page")
        waitForText("1 / 3")
        composeRule.onNodeWithTag("comic_reader").assertExists()
        composeRule.onNodeWithTag("comic_title").assertExists()
        composeRule.onNodeWithText(ComicTestData.COMIC_TITLE).assertExists()
        composeRule.onNodeWithTag("comic_progress_bar").assertExists()
        // A comic has no text search/TOC, only pages and bookmarks.
        composeRule.onNodeWithTag("book_search").assertDoesNotExist()
        composeRule.onNodeWithTag("book_toc").assertDoesNotExist()
    }

    @Test
    fun `scrolling a page updates the indicator and persists the reading position`() {
        val repository = repository()
        val bytes = ComicTestData.validCbz()
        val itemId = ComicTestData.seedComic(repository, bytes = bytes)
        showComic(ComicTestData.parseComic(bytes), repository, itemId)
        waitForTag("comic_page")

        // Robolectric's lazy layout only knows item sizes for pages it has measured, so the
        // first programmatic jump reliably lands on the second page (same as the EPUB reader
        // progress test); the page position/total contract is what is asserted here.
        composeRule.onNodeWithTag("comic_pages").performScrollToIndex(1)

        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == itemId }?.readingState?.position == 1
        }
        val state = repository.items.value.first { it.id == itemId }.readingState
        assertEquals(1, state?.position)
        assertEquals(3, state?.total)
        waitForText("2 / 3")
    }

    @Test
    fun `page jump validates the input and moves to the requested page`() {
        val repository = repository()
        val bytes = ComicTestData.validCbz()
        val itemId = ComicTestData.seedComic(repository, bytes = bytes)
        showComic(ComicTestData.parseComic(bytes), repository, itemId)
        waitForTag("comic_page")

        composeRule.onNodeWithTag("comic_page_progress").performClick()
        waitForTag("comic_page_jump_dialog")
        composeRule.onNodeWithTag("comic_page_field").performTextClearance()
        composeRule.onNodeWithTag("comic_page_field").performTextInput("9")
        composeRule.onNodeWithTag("comic_page_jump_confirm").performClick()
        // Out-of-range input keeps the dialog open and shows the bounded message; the
        // supporting text is merged under the field, so assert it in the unmerged tree.
        waitForTag("comic_page_error", useUnmergedTree = true)
        composeRule.onNodeWithTag("comic_page_jump_dialog").assertExists()

        composeRule.onNodeWithTag("comic_page_field").performTextClearance()
        composeRule.onNodeWithTag("comic_page_field").performTextInput("2")
        composeRule.onNodeWithTag("comic_page_jump_confirm").performClick()
        waitForNoTag("comic_page_jump_dialog")
        waitForText("2 / 3")
    }

    @Test
    fun `bookmark add and delete persist through the encrypted reading state`() {
        val repository = repository()
        val bytes = ComicTestData.validCbz()
        val itemId = ComicTestData.seedComic(repository, bytes = bytes)
        showComic(ComicTestData.parseComic(bytes), repository, itemId)
        waitForTag("comic_page")

        composeRule.onNodeWithTag("comic_bookmark_add").performClick()
        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == itemId }
                ?.readingState?.bookmarks?.any { it.label == "Page 1" && it.position == 0 } == true
        }

        composeRule.onNodeWithTag("comic_bookmarks").performClick()
        waitForTag("comic_bookmarks_dialog")
        waitForTag("comic_bookmark_row")
        composeRule.onNodeWithText("Page 1").assertExists()

        composeRule.onNodeWithTag("comic_bookmark_delete").performClick()
        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == itemId }?.readingState?.bookmarks?.isEmpty() == true
        }
        waitForTag("comic_bookmarks_empty")
    }

    @Test
    fun `bookmark rows jump to their saved page`() {
        val repository = repository()
        val bytes = ComicTestData.validCbz()
        val itemId = ComicTestData.seedComic(
            repository,
            bytes = bytes,
            readingState = ReadingState(
                position = 0,
                total = 3,
                bookmarks = listOf(
                    ReadingBookmark(position = 1, label = "Saved page", createdAt = 1_700_000_000_000L),
                ),
            ),
        )
        showComic(ComicTestData.parseComic(bytes), repository, itemId)
        waitForTag("comic_page")

        composeRule.onNodeWithTag("comic_bookmarks").performClick()
        waitForTag("comic_bookmark_row")
        composeRule.onNodeWithTag("comic_bookmark_row").performClick()
        waitForNoTag("comic_bookmarks_dialog")
        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == itemId }?.readingState?.position == 1
        }
        waitForText("2 / 3")
    }

    @Test
    fun `resumes from the saved reading position`() {
        val repository = repository()
        val bytes = ComicTestData.validCbz()
        val itemId = ComicTestData.seedComic(
            repository,
            bytes = bytes,
            readingState = ReadingState(position = 1, total = 3),
        )
        showComic(ComicTestData.parseComic(bytes), repository, itemId)

        waitForTag("comic_page")
        waitForText("2 / 3")
    }

    @Test
    fun `an empty comic shows the comic error instead of pages`() {
        val repository = repository()
        val bytes = ComicTestData.validCbz()
        val itemId = ComicTestData.seedComic(repository, bytes = bytes)
        showComic(ComicTestData.emptyComic(), repository, itemId)

        waitForTag("comic_error")
        composeRule.onNodeWithTag("comic_pages").assertDoesNotExist()
    }

    @Test
    fun `a page whose bytes cannot be decoded shows the image error`() {
        val repository = repository()
        val bytes = ComicTestData.validCbz()
        val itemId = ComicTestData.seedComic(repository, bytes = bytes)
        // A CBZ whose archive is valid but whose page is missing/unreadable: the reader must
        // show the non-secret image error, never crash. (Robolectric's BitmapFactory shadow
        // fabricates a bitmap for garbage input, so the missing-page combinator is faked here;
        // the real parser/loader rejection path is covered by the corrupt-CBZ test below.)
        showComic(ComicTestData.missingPageComic(), repository, itemId)

        waitForTag("comic_page_image_error")
        composeRule.onNodeWithTag("comic_page").assertDoesNotExist()
    }

    @Test
    fun `corrupt cbz bytes show the book error instead of the comic reader`() {
        val repository = repository()
        val itemId = ComicTestData.seedComic(
            repository,
            itemTitle = "Broken Comic",
            bytes = ComicTestData.corruptCbz(),
        )
        setThemedContent {
            BookReaderScreen(repository = repository, itemId = itemId, onClose = {})
        }

        waitForTag("book_error")
        composeRule.onNodeWithTag("comic_reader").assertDoesNotExist()
    }

    @Test
    fun `book reader dispatches a cbz to the comic reader and back closes it`() {
        val repository = repository()
        val bytes = ComicTestData.validCbz()
        val itemId = ComicTestData.seedComic(repository, bytes = bytes)
        var closed = 0
        setThemedContent {
            BookReaderScreen(repository = repository, itemId = itemId, onClose = { closed++ })
        }

        waitForTag("comic_reader")
        waitForTag("comic_page")
        composeRule.onNodeWithText(ComicTestData.COMIC_TITLE).assertExists()
        composeRule.onNodeWithTag("comic_back").performClick()
        composeRule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun `the comic is closed when the reader leaves the composition`() {
        val repository = repository()
        val bytes = ComicTestData.validCbz()
        val itemId = ComicTestData.seedComic(repository, bytes = bytes)
        val tracking = TrackingComic(ComicTestData.parseComic(bytes))
        var show by mutableStateOf(true)
        setThemedContent {
            if (show) {
                ComicReaderScreen(
                    comic = tracking,
                    repository = repository,
                    itemId = itemId,
                    onClose = { show = false },
                )
            }
        }
        waitForTag("comic_page")
        show = false
        composeRule.waitForIdle()
        assertTrue("expected the comic handle to be closed", tracking.closed)
    }

    /** Delegating [ComicBook] that records the engine close() call. */
    private class TrackingComic(private val delegate: ComicBook) : ComicBook {
        var closed: Boolean = false
            private set

        override val title: String get() = delegate.title
        override val pageCount: Int get() = delegate.pageCount
        override fun pageBytes(index: Int): ByteArray? = delegate.pageBytes(index)
        override fun close() {
            closed = true
            delegate.close()
        }
    }
}
