package com.cipherwell.notezero.ui.sketch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherwell.notezero.R
import com.cipherwell.notezero.data.AttachmentImport
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.SketchDocument
import com.cipherwell.notezero.model.SketchPoint
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Autosave debounce for the encrypted vector (contract: >= 400 ms is fine). */
internal const val SKETCH_AUTOSAVE_DEBOUNCE_MS: Long = 500L

/** How long the "cleared" notice stays visible; the clear itself is undoable. */
private const val CLEAR_NOTICE_MS = 3_000L

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f
private const val ZOOM_STEP = 1.5f
private const val TITLE_MAX_CHARS = 512

/**
 * Standalone drawing studio for an encrypted `ItemKind.SKETCH_FILE` item.
 *
 * Editable state is the repository-owned encrypted [SketchDocument] vector (`item.sketch`); the
 * page is a fixed A4-portrait sheet with a blank/ruled/grid/dotted template. Pen, highlighter,
 * eraser, lasso and shapes (line/rect/ellipse/arrow) mutate the immutable document through
 * [SketchEditorState], which gives bounded undo/redo and drops input that would break the frozen
 * model bounds.
 *
 * Persistence: every committed mutation restarts a [SKETCH_AUTOSAVE_DEBOUNCE_MS] timer that calls
 * [NoteRepository.saveSketch]; [requestClose] flushes immediately (back/idle-safe) and only then
 * calls [onClose]. On exit the flattened PNG preview is rendered **in memory** by
 * [SketchRenderer] and imported through the normal encrypted attachment pipeline (one
 * `sketch-*.png` preview per sketch; the replaced one is deleted). Nothing is written to a
 * plaintext file, cache or log, and preview/vector failures are silent (preview) or a
 * non-secret status line (vector, only while unlocked).
 *
 * Input: one pointer draws/erases/lassoes; two pointers pinch-zoom and pan the page (bounded,
 * [MIN_ZOOM]..[MAX_ZOOM], never beyond the page edges at zoom 1). Zoom buttons and "fit page"
 * live on the page edge; all drawing coordinates stay normalised so the vector is
 * resolution-independent.
 */
@Composable
fun SketchStudioScreen(
    repository: NoteRepository,
    itemId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Long-edge bound for the flattened preview PNG (production: [SketchRenderer.MAX_DIMENSION_PX]).
     * Exposed as an optional parameter so Robolectric tests can exercise the same in-memory
     * render -> encrypted-import pipeline with a small bitmap instead of a 1600 px page.
     */
    previewMaxDimensionPx: Int = SketchRenderer.MAX_DIMENSION_PX,
) {
    val items by repository.items.collectAsStateWithLifecycle()
    val status by repository.status.collectAsStateWithLifecycle()
    val item = items.firstOrNull { it.id == itemId }
    val scope = rememberCoroutineScope()

    var editor by remember(itemId) { mutableStateOf(SketchEditorState()) }
    var loaded by remember(itemId) { mutableStateOf(false) }
    var revision by remember(itemId) { mutableIntStateOf(0) }
    var closing by remember(itemId) { mutableStateOf(false) }
    var saveFailed by remember(itemId) { mutableStateOf(false) }
    var clearNotice by remember(itemId) { mutableStateOf(false) }
    var clearDialogOpen by remember(itemId) { mutableStateOf(false) }
    var renameOpen by remember(itemId) { mutableStateOf(false) }
    var draftStroke by remember(itemId) { mutableStateOf<List<SketchPoint>>(emptyList()) }
    var draftEraser by remember(itemId) { mutableStateOf<List<SketchPoint>>(emptyList()) }
    var draftLasso by remember(itemId) { mutableStateOf<List<SketchPoint>>(emptyList()) }
    var draftShape by remember(itemId) { mutableStateOf<Pair<SketchPoint, SketchPoint>?>(null) }
    var zoom by remember(itemId) { mutableFloatStateOf(MIN_ZOOM) }
    var pan by remember(itemId) { mutableStateOf(Offset.Zero) }
    var pageSize by remember(itemId) { mutableStateOf(IntSize.Zero) }

    val document = editor.document

    // Load the encrypted vector exactly once, when the decrypted index exposes the item.
    LaunchedEffect(itemId, loaded, item?.id) {
        if (loaded || status != VaultStatus.UNLOCKED) return@LaunchedEffect
        val current = item ?: return@LaunchedEffect
        editor = editor.loaded(current.sketch ?: SketchDocument())
        loaded = true
    }

    // Debounced autosave of the encrypted vector after each committed mutation.
    LaunchedEffect(itemId, loaded, revision) {
        if (!loaded || revision == 0) return@LaunchedEffect
        // Debounce on a background scheduler: the timer must not compete with the recomposition
        // frame clock (the UI stays responsive and the save resumes on the composition scope).
        withContext(Dispatchers.Default) { delay(SKETCH_AUTOSAVE_DEBOUNCE_MS) }
        val snapshot = editor.document
        val saved = try {
            repository.saveSketch(itemId, snapshot.takeUnless { it.isEmpty })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        saveFailed = !saved && repository.status.value == VaultStatus.UNLOCKED
    }

    LaunchedEffect(clearNotice) {
        if (clearNotice) {
            delay(CLEAR_NOTICE_MS)
            clearNotice = false
        }
    }

    fun mutate(next: SketchEditorState) {
        if (next === editor) return
        editor = next
        revision += 1
    }

    /** Renders and imports the flattened preview; replaces (and deletes) the previous one. */
    suspend fun publishPreview(snapshot: SketchDocument) {
        val current = repository.items.value.firstOrNull { it.id == itemId } ?: return
        val previousPreviews = current.attachments.filter {
            it.mimeType == SketchRenderer.MIME_TYPE && SketchRenderer.isPreviewName(it.displayName)
        }
        if (snapshot.isEmpty) {
            previousPreviews.forEach { preview ->
                try {
                    repository.deleteAttachment(itemId, preview.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Preview-only cleanup: a failure here never affects the vector save.
                }
            }
            return
        }
        val png = withContext(Dispatchers.Default) {
            SketchRenderer.renderPng(snapshot, maxDimensionPx = previewMaxDimensionPx)
        } ?: return
        val imported = try {
            repository.importAttachment(
                noteId = itemId,
                displayName = SketchRenderer.PREVIEW_NAME_PREFIX +
                    System.currentTimeMillis() +
                    ".png",
                mimeType = SketchRenderer.MIME_TYPE,
                source = ByteArrayInputStream(png),
            )
        } finally {
            // The flattened page has been consumed by the import; do not keep it on the heap.
            png.fill(0)
        }
        if (imported !is AttachmentImport.Success) return
        previousPreviews.forEach { preview ->
            try {
                repository.deleteAttachment(itemId, preview.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Preview-only cleanup: the next flush retries with a fresh snapshot.
            }
        }
    }

    /** Flushes the vector (and preview) before the screen closes. */
    suspend fun flush() {
        val snapshot = editor.document
        try {
            repository.saveSketch(itemId, snapshot.takeUnless { it.isEmpty })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Silent: a locked vault fails closed and the vector stays in memory only.
        }
        try {
            publishPreview(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Preview failures are silent by contract; the vector is the source of truth.
        }
    }

    fun requestClose() {
        if (closing) return
        closing = true
        scope.launch {
            try {
                flush()
            } finally {
                onClose()
            }
        }
    }

    fun beginGesture(point: SketchPoint) {
        when (editor.tool) {
            SketchTool.PEN, SketchTool.HIGHLIGHTER -> draftStroke = listOf(point)

            SketchTool.ERASER -> draftEraser = listOf(point)
            SketchTool.LASSO -> draftLasso = listOf(point)
            SketchTool.SHAPE -> draftShape = point to point
        }
    }

    fun extendGesture(point: SketchPoint) {
        when (editor.tool) {
            SketchTool.PEN, SketchTool.HIGHLIGHTER ->
                if (draftStroke.isNotEmpty()) draftStroke = draftStroke + point

            SketchTool.ERASER -> if (draftEraser.isNotEmpty()) draftEraser = draftEraser + point
            SketchTool.LASSO -> if (draftLasso.isNotEmpty()) draftLasso = draftLasso + point
            SketchTool.SHAPE -> draftShape = draftShape?.let { (start, _) -> start to point }
        }
    }

    fun endGesture() {
        when (editor.tool) {
            SketchTool.PEN, SketchTool.HIGHLIGHTER -> {
                val points = draftStroke
                draftStroke = emptyList()
                mutate(editor.addStroke(points))
            }

            SketchTool.ERASER -> {
                val path = draftEraser
                draftEraser = emptyList()
                mutate(editor.erase(path))
            }

            SketchTool.LASSO -> {
                val polygon = draftLasso
                draftLasso = emptyList()
                mutate(editor.lasso(polygon))
            }

            SketchTool.SHAPE -> {
                val shape = draftShape
                draftShape = null
                if (shape != null) mutate(editor.addShape(shape.first, shape.second))
            }
        }
    }

    fun cancelGesture() {
        draftStroke = emptyList()
        draftEraser = emptyList()
        draftLasso = emptyList()
        draftShape = null
    }

    fun applyTransform(zoomChange: Float, panChange: Offset) {
        val nextZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val factor = nextZoom / zoom
        val nextPan = Offset(pan.x * factor + panChange.x, pan.y * factor + panChange.y)
        zoom = nextZoom
        pan = clampPan(nextPan, pageSize, nextZoom)
    }

    fun zoomTo(value: Float) {
        zoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
        pan = clampPan(pan, pageSize, zoom)
    }

    BackHandler { requestClose() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("sketch_studio"),
    ) {
        val current = item
        if (!loaded || current == null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .testTag("sketch_loading"),
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                SketchTopBar(
                    title = current.title.ifBlank {
                        stringResource(R.string.sketch_untitled)
                    },
                    onBack = { requestClose() },
                    onRename = { renameOpen = true },
                )
                if (saveFailed) {
                    SketchStatusLine(
                        text = stringResource(R.string.sketch_save_failed),
                        tone = SketchStatusTone.ERROR,
                    )
                }
                if (clearNotice) {
                    SketchStatusLine(
                        text = stringResource(R.string.sketch_cleared),
                        tone = SketchStatusTone.NOTICE,
                    )
                }
                SketchPageArea(
                    document = document,
                    editor = editor,
                    draftStroke = draftStroke,
                    draftEraser = draftEraser,
                    draftLasso = draftLasso,
                    draftShape = draftShape,
                    zoom = zoom,
                    pan = pan,
                    onPageSize = { pageSize = it },
                    onGestureStart = ::beginGesture,
                    onGestureMove = ::extendGesture,
                    onGestureEnd = ::endGesture,
                    onGestureCancel = ::cancelGesture,
                    onTransform = ::applyTransform,
                    onZoomIn = { zoomTo(zoom * ZOOM_STEP) },
                    onZoomOut = { zoomTo(zoom / ZOOM_STEP) },
                    onFit = {
                        zoomTo(MIN_ZOOM)
                        pan = Offset.Zero
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                SketchToolRow(
                    tool = editor.tool,
                    onToolSelected = { tool ->
                        cancelGesture()
                        editor = editor.withTool(tool)
                    },
                    onShapeSelected = { kind ->
                        editor = editor.withShapeKind(kind)
                    },
                )
                SketchInkRow(
                    ink = editor.ink,
                    width = editor.width,
                    onInk = { editor = editor.withInk(it) },
                    onWidth = { editor = editor.withWidth(it) },
                )
                SketchPageActionsRow(
                    template = document.template,
                    canUndo = editor.canUndo,
                    canRedo = editor.canRedo,
                    canClear = !document.isEmpty,
                    onTemplate = { template -> mutate(editor.setTemplate(template)) },
                    onUndo = { mutate(editor.undo()) },
                    onRedo = { mutate(editor.redo()) },
                    onClear = { clearDialogOpen = true },
                )
            }
        }

        if (renameOpen && current != null) {
            SketchRenameDialog(
                initialTitle = current.title,
                onSave = { title ->
                    renameOpen = false
                    scope.launch {
                        val fresh = repository.items.value.firstOrNull { it.id == itemId }
                            ?: return@launch
                        try {
                            repository.saveItem(
                                fresh.copy(title = title.take(TITLE_MAX_CHARS)),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Title-only change; keep the studio open on failure.
                        }
                    }
                },
                onDismiss = { renameOpen = false },
            )
        }

        if (clearDialogOpen) {
            SketchClearDialog(
                onConfirm = {
                    clearDialogOpen = false
                    val next = editor.clearContent()
                    if (next !== editor) {
                        mutate(next)
                        clearNotice = true
                    }
                },
                onDismiss = { clearDialogOpen = false },
            )
        }
    }
}

/**
 * The A4 portrait page: paper + template + ink, the in-progress gesture preview, the empty/lasso
 * hints and the floating zoom controls.
 *
 * Single-pointer gestures draw (pen/highlighter/eraser/lasso/shape); two pointers transform the
 * page with bounded pinch-zoom/pan. The transform is delegated to the caller so the values can be
 * clamped against the measured page size; coordinates handed to the callbacks are always
 * normalised 0..1 of the page, independent of zoom.
 */
@Composable
private fun SketchPageArea(
    document: SketchDocument,
    editor: SketchEditorState,
    draftStroke: List<SketchPoint>,
    draftEraser: List<SketchPoint>,
    draftLasso: List<SketchPoint>,
    draftShape: Pair<SketchPoint, SketchPoint>?,
    zoom: Float,
    pan: Offset,
    onPageSize: (IntSize) -> Unit,
    onGestureStart: (SketchPoint) -> Unit,
    onGestureMove: (SketchPoint) -> Unit,
    onGestureEnd: () -> Unit,
    onGestureCancel: () -> Unit,
    onTransform: (Float, Offset) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canvasLabel = stringResource(R.string.cd_drawing_canvas)
    val emptyHint = stringResource(R.string.sketch_empty_hint)
    val lassoHint = stringResource(R.string.sketch_lasso_hint)
    val accent = LocalNoteZeroColors.current.accentIcon
    val showEmptyHint = document.isEmpty && draftStroke.isEmpty() && draftShape == null

    BoxWithConstraints(
        modifier = modifier.padding(horizontal = NoteZeroSpacing.l),
        contentAlignment = Alignment.Center,
    ) {
        val pageWidth = minOf(maxWidth, maxHeight * SketchDocument.PAGE_ASPECT_RATIO)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            if (pageWidth > 0.dp) {
                Box(
                    modifier = Modifier
                        .size(pageWidth, pageWidth / SketchDocument.PAGE_ASPECT_RATIO)
                        .onSizeChanged(onPageSize)
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = pan.x
                            translationY = pan.y
                        }
                        .testTag("sketch_page")
                        .semantics { contentDescription = canvasLabel }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val bounds = size
                                onGestureStart(
                                    SketchGeometry.normalise(
                                        down.position.x,
                                        down.position.y,
                                        bounds.width,
                                        bounds.height,
                                    ),
                                )
                                var transforming = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size >= 2) {
                                        if (!transforming) {
                                            transforming = true
                                            onGestureCancel()
                                        }
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        if (zoomChange != 1f || panChange != Offset.Zero) {
                                            onTransform(zoomChange, panChange)
                                        }
                                        pressed.forEach { it.consume() }
                                    } else {
                                        if (transforming) {
                                            pressed.forEach { it.consume() }
                                            continue
                                        }
                                        val change = event.changes
                                            .firstOrNull { it.id == down.id }
                                            ?: event.changes.firstOrNull { it.pressed }
                                        if (change == null || !change.pressed) break
                                        val pageBounds = size
                                        onGestureMove(
                                            SketchGeometry.normalise(
                                                change.position.x,
                                                change.position.y,
                                                pageBounds.width,
                                                pageBounds.height,
                                            ),
                                        )
                                        change.consume()
                                    }
                                }
                                if (transforming) onGestureCancel() else onGestureEnd()
                            }
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(SketchPalette.paper)
                        drawSketchTemplate(document.template)
                        document.strokes.forEach { drawSketchStroke(it) }
                        document.shapes.forEach { drawSketchShape(it) }
                        if (draftStroke.isNotEmpty()) {
                            drawSketchStrokePoints(
                                points = draftStroke,
                                ink = editor.ink,
                                width = editor.width,
                                kind = editor.inkKind,
                            )
                        }
                        draftShape?.let { (start, end) ->
                            drawSketchShapeDraft(
                                kind = editor.shapeKind,
                                start = start,
                                end = end,
                                ink = editor.ink,
                                width = editor.width,
                            )
                        }
                        if (draftEraser.isNotEmpty()) {
                            drawSketchEraserGuide(draftEraser, accent)
                        }
                        if (draftLasso.isNotEmpty()) {
                            drawSketchLassoGuide(draftLasso, accent)
                        }
                    }
                    if (showEmptyHint) {
                        Text(
                            text = emptyHint,
                            style = NoteZeroType.Caption,
                            color = SketchPalette.templateDot,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(NoteZeroSpacing.l)
                                .testTag("sketch_empty_hint"),
                        )
                    }
                    if (editor.tool == SketchTool.LASSO) {
                        Text(
                            text = lassoHint,
                            style = NoteZeroType.Caption,
                            color = SketchPalette.templateDot,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(NoteZeroSpacing.m)
                                .testTag("sketch_lasso_hint"),
                        )
                    }
                }
            }
            SketchZoomOverlay(
                onZoomIn = onZoomIn,
                onZoomOut = onZoomOut,
                onFit = onFit,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(vertical = NoteZeroSpacing.s),
            )
        }
    }
}

/** Keeps the panned page inside its box: at zoom 1 the page cannot move at all. */
internal fun clampPan(pan: Offset, pageSize: IntSize, zoom: Float): Offset {
    if (pageSize.width <= 0 || pageSize.height <= 0) return Offset.Zero
    val maxX = pageSize.width * (zoom - 1f) / 2f
    val maxY = pageSize.height * (zoom - 1f) / 2f
    return Offset(
        x = pan.x.coerceIn(-maxX, maxX),
        y = pan.y.coerceIn(-maxY, maxY),
    )
}
