package com.cipherwell.notezero.ui.reader

import android.content.Context
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.model.ReadingBookmark
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.ui.FakeNoteRepository
import com.cipherwell.notezero.ui.UiTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose tests for the EPUB reader surface.
 *
 * Every book is a real (in-memory) EPUB built by [EpubTestData] and imported through the
 * repository API, so the tests exercise the same decrypt -> parse -> render path as the
 * device. Alert dialogs are driven inside the Compose test world only (`@Config(sdk=[34])`
 * and no display qualifiers, which is the combination Robolectric supports reliably).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BookReaderScreenTest : UiTestBase() {

    private fun repository(): FakeNoteRepository = EpubTestData.unlockedRepository()

    private fun waitForTag(repository: FakeNoteRepository, itemId: String, tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String, substring: Boolean = true) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun showReader(repository: FakeNoteRepository, itemId: String, onClose: () -> Unit = {}) {
        setThemedContent {
            BookReaderScreen(repository = repository, itemId = itemId, onClose = onClose)
        }
    }

    @Test
    fun `opens a book and renders its title and chapter text`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(repository)
        showReader(repository, itemId)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("book_reader").assertExists()
        composeRule.onNodeWithTag("book_title").assertExists()
        composeRule.onNodeWithText(EpubTestData.BOOK_TITLE).assertExists()
        waitForText(EpubTestData.CHAPTER_ONE_BODY)
        waitForTag(repository, itemId, "book_chapter")
        composeRule.onNodeWithTag("book_progress_percent").assertExists()
    }

    @Test
    fun `table of contents lists entries and a tap scrolls to the chapter`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(repository)
        showReader(repository, itemId)
        waitForText(EpubTestData.CHAPTER_ONE_BODY)

        composeRule.onNodeWithTag("book_toc").performClick()
        waitForTag(repository, itemId, "book_toc_dialog")
        waitForTag(repository, itemId, "book_toc_entry")
        assertEquals(2, composeRule.onAllNodesWithTag("book_toc_entry").fetchSemanticsNodes().size)
        composeRule.onAllNodesWithText("Two")[0].performClick()

        waitForText(EpubTestData.CHAPTER_TWO_BODY)
        composeRule.onAllNodesWithTag("book_toc_dialog").fetchSemanticsNodes().isEmpty()
    }

    @Test
    fun `search finds a phrase in another chapter and shows a result`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(repository)
        showReader(repository, itemId)
        waitForText(EpubTestData.CHAPTER_ONE_BODY)

        composeRule.onNodeWithTag("book_search").performClick()
        waitForTag(repository, itemId, "book_search_dialog")
        composeRule.onNodeWithTag("book_search_query").performTextInput(EpubTestData.SEARCH_PHRASE)

        waitForTag(repository, itemId, "book_search_result")
        composeRule.onAllNodesWithText(EpubTestData.SEARCH_PHRASE, substring = true)
            .fetchSemanticsNodes()
            .let { assertTrue("expected a snippet containing the phrase", it.isNotEmpty()) }
    }

    @Test
    fun `search without a match shows the empty result message`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(repository)
        showReader(repository, itemId)
        waitForText(EpubTestData.CHAPTER_ONE_BODY)

        composeRule.onNodeWithTag("book_search").performClick()
        waitForTag(repository, itemId, "book_search_dialog")
        composeRule.onNodeWithTag("book_search_query").performTextInput("zzzznotinthebook")
        waitForTag(repository, itemId, "book_search_empty")
    }

    @Test
    fun `adding a bookmark persists through the repository`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(repository)
        showReader(repository, itemId)
        waitForText(EpubTestData.CHAPTER_ONE_BODY)

        composeRule.onNodeWithTag("book_bookmarks").performClick()
        waitForTag(repository, itemId, "book_bookmarks_dialog")
        composeRule.onNodeWithTag("book_bookmark_add").performClick()
        waitForTag(repository, itemId, "book_bookmark_label")
        composeRule.onNodeWithTag("book_bookmark_label").performTextInput("My spot")
        composeRule.onNodeWithTag("book_bookmark_save").performClick()

        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == itemId }
                ?.readingState?.bookmarks?.any { it.label == "My spot" } == true
        }
        val bookmark = repository.items.value.first { it.id == itemId }.readingState!!.bookmarks.single()
        assertEquals(0, bookmark.position)
        assertNotNull(bookmark.createdAt)
    }

    @Test
    fun `bookmark rows can be deleted and the change persists`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(
            repository,
            readingState = ReadingState(
                position = 0,
                total = 2,
                bookmarks = listOf(
                    ReadingBookmark(position = 0, label = "Saved mark", createdAt = 1_700_000_000_000L),
                ),
            ),
        )
        showReader(repository, itemId)
        waitForText(EpubTestData.CHAPTER_ONE_BODY)

        composeRule.onNodeWithTag("book_bookmarks").performClick()
        waitForTag(repository, itemId, "book_bookmark_row")
        composeRule.onNodeWithTag("book_bookmark_delete").performClick()

        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == itemId }?.readingState?.bookmarks?.isEmpty() == true
        }
    }

    @Test
    fun `appearance theme toggle is selected and persisted in reader prefs`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(repository)
        showReader(repository, itemId)
        waitForText(EpubTestData.CHAPTER_ONE_BODY)

        composeRule.onNodeWithTag("book_appearance").performClick()
        waitForTag(repository, itemId, "book_appearance_dialog")
        composeRule.onNodeWithTag("book_theme_sepia").performClick()
        composeRule.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(ReaderTheme.SEPIA, ReaderPrefs(context).load().theme)
        composeRule.onNodeWithTag("book_theme_sepia").assertIsSelected()
        composeRule.onNodeWithTag("book_theme_paper").assertIsNotSelected()
    }

    @Test
    fun `font size controls change and persist the reader preference`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(repository)
        showReader(repository, itemId)
        waitForText(EpubTestData.CHAPTER_ONE_BODY)

        composeRule.onNodeWithTag("book_appearance").performClick()
        waitForTag(repository, itemId, "book_appearance_dialog")
        composeRule.onNodeWithTag("book_font_increase").performClick()
        composeRule.onNodeWithTag("book_font_increase").performClick()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = ReaderPrefs(context).load()
        assertTrue(preferences.fontScale > ReaderPreferences.DEFAULT_FONT_SCALE)
    }

    @Test
    fun `corrupt book bytes show the non-secret error instead of crashing`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(
            repository,
            bytes = EpubTestData.corruptBook(),
        )
        showReader(repository, itemId)
        waitForTag(repository, itemId, "book_error")
        composeRule.onNodeWithTag("book_back").performClick()
        composeRule.onNodeWithTag("book_error").assertExists()
    }

    @Test
    fun `progress is saved after the reader scrolls to a later chapter`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(repository)
        showReader(repository, itemId)
        waitForText(EpubTestData.CHAPTER_ONE_BODY)

        composeRule.onNodeWithTag("book_list").performScrollToIndex(1)

        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == itemId }?.readingState?.position == 1
        }
        val state = repository.items.value.first { it.id == itemId }.readingState
        assertEquals(1, state?.position)
        assertEquals(2, state?.total)
    }

    @Test
    fun `restores the saved chapter from encrypted reading state`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(
            repository,
            readingState = ReadingState(position = 1, total = 2),
        )
        showReader(repository, itemId)
        waitForText(EpubTestData.CHAPTER_TWO_BODY)
        composeRule.onNodeWithText(EpubTestData.BOOK_TITLE).assertExists()
    }

    @Test
    fun `tapping the centre toggles the reader chrome`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(repository)
        showReader(repository, itemId)
        waitForText(EpubTestData.CHAPTER_ONE_BODY)

        composeRule.onNodeWithTag("book_title").assertExists()
        composeRule.onNodeWithTag("book_tap_center").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("book_title").assertDoesNotExist()

        composeRule.onNodeWithTag("book_tap_center").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("book_title").assertExists()
    }

    @Test
    fun `tap zones turn the chapter`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(repository)
        showReader(repository, itemId)
        waitForText(EpubTestData.CHAPTER_ONE_BODY)

        composeRule.onNodeWithTag("book_tap_right").performClick()
        waitForText(EpubTestData.CHAPTER_TWO_BODY)

        composeRule.onNodeWithTag("book_tap_left").performClick()
        waitForText(EpubTestData.CHAPTER_ONE_BODY)
    }

    @Test
    fun `back closes the reader`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(repository)
        var closed = 0
        showReader(repository, itemId, onClose = { closed++ })
        waitForText(EpubTestData.CHAPTER_ONE_BODY)
        composeRule.onNodeWithTag("book_back").performClick()
        composeRule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun `bookmark label dialog can be cancelled without persisting`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(repository)
        showReader(repository, itemId)
        waitForText(EpubTestData.CHAPTER_ONE_BODY)

        composeRule.onNodeWithTag("book_bookmarks").performClick()
        waitForTag(repository, itemId, "book_bookmarks_dialog")
        composeRule.onNodeWithTag("book_bookmark_add").performClick()
        waitForTag(repository, itemId, "book_bookmark_label")
        composeRule.onNodeWithTag("book_bookmark_cancel").performClick()
        composeRule.waitForIdle()

        assertEquals(0, repository.items.value.first { it.id == itemId }.readingState?.bookmarks?.size ?: 0)
    }

    @Test
    fun `search result tap closes the dialog and scrolls to the matching chapter`() {
        val repository = repository()
        val itemId = EpubTestData.seedBook(repository)
        showReader(repository, itemId)
        waitForText(EpubTestData.CHAPTER_ONE_BODY)

        composeRule.onNodeWithTag("book_search").performClick()
        waitForTag(repository, itemId, "book_search_dialog")
        composeRule.onNodeWithTag("book_search_query").performTextClearance()
        composeRule.onNodeWithTag("book_search_query").performTextInput("Second chapter body")
        waitForTag(repository, itemId, "book_search_result")
        composeRule.onAllNodesWithTag("book_search_result")[0].performClick()

        waitForText(EpubTestData.CHAPTER_TWO_BODY)
        composeRule.onAllNodesWithTag("book_search_dialog").fetchSemanticsNodes().isEmpty()
    }

    // =========================================================================
    // Multi-format dispatch: TXT / FB2 share the text reader
    // =========================================================================

    @Test
    fun `txt book renders auto-detected chapters and paragraphs`() {
        val repository = repository()
        val itemId = BookFormatsTestData.seedTxt(repository)
        showReader(repository, itemId)

        waitForText(BookFormatsTestData.TXT_BODY_ONE)
        composeRule.onNodeWithText(BookFormatsTestData.TXT_ITEM_TITLE).assertExists()
        // TXT auto-chapters are rendered with the same chapter/header model as EPUB.
        composeRule.onAllNodesWithText(BookFormatsTestData.TXT_CHAPTER_ONE).fetchSemanticsNodes()
            .let { assertTrue("expected the chapter heading", it.isNotEmpty()) }
        composeRule.onNodeWithTag("book_list").performScrollToIndex(1)
        waitForText(BookFormatsTestData.TXT_BODY_TWO)
    }

    @Test
    fun `txt book table of contents lists the auto chapters and jumps`() {
        val repository = repository()
        val itemId = BookFormatsTestData.seedTxt(repository)
        showReader(repository, itemId)
        waitForText(BookFormatsTestData.TXT_BODY_ONE)

        composeRule.onNodeWithTag("book_toc").performClick()
        waitForTag(repository, itemId, "book_toc_dialog")
        waitForTag(repository, itemId, "book_toc_entry")
        assertEquals(2, composeRule.onAllNodesWithTag("book_toc_entry").fetchSemanticsNodes().size)
        composeRule.onAllNodesWithText(BookFormatsTestData.TXT_CHAPTER_TWO)[0].performClick()
        waitForText(BookFormatsTestData.TXT_BODY_TWO)
    }

    @Test
    fun `txt book search finds a phrase in a paragraph`() {
        val repository = repository()
        val itemId = BookFormatsTestData.seedTxt(repository)
        showReader(repository, itemId)
        waitForText(BookFormatsTestData.TXT_BODY_ONE)

        composeRule.onNodeWithTag("book_search").performClick()
        waitForTag(repository, itemId, "book_search_dialog")
        composeRule.onNodeWithTag("book_search_query").performTextInput(BookFormatsTestData.TXT_PHRASE)

        waitForTag(repository, itemId, "book_search_result")
        composeRule.onAllNodesWithText(BookFormatsTestData.TXT_PHRASE, substring = true)
            .fetchSemanticsNodes()
            .let { assertTrue("expected a TXT search snippet", it.isNotEmpty()) }
    }

    @Test
    fun `fb2 book renders metadata, sections and nested body`() {
        val repository = repository()
        val itemId = BookFormatsTestData.seedFb2(repository)
        showReader(repository, itemId)

        waitForText(BookFormatsTestData.FB2_BODY_ONE)
        composeRule.onNodeWithText(BookFormatsTestData.FB2_ITEM_TITLE).assertExists()
        // The metadata line shows the detected format and the FB2 author.
        composeRule.onAllNodesWithText(BookFormatsTestData.FB2_AUTHOR, substring = true)
            .fetchSemanticsNodes()
            .let { assertTrue("expected the FB2 author", it.isNotEmpty()) }
        composeRule.onNodeWithTag("book_list").performScrollToIndex(1)
        waitForText(BookFormatsTestData.FB2_BODY_TWO)
    }

    @Test
    fun `fb2 book table of contents keeps nested sections`() {
        val repository = repository()
        val itemId = BookFormatsTestData.seedFb2(repository)
        showReader(repository, itemId)
        waitForText(BookFormatsTestData.FB2_BODY_ONE)

        composeRule.onNodeWithTag("book_toc").performClick()
        waitForTag(repository, itemId, "book_toc_dialog")
        waitForTag(repository, itemId, "book_toc_entry")
        // Two top-level sections plus the nested depth-1 anchor.
        assertEquals(3, composeRule.onAllNodesWithTag("book_toc_entry").fetchSemanticsNodes().size)
        composeRule.onAllNodesWithText(BookFormatsTestData.FB2_SECTION_TWO)[0].performClick()
        waitForText(BookFormatsTestData.FB2_BODY_TWO)
    }

    @Test
    fun `fb2 book search finds a phrase in a section body`() {
        val repository = repository()
        val itemId = BookFormatsTestData.seedFb2(repository)
        showReader(repository, itemId)
        waitForText(BookFormatsTestData.FB2_BODY_ONE)

        composeRule.onNodeWithTag("book_search").performClick()
        waitForTag(repository, itemId, "book_search_dialog")
        composeRule.onNodeWithTag("book_search_query").performTextInput("fb2phrase")
        waitForTag(repository, itemId, "book_search_result")
    }

    @Test
    fun `unsupported bytes show the reader error instead of a text book`() {
        val repository = repository()
        val itemId = BookFormatsTestData.seedBook(
            repository = repository,
            bytes = EpubTestData.corruptBook(),
            title = "Broken Import",
            displayName = "broken.bin",
            mimeType = "application/octet-stream",
        )
        showReader(repository, itemId)
        waitForTag(repository, itemId, "book_error")
        composeRule.onNodeWithTag("book_chapter").assertDoesNotExist()
        composeRule.onNodeWithTag("comic_reader").assertDoesNotExist()
    }
}
