package com.cipherwell.notezero.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.PdfAnnotations
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Flattens already-rendered page bitmaps and the encrypted annotation layer into a new,
 * rasterised PDF held **in memory**.
 *
 * Returns null (never throws) on any failure — empty/mismatched input, a recycled bitmap,
 * an unsupported renderer or `OutOfMemoryError` — so the export flow can show
 * `pdf_export_annotated_failed` without crashing the viewer. No temp file is written, no
 * plaintext survives the call and nothing is logged: the returned bytes are the caller's
 * only copy until they reach the user-chosen SAF destination.
 *
 * Legacy utility: the viewer no longer takes this exporter (since increment 18 the annotated copy
 * is built by the shared [PdfPageToolsExporter] with an identity [PdfPagePlan], see
 * `docs/PDF-LAZY-VIEWER.md`). The class and its instrumented test are retained so the rasterised
 * flatten path stays covered; it can be removed in a later cleanup.
 */
fun interface PdfFlattenedExporter {
    fun export(pages: PdfPages, annotations: PdfAnnotations): ByteArray?
}

/**
 * Default [PdfFlattenedExporter]: one A4 page per rendered PDF page, white background, the page
 * bitmap drawn scaled-to-fit, then the annotations over it at exactly the same scale.
 *
 * The page/annotation drawing itself lives in [PdfPageDrawing] and is shared with the page-tools
 * exporter, so an annotated copy and a page-tools copy render identically.
 *
 * Bounds: at most [MAX_EXPORT_PAGES] pages (the viewer renderer never produces more than a
 * handful, but the cap keeps a hostile caller from allocating an unbounded document), page
 * count never exceeds `pages.bitmaps.size` nor `pages.totalPages`, and annotations whose page
 * index is not rendered are ignored.
 */
class AndroidPdfFlattenedExporter(
    private val pageWidthPt: Int = BlankPdf.PAGE_WIDTH_PT,
    private val pageHeightPt: Int = BlankPdf.PAGE_HEIGHT_PT,
) : PdfFlattenedExporter {

    override fun export(pages: PdfPages, annotations: PdfAnnotations): ByteArray? {
        // Refuse rather than silently truncate: a flattened copy that drops pages is worse
        // than an explicit failure. Normal viewers only render a bounded prefix, but the
        // exporter also hard-fails for hostile/oversized inputs.
        if (pages.totalPages > MAX_EXPORT_PAGES || pages.bitmaps.size > MAX_EXPORT_PAGES) return null
        val pageCount = minOf(pages.bitmaps.size, pages.totalPages)
        if (pageCount <= 0) return null
        var document: PdfDocument? = null
        return try {
            val pdf = PdfDocument().also { document = it }
            for (index in 0 until pageCount) {
                val bitmap = pages.bitmaps[index]
                if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
                val pageInfo = PdfDocument.PageInfo.Builder(
                    pageWidthPt,
                    pageHeightPt,
                    index + 1,
                ).create()
                val page = pdf.startPage(pageInfo)
                try {
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    PdfPageDrawing.drawPage(
                        canvas = canvas,
                        bitmap = bitmap,
                        pageWidthPt = pageWidthPt.toFloat(),
                        pageHeightPt = pageHeightPt.toFloat(),
                        sourcePage = index,
                        annotations = annotations,
                        quarterTurns = 0,
                    )
                } finally {
                    pdf.finishPage(page)
                }
            }
            ByteArrayOutputStream().use { output ->
                pdf.writeTo(output)
                output.toByteArray()
            }
        } catch (_: Throwable) {
            // OOM / closed document / platform renderer unavailable: fail closed, never crash.
            null
        } finally {
            runCatching { document?.close() }
        }
    }

    private companion object {
        /**
         * Hard ceiling for one export, independent of the model/renderer bounds: the viewer
         * renderer caps at 8 pages, so anything above this is a hostile caller and fails closed.
         */
        const val MAX_EXPORT_PAGES = 32
    }
}
