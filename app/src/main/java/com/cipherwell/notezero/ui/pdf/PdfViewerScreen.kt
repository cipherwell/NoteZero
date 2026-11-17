package com.cipherwell.notezero.ui.pdf

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherwell.notezero.R
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfInkKind
import com.cipherwell.notezero.model.PdfPoint
import com.cipherwell.notezero.model.ReadingBookmark
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.ui.attachments.AttachmentController
import com.cipherwell.notezero.ui.components.common.NzIconButton
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which plaintext egress the user confirmed in the export-choice dialog. */
private enum class PdfExportKind { ORIGINAL, ANNOTATED }

/**
 * Annotation callbacks of one rendered page, grouped so the page composable stays readable.
 * Recreated on recomposition; the overlay reads the latest instance through `rememberUpdatedState`.
 */
private class PdfAnnotationActions(
    val onStroke: (Int, PdfInkKind, List<PdfPoint>) -> Unit,
    val onHighlight: (Int, NormalisedRect) -> Unit,
    val onErase: (Int, Float, Float, Float) -> Unit,
    val onNewNote: (Int, Float, Float) -> Unit,
    val onOpenNote: (Int) -> Unit,
)

/**
 * Full-screen viewer for an encrypted PDF item.
 *
 * The PDF bytes are decrypted from the vault, rasterised to in-memory bitmaps and shown on a
 * paged list; nothing is cached and no file survives viewing (see [AndroidPdfPageRenderer] for
 * the bounded ephemeral-render-file trade-off). Annotations are edited as a normalised layer
 * (never touching the original PDF) and persisted encrypted through
 * [NoteRepository.savePdfAnnotations]; only an explicit export turns them into plaintext.
 *
 * "Export decrypted copy" is the only plaintext egress and goes through the same SAF
 * `CreateDocument` + explicit-warning flow as attachment export, bracketed by
 * [AttachmentController] so the picker does not lock the vault mid-export. When annotations
 * exist the user first chooses the untouched original or a flattened (rasterised) copy; the
 * flattened copy is rendered in memory by [PdfFlattenedExporter] and written through
 * [PdfExportGateway], with a final vault-unlocked re-check before any byte is written.
 *
 * A vault lock replaces this screen with the unlock UI at the shell level; bitmaps are
 * recycled as soon as the composition leaves.
 */
@Composable
fun PdfViewerScreen(
    repository: NoteRepository,
    itemId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    renderer: PdfPageRenderer = remember { AndroidPdfPageRenderer() },
    flattenedExporter: PdfFlattenedExporter = remember { AndroidPdfFlattenedExporter() },
    exportGateway: PdfExportGateway = rememberSafPdfExportGateway(),
    exportDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val context = LocalContext.current
    val items by repository.items.collectAsStateWithLifecycle()
    val item = items.firstOrNull { it.id == itemId }
    val attachment = item?.attachments?.firstOrNull { it.mimeType == BlankPdf.MIME_TYPE }
        ?: item?.attachments?.firstOrNull()

    val controller = remember(repository) { AttachmentController(repository) }
    var loading by remember(itemId) { mutableStateOf(true) }
    var pages by remember(itemId) { mutableStateOf(PdfPages(emptyList(), 0)) }
    var failed by remember(itemId) { mutableStateOf(false) }
    var notice by remember(itemId) { mutableStateOf<String?>(null) }
    var showExportChoice by remember(itemId) { mutableStateOf(false) }
    var pendingExport by remember(itemId) { mutableStateOf<PdfExportKind?>(null) }

    val editor = remember(itemId) { PdfAnnotationEditor() }
    var annotationsLoaded by remember(itemId) { mutableStateOf(false) }
    var annotateMode by remember(itemId) { mutableStateOf(false) }
    var tool by remember(itemId) { mutableStateOf(PdfAnnotationTool.INK) }
    var colorIndex by remember(itemId) { mutableStateOf(0) }
    var widthIndex by remember(itemId) { mutableStateOf(1) }
    var noteDraft by remember(itemId) { mutableStateOf<PdfNoteDraft?>(null) }

    val readingState = item?.readingState
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (readingState?.position ?: 0).coerceAtLeast(0),
    )
    val scope = rememberCoroutineScope()
    val currentPage = listState.firstVisibleItemIndex
    var showBookmarks by remember(itemId) { mutableStateOf(false) }
    var showAddBookmark by remember(itemId) { mutableStateOf(false) }
    var bookmarkLabel by remember(itemId) { mutableStateOf("") }
    var showPageJump by remember(itemId) { mutableStateOf(false) }
    var pageInput by remember(itemId) { mutableStateOf("") }
    var pageError by remember(itemId) { mutableStateOf<String?>(null) }
    val pageInvalidNotice = stringResource(R.string.pdf_page_invalid, pages.totalPages.coerceAtLeast(1))
    val defaultBookmarkLabel = stringResource(
        R.string.pdf_bookmark_default,
        (currentPage + 1).coerceAtMost(pages.totalPages.coerceAtLeast(1)),
    )
    val bookmarkAddedNotice = stringResource(R.string.pdf_bookmark_saved)
    val bookmarkRemovedNotice = stringResource(R.string.pdf_bookmark_removed)

    val title = item?.title?.takeIf { it.isNotBlank() } ?: attachment?.displayName.orEmpty()
    val fileLabel = attachment?.displayName.orEmpty()
    val sizeLabel = attachment
        ?.let { com.cipherwell.notezero.ui.attachments.AttachmentFormat.size(it.sizeBytes) }
        .orEmpty()

    val exportCancelledNotice = stringResource(R.string.attach_export_cancelled)
    val exportWrittenNotice = stringResource(R.string.attach_export_written)
    val exportFailedNotice = stringResource(R.string.attach_export_failed)
    val exportLockedNotice = stringResource(R.string.attach_export_locked)
    val annotatedWrittenNotice = stringResource(R.string.pdf_export_annotated_written)
    val annotatedFailedNotice = stringResource(R.string.pdf_export_annotated_failed)

    fun persistAnnotations() {
        val layer = editor.value
        val payload = layer.takeUnless { it.isEmpty }
        scope.launch { runCatching { repository.savePdfAnnotations(itemId, payload) } }
    }

    // Load the stored encrypted layer once, as soon as the item (and its attachments) exist.
    LaunchedEffect(itemId, item?.pdfAnnotations) {
        if (annotationsLoaded) return@LaunchedEffect
        if (item != null) {
            editor.load(item.pdfAnnotations)
            annotationsLoaded = true
        }
    }

    val annotationActions = PdfAnnotationActions(
        onStroke = { page, kind, points ->
            if (editor.addStroke(page, kind, colorIndex, widthIndex, points)) persistAnnotations()
        },
        onHighlight = { page, rect ->
            if (editor.addHighlight(page, rect.left, rect.top, rect.right, rect.bottom, colorIndex)) {
                persistAnnotations()
            }
        },
        onErase = { page, x, y, tolerance ->
            if (editor.eraseAt(page, x, y, tolerance)) persistAnnotations()
        },
        onNewNote = { page, x, y ->
            noteDraft = PdfNoteDraft(page = page, x = x, y = y, text = "", existingIndex = null)
        },
        onOpenNote = { index ->
            editor.value.notes.getOrNull(index)?.let { note ->
                noteDraft = PdfNoteDraft(
                    page = note.page,
                    x = note.x,
                    y = note.y,
                    text = note.text,
                    existingIndex = index,
                )
            }
        },
    )

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BlankPdf.MIME_TYPE),
    ) { uri ->
        if (uri == null) {
            controller.onExportPickerCancelled()
            notice = exportCancelledNotice
        } else {
            controller.onExportTarget(
                noteId = itemId,
                attachmentId = attachment?.id.orEmpty(),
                openOut = {
                    runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
                },
                onFinished = { written ->
                    notice = if (written) exportWrittenNotice else exportFailedNotice
                },
            )
        }
    }

    fun writeAnnotatedExport(uri: Uri) {
        scope.launch {
            // CPU work off the main thread; the bytes exist only in this coroutine.
            val layer = editor.value
            val bytes = withContext(exportDispatcher) { flattenedExporter.export(pages, layer) }
            val stillUnlocked = repository.status.value == VaultStatus.UNLOCKED &&
                repository.items.value.any { it.id == itemId }
            if (bytes == null) {
                notice = annotatedFailedNotice
                return@launch
            }
            if (!stillUnlocked) {
                // Locked while the picker was open: drop the plaintext, never write it.
                notice = exportLockedNotice
                return@launch
            }
            val written = runCatching { exportGateway.write(uri, bytes) }.getOrDefault(false)
            notice = if (written) annotatedWrittenNotice else annotatedFailedNotice
        }
    }

    fun onAnnotatedDestinationPicked(uri: Uri?) {
        // Ends the external-activity bracket started by controller.beginExport().
        controller.onExportPickerCancelled()
        if (uri == null) {
            notice = exportCancelledNotice
        } else {
            writeAnnotatedExport(uri)
        }
    }

    fun launchAnnotatedExport() {
        val launched = runCatching {
            exportGateway.pickDestination(annotatedPdfFileName(fileLabel)) { uri ->
                onAnnotatedDestinationPicked(uri)
            }
        }
        if (launched.isFailure) {
            controller.onExportPickerCancelled()
            notice = annotatedFailedNotice
        }
    }

    LaunchedEffect(itemId, attachment?.id) {
        loading = true
        failed = false
        val bytes = if (attachment != null) {
            runCatching { repository.attachmentBytes(itemId, attachment.id) }.getOrNull()
        } else {
            null
        }
        val rendered = if (bytes != null) renderer.render(context, bytes) else PdfPages(emptyList(), 0)
        pages = rendered
        failed = bytes == null || rendered.bitmaps.isEmpty()
        loading = false
        if (!failed && rendered.totalPages > 0) {
            val safePosition = (readingState?.position ?: 0)
                .coerceIn(0, rendered.totalPages - 1)
            runCatching {
                repository.saveReadingState(
                    itemId,
                    (readingState ?: ReadingState()).copy(
                        position = safePosition,
                        total = rendered.totalPages,
                    ),
                )
            }
        }
    }

    // Persist the current page shortly after it settles (debounced by effect restart on change).
    LaunchedEffect(currentPage, pages.totalPages) {
        if (loading || failed || pages.totalPages <= 0) return@LaunchedEffect
        delay(600)
        val page = currentPage.coerceIn(0, pages.totalPages - 1)
        val fresh = repository.items.value.firstOrNull { it.id == itemId }?.readingState
        if (fresh?.position == page && fresh.total == pages.totalPages) return@LaunchedEffect
        runCatching {
            repository.saveReadingState(
                itemId,
                (fresh ?: ReadingState()).copy(position = page, total = pages.totalPages),
            )
        }
    }

    // Free the rendered page bitmaps as soon as the screen leaves (manually, not "eventually").
    DisposableEffect(pages) {
        val owned = pages.bitmaps
        onDispose { owned.forEach { it.recycle() } }
    }

    // Back first leaves annotate mode so a drawing gesture can never close the document by
    // accident; a second Back (or the toolbar arrow outside annotate mode) closes the viewer.
    fun handleBack() {
        if (annotateMode) {
            annotateMode = false
        } else {
            onClose()
        }
    }

    BackHandler { handleBack() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("pdf_viewer")
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NoteZeroSpacing.s, vertical = NoteZeroSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NzIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_pdf_back),
                    onClick = { handleBack() },
                    modifier = Modifier.testTag("pdf_back"),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = NoteZeroSpacing.s),
                ) {
                    Text(
                        text = title,
                        style = NoteZeroType.Callout,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("pdf_title"),
                    )
                    if (sizeLabel.isNotBlank()) {
                        Text(
                            text = "PDF · $sizeLabel",
                            style = NoteZeroType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (!failed && pages.bitmaps.isNotEmpty()) {
                    if (annotateMode) {
                        TextButton(
                            onClick = { annotateMode = false },
                            modifier = Modifier
                                .heightIn(min = NoteZeroSpacing.minTarget)
                                .testTag("pdf_annotate_done"),
                        ) {
                            Text(stringResource(R.string.pdf_annotate_done), style = NoteZeroType.Button)
                        }
                    } else {
                        NzIconButton(
                            icon = Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.cd_pdf_annotate),
                            onClick = { annotateMode = true },
                            modifier = Modifier.testTag("pdf_annotate_toggle"),
                        )
                        NzIconButton(
                            icon = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.pdf_bookmark_add),
                            onClick = {
                                bookmarkLabel = ""
                                showAddBookmark = true
                            },
                            modifier = Modifier.testTag("pdf_bookmark_add"),
                        )
                        NzIconButton(
                            icon = Icons.Filled.List,
                            contentDescription = stringResource(R.string.pdf_reader_bookmarks),
                            onClick = { showBookmarks = true },
                            modifier = Modifier.testTag("pdf_bookmarks_open"),
                        )
                    }
                }
                NzIconButton(
                    icon = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.cd_pdf_export),
                    onClick = {
                        if (editor.value.isEmpty) {
                            pendingExport = PdfExportKind.ORIGINAL
                        } else {
                            showExportChoice = true
                        }
                    },
                    modifier = Modifier.testTag("pdf_export"),
                )
            }

            if (!failed && pages.totalPages > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NoteZeroSpacing.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = {
                            ((currentPage + 1).toFloat() / pages.totalPages).coerceIn(0f, 1f)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pdf_progress_bar"),
                    )
                    TextButton(
                        onClick = {
                            pageError = null
                            pageInput = (currentPage + 1).toString()
                            showPageJump = true
                        },
                        modifier = Modifier.testTag("pdf_page_progress"),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.pdf_page_progress,
                                (currentPage + 1).coerceAtMost(pages.totalPages),
                                pages.totalPages,
                            ),
                            style = NoteZeroType.Caption,
                        )
                    }
                }
            }

            notice?.let { text ->
                Text(
                    text = text,
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NoteZeroSpacing.l)
                        .testTag("pdf_notice"),
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(NoteZeroSpacing.m))
                            Text(
                                text = stringResource(R.string.pdf_loading),
                                style = NoteZeroType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    failed || pages.bitmaps.isEmpty() -> Text(
                        text = stringResource(R.string.pdf_error),
                        style = NoteZeroType.Body,
                        color = com.cipherwell.notezero.ui.theme.LocalNoteZeroColors.current.dangerText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(NoteZeroSpacing.l)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("pdf_error"),
                    )

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("pdf_pages"),
                        contentPadding = PaddingValues(
                            start = NoteZeroSpacing.l,
                            end = NoteZeroSpacing.l,
                            top = NoteZeroSpacing.s,
                            bottom = NoteZeroSpacing.l + 48.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.m),
                        userScrollEnabled = !annotateMode,
                    ) {
                        itemsIndexed(
                            pages.bitmaps,
                            key = { index: Int, _: Bitmap -> "pdf_page_$index" },
                        ) { index, bitmap ->
                            PageImage(
                                bitmap = bitmap,
                                index = index,
                                total = pages.totalPages,
                                annotations = editor.value,
                                annotateMode = annotateMode,
                                tool = tool,
                                colorIndex = colorIndex,
                                widthIndex = widthIndex,
                                actions = annotationActions,
                            )
                        }
                        if (pages.isTruncated) {
                            item(key = "pdf_capped") {
                                Text(
                                    text = stringResource(R.string.pdf_pages_capped, pages.renderedPages),
                                    style = NoteZeroType.Caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("pdf_pages_capped"),
                                )
                            }
                        }
                    }
                }
            }

            if (annotateMode && !failed && pages.bitmaps.isNotEmpty()) {
                PdfAnnotationPaletteBar(
                    tool = tool,
                    colorIndex = colorIndex,
                    widthIndex = widthIndex,
                    canUndo = editor.canUndo,
                    canRedo = editor.canRedo,
                    onToolSelected = { tool = it },
                    onColorSelected = { colorIndex = it },
                    onWidthSelected = { widthIndex = it },
                    onUndo = {
                        if (editor.undo()) persistAnnotations()
                    },
                    onRedo = {
                        if (editor.redo()) persistAnnotations()
                    },
                    modifier = Modifier.padding(
                        start = NoteZeroSpacing.s,
                        end = NoteZeroSpacing.s,
                        bottom = NoteZeroSpacing.xs,
                    ),
                )
            }
        }
    }

    if (showExportChoice) {
        AlertDialog(
            onDismissRequest = { showExportChoice = false },
            modifier = Modifier.testTag("pdf_export_choice"),
            title = { Text(stringResource(R.string.pdf_export_title), style = NoteZeroType.Heading) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs)) {
                    TextButton(
                        onClick = {
                            showExportChoice = false
                            pendingExport = PdfExportKind.ORIGINAL
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag("pdf_export_original"),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.pdf_export_original), style = NoteZeroType.Callout)
                            Text(
                                text = stringResource(R.string.pdf_export_original_desc),
                                style = NoteZeroType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            showExportChoice = false
                            pendingExport = PdfExportKind.ANNOTATED
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag("pdf_export_annotated"),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.pdf_export_annotated), style = NoteZeroType.Callout)
                            Text(
                                text = stringResource(R.string.pdf_export_annotated_desc),
                                style = NoteZeroType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showExportChoice = false },
                    modifier = Modifier.testTag("pdf_export_choice_cancel"),
                ) { Text(stringResource(R.string.common_cancel), style = NoteZeroType.Button) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = com.cipherwell.notezero.ui.theme.NoteZeroShapes.sheet,
        )
    }

    pendingExport?.let { kind ->
        AlertDialog(
            onDismissRequest = { pendingExport = null },
            modifier = Modifier.testTag("pdf_export_warning"),
            title = { Text(stringResource(R.string.attach_export_warning_title), style = NoteZeroType.Heading) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s)) {
                    Text(stringResource(R.string.attach_export_warning_message), style = NoteZeroType.Body)
                    if (kind == PdfExportKind.ANNOTATED) {
                        Text(
                            text = stringResource(R.string.pdf_export_annotated_desc),
                            style = NoteZeroType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingExport = null
                        if (controller.beginExport()) {
                            when (kind) {
                                PdfExportKind.ORIGINAL -> {
                                    runCatching { exportLauncher.launch(fileLabel) }
                                        .onFailure { controller.onExportPickerCancelled() }
                                }

                                PdfExportKind.ANNOTATED -> launchAnnotatedExport()
                            }
                        } else {
                            notice = exportLockedNotice
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("pdf_export_confirm"),
                ) {
                    Text(stringResource(R.string.attach_choose_location), style = NoteZeroType.Button)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingExport = null },
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("pdf_export_cancel"),
                ) {
                    Text(stringResource(R.string.common_cancel), style = NoteZeroType.Button)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = com.cipherwell.notezero.ui.theme.NoteZeroShapes.sheet,
        )
    }

    noteDraft?.let { draft ->
        PdfNoteDialog(
            draft = draft,
            onDismiss = { noteDraft = null },
            onSave = { text ->
                val saved = if (draft.existingIndex != null) {
                    editor.updateNote(draft.existingIndex, text)
                } else {
                    editor.addNote(draft.page, draft.x, draft.y, text) != null
                }
                if (saved) persistAnnotations()
                noteDraft = null
            },
            onDelete = {
                if (draft.existingIndex != null && editor.deleteNote(draft.existingIndex)) {
                    persistAnnotations()
                }
                noteDraft = null
            },
        )
    }

    if (showAddBookmark) {
        AlertDialog(
            onDismissRequest = { showAddBookmark = false },
            modifier = Modifier.testTag("pdf_bookmark_dialog"),
            title = { Text(stringResource(R.string.pdf_bookmark_add), style = NoteZeroType.Heading) },
            text = {
                OutlinedTextField(
                    value = bookmarkLabel,
                    onValueChange = { bookmarkLabel = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.book_bookmark_label), style = NoteZeroType.Callout) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pdf_bookmark_label"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val label = bookmarkLabel.trim().ifBlank { defaultBookmarkLabel }
                        val current = repository.items.value.firstOrNull { it.id == itemId }?.readingState
                        val updated = (current ?: ReadingState()).copy(
                            position = currentPage.coerceIn(0, (pages.totalPages - 1).coerceAtLeast(0)),
                            total = pages.totalPages.coerceAtLeast(current?.total ?: 0),
                            bookmarks = (current?.bookmarks ?: emptyList()) + ReadingBookmark(
                                position = currentPage.coerceIn(0, (pages.totalPages - 1).coerceAtLeast(0)),
                                label = label,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        scope.launch { runCatching { repository.saveReadingState(itemId, updated) } }
                        showAddBookmark = false
                        notice = bookmarkAddedNotice
                    },
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("pdf_bookmark_save"),
                ) { Text(stringResource(R.string.common_save), style = NoteZeroType.Button) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAddBookmark = false },
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("pdf_bookmark_cancel"),
                ) { Text(stringResource(R.string.common_cancel), style = NoteZeroType.Button) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = com.cipherwell.notezero.ui.theme.NoteZeroShapes.sheet,
        )
    }

    if (showBookmarks) {
        val bookmarks = (repository.items.value.firstOrNull { it.id == itemId }?.readingState?.bookmarks)
            .orEmpty()
        AlertDialog(
            onDismissRequest = { showBookmarks = false },
            modifier = Modifier.testTag("pdf_bookmarks_dialog"),
            title = { Text(stringResource(R.string.pdf_reader_bookmarks), style = NoteZeroType.Heading) },
            text = {
                if (bookmarks.isEmpty()) {
                    Text(stringResource(R.string.pdf_bookmarks_empty), style = NoteZeroType.Body)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(bookmarks) { bookmark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showBookmarks = false
                                        scope.launch {
                                            listState.scrollToItem(
                                                bookmark.position.coerceIn(0, (pages.totalPages - 1).coerceAtLeast(0)),
                                            )
                                        }
                                    }
                                    .padding(vertical = NoteZeroSpacing.s)
                                    .testTag("pdf_bookmark_row"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bookmark.label,
                                        style = NoteZeroType.Callout,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = stringResource(R.string.pdf_bookmark_default, bookmark.position + 1),
                                        style = NoteZeroType.Caption,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                NzIconButton(
                                    icon = Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.common_delete),
                                    onClick = {
                                        val current = repository.items.value.firstOrNull { it.id == itemId }?.readingState
                                        if (current != null) {
                                            val updated = current.copy(
                                                bookmarks = current.bookmarks.filterNot { it == bookmark },
                                            )
                                            scope.launch {
                                                runCatching { repository.saveReadingState(itemId, updated) }
                                            }
                                        }
                                        notice = bookmarkRemovedNotice
                                    },
                                    modifier = Modifier.testTag("pdf_bookmark_delete"),
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showBookmarks = false },
                    modifier = Modifier.testTag("pdf_bookmarks_close"),
                ) { Text(stringResource(R.string.common_close), style = NoteZeroType.Button) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = com.cipherwell.notezero.ui.theme.NoteZeroShapes.sheet,
        )
    }

    if (showPageJump) {
        val total = pages.totalPages.coerceAtLeast(1)
        AlertDialog(
            onDismissRequest = { showPageJump = false },
            modifier = Modifier.testTag("pdf_page_jump_dialog"),
            title = { Text(stringResource(R.string.pdf_page_jump), style = NoteZeroType.Heading) },
            text = {
                Column {
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = {
                            pageInput = it.filter(Char::isDigit).take(7)
                            pageError = null
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.pdf_page_jump_field), style = NoteZeroType.Callout) },
                        isError = pageError != null,
                        supportingText = {
                            if (pageError != null) {
                                Text(
                                    text = pageError.orEmpty(),
                                    style = NoteZeroType.Caption,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("pdf_page_error"),
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pdf_page_field"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val requested = pageInput.toIntOrNull()
                        if (requested == null || requested !in 1..total) {
                            pageError = pageInvalidNotice
                        } else {
                            showPageJump = false
                            scope.launch { listState.scrollToItem(requested - 1) }
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("pdf_page_jump_confirm"),
                ) { Text(stringResource(R.string.common_go), style = NoteZeroType.Button) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPageJump = false },
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("pdf_page_jump_cancel"),
                ) { Text(stringResource(R.string.common_cancel), style = NoteZeroType.Button) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = com.cipherwell.notezero.ui.theme.NoteZeroShapes.sheet,
        )
    }
}

@Composable
private fun PageImage(
    bitmap: Bitmap,
    index: Int,
    total: Int,
    annotations: PdfAnnotations,
    annotateMode: Boolean,
    tool: PdfAnnotationTool,
    colorIndex: Int,
    widthIndex: Int,
    actions: PdfAnnotationActions,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                .clip(RoundedCornerShape(NoteZeroSpacing.s))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .testTag("pdf_page_frame"),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.pdf_page_indicator, index + 1, total),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("pdf_page"),
            )
            PdfAnnotationOverlay(
                page = index,
                annotations = annotations,
                annotateMode = annotateMode,
                tool = tool,
                colorIndex = colorIndex,
                widthIndex = widthIndex,
                onStroke = actions.onStroke,
                onHighlight = actions.onHighlight,
                onErase = actions.onErase,
                onNewNote = actions.onNewNote,
                onOpenNote = actions.onOpenNote,
            )
        }
        Spacer(Modifier.height(NoteZeroSpacing.xxs))
        Text(
            text = stringResource(R.string.pdf_page_indicator, index + 1, total),
            style = NoteZeroType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
