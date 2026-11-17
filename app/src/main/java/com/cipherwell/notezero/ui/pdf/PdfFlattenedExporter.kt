package com.cipherwell.notezero.ui.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfInkKind
import com.cipherwell.notezero.model.PdfNote
import com.cipherwell.notezero.model.PdfPoint
import java.io.ByteArrayOutputStream
import kotlin.math.max
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
 * Injectable into `PdfViewerScreen` so Robolectric tests can assert the export wiring without
 * the native `android.graphics.pdf` implementation (the real ones is exercised by
 * `PdfFlattenedExportInstrumentedTest` on a device/emulator).
 */
fun interface PdfFlattenedExporter {
    fun export(pages: PdfPages, annotations: PdfAnnotations): ByteArray?
}

/**
 * Default [PdfFlattenedExporter]: one A4 page per rendered PDF page, white background, the page
 * bitmap drawn scaled-to-fit, then the annotations over it at exactly the same scale.
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
            // One paint per export so concurrent exports cannot share mutable Paint state.
            val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                isAntiAlias = true
                isFilterBitmap = true
                isDither = true
            }
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
                    drawPage(canvas, bitmap, index, annotations, bitmapPaint)
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

    private fun drawPage(
        canvas: Canvas,
        bitmap: Bitmap,
        pageIndex: Int,
        annotations: PdfAnnotations,
        bitmapPaint: Paint,
    ) {
        val pageWidth = pageWidthPt.toFloat()
        val pageHeight = pageHeightPt.toFloat()
        val scale = min(pageWidth / bitmap.width, pageHeight / bitmap.height)
        // Bitmap coordinates and page points share the transform so annotation widths match the
        // on-screen layer: draw in bitmap space, let the canvas scale convert to points.
        canvas.save()
        try {
            canvas.translate(
                (pageWidth - bitmap.width * scale) / 2f,
                (pageHeight - bitmap.height * scale) / 2f,
            )
            canvas.scale(scale, scale)
            canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
            drawAnnotations(canvas, pageIndex, bitmap.width, bitmap.height, annotations)
        } finally {
            canvas.restore()
        }
    }

    private fun drawAnnotations(
        canvas: Canvas,
        pageIndex: Int,
        pageWidth: Int,
        pageHeight: Int,
        annotations: PdfAnnotations,
    ) {
        val minEdge = min(pageWidth, pageHeight).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF()

        annotations.highlights.forEach { highlight ->
            if (highlight.page != pageIndex) return@forEach
            rect.set(
                highlight.left * pageWidth,
                highlight.top * pageHeight,
                highlight.right * pageWidth,
                highlight.bottom * pageHeight,
            )
            paint.style = Paint.Style.FILL
            paint.color = PdfAnnotationPalette.argb(
                highlight.colorIndex,
                PdfAnnotationPalette.HIGHLIGHT_FILL_ALPHA,
            )
            canvas.drawRect(rect, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = PdfAnnotationPalette.highlightBorderWidthPx(minEdge)
            paint.color = PdfAnnotationPalette.argb(
                highlight.colorIndex,
                PdfAnnotationPalette.HIGHLIGHT_BORDER_ALPHA,
            )
            canvas.drawRect(rect, paint)
        }

        annotations.strokes.forEach { stroke ->
            if (stroke.page != pageIndex || stroke.points.size < 2) return@forEach
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = PdfAnnotationPalette.strokeWidthPx(
                minEdge,
                stroke.kind,
                stroke.widthIndex,
            )
            paint.color = PdfAnnotationPalette.argb(
                stroke.colorIndex,
                PdfAnnotationPalette.strokeAlpha(stroke.kind),
            )
            canvas.drawPath(strokePath(stroke.points, pageWidth, pageHeight), paint)
        }

        annotations.notes.forEach { note ->
            if (note.page != pageIndex) return@forEach
            drawNoteMarker(canvas, note, pageWidth, pageHeight, minEdge, paint)
        }
    }

    private fun strokePath(points: List<PdfPoint>, pageWidth: Int, pageHeight: Int): Path {
        val path = Path()
        path.moveTo(points[0].x * pageWidth, points[0].y * pageHeight)
        for (index in 1 until points.size) {
            path.lineTo(points[index].x * pageWidth, points[index].y * pageHeight)
        }
        return path
    }

    private fun drawNoteMarker(
        canvas: Canvas,
        note: PdfNote,
        pageWidth: Int,
        pageHeight: Int,
        minEdge: Float,
        paint: Paint,
    ) {
        val centerX = note.x * pageWidth
        val centerY = note.y * pageHeight
        val radius = PdfAnnotationPalette.markerRadiusPx(minEdge)
        paint.style = Paint.Style.FILL
        paint.color = PdfAnnotationPalette.paintArgb(PdfAnnotationPalette.noteMarkerFill)
        canvas.drawCircle(centerX, centerY, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, radius * 0.22f)
        paint.color = PdfAnnotationPalette.paintArgb(PdfAnnotationPalette.noteMarkerGlyph)
        canvas.drawCircle(centerX, centerY, radius, paint)

        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(1f, radius * 0.18f)
        val halfLine = radius * 0.55f
        val lineGap = radius * 0.42f
        for (index in -1..1) {
            val y = centerY + index * lineGap
            canvas.drawLine(centerX - halfLine, y, centerX + halfLine, y, paint)
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
