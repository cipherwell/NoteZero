package com.cipherwell.notezero.ui.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfNote
import com.cipherwell.notezero.model.PdfPoint
import kotlin.math.max
import kotlin.math.min

/**
 * The single implementation of "draw one rendered page + its annotations onto a PDF page
 * canvas", shared by the annotated-copy exporter ([AndroidPdfFlattenedExporter]) and the
 * page-tools exporter ([AndroidPdfPageToolsExporter]).
 *
 * Sharing this code is what keeps annotation rendering from drifting between the two exports:
 * annotations are drawn in **bitmap space** (normalised coordinates × the rendered bitmap's
 * width/height) on the same canvas transform that draws the page bitmap, so their relative size
 * and position match the on-screen overlay and they move/rotate with their page.
 *
 * The canvas is left exactly as it was found (the caller owns it) and nothing is recycled or
 * cached here: the function is a pure draw into the provided canvas.
 */
internal object PdfPageDrawing {

    /**
     * Draws [bitmap] scaled-to-fit and centred on a [pageWidthPt] × [pageHeightPt] page and, over
     * it, the annotations of [sourcePage] in the same bitmap coordinate space.
     *
     * [quarterTurns] rotates the whole composition clockwise around the page centre, normalised
     * to `0..3`. The rotation is applied to the shared transform, so a rotated page's
     * annotations are drawn rotated as well — the composed copy looks exactly like the on-screen
     * page turned on its side, not like a page with a rotated bitmap and horizontal ink.
     */
    fun drawPage(
        canvas: Canvas,
        bitmap: Bitmap,
        pageWidthPt: Float,
        pageHeightPt: Float,
        sourcePage: Int,
        annotations: PdfAnnotations,
        quarterTurns: Int = 0,
    ) {
        val turns = ((quarterTurns % 4) + 4) % 4
        // A quarter-turn swaps the content's effective width/height, so fitting must use the
        // swapped extents or a landscape page would overflow the portrait paper.
        val contentWidth = if (turns % 2 == 1) bitmap.height.toFloat() else bitmap.width.toFloat()
        val contentHeight = if (turns % 2 == 1) bitmap.width.toFloat() else bitmap.height.toFloat()
        val scale = min(pageWidthPt / contentWidth, pageHeightPt / contentHeight)
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }
        // Bitmap coordinates and page points share the transform so annotation widths match the
        // on-screen layer: draw in bitmap space, let the canvas scale convert to points.
        canvas.save()
        try {
            canvas.translate(pageWidthPt / 2f, pageHeightPt / 2f)
            if (turns != 0) canvas.rotate(turns * 90f)
            canvas.scale(scale, scale)
            canvas.translate(-bitmap.width / 2f, -bitmap.height / 2f)
            canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
            drawAnnotations(canvas, sourcePage, bitmap.width, bitmap.height, annotations)
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
}
