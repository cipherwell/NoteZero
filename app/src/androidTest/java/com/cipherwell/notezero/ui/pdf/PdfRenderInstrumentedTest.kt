package com.cipherwell.notezero.ui.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cipherwell.notezero.model.BlankPdf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the generated blank PDF with the real platform renderer and proves the renderer's
 * resource/plaintext discipline: bounded pages, in-memory bitmaps and no leftover temp file.
 *
 * Robolectric cannot run PdfRenderer (no native implementation), so this is device/emulator-only.
 */
@RunWith(AndroidJUnit4::class)
class PdfRenderInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun generatedBlankPdfIsAcceptedByThePlatformRenderer() = runBlocking {
        val result = AndroidPdfPageRenderer().render(context, BlankPdf.create())

        assertEquals("the file must be a valid 1-page PDF", 1, result.totalPages)
        assertEquals(1, result.renderedPages)
        val page = result.bitmaps.single()
        assertTrue("rendered page must have real pixels", page.width > 100 && page.height > 100)
        assertTrue("A4 page is taller than wide", page.height > page.width)
        result.bitmaps.forEach { it.recycle() }
    }

    @Test
    fun corruptBytesFailCleanlyAndLeaveNoRenderFile() = runBlocking {
        val result = AndroidPdfPageRenderer().render(context, "not a pdf".toByteArray())

        assertEquals(0, result.totalPages)
        assertTrue(result.bitmaps.isEmpty())
        assertTrue(
            "render temp files must be deleted",
            context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("pdf-render-") },
        )
    }
}
