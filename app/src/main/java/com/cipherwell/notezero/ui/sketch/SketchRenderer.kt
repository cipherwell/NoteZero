package com.cipherwell.notezero.ui.sketch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.cipherwell.notezero.data.AttachmentLimits
import com.cipherwell.notezero.model.SketchDocument
import com.cipherwell.notezero.model.SketchInkKind
import com.cipherwell.notezero.model.SketchPoint
import com.cipherwell.notezero.model.SketchShape
import com.cipherwell.notezero.model.SketchShapeKind
import com.cipherwell.notezero.model.SketchStroke
import com.cipherwell.notezero.model.SketchTemplate
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders a [SketchDocument] to a flattened PNG entirely in memory.
 *
 * This is the only Android-aware part of the studio preview path. Security: no `Context`, no
 * file, no cache directory and no logging — the bitmap and the encoded bytes live only in the
 * heap and the caller hands the array straight to [com.cipherwell.notezero.data.NoteRepository.importAttachment],
 * which encrypts it into the vault. The vector document stays the source of truth; the PNG is
 * only the shelf preview.
 *
 * Bounds: the A4 page's long edge never exceeds [MAX_DIMENSION_PX] (a 1600 px page is
 * 1131x1600x4 ≈ 7 MiB of ARGB pixels), and if a dense page still encodes above the 10 MiB
 * attachment cap the renderer halves the resolution instead of handing the pipeline an
 * oversized blob. All drawing is driven by the already-bounded [SketchDocument], so hostile
 * input can only produce a bounded page.
 */
object SketchRenderer {

    /** Longest page edge in pixels; a preview can never allocate a larger bitmap. */
    const val MAX_DIMENSION_PX: Int = 1600

    /** Fallback floor tried before giving up on an unencodable page. */
    const val MIN_DIMENSION_PX: Int = 256

    /** PNG `compress` quality; PNG ignores the value but the platform requires 0..100. */
    private const val PNG_QUALITY = 100

    /** Pre-sized encode buffer: a line drawing page fits comfortably. */
    private const val INITIAL_BUFFER_BYTES = 96 * 1024

    /** Page pixel dimensions for a requested long (height) edge. */
    data class PageSize(val widthPx: Int, val heightPx: Int)

    /**
     * Page size for a requested long edge, clamped into the renderer bound. A4 portrait is
     * `width / height = SketchDocument.PAGE_ASPECT_RATIO`.
     */
    fun pageSize(maxDimensionPx: Int = MAX_DIMENSION_PX): PageSize {
        val height = maxDimensionPx.coerceIn(1, MAX_DIMENSION_PX)
        val width = (height * SketchDocument.PAGE_ASPECT_RATIO).roundToInt().coerceAtLeast(1)
        return PageSize(widthPx = width, heightPx = height)
    }

    /**
     * Renders [document] to PNG bytes within the attachment cap, or null when the document has
     * no content or no attempt could be encoded. Never throws.
     */
    fun renderPng(
        document: SketchDocument,
        maxDimensionPx: Int = MAX_DIMENSION_PX,
    ): ByteArray? {
        if (document.isEmpty) return null
        var height = maxDimensionPx.coerceIn(1, MAX_DIMENSION_PX)
        while (true) {
            val bytes = renderAt(document, height) ?: return null
            if (bytes.size <= AttachmentLimits.MAX_ATTACHMENT_BYTES) return bytes
            if (height <= MIN_DIMENSION_PX) return null
            height = (height / 2).coerceAtLeast(MIN_DIMENSION_PX)
        }
    }

    /** One encode attempt at a page whose long edge is [longEdgePx]. Never throws. */
    private fun renderAt(document: SketchDocument, longEdgePx: Int): ByteArray? {
        val page = pageSize(longEdgePx)
        val bitmap = try {
            Bitmap.createBitmap(page.widthPx, page.heightPx, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            return null
        }
        return try {
            val canvas = Canvas(bitmap)
            paintPage(canvas, document, page)
            ByteArrayOutputStream(INITIAL_BUFFER_BYTES).use { out ->
                if (bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) {
                    out.toByteArray()
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            // The plaintext page must not linger on the heap surface longer than the encode.
            bitmap.recycle()
        }
    }

    /** Draws the paper, the template and the whole document in a deterministic paint order. */
    private fun paintPage(canvas: Canvas, document: SketchDocument, page: PageSize) {
        canvas.drawColor(SketchPalette.paperArgb)
        paintTemplate(canvas, document.template, page)
        val minEdgePx = min(page.widthPx, page.heightPx).toFloat()
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        document.strokes.forEach { stroke -> paintStroke(canvas, stroke, page, minEdgePx, strokePaint) }
        document.shapes.forEach { shape -> paintShape(canvas, shape, page, minEdgePx, strokePaint) }
    }

    /** Ruled lines, grid lines or dotted lattice in the low-contrast paper tokens. */
    private fun paintTemplate(canvas: Canvas, template: SketchTemplate, page: PageSize) {
        if (template == SketchTemplate.BLANK) return
        val width = page.widthPx.toFloat()
        val height = page.heightPx.toFloat()
        val lineWidth = max(1f, min(width, height) * LINE_WIDTH_FRACTION)
        when (template) {
            SketchTemplate.BLANK -> Unit

            SketchTemplate.RULED -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = SketchPalette.templateLineArgb
                    strokeWidth = lineWidth
                    style = Paint.Style.STROKE
                }
                val step = height / SketchGeometry.RULED_LINES
                var y = step
                while (y < height) {
                    canvas.drawLine(0f, y, width, y, paint)
                    y += step
                }
            }

            SketchTemplate.GRID -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = SketchPalette.templateLineArgb
                    strokeWidth = lineWidth
                    style = Paint.Style.STROKE
                }
                val stepX = width / SketchGeometry.GRID_COLUMNS
                val stepY = height / SketchGeometry.GRID_ROWS
                var x = stepX
                while (x < width) {
                    canvas.drawLine(x, 0f, x, height, paint)
                    x += stepX
                }
                var y = stepY
                while (y < height) {
                    canvas.drawLine(0f, y, width, y, paint)
                    y += stepY
                }
            }

            SketchTemplate.DOTTED -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = SketchPalette.templateDotArgb
                    style = Paint.Style.FILL
                }
                val radius = max(1f, min(width, height) * DOT_RADIUS_FRACTION)
                val stepX = width / SketchGeometry.DOTTED_COLUMNS
                val stepY = height / SketchGeometry.DOTTED_ROWS
                var column = 0
                while (column <= SketchGeometry.DOTTED_COLUMNS) {
                    var row = 0
                    while (row <= SketchGeometry.DOTTED_ROWS) {
                        canvas.drawCircle(
                            column * stepX,
                            row * stepY,
                            radius,
                            paint,
                        )
                        row += 1
                    }
                    column += 1
                }
            }
        }
    }

    /** One freehand stroke: a highlighter is translucent + wider, a tap is a dot. */
    private fun paintStroke(
        canvas: Canvas,
        stroke: SketchStroke,
        page: PageSize,
        minEdgePx: Float,
        paint: Paint,
    ) {
        if (stroke.points.isEmpty()) return
        paint.color = SketchPalette.inkArgb(stroke.ink, stroke.kind)
        paint.strokeWidth = SketchPalette.strokeWidthPx(stroke.width, stroke.kind, minEdgePx)
        val points = stroke.points.map { it.toPixels(page) }
        if (SketchEditorState.isDot(stroke) || points.size < 2) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(points.first().x, points.first().y, paint.strokeWidth / 2f, paint)
            return
        }
        paint.style = Paint.Style.STROKE
        val path = Path()
        path.moveTo(points.first().x, points.first().y)
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            path.quadTo(
                previous.x,
                previous.y,
                (previous.x + current.x) / 2f,
                (previous.y + current.y) / 2f,
            )
        }
        val last = points.last()
        path.lineTo(last.x, last.y)
        canvas.drawPath(path, paint)
    }

    /** One geometric shape in the selected ink/width, with an arrow-head for arrows. */
    private fun paintShape(
        canvas: Canvas,
        shape: SketchShape,
        page: PageSize,
        minEdgePx: Float,
        paint: Paint,
    ) {
        paint.color = SketchPalette.inkArgb(shape.ink)
        paint.strokeWidth = SketchPalette.strokeWidthPx(shape.width, minEdgePx)
        paint.style = Paint.Style.STROKE
        val start = shape.start
        val end = shape.end
        when (shape.kind) {
            SketchShapeKind.LINE -> canvas.drawLine(
                start.x * page.widthPx,
                start.y * page.heightPx,
                end.x * page.widthPx,
                end.y * page.heightPx,
                paint,
            )

            SketchShapeKind.RECT -> canvas.drawRect(shapeRect(shape, page), paint)

            SketchShapeKind.ELLIPSE -> canvas.drawOval(shapeRect(shape, page), paint)

            SketchShapeKind.ARROW -> {
                val startPx = start.toPixels(page)
                val endPx = end.toPixels(page)
                canvas.drawLine(startPx.x, startPx.y, endPx.x, endPx.y, paint)
                val head = SketchGeometry.arrowHead(start, end)
                head.forEach { point ->
                    val pointPx = point.toPixels(page)
                    canvas.drawLine(endPx.x, endPx.y, pointPx.x, pointPx.y, paint)
                }
            }
        }
    }

    private fun shapeRect(shape: SketchShape, page: PageSize): RectF {
        val left = min(shape.start.x, shape.end.x) * page.widthPx
        val right = max(shape.start.x, shape.end.x) * page.widthPx
        val top = min(shape.start.y, shape.end.y) * page.heightPx
        val bottom = max(shape.start.y, shape.end.y) * page.heightPx
        return RectF(left, top, right, bottom)
    }

    private fun SketchPoint.toPixels(page: PageSize): android.graphics.PointF =
        android.graphics.PointF(x * page.widthPx, y * page.heightPx)

    /** Template line width as a fraction of the page short edge (1 px minimum). */
    private const val LINE_WIDTH_FRACTION = 0.0012f

    /** Dotted-template dot radius as a fraction of the page short edge. */
    private const val DOT_RADIUS_FRACTION = 0.0024f

    /** Marker for the preview attachment MIME type. */
    const val MIME_TYPE: String = "image/png"

    /** Display-name prefix of the one preview attachment a sketch may own. */
    const val PREVIEW_NAME_PREFIX: String = "sketch-"

    /** True when [name] looks like a studio preview file name. */
    fun isPreviewName(name: String): Boolean =
        name.startsWith(PREVIEW_NAME_PREFIX) && name.endsWith(".png")
}
