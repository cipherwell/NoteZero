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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Device/emulator validation of [AndroidPdfThumbnailRenderer] against the real platform
 * `android.graphics.pdf` implementation (Robolectric cannot run `PdfRenderer`).
 *
 * The fixture is a genuine 3-page A4 PDF filled red/green/blue, so page count, per-page
 * correctness, the long-edge bound and the no-upscale rule can be verified from pixels. Hostile
 * input (empty/corrupt bytes, a zero page cap) must fail closed with an empty set and the
 * renderer's ephemeral cache file must never survive a render.
 */
@RunWith(AndroidJUnit4::class)
class PdfThumbnailsInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val PAGE_WIDTH_PT = BlankPdf.PAGE_WIDTH_PT
        const val PAGE_HEIGHT_PT = BlankPdf.PAGE_HEIGHT_PT

        /** JPEG/colour-management slack when reading a rendered page centre back. */
        const val COLOUR_TOLERANCE = 48

        /** Contract cap for [AndroidPdfThumbnailRenderer] default sizing. */
        const val LONG_EDGE_PX = 144

        const val THUMB_CACHE_PREFIX = "pdf-thumbs-"
    }

    /** Builds a real 3-page A4 PDF: page 1 red, page 2 green, page 3 blue. */
    private fun threePageFixture(): ByteArray {
        val colours = intArrayOf(Color.RED, Color.GREEN, Color.BLUE)
        val document = PdfDocument()
        try {
            colours.forEachIndexed { index, colour ->
                val pageInfo = PdfDocument.PageInfo.Builder(
                    PAGE_WIDTH_PT,
                    PAGE_HEIGHT_PT,
                    index + 1,
                ).create()
                val page = document.startPage(pageInfo)
                try {
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    val paint = Paint().apply {
                        this.color = colour
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(
                        0f,
                        0f,
                        PAGE_WIDTH_PT.toFloat(),
                        PAGE_HEIGHT_PT.toFloat(),
                        paint,
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

    private fun assertColourNear(expected: Int, actual: Int, label: String) {
        val message = "$label: expected ~(${Color.red(expected)},${Color.green(expected)}," +
            "${Color.blue(expected)}) but was (${Color.red(actual)},${Color.green(actual)}," +
            "${Color.blue(actual)})"
        assertTrue(message, abs(Color.red(actual) - Color.red(expected)) <= COLOUR_TOLERANCE)
        assertTrue(message, abs(Color.green(actual) - Color.green(expected)) <= COLOUR_TOLERANCE)
        assertTrue(message, abs(Color.blue(actual) - Color.blue(expected)) <= COLOUR_TOLERANCE)
    }

    /** The renderer must delete its one ephemeral cache file in `finally`. */
    private fun thumbnailCacheFiles(): List<java.io.File> =
        context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith(THUMB_CACHE_PREFIX) }

    @Test
    fun rendersOneBoundedThumbnailPerPageWithThePageColour() = runBlocking {
        val thumbnails = AndroidPdfThumbnailRenderer().render(context, threePageFixture())
        try {
            assertEquals(3, thumbnails.size)
            assertTrue(thumbnails.bitmap(3) == null)
            val expectedColours = intArrayOf(Color.RED, Color.GREEN, Color.BLUE)
            (0 until 3).forEach { page ->
                val bitmap = thumbnails.bitmap(page)!!
                assertTrue("thumbnail $page must have real pixels", bitmap.width > 0 && bitmap.height > 0)
                assertTrue(
                    "thumbnail long edge must be <= $LONG_EDGE_PX px",
                    maxOf(bitmap.width, bitmap.height) <= LONG_EDGE_PX,
                )
                assertTrue("an A4 page thumbnail stays portrait", bitmap.height > bitmap.width)
                assertColourNear(
                    expectedColours[page],
                    bitmap.getPixel(bitmap.width / 2, bitmap.height / 2),
                    "page ${page + 1} centre",
                )
            }
        } finally {
            thumbnails.recycle()
        }
        assertEquals("a recycled set is empty", 0, thumbnails.size)
        assertTrue(
            "no thumbnail cache file may survive: ${thumbnailCacheFiles().map { it.name }}",
            thumbnailCacheFiles().isEmpty(),
        )
    }

    @Test
    fun whiteBlankPageRendersOnAWhiteBackground() = runBlocking {
        val thumbnails = AndroidPdfThumbnailRenderer().render(context, BlankPdf.create())
        try {
            assertEquals(1, thumbnails.size)
            val bitmap = thumbnails.bitmap(0)!!
            assertColourNear(
                Color.WHITE,
                bitmap.getPixel(bitmap.width / 2, bitmap.height / 2),
                "blank page centre",
            )
        } finally {
            thumbnails.recycle()
        }
        assertTrue(thumbnailCacheFiles().isEmpty())
    }

    @Test
    fun emptyAndCorruptBytesFailClosedToAnEmptySet() = runBlocking {
        val renderer = AndroidPdfThumbnailRenderer()

        assertTrue("empty bytes are not a document", renderer.render(context, ByteArray(0)).isEmpty)
        assertTrue(
            "corrupt bytes must never surface a bitmap",
            renderer.render(context, "not a pdf at all".toByteArray()).isEmpty,
        )
        assertTrue(
            "a corrupt render must not leave its cache file",
            thumbnailCacheFiles().isEmpty(),
        )
    }

    @Test
    fun pageCapAndLongEdgeAreEnforced() = runBlocking {
        val fixture = threePageFixture()

        val capped = AndroidPdfThumbnailRenderer(maxPages = 2, longEdgePx = 48)
            .render(context, fixture)
        try {
            assertEquals("only the configured pages are rendered", 2, capped.size)
            (0 until 2).forEach { page ->
                val bitmap = capped.bitmap(page)!!
                assertTrue(bitmap.width <= 48 && bitmap.height <= 48)
            }
        } finally {
            capped.recycle()
        }

        val none = AndroidPdfThumbnailRenderer(maxPages = 0).render(context, fixture)
        assertTrue("a zero page cap renders nothing", none.isEmpty)

        assertTrue(thumbnailCacheFiles().isEmpty())
    }

    @Test
    fun thumbnailsAreNeverUpscaledBeyondThePdfPageSize() = runBlocking {
        val thumbnails = AndroidPdfThumbnailRenderer(longEdgePx = 10_000)
            .render(context, threePageFixture())
        try {
            assertEquals(3, thumbnails.size)
            val bitmap: Bitmap = thumbnails.bitmap(0)!!
            assertTrue(
                "a huge long edge must not invent pixels: ${bitmap.width}x${bitmap.height}",
                bitmap.width <= PAGE_WIDTH_PT && bitmap.height <= PAGE_HEIGHT_PT,
            )
        } finally {
            thumbnails.recycle()
        }
    }
}
