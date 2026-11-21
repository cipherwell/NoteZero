package com.cipherwell.notezero.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.PdfAnnotations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Builds the flattened, encrypted-ready PDF for the page-tools copy: the planned source pages in
 * their new order/rotation, inserted blank pages, and the annotation layer, as a new in-memory
 * rasterised PDF.
 *
 * Injectable so Robolectric UI tests never touch the native `android.graphics.pdf`
 * implementation; the real [AndroidPdfPageToolsExporter] is exercised by
 * `PdfPageToolsExportInstrumentedTest` on a device/emulator.
 */
fun interface PdfPageToolsExporter {
    /**
     * Returns the flattened copy's bytes, or `null` on any failure. The returned array is owned
     * only by the caller (it is stored through the encrypted attachment pipeline, never in a
     * plaintext file).
     */
    suspend fun export(
        context: Context,
        pdfBytes: ByteArray,
        plan: PdfPagePlan,
        annotations: PdfAnnotations,
    ): ByteArray?
}

/**
 * Default [PdfPageToolsExporter] (`android.graphics.pdf`).
 *
 * For each plan entry it either draws a white A4 page (inserted blank page) or renders the
 * source page with [PdfRenderer] at a bounded resolution, draws it scaled-to-fit and centred on
 * a white A4 page with the entry's clockwise quarter-turn applied, and draws that source page's
 * annotations in the same bitmap space through the shared [PdfPageDrawing] helper — so an
 * annotation rotates and moves together with its page.
 *
 * Security / temporary file: `PdfRenderer` needs a **seekable file descriptor**, so the
 * decrypted bytes are written to a single ephemeral app-private cache file for the duration of
 * the export and deleted in the `finally` block (the exact trade-off documented for
 * [AndroidPdfPageSessionFactory] and [AndroidPdfPageRenderer] in DECISIONS #20 / THREAT-MODEL:
 * one file, app-private cache, short-lived, no backup). The output is returned in memory and reaches disk only through the
 * encrypted attachment pipeline; nothing is logged.
 *
 * Bounds: a plan is re-validated against the actual document (at most [maxPages] pages, no
 * out-of-range/duplicate source pages) and every rendered page is capped at [MAX_EDGE_PX] on
 * the long edge. Because a [PdfDocument] keeps its recorded page content until `writeTo`, the
 * rendered bitmaps stay alive for the export; to keep that heap bounded for long documents the
 * per-page resolution also respects a total pixel budget ([MAX_TOTAL_PIXELS]) — a 100-page copy
 * is lower-resolution than a 3-page one, which is the only safe way to flatten a whole document
 * on a phone. Any failure (bad plan, corrupt PDF, missing native renderer, OOM) returns null and
 * every bitmap/page/document is recycled/closed; no exception escapes (except coroutine
 * cancellation, which is rethrown so the caller's coroutine ends properly).
 */
class AndroidPdfPageToolsExporter(
    private val maxPages: Int = PdfPagePlan.MAX_PAGES,
    private val pageWidthPt: Int = BlankPdf.PAGE_WIDTH_PT,
    private val pageHeightPt: Int = BlankPdf.PAGE_HEIGHT_PT,
) : PdfPageToolsExporter {

    override suspend fun export(
        context: Context,
        pdfBytes: ByteArray,
        plan: PdfPagePlan,
        annotations: PdfAnnotations,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (pdfBytes.isEmpty() || !plan.isValid) return@withContext null
        val pageLimit = maxPages.coerceIn(1, PdfPagePlan.MAX_PAGES)
        if (plan.size > pageLimit) return@withContext null
        val temp = File(context.cacheDir, "pdf-page-tools-${UUID.randomUUID()}.pdf")
        try {
            temp.writeBytes(pdfBytes)
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    writeFlattenedCopy(renderer, plan, annotations)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Corrupt file / missing native renderer / OOM: clean failure, never a crash.
            null
        } finally {
            temp.delete()
        }
    }

    /** Renders the plan into one [PdfDocument]; all bitmaps are recycled by the caller's finally. */
    private fun writeFlattenedCopy(
        renderer: PdfRenderer,
        plan: PdfPagePlan,
        annotations: PdfAnnotations,
    ): ByteArray? {
        val sourcePageCount = renderer.pageCount
        if (sourcePageCount <= 0) return null
        // The plan was validated against the viewer's page count, but the document is the
        // authority: a plan referencing a page that does not exist is a bad plan, not a
        // silently dropped page.
        if (plan.entries.any { entry -> entry.sourcePage?.let { it >= sourcePageCount } == true }) {
            return null
        }
        val perPagePixels = (MAX_TOTAL_PIXELS / plan.size).coerceAtLeast(MIN_PAGE_PIXELS)
        val renderedBitmaps = ArrayList<Bitmap>(plan.size)
        var document: PdfDocument? = null
        try {
            val pdf = PdfDocument().also { document = it }
            for ((index, entry) in plan.entries.withIndex()) {
                val pageInfo = PdfDocument.PageInfo.Builder(
                    pageWidthPt,
                    pageHeightPt,
                    index + 1,
                ).create()
                val page = pdf.startPage(pageInfo)
                try {
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    val sourcePage = entry.sourcePage
                    if (sourcePage != null) {
                        val bitmap = renderSourcePage(renderer, sourcePage, perPagePixels)
                            ?: return null
                        renderedBitmaps += bitmap
                        PdfPageDrawing.drawPage(
                            canvas = canvas,
                            bitmap = bitmap,
                            pageWidthPt = pageWidthPt.toFloat(),
                            pageHeightPt = pageHeightPt.toFloat(),
                            sourcePage = sourcePage,
                            annotations = annotations,
                            quarterTurns = entry.quarterTurns,
                        )
                    }
                } finally {
                    pdf.finishPage(page)
                }
            }
            ByteArrayOutputStream().use { output ->
                pdf.writeTo(output)
                return output.toByteArray()
            }
        } finally {
            // Nothing survives the call: every page bitmap and the document are released here,
            // including on the early `return null` paths.
            renderedBitmaps.forEach { bitmap -> runCatching { bitmap.recycle() } }
            runCatching { document?.close() }
        }
    }

    /** Renders one source page into a fresh bitmap, bounded by [perPagePixels] and the edge cap. */
    private fun renderSourcePage(
        renderer: PdfRenderer,
        sourcePage: Int,
        perPagePixels: Int,
    ): Bitmap? = renderer.openPage(sourcePage).use { page ->
        val widthPoints = page.width.coerceAtLeast(1).toFloat()
        val heightPoints = page.height.coerceAtLeast(1).toFloat()
        val edgeScale = MAX_EDGE_PX / max(widthPoints, heightPoints)
        val areaScale = sqrt(
            perPagePixels.toDouble() / (widthPoints.toDouble() * heightPoints.toDouble()),
        ).toFloat()
        val scale = min(edgeScale, areaScale).coerceAtLeast(MIN_RENDER_SCALE)
        val width = (widthPoints * scale).toInt().coerceAtLeast(1)
        val height = (heightPoints * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (throwable: Throwable) {
            bitmap.recycle()
            throw throwable
        }
    }

    private companion object {
        /** Long-edge cap for one rendered source page (bounded, resolution-independent quality). */
        const val MAX_EDGE_PX = 1_600

        /**
         * Total pixel budget for all source pages of one export (~48 MiB at ARGB_8888).
         * `PdfDocument` keeps every recorded page until `writeTo`, so for long documents the
         * per-page resolution is lowered instead of risking an unbounded allocation.
         */
        const val MAX_TOTAL_PIXELS = 12_000_000

        /** Never render a page into a degenerate 0-pixel bitmap; tiny-but-usable floor. */
        const val MIN_PAGE_PIXELS = 10_000

        /** Never scale a source page below 2% (a corrupt MediaBox must not make width/height 0). */
        const val MIN_RENDER_SCALE = 0.02f
    }
}
