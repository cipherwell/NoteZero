package com.cipherwell.notezero.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.ui.FakeNoteRepository
import com.cipherwell.notezero.ui.UiTestBase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * Viewer behaviour with an injected renderer (the platform PdfRenderer has no Robolectric
 * implementation; the real renderer is exercised by `PdfRenderInstrumentedTest` on device).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PdfViewerScreenTest : UiTestBase() {

    private fun repositoryWithPdf(): Pair<FakeNoteRepository, String> {
        val repository = FakeNoteRepository(
            initialStatus = VaultStatus.UNLOCKED,
            initialPassword = FakeNoteRepository.STRONG_PASSWORD,
        )
        val created = runBlocking {
            val item = repository.createItem(ItemKind.PDF_FILE, null)
            repository.saveItem(item.copy(title = "document.pdf"))
        }
        runBlocking {
            repository.importAttachment(
                noteId = created.id,
                displayName = "document.pdf",
                mimeType = BlankPdf.MIME_TYPE,
                source = ByteArrayInputStream(BlankPdf.create()),
            )
        }
        return repository to created.id
    }

    private fun fakeRenderer(pages: Int, total: Int): PdfPageRenderer = PdfPageRenderer { _, _ ->
        val bitmaps = (0 until pages).map { index ->
            Bitmap.createBitmap(12, 18, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (index % 2 == 0) Color.WHITE else Color.LTGRAY)
            }
        }
        PdfPages(bitmaps, total)
    }

    @Test
    fun `renders the pages, shows the item title and closes on back`() {
        val (repository, itemId) = repositoryWithPdf()
        var closed = 0
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = { closed++ },
                renderer = fakeRenderer(pages = 2, total = 2),
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_viewer").assertExists()
        composeRule.onNodeWithTag("pdf_title").assertExists()
        composeRule.onNodeWithTag("pdf_back").performClick()
        composeRule.waitForIdle()

        assertEquals(1, closed)
    }

    @Test
    fun `renderer failure shows the non-secret error instead of a crash`() {
        val (repository, itemId) = repositoryWithPdf()
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = {},
                renderer = PdfPageRenderer { _, _ -> PdfPages(emptyList(), 0) },
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_error").assertExists()
    }

    @Test
    fun `export asks for explicit confirmation before writing plaintext`() {
        val (repository, itemId) = repositoryWithPdf()
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = {},
                renderer = fakeRenderer(pages = 1, total = 1),
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_export").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_export_warning").assertExists()
    }

    @Test
    fun `opening a pdf stores the page total in encrypted reading state`() {
        val (repository, itemId) = repositoryWithPdf()
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = {},
                renderer = fakeRenderer(pages = 3, total = 3),
            )
        }

        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == itemId }?.readingState?.total == 3
        }
        composeRule.waitForIdle()
        val state = repository.items.value.first { it.id == itemId }.readingState
        assertEquals(3, state?.total)
        assertEquals(0, state?.position)
    }

    @Test
    fun `adding a bookmark persists it in the encrypted reading state`() {
        val (repository, itemId) = repositoryWithPdf()
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = {},
                renderer = fakeRenderer(pages = 3, total = 3),
            )
        }
        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == itemId }?.readingState?.total == 3
        }

        composeRule.onNodeWithTag("pdf_bookmark_add").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_bookmark_dialog").assertExists()
        composeRule.onNodeWithTag("pdf_bookmark_label").performTextInput("My page")
        composeRule.onNodeWithTag("pdf_bookmark_save").performClick()

        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == itemId }
                ?.readingState?.bookmarks?.any { it.label == "My page" } == true
        }
        val bookmark = repository.items.value.first { it.id == itemId }.readingState!!.bookmarks.single()
        assertEquals(0, bookmark.position)
        assertEquals("My page", bookmark.label)
    }

    @Test
    fun `bookmarks list can jump and delete`() {
        val (repository, itemId) = repositoryWithPdf()
        runBlocking {
            repository.saveReadingState(
                itemId,
                ReadingState(
                    position = 1,
                    total = 3,
                    bookmarks = listOf(
                        com.cipherwell.notezero.model.ReadingBookmark(position = 2, label = "Last page", createdAt = 1L),
                    ),
                ),
            )
        }
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = {},
                renderer = fakeRenderer(pages = 3, total = 3),
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pdf_bookmarks_open").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_bookmarks_dialog").assertExists()
        composeRule.onNodeWithTag("pdf_bookmark_row").assertExists()

        composeRule.onNodeWithTag("pdf_bookmark_delete").performClick()
        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == itemId }?.readingState?.bookmarks?.isEmpty() == true
        }
    }

    @Test
    fun `page jump rejects an out of range page`() {
        val (repository, itemId) = repositoryWithPdf()
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = {},
                renderer = fakeRenderer(pages = 2, total = 2),
            )
        }
        composeRule.waitUntil(5_000) {
            repository.items.value.firstOrNull { it.id == itemId }?.readingState?.total == 2
        }

        composeRule.onNodeWithTag("pdf_page_progress").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_page_jump_dialog").assertExists()
        composeRule.onNodeWithTag("pdf_page_field").performTextInput("999")
        composeRule.onNodeWithTag("pdf_page_jump_confirm").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pdf_page_error", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `page tools action opens the overlay and back closes only the overlay`() {
        val (repository, itemId) = repositoryWithPdf()
        var closed = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        setThemedContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = { closed++ },
                renderer = fakeRenderer(pages = 2, total = 2),
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pdf_page_tools").assertIsEnabled()
        composeRule.onNodeWithTag("pdf_page_tools").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_page_tools_screen").assertExists()

        // System Back closes the tools overlay only: the viewer stays open.
        composeRule.runOnUiThread { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_page_tools_screen").assertDoesNotExist()
        composeRule.onNodeWithTag("pdf_viewer").assertExists()
        assertEquals(0, closed)

        // A second Back leaves the viewer, exactly like before page tools existed.
        composeRule.runOnUiThread { dispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun `page tools action is disabled when the PDF failed to render`() {
        val (repository, itemId) = repositoryWithPdf()
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = {},
                renderer = PdfPageRenderer { _, _ -> PdfPages(emptyList(), 0) },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pdf_page_tools").assertExists()
        composeRule.onNodeWithTag("pdf_page_tools").assertIsNotEnabled()
    }

    @Test
    fun `page tools action is hidden while annotating and returns afterwards`() {
        val (repository, itemId) = repositoryWithPdf()
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = {},
                renderer = fakeRenderer(pages = 1, total = 1),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_page_tools").assertExists()

        composeRule.onNodeWithTag("pdf_annotate_toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_page_tools").assertDoesNotExist()

        composeRule.onNodeWithTag("pdf_annotate_done").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_page_tools").assertExists()
    }

    @Test
    fun `resumes from the saved encrypted page`() {
        val (repository, itemId) = repositoryWithPdf()
        runBlocking { repository.saveReadingState(itemId, ReadingState(position = 1, total = 3)) }
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = {},
                renderer = fakeRenderer(pages = 3, total = 3),
            )
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty()
        }
        // Both the progress button and the page caption may show "2 / 3"; either proves the
        // saved position was restored.
        assertTrue(composeRule.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty())
    }
}
