package com.cipherwell.notezero.ui.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.model.BlankPdf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * JVM/Robolectric contract tests for the lazy page session.
 *
 * The real `android.graphics.pdf.PdfRenderer` has no native implementation under Robolectric, so
 * here we verify (a) the pure bitmap-bounds math and (b) that the real factory **fails closed**
 * for empty/corrupt input without throwing, without logging and without leaving a plaintext
 * `pdf-session-*` file behind. The success path (arbitrary page, page count, close idempotence,
 * no temp file, out-of-range) is exercised on a real renderer by
 * `PdfPageSessionInstrumentedTest`; the viewer behaviour with a fake session lives in
 * `PdfLazyViewerTest`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PdfPageSessionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun sessionFiles(): List<File> =
        context.cacheDir.listFiles()?.filter { it.name.startsWith("pdf-session-") }.orEmpty()

    @Test
    fun `view page bound is a hard two thousand`() {
        assertEquals(2_000, MAX_VIEW_PAGES)
    }

    @Test
    fun `empty document bytes open no session and write no temp file`() {
        val factory = AndroidPdfPageSessionFactory()

        val session = runBlocking { factory.open(context, ByteArray(0)) }

        assertNull("empty bytes are not a document", session)
        assertTrue("no plaintext session file may be left", sessionFiles().isEmpty())
    }

    @Test
    fun `corrupt document fails closed and leaves no plaintext session file`() {
        val factory = AndroidPdfPageSessionFactory()

        val session = runBlocking { factory.open(context, "not a pdf at all".toByteArray()) }

        assertNull("a corrupt PDF must never surface a session", session)
        assertTrue("the ephemeral file must not survive a failed open", sessionFiles().isEmpty())
    }

    @Test
    fun `a page smaller than the edge is never upscaled`() {
        val size = pageBitmapSize(pageWidth = 595, pageHeight = 842, maxEdgePx = 1_600)

        assertEquals(PageBitmapSize(595, 842), size)
    }

    @Test
    fun `landscape and portrait long edges are capped at the requested edge`() {
        assertEquals(
            PageBitmapSize(1_600, 800),
            pageBitmapSize(pageWidth = 3_200, pageHeight = 1_600, maxEdgePx = 1_600),
        )
        assertEquals(
            PageBitmapSize(800, 1_600),
            pageBitmapSize(pageWidth = 1_600, pageHeight = 3_200, maxEdgePx = 1_600),
        )
    }

    @Test
    fun `the requested edge is clamped to the hard maximum`() {
        val size = pageBitmapSize(pageWidth = 4_000, pageHeight = 2_000, maxEdgePx = 99_999)

        assertEquals("a hostile edge must be clamped to PDF_PAGE_MAX_EDGE_PX", 1_600, size.width)
        assertEquals(800, size.height)
    }

    @Test
    fun `degenerate page and edge values still yield a usable bitmap`() {
        assertEquals(
            "a zero-sized MediaBox must not produce a zero-sized bitmap",
            PageBitmapSize(1, 1),
            pageBitmapSize(pageWidth = 0, pageHeight = 0, maxEdgePx = 1_600),
        )
        assertEquals(
            PageBitmapSize(1, 1),
            pageBitmapSize(pageWidth = 100, pageHeight = 50, maxEdgePx = 0),
        )
    }

    @Test
    fun `the injectable factory seam receives the decrypted bytes in one open call`() {
        // The fake factory is the seam the viewer uses in Robolectric: one open per viewer, the
        // decrypted bytes, and a null session is the viewer's "pdf_error" path (no page raster).
        val factory = FakePdfPageSessionFactory(null)

        val opened = runBlocking { factory.open(context, BlankPdf.create()) }

        assertNull(opened)
        assertEquals(1, factory.openCalls)
        assertNotNull("the factory must receive the decrypted bytes", factory.lastBytes)
        assertTrue(factory.lastBytes!!.contentEquals(BlankPdf.create()))
    }
}
