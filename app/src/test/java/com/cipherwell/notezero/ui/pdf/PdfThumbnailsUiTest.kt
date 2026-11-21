package com.cipherwell.notezero.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.ReadingBookmark
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.ui.FakeNoteRepository
import com.cipherwell.notezero.ui.UiTestBase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * Robolectric UI tests for the visual page navigation surfaces:
 *
 *  - the Pages overview (the upgraded page-jump dialog): one thumbnail cell per rendered page,
 *    tap-to-jump, the loading row and the unavailable fallback to the numeric field;
 *  - bookmark row thumbnails: text-only while loading/unavailable, image once loaded.
 *
 * Every test injects a [PdfThumbnailRenderer] fake that produces tiny in-memory bitmaps, so no
 * native `android.graphics.pdf` class is ever touched here; the real renderer is validated on a
 * device by `PdfThumbnailsInstrumentedTest`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PdfThumbnailsUiTest : UiTestBase() {

    private class FakeThumbnailRenderer(
        private val pageCount: Int = 3,
        var gate: CompletableDeferred<Unit>? = null,
    ) : PdfThumbnailRenderer {
        var calls: Int = 0
            private set
        var lastBytes: ByteArray? = null
            private set
        var returnedThumbnails: PdfThumbnails? = null
            private set

        override suspend fun render(context: Context, pdfBytes: ByteArray): PdfThumbnails {
            calls++
            lastBytes = pdfBytes
            gate?.await()
            val bitmaps = (0 until pageCount).map { index ->
                Bitmap.createBitmap(12, 18, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(if (index % 2 == 0) Color.WHITE else Color.LTGRAY)
                }
            }
            return PdfThumbnails(bitmaps).also { returnedThumbnails = it }
        }
    }

    private fun fakePageRenderer(pages: Int = 3, total: Int = pages): PdfPageRenderer =
        PdfPageRenderer { _, _ ->
            val bitmaps = (0 until pages).map { index ->
                Bitmap.createBitmap(12, 18, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(if (index % 2 == 0) Color.WHITE else Color.LTGRAY)
                }
            }
            PdfPages(bitmaps, total)
        }

    private fun repositoryWithPdf(bookmarks: List<ReadingBookmark> = emptyList()): Pair<FakeNoteRepository, String> {
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
            if (bookmarks.isNotEmpty()) {
                repository.saveReadingState(
                    created.id,
                    ReadingState(total = 3, bookmarks = bookmarks),
                )
            }
        }
        return repository to created.id
    }

    private fun openViewer(
        repository: FakeNoteRepository,
        itemId: String,
        thumbnailRenderer: PdfThumbnailRenderer,
        pages: Int = 3,
        total: Int = pages,
    ) {
        setThemedContent {
            PdfViewerScreen(
                repository = repository,
                itemId = itemId,
                onClose = {},
                renderer = fakePageRenderer(pages = pages, total = total),
                thumbnailRenderer = thumbnailRenderer,
            )
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("pdf_page_progress").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openOverview() {
        composeRule.onNodeWithTag("pdf_page_progress").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_page_jump_dialog").assertExists()
    }

    private fun openBookmarks() {
        composeRule.onNodeWithTag("pdf_bookmarks_open").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_bookmarks_dialog").assertExists()
    }

    private fun tagCount(tag: String): Int =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /** Bookmark thumbnails live inside a clickable (merging) bookmark row. */
    private fun unmergedTagCount(tag: String): Int =
        composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun textExists(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // ---- Pages overview -------------------------------------------------------------------

    @Test
    fun `overview replaces the title and shows one thumbnail cell per rendered page`() {
        val (repository, itemId) = repositoryWithPdf()
        val thumbnailRenderer = FakeThumbnailRenderer(pageCount = 3)
        openViewer(repository, itemId, thumbnailRenderer)
        openOverview()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("pdf_page_overview_grid").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("pdf_page_jump_dialog").assertExists()
        composeRule.onNodeWithText("Pages").assertExists()
        composeRule.onNodeWithTag("pdf_page_overview_hint").assertExists()
        composeRule.onNodeWithTag("pdf_page_overview_grid").assertExists()
        (0..2).forEach { page ->
            composeRule.onNodeWithTag("pdf_page_thumb_$page").assertExists()
        }
        // The numeric field and Go/Cancel remain as the keyboard/long-document fallback.
        composeRule.onNodeWithTag("pdf_page_field").assertExists()
        composeRule.onNodeWithTag("pdf_page_jump_confirm").assertExists()
        composeRule.onNodeWithTag("pdf_page_jump_cancel").assertExists()
        assertEquals(3, tagCount("pdf_page_thumb_0") + tagCount("pdf_page_thumb_1") + tagCount("pdf_page_thumb_2"))

        // The decrypted bytes are re-read by the thumbnail path and never held by the UI.
        assertEquals(1, thumbnailRenderer.calls)
        assertTrue(thumbnailRenderer.lastBytes!!.contentEquals(BlankPdf.create()))
    }

    @Test
    fun `tapping a thumbnail jumps to that page and closes the overview`() {
        val (repository, itemId) = repositoryWithPdf()
        openViewer(repository, itemId, FakeThumbnailRenderer(pageCount = 3))
        openOverview()
        composeRule.waitUntil(5_000) { tagCount("pdf_page_overview_grid") > 0 }

        composeRule.onNodeWithTag("pdf_page_thumb_2").performClick()
        composeRule.waitUntil(5_000) { tagCount("pdf_page_jump_dialog") == 0 }
        // The pager scrolled to the third page: the progress button now reads "3 / 3".
        composeRule.waitUntil(5_000) { textExists("3 / 3") }
        assertTrue(textExists("3 / 3"))
    }

    @Test
    fun `thumbnail failure shows the unavailable copy and keeps the numeric fallback working`() {
        val (repository, itemId) = repositoryWithPdf()
        openViewer(repository, itemId, FakeThumbnailRenderer(pageCount = 0))
        openOverview()

        composeRule.waitUntil(5_000) { tagCount("pdf_thumbnails_unavailable") > 0 }
        composeRule.onNodeWithTag("pdf_thumbnails_unavailable").assertExists()
        assertEquals(0, tagCount("pdf_page_overview_grid"))
        composeRule.onNodeWithTag("pdf_page_field").assertExists()

        // The number field still jumps: the grid is a convenience, never the only path.
        composeRule.onNodeWithTag("pdf_page_field").performTextClearance()
        composeRule.onNodeWithTag("pdf_page_field").performTextInput("2")
        composeRule.onNodeWithTag("pdf_page_jump_confirm").performClick()
        composeRule.waitUntil(5_000) { tagCount("pdf_page_jump_dialog") == 0 }
        composeRule.waitUntil(5_000) { textExists("2 / 3") }
    }

    @Test
    fun `loading row is shown while thumbnails render and the grid appears afterwards`() {
        val (repository, itemId) = repositoryWithPdf()
        val gate = CompletableDeferred<Unit>()
        val thumbnailRenderer = FakeThumbnailRenderer(pageCount = 3, gate = gate)
        openViewer(repository, itemId, thumbnailRenderer)
        openOverview()

        composeRule.waitUntil(5_000) { tagCount("pdf_thumbnails_loading") > 0 }
        assertEquals(0, tagCount("pdf_page_overview_grid"))
        // The numeric fallback is usable even while the grid is loading.
        composeRule.onNodeWithTag("pdf_page_field").assertExists()

        composeRule.runOnUiThread { gate.complete(Unit) }
        composeRule.waitUntil(5_000) { tagCount("pdf_page_overview_grid") > 0 }
        assertEquals(0, tagCount("pdf_thumbnails_loading"))
        composeRule.onNodeWithTag("pdf_page_thumb_0").assertExists()
    }

    // ---- Bookmark rows ---------------------------------------------------------------------

    @Test
    fun `bookmark row shows the page thumbnail once loaded`() {
        val (repository, itemId) = repositoryWithPdf(
            bookmarks = listOf(ReadingBookmark(position = 1, label = "Chapter 2", createdAt = 1L)),
        )
        openViewer(repository, itemId, FakeThumbnailRenderer(pageCount = 3))
        openBookmarks()

        composeRule.waitUntil(5_000) { unmergedTagCount("pdf_bookmark_thumbnail") > 0 }
        composeRule.onNode(
            hasTestTag("pdf_bookmark_thumbnail") and hasContentDescription("Page 2"),
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithText("Chapter 2").assertExists()
    }

    @Test
    fun `bookmark row stays text only until thumbnails are loaded`() {
        val (repository, itemId) = repositoryWithPdf(
            bookmarks = listOf(ReadingBookmark(position = 0, label = "Start", createdAt = 1L)),
        )
        val gate = CompletableDeferred<Unit>()
        val thumbnailRenderer = FakeThumbnailRenderer(pageCount = 3, gate = gate)
        openViewer(repository, itemId, thumbnailRenderer)
        openBookmarks()

        composeRule.waitUntil(5_000) { tagCount("pdf_bookmark_row") > 0 }
        // Loading: the row is text only, no broken image placeholder.
        assertEquals(0, unmergedTagCount("pdf_bookmark_thumbnail"))
        composeRule.onNodeWithText("Start").assertExists()

        composeRule.runOnUiThread { gate.complete(Unit) }
        composeRule.waitUntil(5_000) { unmergedTagCount("pdf_bookmark_thumbnail") > 0 }
        composeRule.onNode(
            hasTestTag("pdf_bookmark_thumbnail") and hasContentDescription("Page 1"),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun `bookmark row has no thumbnail when the renderer failed`() {
        val (repository, itemId) = repositoryWithPdf(
            bookmarks = listOf(ReadingBookmark(position = 0, label = "Start", createdAt = 1L)),
        )
        val thumbnailRenderer = FakeThumbnailRenderer(pageCount = 0)
        openViewer(repository, itemId, thumbnailRenderer)
        openBookmarks()

        composeRule.waitUntil(5_000) { thumbnailRenderer.calls == 1 }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pdf_bookmark_row").assertExists()
        assertEquals(0, unmergedTagCount("pdf_bookmark_thumbnail"))
        composeRule.onAllNodes(hasContentDescription("Page 1"), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .let { assertTrue("a failed thumbnail must not leave a stale image", it.isEmpty()) }
    }
}
