package com.cipherwell.notezero.ui.sketch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.model.SketchInk
import com.cipherwell.notezero.model.SketchInkKind
import com.cipherwell.notezero.model.SketchPoint
import com.cipherwell.notezero.model.SketchShape
import com.cipherwell.notezero.model.SketchShapeKind
import com.cipherwell.notezero.model.SketchStroke
import com.cipherwell.notezero.model.SketchTemplate
import com.cipherwell.notezero.model.SketchWidth
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Compose drawing for the studio page: paper template, freehand ink (pen/highlighter), geometric
 * shapes and the in-progress eraser/lasso guides.
 *
 * The maths mirrors [SketchRenderer] (same template counts from [SketchGeometry], same width
 * fractions from [SketchPalette], same midpoint-quadratic stroke smoothing), so the page the
 * user draws on and the flattened PNG preview stay visually consistent. The renderer is the
 * portable truth; this file exists only so the on-screen page is vector-crisp while drawing.
 * No `android.graphics` types and no logging here.
 */

/** Draws the ruled/grid/dotted paper behind the ink; blank draws nothing. */
internal fun DrawScope.drawSketchTemplate(template: SketchTemplate) {
    if (template == SketchTemplate.BLANK) return
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) return
    val lineWidth = max(1f, min(width, height) * TEMPLATE_LINE_FRACTION)
    when (template) {
        SketchTemplate.BLANK -> Unit

        SketchTemplate.RULED -> {
            val step = height / SketchGeometry.RULED_LINES
            var y = step
            while (y < height) {
                drawLine(
                    color = SketchPalette.templateLine,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = lineWidth,
                )
                y += step
            }
        }

        SketchTemplate.GRID -> {
            val stepX = width / SketchGeometry.GRID_COLUMNS
            val stepY = height / SketchGeometry.GRID_ROWS
            var x = stepX
            while (x < width) {
                drawLine(
                    color = SketchPalette.templateLine,
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = lineWidth,
                )
                x += stepX
            }
            var y = stepY
            while (y < height) {
                drawLine(
                    color = SketchPalette.templateLine,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = lineWidth,
                )
                y += stepY
            }
        }

        SketchTemplate.DOTTED -> {
            val radius = max(1f, min(width, height) * TEMPLATE_DOT_FRACTION)
            val stepX = width / SketchGeometry.DOTTED_COLUMNS
            val stepY = height / SketchGeometry.DOTTED_ROWS
            var column = 0
            while (column <= SketchGeometry.DOTTED_COLUMNS) {
                var row = 0
                while (row <= SketchGeometry.DOTTED_ROWS) {
                    drawCircle(
                        color = SketchPalette.templateDot,
                        radius = radius,
                        center = Offset(column * stepX, row * stepY),
                    )
                    row += 1
                }
                column += 1
            }
        }
    }
}

/** Draws one committed freehand stroke in paint order. */
internal fun DrawScope.drawSketchStroke(stroke: SketchStroke) {
    drawSketchStrokePoints(stroke.points, stroke.ink, stroke.width, stroke.kind)
}

/**
 * Draws a stroke from raw normalised samples (used for both committed strokes and the
 * in-progress draft under the finger). A tap renders as a round dot.
 */
internal fun DrawScope.drawSketchStrokePoints(
    points: List<SketchPoint>,
    ink: SketchInk,
    width: SketchWidth,
    kind: SketchInkKind,
) {
    if (points.isEmpty()) return
    val minEdge = min(size.width, size.height)
    val strokeWidth = SketchPalette.strokeWidthPx(width, kind, minEdge)
    val color = when (kind) {
        SketchInkKind.PEN -> SketchPalette.ink(ink)
        SketchInkKind.HIGHLIGHTER ->
            SketchPalette.ink(ink).copy(alpha = SketchPalette.HIGHLIGHTER_ALPHA)
    }
    val first = points.first()
    val start = Offset(first.x * size.width, first.y * size.height)
    if (points.size < 2 || points.all {
            hypot(it.x - first.x, it.y - first.y) <= SketchEditorState.DOT_RADIUS
        }
    ) {
        drawCircle(color = color, radius = strokeWidth / 2f, center = start)
        return
    }
    val path = Path()
    path.moveTo(start.x, start.y)
    for (index in 1 until points.size) {
        val previous = points[index - 1]
        val current = points[index]
        path.quadraticTo(
            previous.x * size.width,
            previous.y * size.height,
            (previous.x + current.x) / 2f * size.width,
            (previous.y + current.y) / 2f * size.height,
        )
    }
    val last = points.last()
    path.lineTo(last.x * size.width, last.y * size.height)
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** Draws one geometric shape (line/rect/ellipse/arrow). */
internal fun DrawScope.drawSketchShape(shape: SketchShape) {
    drawSketchShapeDraft(shape.kind, shape.start, shape.end, shape.ink, shape.width)
}

/** Draws a shape from its two defining points (committed shape or in-progress drag). */
internal fun DrawScope.drawSketchShapeDraft(
    kind: SketchShapeKind,
    start: SketchPoint,
    end: SketchPoint,
    ink: SketchInk,
    width: SketchWidth,
) {
    val minEdge = min(size.width, size.height)
    val strokeWidth = SketchPalette.strokeWidthPx(width, minEdge)
    val color = SketchPalette.ink(ink)
    val a = Offset(start.x * size.width, start.y * size.height)
    val b = Offset(end.x * size.width, end.y * size.height)
    when (kind) {
        SketchShapeKind.LINE -> drawLine(
            color = color,
            start = a,
            end = b,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )

        SketchShapeKind.RECT -> drawRect(
            color = color,
            topLeft = Offset(min(a.x, b.x), min(a.y, b.y)),
            size = Size(abs(b.x - a.x), abs(b.y - a.y)),
            style = Stroke(width = strokeWidth),
        )

        SketchShapeKind.ELLIPSE -> drawOval(
            color = color,
            topLeft = Offset(min(a.x, b.x), min(a.y, b.y)),
            size = Size(abs(b.x - a.x), abs(b.y - a.y)),
            style = Stroke(width = strokeWidth),
        )

        SketchShapeKind.ARROW -> {
            drawLine(
                color = color,
                start = a,
                end = b,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            SketchGeometry.arrowHead(start, end).forEach { point ->
                drawLine(
                    color = color,
                    start = b,
                    end = Offset(point.x * size.width, point.y * size.height),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** Dashed guide for the eraser drag. */
internal fun DrawScope.drawSketchEraserGuide(points: List<SketchPoint>, color: Color) {
    drawSketchGuidePath(points, color, dash = true)
}

/** Closed-looking guide for the lasso drag (solid polyline, low contrast). */
internal fun DrawScope.drawSketchLassoGuide(points: List<SketchPoint>, color: Color) {
    drawSketchGuidePath(points, color, dash = false)
}

private fun DrawScope.drawSketchGuidePath(points: List<SketchPoint>, color: Color, dash: Boolean) {
    if (points.size < 2) return
    val path = Path()
    points.forEachIndexed { index, point ->
        val x = point.x * size.width
        val y = point.y * size.height
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val strokeWidth = max(1f, 1.5.dp.toPx())
    val effect = if (dash) {
        PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
    } else {
        null
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = effect,
        ),
    )
}

/** Paper template line width as a fraction of the page short edge (matches the renderer). */
private const val TEMPLATE_LINE_FRACTION = 0.0012f

/** Dotted-template dot radius as a fraction of the page short edge (matches the renderer). */
private const val TEMPLATE_DOT_FRACTION = 0.0024f
