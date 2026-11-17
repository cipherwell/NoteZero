package com.cipherwell.notezero.ui.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfInkKind
import com.cipherwell.notezero.model.PdfInkStroke
import com.cipherwell.notezero.model.PdfNote
import com.cipherwell.notezero.model.PdfPoint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Drawing + gesture surface layered exactly over one rendered `pdf_page` Image.
 *
 * The overlay is present in both modes so stored annotations are always visible; only annotate
 * mode attaches the drawing gesture handler (in scroll mode a tap on a sticky-note marker still
 * reopens it, everything else passes through to the scrollable list).
 *
 * Coordinates are normalised to the displayed page bounds before they reach the editor, so the
 * same layer renders correctly on any bitmap size (phone, tablet, flattened A4 export — the
 * exporter uses the same maths on the bitmap dimensions).
 */
@Composable
internal fun PdfAnnotationOverlay(
    page: Int,
    annotations: PdfAnnotations,
    annotateMode: Boolean,
    tool: PdfAnnotationTool,
    colorIndex: Int,
    widthIndex: Int,
    onStroke: (Int, PdfInkKind, List<PdfPoint>) -> Unit,
    onHighlight: (Int, NormalisedRect) -> Unit,
    onErase: (Int, Float, Float, Float) -> Unit,
    onNewNote: (Int, Float, Float) -> Unit,
    onOpenNote: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnStroke by rememberUpdatedState(onStroke)
    val currentOnHighlight by rememberUpdatedState(onHighlight)
    val currentOnErase by rememberUpdatedState(onErase)
    val currentOnNewNote by rememberUpdatedState(onNewNote)
    val currentOnOpenNote by rememberUpdatedState(onOpenNote)

    val pageNotes = remember(annotations.notes, page) {
        annotations.notes.mapIndexedNotNull { index, note -> if (note.page == page) index to note else null }
    }
    val currentPageNotes by rememberUpdatedState(pageNotes)

    var livePoints by remember(page) { mutableStateOf<List<PdfPoint>>(emptyList()) }
    var liveRect by remember(page) { mutableStateOf<NormalisedRect?>(null) }

    val gestureModifier = Modifier.pointerInput(page, annotateMode, tool, colorIndex, widthIndex) {
        if (!annotateMode) {
            detectTapGestures { position ->
                hitNoteMarker(currentPageNotes, size, position)?.let(currentOnOpenNote)
            }
            return@pointerInput
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            var current = start
            val points = mutableListOf(start.toPdfPoint(size))
            var moved = false
            var finished = false
            while (!finished) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.pressed) {
                    current = change.position
                    if ((current - start).getDistance() > viewConfiguration.touchSlop) moved = true
                    when (tool) {
                        PdfAnnotationTool.INK,
                        PdfAnnotationTool.HIGHLIGHTER,
                        -> {
                            if (points.size < PdfAnnotations.MAX_POINTS_PER_STROKE) {
                                points += current.toPdfPoint(size)
                                livePoints = points.toList()
                            }
                        }

                        PdfAnnotationTool.RECT -> {
                            val from = start.toPdfPoint(size)
                            val to = current.toPdfPoint(size)
                            liveRect = normalisedRect(from.x, from.y, to.x, to.y)
                        }

                        PdfAnnotationTool.ERASER -> if (moved) {
                            val point = current.toPdfPoint(size)
                            currentOnErase(page, point.x, point.y, eraseTolerance(size))
                        }

                        PdfAnnotationTool.NOTE -> Unit
                    }
                    change.consume()
                } else {
                    change.consume()
                    finished = true
                }
            }
            // A tap (not a drag) on an existing sticky-note marker reopens it for every tool
            // except the eraser, which deliberately erases notes near the point instead.
            val markerHit = if (!moved && tool != PdfAnnotationTool.ERASER) {
                hitNoteMarker(currentPageNotes, size, start)
            } else {
                null
            }
            if (markerHit != null) {
                currentOnOpenNote(markerHit)
            } else {
                when (tool) {
                    PdfAnnotationTool.INK,
                    PdfAnnotationTool.HIGHLIGHTER,
                    -> {
                        val kind = tool.inkKind()
                        if (moved && kind != null && points.size >= PdfAnnotations.MIN_POINTS_PER_STROKE) {
                            currentOnStroke(page, kind, points.toList())
                        }
                    }

                    PdfAnnotationTool.RECT -> if (moved) {
                        liveRect?.let { currentOnHighlight(page, it) }
                    }

                    PdfAnnotationTool.ERASER -> if (!moved) {
                        val point = start.toPdfPoint(size)
                        currentOnErase(page, point.x, point.y, eraseTolerance(size))
                    }

                    PdfAnnotationTool.NOTE -> if (!moved) {
                        val point = start.toPdfPoint(size)
                        currentOnNewNote(page, point.x, point.y)
                    }
                }
            }
            livePoints = emptyList()
            liveRect = null
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("pdf_annotation_overlay")
            .then(gestureModifier),
    ) {
        drawAnnotationPage(
            page = page,
            annotations = annotations,
            pageNotes = pageNotes,
            livePoints = livePoints,
            liveRect = liveRect,
            liveKind = tool.inkKind(),
            liveColorIndex = colorIndex,
            liveWidthIndex = widthIndex,
            showLiveRect = tool == PdfAnnotationTool.RECT,
        )
    }
}

/** Draws the stored annotation layer of [page] plus the in-progress gesture preview. */
private fun DrawScope.drawAnnotationPage(
    page: Int,
    annotations: PdfAnnotations,
    pageNotes: List<Pair<Int, PdfNote>>,
    livePoints: List<PdfPoint>,
    liveRect: NormalisedRect?,
    liveKind: PdfInkKind?,
    liveColorIndex: Int,
    liveWidthIndex: Int,
    showLiveRect: Boolean,
) {
    val pageWidth = size.width
    val pageHeight = size.height
    if (pageWidth <= 0f || pageHeight <= 0f) return
    val minEdge = min(pageWidth, pageHeight)

    annotations.highlights.forEach { highlight ->
        if (highlight.page != page) return@forEach
        drawHighlightRect(
            rect = NormalisedRect(
                left = highlight.left,
                top = highlight.top,
                right = highlight.right,
                bottom = highlight.bottom,
            ),
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            minEdge = minEdge,
            colorIndex = highlight.colorIndex,
        )
    }

    annotations.strokes.forEach { stroke ->
        if (stroke.page != page || stroke.points.size < 2) return@forEach
        drawStroke(
            points = stroke.points,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            minEdge = minEdge,
            kind = stroke.kind,
            colorIndex = stroke.colorIndex,
            widthIndex = stroke.widthIndex,
        )
    }

    pageNotes.forEach { (_, note) ->
        drawNoteMarker(note, pageWidth, pageHeight, minEdge)
    }

    if (showLiveRect && liveRect != null) {
        drawHighlightRect(
            rect = liveRect,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            minEdge = minEdge,
            colorIndex = liveColorIndex,
        )
    }
    if (liveKind != null && livePoints.size >= 2) {
        drawStroke(
            points = livePoints,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            minEdge = minEdge,
            kind = liveKind,
            colorIndex = liveColorIndex,
            widthIndex = liveWidthIndex,
        )
    }
}

private fun DrawScope.drawHighlightRect(
    rect: NormalisedRect,
    pageWidth: Float,
    pageHeight: Float,
    minEdge: Float,
    colorIndex: Int,
) {
    val color = PdfAnnotationPalette.color(colorIndex)
    val topLeft = Offset(rect.left * pageWidth, rect.top * pageHeight)
    val rectSize = Size(
        (rect.right - rect.left) * pageWidth,
        (rect.bottom - rect.top) * pageHeight,
    )
    drawRect(
        color = color.copy(alpha = PdfAnnotationPalette.HIGHLIGHT_FILL_ALPHA),
        topLeft = topLeft,
        size = rectSize,
    )
    drawRect(
        color = color.copy(alpha = PdfAnnotationPalette.HIGHLIGHT_BORDER_ALPHA),
        topLeft = topLeft,
        size = rectSize,
        style = Stroke(width = PdfAnnotationPalette.highlightBorderWidthPx(minEdge)),
    )
}

private fun DrawScope.drawStroke(
    points: List<PdfPoint>,
    pageWidth: Float,
    pageHeight: Float,
    minEdge: Float,
    kind: PdfInkKind,
    colorIndex: Int,
    widthIndex: Int,
) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x * pageWidth, points[0].y * pageHeight)
        for (index in 1 until points.size) {
            lineTo(points[index].x * pageWidth, points[index].y * pageHeight)
        }
    }
    drawPath(
        path = path,
        color = PdfAnnotationPalette.strokeColor(kind, colorIndex),
        style = Stroke(
            width = PdfAnnotationPalette.strokeWidthPx(minEdge, kind, widthIndex),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private fun DrawScope.drawNoteMarker(note: PdfNote, pageWidth: Float, pageHeight: Float, minEdge: Float) {
    val center = Offset(note.x * pageWidth, note.y * pageHeight)
    val radius = PdfAnnotationPalette.markerRadiusPx(minEdge)
    drawCircle(color = PdfAnnotationPalette.noteMarkerFill, radius = radius, center = center)
    drawCircle(
        color = PdfAnnotationPalette.noteMarkerGlyph,
        radius = radius,
        center = center,
        style = Stroke(width = max(1f, radius * 0.22f)),
    )
    val halfLine = radius * 0.55f
    val lineGap = radius * 0.42f
    repeat(3) { index ->
        val y = center.y + (index - 1) * lineGap
        drawLine(
            color = PdfAnnotationPalette.noteMarkerGlyph,
            start = Offset(center.x - halfLine, y),
            end = Offset(center.x + halfLine, y),
            strokeWidth = max(1f, radius * 0.18f),
            cap = StrokeCap.Round,
        )
    }
}

/** Returns the index of the sticky note whose marker is nearest to [position], if any. */
private fun hitNoteMarker(
    pageNotes: List<Pair<Int, PdfNote>>,
    size: IntSize,
    position: Offset,
): Int? {
    val width = size.width
    val height = size.height
    if (width <= 0 || height <= 0) return null
    val minEdge = min(width, height).toFloat()
    val hitRadius = PdfAnnotationPalette.markerHitRadiusPx(minEdge)
    var bestIndex: Int? = null
    var bestDistance = Float.MAX_VALUE
    pageNotes.forEach { (index, note) ->
        val dx = note.x * width - position.x
        val dy = note.y * height - position.y
        val distance = hypot(dx, dy)
        if (distance <= hitRadius && distance < bestDistance) {
            bestIndex = index
            bestDistance = distance
        }
    }
    return bestIndex
}

private fun Offset.toPdfPoint(size: IntSize): PdfPoint {
    val width = size.width.coerceAtLeast(1).toFloat()
    val height = size.height.coerceAtLeast(1).toFloat()
    return PdfPoint(
        x = (x / width).coerceIn(0f, 1f),
        y = (y / height).coerceIn(0f, 1f),
    )
}

/** Eraser hit tolerance in normalised page units (a fingertip-sized radius). */
private fun eraseTolerance(size: IntSize): Float {
    val minEdge = min(size.width, size.height).toFloat().coerceAtLeast(1f)
    return (PdfAnnotationPalette.markerHitRadiusPx(minEdge) / minEdge)
        .coerceIn(PdfAnnotationEditor.MIN_ERASE_TOLERANCE, PdfAnnotationEditor.MAX_ERASE_TOLERANCE)
}
