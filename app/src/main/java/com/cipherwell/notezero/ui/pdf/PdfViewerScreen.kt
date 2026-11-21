package com.cipherwell.notezero.ui.pdf

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
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
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which plaintext egress the user confirmed in the export-choice dialog. */
private enum class PdfExportKind { ORIGINAL, ANNOTATED }

/** Aspect ratio of the white A4 page the viewer's placeholder cells mirror. */
private const val A4_ASPECT_RATIO = 595f / 842f

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
 * The PDF bytes are decrypted from the vault and opened as **one** [PdfPageSession]: the document
 * handle stays open for the viewing session while each page is rasterised lazily, only while its
 * row is composed (see [AndroidPdfPageSessionFactory] for the unlinked ephemeral-file trade-off).
 * Every page of the document is reachable by scrolling or page jump; nothing is cached to disk and
 * page bitmaps are recycled as their rows leave the composition. Annotations are edited as a
 * normalised layer (never touching the original PDF) and persisted encrypted through
 * [NoteRepository.savePdfAnnotations]; only an explicit export turns them into plaintext.
 *
 * "Export decrypted copy" is the only plaintext egress and goes through the same SAF
 * `CreateDocument` + explicit-warning flow as attachment export, bracketed by
 * [AttachmentController] so the picker does not lock the vault mid-export. When annotations
 * exist the user first chooses the untouched original or a flattened (rasterised) copy; the
 * flattened copy is built from the encrypted bytes in memory by the shared [PdfPageToolsExporter]
 * (identity page plan, same 100-page cap as page tools) and written through [PdfExportGateway],
 * with a final vault-unlocked re-check before any byte is written.
 *
 * A vault lock replaces this screen with the unlock UI at the shell level; the session is closed
 * and page bitmaps are recycled as soon as the composition leaves.
 */
@Composable
fun PdfViewerScreen(
    repository: NoteRepository,
    itemId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    pageSessionFactory: PdfPageSessionFactory = remember { AndroidPdfPageSessionFactory() },
    exportGateway: PdfExportGateway = rememberSafPdfExportGateway(),
    exportDispatcher: CoroutineDispatcher = Dispatchers.Default,
    pageToolsExporter: PdfPageToolsExporter = remember { AndroidPdfPageToolsExporter() },
    pageCopySaver: PdfPageCopySaver = remember { DefaultPdfPageCopySaver() },
    thumbnailRenderer: PdfThumbnailRenderer = remember { AndroidPdfThumbnailRenderer() },
) {
    val context = LocalContext.current
    val items by repository.items.collectAsStateWithLifecycle()
    val item = items.firstOrNull { it.id == itemId }
    val attachment = item?.attachments?.firstOrNull { it.mimeType == BlankPdf.MIME_TYPE }
        ?: item?.attachments?.firstOrNull()

    val controller = remember(repository) { AttachmentController(repository) }
    var loading by remember(itemId) { mutableStateOf(true) }
    // The one open document handle of this viewing session; pageCount drives the lazy list. No
    // page bitmap lives here: each page row owns (and recycles) its own bitmap.
    var session by remember(itemId) { mutableStateOf<PdfPageSession?>(null) }
    var pageCount by remember(itemId) { mutableStateOf(0) }
    var failed by remember(itemId) { mutableStateOf(false) }
    var notice by remember(itemId) { mutableStateOf<String?>(null) }
    var showExportChoice by remember(itemId) { mutableStateOf(false) }
    var pendingExport by remember(itemId) { mutableStateOf<PdfExportKind?>(null) }
    var showPageTools by remember(itemId) { mutableStateOf(false) }
    var pagePlan by remember(itemId) { mutableStateOf(PdfPagePlan.of(0)) }
    var pageToolsSaving by remember(itemId) { mutableStateOf(false) }
    var pageToolsNotice by remember(itemId) { mutableStateOf<String?>(null) }

    // In-memory page thumbnails: rendered lazily on the first thumbnail surface and owned by the
    // viewer for this session only (no file/cache — see PdfThumbnails).
    var thumbnails by remember(itemId) { mutableStateOf<PdfThumbnails?>(null) }
    var thumbnailsLoading by remember(itemId) { mutableStateOf(false) }
    var thumbnailsLoaded by remember(itemId) { mutableStateOf(false) }
    var thumbnailsRequested by remember(itemId) { mutableStateOf(false) }

    val editor = remember(itemId) { PdfAnnotationEditor() }
    var annotationsLoaded by remember(itemId) { mutableStateOf(false) }
    var annotateMode by remember(itemId) { mutableStateOf(false) }
    var tool by remember(itemId) { mutableStateOf(PdfAnnotationTool.INK) }
    var colorIndex by remember(itemId) { mutableStateOf(0) }
    var widthIndex by remember(itemId) { mutableStateOf(1) }
    var noteDraft by remember(itemId) { mutableStateOf<PdfNoteDraft?>(null) }

    val readingState = item?.readingState
    // The list always starts at the top: the encrypted reading position is applied after the
    // document's real pageCount is known (below), so a stale/hostile stored position can never
    // drive the lazy list out of its item range.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentPage = listState.firstVisibleItemIndex
    var showBookmarks by remember(itemId) { mutableStateOf(false) }
    var showAddBookmark by remember(itemId) { mutableStateOf(false) }
    var bookmarkLabel by remember(itemId) { mutableStateOf("") }
    var showPageJump by remember(itemId) { mutableStateOf(false) }
    var pageInput by remember(itemId) { mutableStateOf("") }
    var pageError by remember(itemId) { mutableStateOf<String?>(null) }
    val pageInvalidNotice = stringResource(R.string.pdf_page_invalid, pageCount.coerceAtLeast(1))
    val defaultBookmarkLabel = stringResource(
        R.string.pdf_bookmark_default,
        (currentPage + 1).coerceAtMost(pageCount.coerceAtLeast(1)),
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
    val annotatedTooManyNotice = stringResource(
        R.string.pdf_annotated_export_too_many,
        PdfPagePlan.MAX_PAGES,
    )
    val pageCopySuffix = stringResource(R.string.pdf_pages_copy_suffix)
    // The page-tools save flow is a separate coroutine from the annotated export; these are
    // captured here (the save runs outside composition but only uses formatted one-shot copy).
    val pageToolsLockedNotice = stringResource(R.string.pdf_pages_locked)
    val pageToolsExportFailedNotice = stringResource(R.string.pdf_pages_export_failed)
    val pageToolsSaveFailedNotice = stringResource(R.string.pdf_pages_save_failed)

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

    /**
     * Builds and writes the annotated (flattened) copy of the whole document.
     *
     * The copy uses the same all-pages exporter as the page-tools copy with an identity
     * [PdfPagePlan] (one page per source page, in order, no rotation). Security order matters:
     * the decrypted bytes are **re-read from the repository at export time** (never taken from
     * the viewer's session or a cached copy), the exporter runs off the main thread, and the
     * vault/item are re-checked before the bytes are handed to the gateway — so a vault that
     * locks while the picker is open drops the plaintext instead of writing it. Above the
     * exporter's page cap nothing is read or written, only a notice is shown.
     */
    fun writeAnnotatedExport(uri: Uri) {
        scope.launch {
            val layer = editor.value
            if (pageCount > PdfPagePlan.MAX_PAGES) {
                // The shared page exporter is capped at MAX_PAGES: refuse rather than truncate.
                notice = annotatedTooManyNotice
                return@launch
            }
            // Re-check the vault/item before reading any decrypted byte: a lock that happened
            // while the destination picker was open must discard the export with its own notice.
            if (repository.status.value != VaultStatus.UNLOCKED ||
                repository.items.value.none { it.id == itemId }
            ) {
                notice = exportLockedNotice
                return@launch
            }
            val attachmentId = attachment?.id
            val bytes = attachmentId?.let { id ->
                runCatching { repository.attachmentBytes(itemId, id) }.getOrNull()
            }
            if (bytes == null) {
                notice = annotatedFailedNotice
                return@launch
            }
            // CPU work off the main thread; the flattened bytes exist only in this coroutine.
            val exported = withContext(exportDispatcher) {
                pageToolsExporter.export(context, bytes, PdfPagePlan.of(pageCount), layer)
            }
            val stillUnlocked = repository.status.value == VaultStatus.UNLOCKED &&
                repository.items.value.any { it.id == itemId }
            if (exported == null) {
                notice = annotatedFailedNotice
                return@launch
            }
            if (!stillUnlocked) {
                // Locked while the picker was open: drop the plaintext, never write it.
                notice = exportLockedNotice
                return@launch
            }
            val written = runCatching { exportGateway.write(uri, exported) }.getOrDefault(false)
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
        if (pageCount > PdfPagePlan.MAX_PAGES) {
            // Refuse before opening a destination picker: no empty/partial file is ever created.
            // The bracket armed by controller.beginExport() is closed here.
            controller.onExportPickerCancelled()
            notice = annotatedTooManyNotice
            return
        }
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

    /**
     * Saves the page-tools plan as a new flattened encrypted PDF item.
     *
     * Security order matters: the decrypted bytes are re-read from the repository **at save
     * time** (never cached from the viewer load), the plan is exported off the main thread, and
     * the vault/item are re-checked before the bytes are handed to the saver — so a vault that
     * locks mid-export drops the plaintext instead of writing it. The original document and its
     * annotation layer are never modified; only a new encrypted item is created.
     */
    fun savePageCopy() {
        if (pageToolsSaving) return
        val plan = pagePlan
        if (!plan.isValid) return
        val attachmentId = attachment?.id
        val copyName = pageCopyFileName(title, pageCopySuffix)
        pageToolsSaving = true
        pageToolsNotice = null
        scope.launch {
            try {
                val source = repository.items.value.firstOrNull { it.id == itemId }
                if (repository.status.value != VaultStatus.UNLOCKED || source == null) {
                    pageToolsNotice = pageToolsLockedNotice
                    return@launch
                }
                val bytes = attachmentId?.let { id ->
                    runCatching { repository.attachmentBytes(itemId, id) }.getOrNull()
                }
                if (bytes == null) {
                    pageToolsNotice = pageToolsExportFailedNotice
                    return@launch
                }
                val layer = editor.value
                val exported = withContext(exportDispatcher) {
                    pageToolsExporter.export(context, bytes, plan, layer)
                }
                if (exported == null) {
                    pageToolsNotice = pageToolsExportFailedNotice
                    return@launch
                }
                // Re-check after the CPU work: the vault may have locked (or the item been
                // trashed) while the export was running off the main thread.
                val sourceNow = repository.items.value.firstOrNull { it.id == itemId }
                if (repository.status.value != VaultStatus.UNLOCKED || sourceNow == null) {
                    pageToolsNotice = pageToolsLockedNotice
                    return@launch
                }
                val savedId = pageCopySaver.save(repository, sourceNow, copyName, exported)
                if (savedId == null) {
                    pageToolsNotice = pageToolsSaveFailedNotice
                } else {
                    pageToolsNotice = null
                    showPageTools = false
                    notice = context.getString(R.string.pdf_pages_saved, copyName)
                }
            } finally {
                pageToolsSaving = false
            }
        }
    }

    // Open exactly one document session after the bytes are read; nothing is rasterised here.
    // A viewer may only list MAX_VIEW_PAGES rows, so a hostile page count fails like a bad file.
    LaunchedEffect(itemId, attachment?.id) {
        loading = true
        failed = false
        val bytes = if (attachment != null) {
            runCatching { repository.attachmentBytes(itemId, attachment.id) }.getOrNull()
        } else {
            null
        }
        val opened = if (bytes != null) {
            runCatching { pageSessionFactory.open(context, bytes) }.getOrNull()
        } else {
            null
        }
        val openedCount = opened?.pageCount ?: 0
        if (opened == null || openedCount <= 0 || openedCount > MAX_VIEW_PAGES) {
            runCatching { opened?.close() }
            session = null
            pageCount = 0
            failed = true
            loading = false
            return@LaunchedEffect
        }
        session = opened
        pageCount = openedCount
        failed = false
        loading = false
        val safePosition = (readingState?.position ?: 0).coerceIn(0, openedCount - 1)
        runCatching {
            repository.saveReadingState(
                itemId,
                (readingState ?: ReadingState()).copy(
                    position = safePosition,
                    total = openedCount,
                ),
            )
        }
    }

    // Restore the encrypted reading position once the real page count is known. Coerced so a
    // stale/hostile position cannot ask the lazy list for a row that does not exist.
    LaunchedEffect(pageCount) {
        if (pageCount <= 0) return@LaunchedEffect
        val safePosition = (readingState?.position ?: 0).coerceIn(0, pageCount - 1)
        runCatching { listState.scrollToItem(safePosition) }
    }

    // The session owns the native document handle and the (already unlinked) temp file: close it
    // as soon as the viewer leaves or a different item replaces this one. Page bitmaps belong to
    // their rows and are recycled on row disposal, never here. The key is captured as a local
    // value: DisposableEffect runs its block after composition, so reading the state inside the
    // block could observe the next session and close it on the key-change disposal.
    val sessionToClose = session
    DisposableEffect(sessionToClose) {
        onDispose { runCatching { sessionToClose?.close() } }
    }

    // Thumbnails are loaded lazily, exactly once per viewer session: the first time the user
    // opens a thumbnail surface (Pages overview, bookmarks or page tools) the decrypted bytes are
    // re-read from the repository and rendered; later surfaces reuse the in-memory set. The bytes
    // are only ever held by this coroutine, never stored in composition state.
    LaunchedEffect(thumbnailsRequested, itemId) {
        if (!thumbnailsRequested || thumbnailsLoaded) return@LaunchedEffect
        thumbnailsLoading = true
        val attachmentId = attachment?.id
        val bytes = if (attachmentId != null) {
            try {
                repository.attachmentBytes(itemId, attachmentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        val rendered = if (bytes == null) {
            PdfThumbnails(emptyList())
        } else {
            try {
                thumbnailRenderer.render(context, bytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The UI falls back to the numeric page field, never to a broken grid.
                PdfThumbnails(emptyList())
            }
        }
        thumbnails = rendered
        thumbnailsLoaded = true
        thumbnailsLoading = false
    }

    // Free the thumbnail bitmaps when the viewer leaves (or when a new set replaces this one);
    // never recycle a set that is still attached to the composition, only the previous one. The
    // set is captured at composition time for the same reason as the page session above.
    val thumbnailsToRecycle = thumbnails
    DisposableEffect(thumbnailsToRecycle) {
        onDispose { thumbnailsToRecycle?.recycle() }
    }

    // Persist the current page shortly after it settles (debounced by effect restart on change).
    LaunchedEffect(currentPage, pageCount) {
        if (loading || failed || pageCount <= 0) return@LaunchedEffect
        delay(600)
        val page = currentPage.coerceIn(0, pageCount - 1)
        val fresh = repository.items.value.firstOrNull { it.id == itemId }?.readingState
        if (fresh?.position == page && fresh.total == pageCount) return@LaunchedEffect
        runCatching {
            repository.saveReadingState(
                itemId,
                (fresh ?: ReadingState()).copy(position = page, total = pageCount),
            )
        }
    }

    // Back closes the page-tools overlay first (it is modal), then leaves annotate mode, then
    // closes the viewer — a drawing gesture or an open tools sheet can never close the document
    // by accident.
    fun handleBack() {
        if (showPageTools) {
            // While the copy is being saved the overlay stays put so its result is not missed;
            // the save itself cannot be interrupted (the encrypted import is already in flight).
            if (!pageToolsSaving) {
                showPageTools = false
                pageToolsNotice = null
            }
        } else if (annotateMode) {
            annotateMode = false
        } else {
            onClose()
        }
    }

    BackHandler { handleBack() }

    // Captured as a local value so the lazy list can use a non-null session in its else branch.
    val activeSession = session

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
                if (!failed && pageCount > 0) {
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
                            onClick = {
                                thumbnailsRequested = true
                                showBookmarks = true
                            },
                            modifier = Modifier.testTag("pdf_bookmarks_open"),
                        )
                    }
                }
                if (!annotateMode) {
                    NzIconButton(
                        icon = Icons.Filled.Build,
                        contentDescription = stringResource(R.string.cd_pdf_page_tools),
                        onClick = {
                            pagePlan = PdfPagePlan.of(pageCount)
                            pageToolsNotice = null
                            thumbnailsRequested = true
                            showPageTools = true
                        },
                        enabled = !failed && pageCount > 0,
                        modifier = Modifier.testTag("pdf_page_tools"),
                    )
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

            if (!failed && pageCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NoteZeroSpacing.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = {
                            ((currentPage + 1).toFloat() / pageCount).coerceIn(0f, 1f)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pdf_progress_bar"),
                    )
                    TextButton(
                        onClick = {
                            pageError = null
                            pageInput = (currentPage + 1).toString()
                            thumbnailsRequested = true
                            showPageJump = true
                        },
                        modifier = Modifier.testTag("pdf_page_progress"),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.pdf_page_progress,
                                (currentPage + 1).coerceAtMost(pageCount),
                                pageCount,
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

                    failed || activeSession == null || pageCount <= 0 -> Text(
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
                        // Every document page is a row; the bitmap itself is produced lazily by
                        // the row while it is composed and recycled when it leaves composition.
                        items(count = pageCount, key = { index -> "pdf_page_$index" }) { index ->
                            PdfPageItem(
                                pageIndex = index,
                                total = pageCount,
                                session = activeSession,
                                annotations = editor.value,
                                annotateMode = annotateMode,
                                tool = tool,
                                colorIndex = colorIndex,
                                widthIndex = widthIndex,
                                actions = annotationActions,
                            )
                        }
                    }
                }
            }

            if (annotateMode && !failed && pageCount > 0) {
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
                            position = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                            total = pageCount.coerceAtLeast(current?.total ?: 0),
                            bookmarks = (current?.bookmarks ?: emptyList()) + ReadingBookmark(
                                position = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
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
                            val bookmarkThumbnail = thumbnails?.bitmap(bookmark.position)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showBookmarks = false
                                        scope.launch {
                                            listState.scrollToItem(
                                                bookmark.position.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                                            )
                                        }
                                    }
                                    .padding(vertical = NoteZeroSpacing.s)
                                    .testTag("pdf_bookmark_row"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Text-only while thumbnails are still loading or unavailable:
                                // a bookmark row never shows a broken image.
                                if (bookmarkThumbnail != null) {
                                    Image(
                                        bitmap = bookmarkThumbnail.asImageBitmap(),
                                        contentDescription = stringResource(
                                            R.string.cd_pdf_page_thumbnail,
                                            bookmark.position + 1,
                                        ),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(width = 48.dp, height = 64.dp)
                                            .clip(RoundedCornerShape(NoteZeroSpacing.xs))
                                            .testTag("pdf_bookmark_thumbnail"),
                                    )
                                    Spacer(Modifier.width(NoteZeroSpacing.s))
                                }
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
        val total = pageCount.coerceAtLeast(1)
        // The overview covers min(pageCount, thumbnails.size): thumbnails themselves are capped
        // (PdfThumbnails.MAX_PAGES), so a very long document still gets a full-range numeric
        // field as the jump fallback while the grid shows the first 100 loaded pages.
        val thumbnailSet = thumbnails
        val gridCells = thumbnailSet
            ?.takeUnless { it.isEmpty }
            ?.size
            ?.coerceAtMost(pageCount)
            ?: 0
        AlertDialog(
            onDismissRequest = { showPageJump = false },
            modifier = Modifier.testTag("pdf_page_jump_dialog"),
            title = {
                Text(
                    text = stringResource(R.string.pdf_page_overview),
                    style = NoteZeroType.Heading,
                    modifier = Modifier.testTag("pdf_page_overview_title"),
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.pdf_page_overview_hint),
                        style = NoteZeroType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("pdf_page_overview_hint"),
                    )
                    Spacer(Modifier.height(NoteZeroSpacing.xs))
                    when {
                        gridCells > 0 -> LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .testTag("pdf_page_overview_grid"),
                            contentPadding = PaddingValues(vertical = NoteZeroSpacing.xxs),
                            horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
                            verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
                        ) {
                            items(
                                count = gridCells,
                                key = { index -> "pdf_page_thumb_$index" },
                            ) { index ->
                                PageOverviewThumbnail(
                                    page = index,
                                    bitmap = thumbnailSet?.bitmap(index),
                                    selected = index == currentPage,
                                    onClick = {
                                        showPageJump = false
                                        scope.launch {
                                            listState.scrollToItem(
                                                index.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                                            )
                                        }
                                    },
                                )
                            }
                        }

                        thumbnailsLoading || !thumbnailsLoaded -> Row(
                            modifier = Modifier.testTag("pdf_thumbnails_loading"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(NoteZeroSpacing.s))
                            Text(
                                text = stringResource(R.string.pdf_thumbnails_loading),
                                style = NoteZeroType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        else -> Text(
                            text = stringResource(R.string.pdf_thumbnails_unavailable),
                            style = NoteZeroType.Caption,
                            color = com.cipherwell.notezero.ui.theme.LocalNoteZeroColors.current.warningText,
                            modifier = Modifier
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("pdf_thumbnails_unavailable"),
                        )
                    }
                    Spacer(Modifier.height(NoteZeroSpacing.s))
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

    if (showPageTools) {
        PdfPageToolsScreen(
            plan = pagePlan,
            saving = pageToolsSaving,
            notice = pageToolsNotice,
            onPlanChange = { updated -> pagePlan = updated },
            onSave = { savePageCopy() },
            onCancel = {
                if (!pageToolsSaving) {
                    showPageTools = false
                    pageToolsNotice = null
                }
            },
            thumbnails = thumbnails,
        )
    }
}

/** Lazy render state of one page row: producing the bitmap, ready to show, or failed. */
private sealed interface PdfPageRenderState {
    object Loading : PdfPageRenderState
    class Ready(val bitmap: Bitmap) : PdfPageRenderState
    object Failed : PdfPageRenderState
}

/**
 * One row of the lazy document pager.
 *
 * The page is rendered (only) while this row is composed: [LaunchedEffect] asks the session for
 * the bitmap, a bounded A4-aspect placeholder is shown while that runs, and a failed render shows
 * a non-secret placeholder instead of a crash or a broken image. The bitmap is recycled when the
 * row leaves the composition (or when a replacement bitmap arrives), never while it is displayed.
 */
@Composable
private fun PdfPageItem(
    pageIndex: Int,
    total: Int,
    session: PdfPageSession,
    annotations: PdfAnnotations,
    annotateMode: Boolean,
    tool: PdfAnnotationTool,
    colorIndex: Int,
    widthIndex: Int,
    actions: PdfAnnotationActions,
) {
    val renderState = remember(pageIndex) {
        mutableStateOf<PdfPageRenderState>(PdfPageRenderState.Loading)
    }
    val bitmap = (renderState.value as? PdfPageRenderState.Ready)?.bitmap
    LaunchedEffect(session, pageIndex) {
        renderState.value = PdfPageRenderState.Loading
        val rendered = session.render(pageIndex)
        renderState.value = if (rendered != null) {
            PdfPageRenderState.Ready(rendered)
        } else {
            PdfPageRenderState.Failed
        }
    }
    // One disposal owner per bitmap: replacing a bitmap recycles the previous one, leaving the
    // composition recycles the current one. A bitmap that is still displayed is never recycled.
    DisposableEffect(bitmap) {
        val owned = bitmap
        onDispose {
            owned?.let { pageBitmap ->
                runCatching { if (!pageBitmap.isRecycled) pageBitmap.recycle() }
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pdf_page_$pageIndex"),
    ) {
        when (val state = renderState.value) {
            is PdfPageRenderState.Ready -> PageImage(
                bitmap = state.bitmap,
                index = pageIndex,
                total = total,
                annotations = annotations,
                annotateMode = annotateMode,
                tool = tool,
                colorIndex = colorIndex,
                widthIndex = widthIndex,
                actions = actions,
            )

            PdfPageRenderState.Loading -> PageImagePlaceholder(
                message = stringResource(R.string.pdf_page_loading),
                messageTag = "pdf_page_loading",
            )

            PdfPageRenderState.Failed -> PageImagePlaceholder(
                message = stringResource(R.string.pdf_page_render_failed),
                messageTag = "pdf_page_render_failed",
                failed = true,
            )
        }
        Spacer(Modifier.height(NoteZeroSpacing.xxs))
        Text(
            text = stringResource(R.string.pdf_page_indicator, pageIndex + 1, total),
            style = NoteZeroType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/** Rendered page frame: the bitmap plus its annotation/gesture overlay. */
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
}

/**
 * Bounded A4-aspect page placeholder used while a page renders and when it fails. It reserves the
 * final layout space (no scroll jump when the bitmap arrives) and never spins.
 */
@Composable
private fun PageImagePlaceholder(
    message: String,
    messageTag: String,
    failed: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(A4_ASPECT_RATIO)
            .clip(RoundedCornerShape(NoteZeroSpacing.s))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .testTag("pdf_page_frame"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = NoteZeroType.Caption,
            color = if (failed) {
                com.cipherwell.notezero.ui.theme.LocalNoteZeroColors.current.warningText
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .padding(NoteZeroSpacing.m)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(messageTag),
        )
    }
}

/**
 * One page cell of the Pages overview: a thumbnail (or a light placeholder when the renderer
 * could not produce that page), the 1-based page number and a primary-colour emphasis for the
 * page the reader is currently on. Tapping the cell is the visual jump action — the caller
 * scrolls the pager and closes the dialog.
 */
@Composable
private fun PageOverviewThumbnail(
    page: Int,
    bitmap: Bitmap?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(NoteZeroSpacing.xs)
    val emphasis = if (selected) 2.dp else 1.dp
    Column(
        modifier = Modifier
            .testTag("pdf_page_thumb_$page")
            .clickable(onClick = onClick)
            .padding(NoteZeroSpacing.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    if (bitmap != null) {
                        bitmap.width.toFloat() / bitmap.height.toFloat()
                    } else {
                        A4_ASPECT_RATIO
                    },
                )
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(
                    width = emphasis,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = shape,
                ),
        ) {
            if (bitmap != null) {
                // Inset so the selection border stays visible above the page image.
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_pdf_page_thumbnail, page + 1),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = (page + 1).toString(),
            style = NoteZeroType.Caption,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}
