package com.cipherwell.notezero.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cipherwell.notezero.model.BlankPdf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Device/emulator validation of the real [AndroidPdfPageSessionFactory] and its
 * `AndroidPdfPageSession` (`android.graphics.pdf` has no Robolectric implementation).
 *
 * The fixture is a genuine 12-page A4 PDF whose pages are filled with distinct colours, so the
 * session can be proven to render an **arbitrary** page (e.g. page 10, beyond the old 8-page
 * viewer cap) and only that page. The tests also pin the lifecycle contract: empty/corrupt input
 * opens no session, the session file is unlinked while the document is open, out-of-range renders
 * return `null`, and `close()` is idempotent and leaves no `pdf-session-*` file behind.
 *
 * Compiled into the debug androidTest APK; it does not need to run as part of the JVM suite.
 * Method names are camelCase on purpose: Kotlin letternames with spaces become `...$name$1`
 * anonymous classes, which R8/D8 rejects for androidTest dex (space in SimpleName).
 */
@RunWith(AndroidJUnit4::class)
class PdfPageSessionInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val DOCUMENT_PAGES = 12

        /** Index beyond the old eager 8-page viewer cap, used for the arbitrary-page assertion. */
        const val ARBITRARY_PAGE = 10

        /** JPEG/colour-management slack when reading a rendered page centre back. */
        const val COLOUR_TOLERANCE = 48
    }

    private val pageColours: IntArray = intArrayOf(
        Color.RED,
        Color.GREEN,
        Color.BLUE,
        Color.YELLOW,
        Color.CYAN,
        Color.MAGENTA,
        Color.GRAY,
        Color.DKGRAY,
        Color.LTGRAY,
        Color.rgb(200, 100, 50),
        Color.rgb(50, 100, 200),
        Color.rgb(100, 200, 50),
    )

    /** Builds a real 12-page A4 PDF, page `i` filled with `pageColours[i]`. */
    private fun twelvePagePdf(): ByteArray {
        val document = PdfDocument()
        try {
            pageColours.forEachIndexed { index, colour ->
                val pageInfo = PdfDocument.PageInfo.Builder(
                    BlankPdf.PAGE_WIDTH_PT,
                    BlankPdf.PAGE_HEIGHT_PT,
                    index + 1,
                ).create()
                val page = document.startPage(pageInfo)
                try {
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    canvas.drawRect(
                        0f,
                        0f,
                        BlankPdf.PAGE_WIDTH_PT.toFloat(),
                        BlankPdf.PAGE_HEIGHT_PT.toFloat(),
                        Paint().apply {
                            this.color = colour
                            style = Paint.Style.FILL
                        },
                    )
                } finally {
                    document.finishPage(page)
                }
            }
            return ByteArrayOutputStream().use { output ->
                document.writeTo(output)
                output.toByteArray()
            }
        } finally {
            document.close()
        }
    }

    private fun sessionFiles() = context.cacheDir
        .listFiles()
        ?.filter { it.name.startsWith("pdf-session-") }
        .orEmpty()

    private fun assertCloseTo(expected: Int, actual: Int) {
        assertTrue(
            "colour mismatch: expected $expected but was $actual",
            abs(Color.red(expected) - Color.red(actual)) <= COLOUR_TOLERANCE &&
                abs(Color.green(expected) - Color.green(actual)) <= COLOUR_TOLERANCE &&
                abs(Color.blue(expected) - Color.blue(actual)) <= COLOUR_TOLERANCE,
        )
    }

    @Test
    fun opensTwelvePageDocumentAndRendersArbitraryPage() = runBlocking {
        val factory = AndroidPdfPageSessionFactory()
        val opened = factory.open(context, twelvePagePdf())
        assertNotNull("a real 12-page PDF must open a session", opened)
        val session = requireNotNull(opened)
        try {
            assertEquals(DOCUMENT_PAGES, session.pageCount)

            val rendered = session.render(ARBITRARY_PAGE)
            assertNotNull("page 10 (beyond the old 8-page cap) must render", rendered)
            val bitmap = requireNotNull(rendered)
            try {
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
                assertTrue(
                    "the long edge must never exceed the hard 1600 px cap",
                    max(bitmap.width, bitmap.height) <= PDF_PAGE_MAX_EDGE_PX,
                )
                assertTrue(
                    "an A4 page must never be upscaled above its 595x842 pt size",
                    bitmap.width <= BlankPdf.PAGE_WIDTH_PT && bitmap.height <= BlankPdf.PAGE_HEIGHT_PT,
                )
                // The colour proves this is really page 11 of the fixture, not page 0.
                assertCloseTo(pageColours[ARBITRARY_PAGE], bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            } finally {
                bitmap.recycle()
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun rendersBoundedDownscaleForSmallerRequestedEdge() = runBlocking {
        val factory = AndroidPdfPageSessionFactory()
        val opened = factory.open(context, twelvePagePdf())
        assertNotNull(opened)
        val session = requireNotNull(opened)
        try {
            val edge = 200
            val rendered = session.render(pageIndex = 3, maxEdgePx = edge)
            assertNotNull(rendered)
            val bitmap = requireNotNull(rendered)
            try {
                assertTrue(
                    "a requested edge must bound the long edge",
                    max(bitmap.width, bitmap.height) <= edge,
                )
                val expectedRatio = BlankPdf.PAGE_WIDTH_PT.toFloat() / BlankPdf.PAGE_HEIGHT_PT
                assertEquals(
                    "downscaling must preserve the page aspect ratio",
                    expectedRatio,
                    bitmap.width.toFloat() / bitmap.height.toFloat(),
                    0.02f,
                )
            } finally {
                bitmap.recycle()
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun outOfRangePagesReturnNullInsteadOfThrowing() = runBlocking {
        val opened = AndroidPdfPageSessionFactory().open(context, twelvePagePdf())
        assertNotNull(opened)
        val session = requireNotNull(opened)
        try {
            assertNull("negative index is hostile input", session.render(-1))
            assertNull("pageCount is one past the last valid index", session.render(DOCUMENT_PAGES))
            assertNull("a huge index must not allocate", session.render(Int.MAX_VALUE))
        } finally {
            session.close()
        }
    }

    @Test
    fun sessionFileIsUnlinkedWhileDocumentOpen() = runBlocking {
        val opened = AndroidPdfPageSessionFactory().open(context, twelvePagePdf())
        assertNotNull(opened)
        val session = requireNotNull(opened)
        try {
            assertTrue(
                "the decrypted PDF must have no path while the session is open",
                sessionFiles().isEmpty(),
            )
            val bitmap = session.render(0)
            assertNotNull(bitmap)
            bitmap!!.recycle()
            assertTrue("rendering must not materialise a plaintext path", sessionFiles().isEmpty())
        } finally {
            session.close()
        }
        assertTrue("nothing may be left after close", sessionFiles().isEmpty())
    }

    @Test
    fun closeIsIdempotentAndLeavesNoTempFile() = runBlocking {
        val opened = AndroidPdfPageSessionFactory().open(context, twelvePagePdf())
        assertNotNull(opened)
        val session = requireNotNull(opened)
        session.render(0)?.recycle()

        session.close()
        session.close()
        session.close()

        assertTrue("a double close must not throw and must leave no file", sessionFiles().isEmpty())
        assertNull("a closed session renders nothing", session.render(0))
    }

    @Test
    fun emptyAndCorruptDocumentsOpenNoSessionAndLeaveNoFile() = runBlocking {
        val factory = AndroidPdfPageSessionFactory()

        assertNull("empty bytes are not a document", factory.open(context, ByteArray(0)))
        assertNull(
            "arbitrary bytes are not a document",
            factory.open(context, "this is not a pdf".toByteArray()),
        )
        val valid = twelvePagePdf()
        assertNull(
            "a truncated PDF is corrupt, not a crash",
            factory.open(context, valid.copyOf(valid.size / 3)),
        )

        assertTrue("failed opens must not leave a plaintext session file", sessionFiles().isEmpty())
    }
}
