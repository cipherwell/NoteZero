package com.cipherwell.notezero.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfInkKind
import com.cipherwell.notezero.model.PdfInkStroke
import com.cipherwell.notezero.model.PdfPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * Device/emulator validation of the real [AndroidPdfPageToolsExporter]
 * (`android.graphics.pdf` is not implemented by Robolectric).
 *
 * The fixture is a genuine 3-page A4 PDF whose pages are filled red/green/blue, so the planned
 * copy's **page count, order, rotation survival and inserted blank pages** can be verified by
 * rendering the produced PDF back through `PdfRenderer` and comparing page-centre colours.
 * Hostile input (empty/corrupt bytes, a plan that references missing pages, an oversized plan)
 * must fail closed with `null` instead of throwing, crashing or allocating an unbounded document.
 */
@RunWith(AndroidJUnit4::class)
class PdfPageToolsExportInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val PAGE_WIDTH_PT = BlankPdf.PAGE_WIDTH_PT
        const val PAGE_HEIGHT_PT = BlankPdf.PAGE_HEIGHT_PT

        /** JPEG/colour-management slack when reading a rendered page centre back. */
        const val COLOUR_TOLERANCE = 48

        const val RENDER_WIDTH_PX = 24
        const val RENDER_HEIGHT_PX = 34
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

    private fun annotationLayer(): PdfAnnotations = PdfAnnotations(
        strokes = listOf(
            PdfInkStroke(
                page = 0,
                kind = PdfInkKind.INK,
                colorIndex = 0,
                widthIndex = 1,
                // Keep the test ink off the page centre: the colour assertions sample the centre.
                points = listOf(PdfPoint(0.05f, 0.05f), PdfPoint(0.15f, 0.05f)),
            ),
        ),
    )

    /**
     * Renders every page of [pdfBytes] at a small fixed resolution. The caller owns and recycles
     * the returned bitmaps. Reuses the same seekable-descriptor approach the exporter documents.
     */
    private fun renderCentres(pdfBytes: ByteArray): List<Int> {
        val temp = File.createTempFile("nz-page-tools-test-", ".pdf", context.cacheDir)
        try {
            temp.writeBytes(pdfBytes)
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertTrue("the produced PDF must have at least one page", renderer.pageCount > 0)
                    return (0 until renderer.pageCount).map { index ->
                        renderer.openPage(index).use { page ->
                            val bitmap = Bitmap.createBitmap(
                                RENDER_WIDTH_PX,
                                RENDER_HEIGHT_PX,
                                Bitmap.Config.ARGB_8888,
                            )
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(
                                    bitmap,
                                    null,
                                    null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                )
                                bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        } finally {
            temp.delete()
        }
    }

    private fun assertColourNear(expected: Int, actual: Int, label: String) {
        val expectedRed = Color.red(expected)
        val expectedGreen = Color.green(expected)
        val expectedBlue = Color.blue(expected)
        val message = "$label: expected ~($expectedRed,$expectedGreen,$expectedBlue) " +
            "but was (${Color.red(actual)},${Color.green(actual)},${Color.blue(actual)})"
        assertTrue(message, abs(Color.red(actual) - expectedRed) <= COLOUR_TOLERANCE)
        assertTrue(message, abs(Color.green(actual) - expectedGreen) <= COLOUR_TOLERANCE)
        assertTrue(message, abs(Color.blue(actual) - expectedBlue) <= COLOUR_TOLERANCE)
    }

    @Test
    fun plannedCopyKeepsPageCountOrderRotationAndInsertedBlanks() = runBlocking {
        val fixture = threePageFixture()

        // [blue, red, green, blank] with the (uniform blue) first page rotated 90°.
        val plan = PdfPagePlan.of(3)
            .moveUp(2)
            .moveUp(1)
            .insertBlank()
            .rotateRight(0)
        assertEquals(listOf(2, 0, 1, null), plan.entries.map { it.sourcePage })
        assertEquals(listOf(1, 0, 0, 0), plan.entries.map { it.quarterTurns })

        val exported = AndroidPdfPageToolsExporter().export(
            context,
            fixture,
            plan,
            annotationLayer(),
        )
        assertNotNull("the page-tools export must produce bytes", exported)
        val bytes = exported ?: return@runBlocking
        assertTrue(bytes.size > 100)
        assertEquals("%PDF", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))

        val centres = renderCentres(bytes)
        assertEquals("one output page per plan entry", 4, centres.size)
        assertColourNear(Color.BLUE, centres[0], "reordered page 3")
        assertColourNear(Color.RED, centres[1], "reordered page 1")
        assertColourNear(Color.GREEN, centres[2], "reordered page 2")
        assertColourNear(Color.WHITE, centres[3], "inserted blank page")
    }

    @Test
    fun deletedPagesAreDroppedAndTheirAnnotationsWithThem() = runBlocking {
        val fixture = threePageFixture()
        val plan = PdfPagePlan.of(3).delete(0).plan
        assertEquals(listOf(1, 2), plan.entries.map { it.sourcePage })

        val exported = AndroidPdfPageToolsExporter().export(
            context,
            fixture,
            plan,
            annotationLayer(),
        )
        assertNotNull(exported)
        val centres = renderCentres(exported!!)
        assertEquals(2, centres.size)
        assertColourNear(Color.GREEN, centres[0], "page 2 after page 1 was deleted")
        assertColourNear(Color.BLUE, centres[1], "page 3 after page 1 was deleted")
    }

    @Test
    fun hostileInputFailsClosedInsteadOfThrowing() = runBlocking {
        val exporter = AndroidPdfPageToolsExporter()
        val fixture = threePageFixture()

        // Empty bytes.
        assertNull(exporter.export(context, ByteArray(0), PdfPagePlan.of(3), PdfAnnotations()))
        // Not a PDF at all.
        val corrupt = ByteArray(512) { index -> (index % 251).toByte() }
        assertNull(exporter.export(context, corrupt, PdfPagePlan.of(3), PdfAnnotations()))
        // A plan that references pages the document does not have is a bad plan, not a copy.
        assertNull(exporter.export(context, fixture, PdfPagePlan.of(4), PdfAnnotations()))
        // Too many pages for page tools -> invalid plan, exported by nobody.
        assertNull(
            exporter.export(
                context,
                fixture,
                PdfPagePlan.of(PdfPagePlan.MAX_PAGES + 1),
                PdfAnnotations(),
            ),
        )
        // An empty plan is never a valid copy.
        assertNull(exporter.export(context, fixture, PdfPagePlan.of(0), PdfAnnotations()))
    }

    @Test
    fun maximumInBoundsPlanStillExportsAValidDocument() = runBlocking {
        val fixture = threePageFixture()
        val plan = PdfPagePlan.of(PdfPagePlan.MAX_PAGES)
        // Only the three source pages exist, so the maximum plan must be rejected cleanly; a
        // blank-only plan (no source references) must still work and stay bounded.
        assertNull(AndroidPdfPageToolsExporter().export(context, fixture, plan, PdfAnnotations()))

        val blankOnly = PdfPagePlan.of(0).insertBlank().insertBlank()
        val exported = AndroidPdfPageToolsExporter().export(
            context,
            fixture,
            blankOnly,
            PdfAnnotations(),
        )
        assertNotNull(exported)
        val centres = renderCentres(exported!!)
        assertEquals(2, centres.size)
        assertColourNear(Color.WHITE, centres[0], "first blank page")
        assertColourNear(Color.WHITE, centres[1], "second blank page")
    }
}
