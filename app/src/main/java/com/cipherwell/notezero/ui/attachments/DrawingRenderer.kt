package com.cipherwell.notezero.ui.attachments

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.toArgb
import com.cipherwell.notezero.data.AttachmentLimits
import com.cipherwell.notezero.ui.theme.NoteZeroPalette
import java.io.ByteArrayOutputStream

/**
 * Renders a [DrawingSketch] to an in-memory PNG.
 *
 * This is the only Android-aware part of the drawing SAVE path, mirroring how
 * `AttachmentSourceResolver` is the only Android-aware part of the attachment INPUT path.
 *
 * Security: the bitmap and the encoded bytes exist only in memory — no `Context`, no file,
 * no cache directory and no logging. The caller hands the bytes straight to
 * [AttachmentController.importDrawing], which encrypts them through the repository and wipes
 * the array once the import has consumed it.
 *
 * Size: the page is bounded by [DrawingFormat.MAX_DIMENSION_PX], and the encoded PNG is
 * checked against the attachment limit ([AttachmentLimits.MAX_ATTACHMENT_BYTES], 10 MiB). A
 * drawing that would not fit (dense full-page ink) is re-rendered at half the resolution
 * instead of failing to save; below [MIN_DIMENSION_PX] the renderer gives up and the UI
 * shows the non-secret save error. This is what keeps the drawing path inside the same
 * per-file cap as every other attachment.
 */
object DrawingRenderer {

    /** PNG `compress` quality; PNG ignores it but the platform requires 0..100. */
    private const val PNG_QUALITY = 100

    /** Pre-sized encode buffer: a line drawing page fits comfortably. */
    private const val INITIAL_BUFFER_BYTES = 64 * 1024

    /** Smallest fallback page edge tried before giving up. */
    const val MIN_DIMENSION_PX = 256

    /**
     * Renders [sketch] on a white page at [maxDimensionPx] (clamped by
     * [DrawingFormat.pixelSize]) and returns PNG bytes within the attachment limit, or null
     * when there is nothing to draw or the page cannot be encoded under the limit. Never
     * throws.
     */
    fun renderPng(
        sketch: DrawingSketch,
        maxDimensionPx: Int = DrawingFormat.MAX_DIMENSION_PX,
    ): ByteArray? {
        if (!sketch.hasContent) return null
        var dimension = DrawingFormat.pixelSize(maxDimensionPx).heightPx
        while (true) {
            val bytes = renderAt(sketch, dimension) ?: return null
            if (bytes.size <= AttachmentLimits.MAX_ATTACHMENT_BYTES) return bytes
            if (dimension <= MIN_DIMENSION_PX) return null
            dimension = (dimension / 2).coerceAtLeast(MIN_DIMENSION_PX)
        }
    }

    /** One encode attempt at a page whose long edge is [longEdgePx]. */
    private fun renderAt(sketch: DrawingSketch, longEdgePx: Int): ByteArray? {
        val size = DrawingFormat.pixelSize(longEdgePx)
        val bitmap = try {
            Bitmap.createBitmap(size.widthPx, size.heightPx, Bitmap.Config.ARGB_8888)
        } catch (error: Exception) {
            return null
        }
        return try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(NoteZeroPalette.SurfaceLight.toInt())
            val minEdgePx = minOf(size.widthPx, size.heightPx).toFloat()
            sketch.visibleStrokes.forEach { stroke -> drawStroke(canvas, stroke, size, minEdgePx) }
            ByteArrayOutputStream(INITIAL_BUFFER_BYTES).use { out ->
                if (bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) {
                    out.toByteArray()
                } else {
                    null
                }
            }
        } catch (error: Exception) {
            null
        } finally {
            // The plaintext page must not linger in the bitmap cache/heap surface.
            bitmap.recycle()
        }
    }

    /**
     * Draws one stroke with the midpoint-quadratic smoothing used by the preview, so the
     * saved image matches the on-screen ink. A one-sample stroke is a dot.
     */
    private fun drawStroke(
        canvas: Canvas,
        stroke: DrawingStroke,
        size: DrawingSize,
        minEdgePx: Float,
    ) {
        if (stroke.points.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DrawingPalette.ink(stroke.ink).toArgb()
            strokeWidth = DrawingPalette.strokeWidthPx(stroke.width, minEdgePx)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val widthPx = size.widthPx.toFloat()
        val heightPx = size.heightPx.toFloat()
        val first = stroke.points.first()

        if (stroke.points.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(first.x * widthPx, first.y * heightPx, paint.strokeWidth / 2f, paint)
            return
        }

        paint.style = Paint.Style.STROKE
        val path = Path()
        path.moveTo(first.x * widthPx, first.y * heightPx)
        for (index in 1 until stroke.points.size) {
            val previous = stroke.points[index - 1]
            val current = stroke.points[index]
            path.quadTo(
                previous.x * widthPx,
                previous.y * heightPx,
                (previous.x + current.x) / 2f * widthPx,
                (previous.y + current.y) / 2f * heightPx,
            )
        }
        val last = stroke.points.last()
        path.lineTo(last.x * widthPx, last.y * heightPx)
        canvas.drawPath(path, paint)
    }
}
