package com.cipherwell.notezero.ui.attachments

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cipherwell.notezero.R
import com.cipherwell.notezero.ui.components.common.NzTextButton
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroPalette
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen freehand drawing surface, opened from the attachment add menu.
 *
 * Input: raw pointer samples (finger or stylus — Compose delivers both as pointer events)
 * go into the immutable [DrawingSketch]; the same model drives the on-screen preview and
 * the PNG render, so what is saved matches what was drawn. Undo/clear only touch that model.
 *
 * Save: [DrawingRenderer.renderPng] encodes a white page in memory (bounded to
 * [DrawingFormat.MAX_DIMENSION_PX] and to the 10 MiB attachment cap) and [onSave] hands the
 * bytes to `AttachmentController.importDrawing`, which encrypts them. Nothing is written to
 * disk here, no file/cache is created and nothing is logged; the rendered array is wiped by
 * the import as soon as it has been read. Encoding runs off the main thread on the
 * composition scope, so cancelling the canvas while a save is in flight also cancels the
 * render and nothing is imported.
 *
 * Accessibility: every control is at least 48dp, colour and width controls carry a content
 * description and expose the `selected` state, and the page itself is described as the
 * drawing canvas.
 */
@Composable
fun DrawingCanvasScreen(
    onCancel: () -> Unit,
    onSave: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sketch by remember { mutableStateOf(DrawingSketch()) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canvasLabel = stringResource(R.string.cd_drawing_canvas)

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            // The dialog content applies WindowInsets.safeDrawing itself; letting the window
            // also fit system windows double-insets and clips the tool rows on tall devices
            // with a larger font scale (device QA: buttons were pushed off-screen).
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .testTag("drawing_dialog"),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState()),
            ) {
                DrawingHeader(
                    canSave = sketch.hasContent && !saving,
                    onCancel = onCancel,
                    onSave = {
                        if (!saving && sketch.hasContent) {
                            saving = true
                            failed = false
                            // Snapshot: encoding runs off the main thread on a value copy.
                            val snapshot = sketch
                            scope.launch {
                                val png = withContext(Dispatchers.Default) {
                                    DrawingRenderer.renderPng(snapshot)
                                }
                                saving = false
                                if (png == null || png.isEmpty()) {
                                    failed = true
                                } else {
                                    onSave(png)
                                }
                            }
                        }
                    },
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.s)
                        .aspectRatio(DrawingFormat.aspectRatio)
                        .testTag("drawing_paper"),
                    shape = NoteZeroShapes.field,
                    color = DrawingPalette.paper,
                ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("drawing_canvas")
                                    .semantics { contentDescription = canvasLabel }
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            sketch = sketch.start(
                                                down.position.toDrawingPoint(size),
                                            )
                                            down.consume()
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes
                                                    .firstOrNull { it.id == down.id }
                                                    ?: break
                                                if (!change.pressed) break
                                                sketch = sketch.extend(
                                                    change.position.toDrawingPoint(size),
                                                )
                                                change.consume()
                                            }
                                            sketch = sketch.finish()
                                        }
                                    },
                            ) {
                                drawRect(DrawingPalette.paper)
                                sketch.visibleStrokes.forEach { stroke -> drawInkStroke(stroke) }
                            }

                            if (!sketch.hasContent) {
                                Text(
                                    text = stringResource(R.string.drawing_canvas_hint),
                                    style = NoteZeroType.Caption,
                                    color = onPaperSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        }
                }

                if (failed) {
                    Text(
                        text = stringResource(R.string.drawing_save_failed),
                        style = NoteZeroType.Caption,
                        color = LocalNoteZeroColors.current.dangerText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NoteZeroSpacing.l)
                            .testTag("drawing_save_error"),
                    )
                }

                DrawingTools(
                    sketch = sketch,
                    onInk = { sketch = sketch.withInk(it) },
                    onWidth = { sketch = sketch.withWidth(it) },
                    onUndo = { sketch = sketch.undo() },
                    onClear = { sketch = sketch.clear() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DrawingHeader(
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NoteZeroSpacing.s, vertical = NoteZeroSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NzTextButton(
            text = stringResource(R.string.common_cancel),
            onClick = onCancel,
            modifier = Modifier.testTag("drawing_cancel"),
        )
        Text(
            text = stringResource(R.string.attach_drawing),
            style = NoteZeroType.Heading,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        NzTextButton(
            text = stringResource(R.string.drawing_save),
            onClick = onSave,
            enabled = canSave,
            modifier = Modifier.testTag("drawing_save"),
        )
    }
}

@Composable
private fun DrawingTools(
    sketch: DrawingSketch,
    onInk: (DrawingInk) -> Unit,
    onWidth: (DrawingWidth) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.s),
    ) {
        // Seven 48dp swatches do not fit every phone width: the ink row scrolls, the
        // selection stays visible because the active swatch is always within the first ones.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrawingInk.entries.forEach { ink ->
                InkSwatch(
                    ink = ink,
                    selected = sketch.ink == ink,
                    onClick = { onInk(ink) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrawingWidth.entries.forEach { width ->
                WidthSwatch(
                    width = width,
                    selected = sketch.width == width,
                    onClick = { onWidth(width) },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            NzTextButton(
                text = stringResource(R.string.common_undo),
                onClick = onUndo,
                enabled = sketch.hasContent,
                modifier = Modifier.testTag("drawing_undo"),
            )
            NzTextButton(
                text = stringResource(R.string.drawing_clear),
                onClick = onClear,
                enabled = sketch.hasContent,
                modifier = Modifier.testTag("drawing_clear"),
            )
        }
    }
}

/** One ink choice: a 48dp target wrapping the colour dot, with a check glyph when selected. */
@Composable
private fun InkSwatch(
    ink: DrawingInk,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(inkDescription(ink))
    val colors = LocalNoteZeroColors.current
    Box(
        modifier = Modifier
            .size(NoteZeroSpacing.minTarget)
            .clip(NoteZeroShapes.pill)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(inkTag(ink)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(NoteZeroShapes.pill)
                .background(DrawingPalette.ink(ink))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.accentIcon else colors.fieldBorder,
                    shape = NoteZeroShapes.pill,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text(text = "✓", style = NoteZeroType.Caption, color = DrawingPalette.paper)
            }
        }
    }
}

/** One pen width: a dot whose size previews the stroke, 48dp target. */
@Composable
private fun WidthSwatch(
    width: DrawingWidth,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(widthDescription(width))
    val colors = LocalNoteZeroColors.current
    Box(
        modifier = Modifier
            .size(NoteZeroSpacing.minTarget)
            .clip(NoteZeroShapes.pill)
            .background(if (selected) colors.pillSelected else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(widthTag(width)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(dotSize(width))
                .clip(NoteZeroShapes.pill)
                .background(MaterialTheme.colorScheme.onSurface),
        )
    }
}

/** Preview and PNG share this: one stroke with midpoint-quadratic smoothing. */
private fun DrawScope.drawInkStroke(stroke: DrawingStroke) {
    if (stroke.points.isEmpty()) return
    val minEdgePx = minOf(size.width, size.height)
    val strokeWidthPx = DrawingPalette.strokeWidthPx(stroke.width, minEdgePx)
    val color = DrawingPalette.ink(stroke.ink)
    val first = stroke.points.first()
    val start = Offset(first.x * size.width, first.y * size.height)

    if (stroke.points.size == 1) {
        drawCircle(color = color, radius = strokeWidthPx / 2f, center = start)
        return
    }

    val path = Path()
    path.moveTo(start.x, start.y)
    for (index in 1 until stroke.points.size) {
        val previous = stroke.points[index - 1]
        val current = stroke.points[index]
        path.quadraticBezierTo(
            previous.x * size.width,
            previous.y * size.height,
            (previous.x + current.x) / 2f * size.width,
            (previous.y + current.y) / 2f * size.height,
        )
    }
    val last = stroke.points.last()
    path.lineTo(last.x * size.width, last.y * size.height)
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** Canvas size is known to the pointer coroutine, not to composition. */
private fun Offset.toDrawingPoint(canvasSize: IntSize): DrawingPoint {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return DrawingPoint(0f, 0f)
    return DrawingPoint(x / canvasSize.width, y / canvasSize.height)
}

/** Hint text sits on the white page in both themes, so it uses the light-ground token. */
private val onPaperSecondary: Color = Color(NoteZeroPalette.TextSecondaryLight)

@StringRes
private fun inkDescription(ink: DrawingInk): Int = when (ink) {
    DrawingInk.BLACK -> R.string.cd_drawing_ink_black
    DrawingInk.GREY -> R.string.cd_drawing_ink_grey
    DrawingInk.RED -> R.string.cd_drawing_ink_red
    DrawingInk.ORANGE -> R.string.cd_drawing_ink_orange
    DrawingInk.GREEN -> R.string.cd_drawing_ink_green
    DrawingInk.BLUE -> R.string.cd_drawing_ink_blue
    DrawingInk.PURPLE -> R.string.cd_drawing_ink_purple
}

@StringRes
private fun widthDescription(width: DrawingWidth): Int = when (width) {
    DrawingWidth.THIN -> R.string.cd_drawing_width_thin
    DrawingWidth.MEDIUM -> R.string.cd_drawing_width_medium
    DrawingWidth.BOLD -> R.string.cd_drawing_width_bold
}

private fun inkTag(ink: DrawingInk): String = "drawing_ink_" + ink.name.lowercase()

private fun widthTag(width: DrawingWidth): String = "drawing_width_" + width.name.lowercase()

private fun dotSize(width: DrawingWidth) = when (width) {
    DrawingWidth.THIN -> 8.dp
    DrawingWidth.MEDIUM -> 15.dp
    DrawingWidth.BOLD -> 24.dp
}
