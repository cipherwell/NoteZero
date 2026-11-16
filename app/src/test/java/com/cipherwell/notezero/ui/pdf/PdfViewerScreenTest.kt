package com.cipherwell.notezero.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.FakeNoteRepository
import com.cipherwell.notezero.ui.UiTestBase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
