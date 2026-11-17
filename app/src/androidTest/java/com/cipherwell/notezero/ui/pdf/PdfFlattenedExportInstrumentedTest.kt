package com.cipherwell.notezero.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfHighlightRect
import com.cipherwell.notezero.model.PdfInkKind
import com.cipherwell.notezero.model.PdfInkStroke
import com.cipherwell.notezero.model.PdfNote
import com.cipherwell.notezero.model.PdfPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Device/emulator validation of the real [AndroidPdfFlattenedExporter] (`android.graphics.pdf` is
 * not implemented by Robolectric).
 *
 * Proves the flattened output is a genuine PDF that the platform renderer accepts with the same
 * page count as the source, and that hostile/oversized input fails closed with `null` instead of
 * throwing, crashing or allocating an unbounded document. The temp file here exists only inside
 * the test to give `PdfRenderer` a seekable file descriptor; the exporter itself never writes one.
 */
@RunWith(AndroidJUnit4::class)
class PdfFlattenedExportInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun annotationLayer(): PdfAnnotations = PdfAnnotations(
        strokes = listOf(
            PdfInkStroke(
                page = 0,
                kind = PdfInkKind.INK,
                colorIndex = 0,
                widthIndex = 1,
                points = listOf(PdfPoint(0.1f, 0.1f), PdfPoint(0.5f, 0.6f), PdfPoint(0.9f, 0.2f)),
            ),
            PdfInkStroke(
                page = 0,
                kind = PdfInkKind.HIGHLIGHTER,
                colorIndex = 3,
                widthIndex = 2,
                points = listOf(PdfPoint(0.1f, 0.8f), PdfPoint(0.9f, 0.8f)),
            ),
        ),
        highlights = listOf(PdfHighlightRect(0, 0.12f, 0.3f, 0.5f, 0.42f, 2)),
        notes = listOf(PdfNote(0, 0.75f, 0.5f, "instrumented note")),
    )

    private fun assertValidPdf(bytes: ByteArray?, expectedPageCount: Int) {
        assertNotNull("the flattened export must produce bytes", bytes)
        val pdf = bytes ?: return
        assertTrue("flattened PDF must not be empty", pdf.size > 100)
        val magic = String(pdf.copyOfRange(0, 4), StandardCharsets.US_ASCII)
        assertEquals("output must start with the PDF magic", "%PDF", magic)

        // PdfRenderer only accepts a seekable descriptor, so the test writes the produced bytes
        // to its own cache scratch file (never the exporter) and deletes it again.
        val temp = File.createTempFile("nz-flatten-test-", ".pdf", context.cacheDir)
        try {
            temp.writeBytes(pdf)
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals("page count must be preserved", expectedPageCount, renderer.pageCount)
                }
            }
        } finally {
            temp.delete()
        }
    }

    @Test
    fun flattenedExportIsAValidPdfWithTheSamePageCount() = runBlocking {
        val pages = AndroidPdfPageRenderer().render(context, BlankPdf.create())
        assertEquals("fixture must render", 1, pages.totalPages)
        try {
            val bytes = AndroidPdfFlattenedExporter().export(pages, annotationLayer())
            assertValidPdf(bytes, expectedPageCount = pages.renderedPages)
        } finally {
            pages.bitmaps.forEach { it.recycle() }
        }
    }

    @Test
    fun hostileOrOversizedInputFailsClosedInsteadOfThrowing() = runBlocking {
        val exporter = AndroidPdfFlattenedExporter()

        // No rendered pages at all.
        assertNull(exporter.export(PdfPages(emptyList(), 0), PdfAnnotations()))

        // A hostile page list whose accessor throws must be caught, not propagated.
        val hostilePages = PdfPages(
            bitmaps = object : AbstractList<Bitmap>() {
                override val size: Int get() = 1
                override fun get(index: Int): Bitmap = throw IllegalStateException("hostile page list")
            },
            totalPages = 1,
        )
        assertNull(exporter.export(hostilePages, PdfAnnotations()))

        // Oversized page list: above the exporter's hard page ceiling, so it fails closed instead
        // of allocating a huge document.
        val oversized = (0 until 33).map {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        }
        try {
            assertNull(exporter.export(PdfPages(oversized, oversized.size), PdfAnnotations()))
        } finally {
            oversized.forEach { it.recycle() }
        }

        // Annotations pointing at pages that were not rendered are ignored, not fatal.
        val pages = AndroidPdfPageRenderer().render(context, BlankPdf.create())
        try {
            val farPageLayer = PdfAnnotations(
                strokes = listOf(
                    PdfInkStroke(
                        page = 7,
                        kind = PdfInkKind.INK,
                        colorIndex = 1,
                        widthIndex = 0,
                        points = listOf(PdfPoint(0.1f, 0.1f), PdfPoint(0.2f, 0.2f)),
                    ),
                ),
            )
            assertValidPdf(exporter.export(pages, farPageLayer), expectedPageCount = 1)
        } finally {
            pages.bitmaps.forEach { it.recycle() }
        }
    }

    @Test
    fun modelBoundsRejectHostileAnnotationValuesBeforeExport() {
        // The frozen model is the first line of defence: values outside its bounds never reach
        // the exporter (and the codec would reject them too).
        assertThrows(IllegalArgumentException::class.java) {
            PdfAnnotations(
                strokes = listOf(
                    PdfInkStroke(
                        page = PdfAnnotations.MAX_PAGE + 1,
                        kind = PdfInkKind.INK,
                        colorIndex = 0,
                        widthIndex = 0,
                        points = listOf(PdfPoint(0.1f, 0.1f), PdfPoint(0.2f, 0.2f)),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfAnnotations(notes = listOf(PdfNote(0, 1.5f, 0.5f, "out of bounds")))
        }
    }

    @Test
    fun maximumInBoundsAnnotationDensityStillExports() = runBlocking {
        val pages = AndroidPdfPageRenderer().render(context, BlankPdf.create())
        try {
            // 50 strokes x 2,000 points = the model's 100,000 total-point ceiling.
            val denseStrokes = (0 until 50).map { strokeIndex ->
                val points = (0 until PdfAnnotations.MAX_POINTS_PER_STROKE).map { pointIndex ->
                    PdfPoint(
                        x = pointIndex / PdfAnnotations.MAX_POINTS_PER_STROKE.toFloat(),
                        y = (strokeIndex % 10) / 10f,
                    )
                }
                PdfInkStroke(
                    page = 0,
                    kind = PdfInkKind.INK,
                    colorIndex = strokeIndex % PdfAnnotations.PALETTE_SIZE,
                    widthIndex = strokeIndex % PdfAnnotations.WIDTH_LEVELS,
                    points = points,
                )
            }
            val dense = PdfAnnotations(strokes = denseStrokes)
            assertEquals(PdfAnnotations.MAX_TOTAL_POINTS, dense.strokes.sumOf { it.points.size })

            // Must either produce a valid PDF or fail closed with null — never throw/OOM.
            val bytes = AndroidPdfFlattenedExporter().export(pages, dense)
            if (bytes != null) {
                assertValidPdf(bytes, expectedPageCount = 1)
            }
        } finally {
            pages.bitmaps.forEach { it.recycle() }
        }
    }
}
