package com.cipherwell.notezero.ui.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherwell.notezero.R
import com.cipherwell.notezero.book.BookDetector
import com.cipherwell.notezero.book.BookFormat
import com.cipherwell.notezero.book.BookLoader
import com.cipherwell.notezero.book.LoadedBook
import com.cipherwell.notezero.data.AttachmentImport
import com.cipherwell.notezero.data.AttachmentLimits
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.ReadingState
import com.cipherwell.notezero.ui.attachments.AttachmentImages
import com.cipherwell.notezero.ui.attachments.DrawingFormat
import com.cipherwell.notezero.ui.components.common.NzFab
import com.cipherwell.notezero.ui.components.common.NzIconButton
import com.cipherwell.notezero.ui.locks.LockVisibility
import com.cipherwell.notezero.ui.pdf.PdfViewerScreen
import com.cipherwell.notezero.ui.reader.BookReaderScreen
import com.cipherwell.notezero.ui.sketch.SketchStudioScreen
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Plain text MIME the library imports and recognises as a PDF document. */
private const val PDF_MIME = "application/pdf"

/** Attachment MIME used only if a detected book format somehow carries no MIME of its own. */
private const val FALLBACK_BOOK_MIME = "application/octet-stream"

/** MIME types a book import stores (content-detected; the formats the engine supports). */
private val BOOK_MIME_TYPES: Set<String> =
    BookFormat.values().map { it.mimeType }.toSet() + FALLBACK_BOOK_MIME

private const val FALLBACK_PDF_NAME = "document.pdf"
private const val FALLBACK_BOOK_NAME = "book"

/**
 * Item title when neither the book metadata nor the SAF display name yields any text. This is
 * stored user data (not reachable UI chrome); only the sketch shelf has an "untitled" resource.
 */
private const val MAX_METADATA_CHARS = 256
private const val PNG_MIME = "image/png"

/** Shelf filter chips. */
internal enum class LibraryFilter { ALL, BOOKS, PDFS, DRAWINGS }

/** Document kind of one shelf row. */
internal enum class LibraryEntryType { PDF, BOOK, DRAWING }

/**
 * One document on the shelf: the owning item plus the attachment that opens it.
 *
 * [attachment] is nullable because a standalone `SKETCH_FILE` opens in the drawing studio and
 * exists before its flattened PNG preview has ever been rendered: the sketch must be listed (and
 * editable) even with no attachment yet. Note-owned drawings always carry one.
 */
internal data class LibraryEntry(
    val item: Item,
    val attachment: AttachmentRef?,
    val type: LibraryEntryType,
) {
    val updatedAt: Long get() = item.updatedAt
}

/**
 * All-in-one document shelf.
 *
 * Lists encrypted PDFs, e-books (EPUB/FB2/TXT/comic) and the note-owned drawings, filtered by
 * the four chips and sorted newest-first. Locked items the session has not verified are
 * removed entirely (fail closed, exactly like the code workspace) and items inside a
 * hidden locked folder never appear. Import goes through SAF, is bracketed by
 * [NoteRepository.beginExternalActivity]/[NoteRepository.endExternalActivity] so the
 * picker does not trip the background lock, and only writes encrypted attachment blobs —
 * a failed import trashes the just-created item instead of leaving an empty row.
 */
@Composable
fun LibraryScreen(
    repository: NoteRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val flowItems by repository.items.collectAsStateWithLifecycle()
    val folders by repository.folders.collectAsStateWithLifecycle()
    val status by repository.status.collectAsStateWithLifecycle()
    val verifiedLockedIds by repository.verifiedLockedIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var importMenuOpen by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var openPdfId by remember { mutableStateOf<String?>(null) }
    var openBookId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<Item?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var previewTarget by remember { mutableStateOf<LibraryEntry?>(null) }
    var previewImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewFailed by remember { mutableStateOf(false) }
    var openSketchId by remember { mutableStateOf<String?>(null) }
    var creatingSketch by remember { mutableStateOf(false) }

    val importFailedNotice = stringResource(R.string.library_import_failed)
    val untitledBookTitle = stringResource(R.string.book_untitled)
    val importUnsupportedNotice = stringResource(R.string.library_import_unsupported)
    val sketchSaveFailedNotice = stringResource(R.string.sketch_save_failed)

    val entries = remember(flowItems, folders, verifiedLockedIds, filter) {
        buildLibraryEntries(flowItems, folders, verifiedLockedIds, filter)
    }

    fun startImport(uri: Uri?, book: Boolean) {
        // The SAF bracket closes as soon as the result arrives; a locked vault drops the
        // URI without touching it (the picker may outlive the idle auto-lock).
        repository.endExternalActivity()
        if (uri == null || importing) return
        if (repository.status.value != VaultStatus.UNLOCKED) return
        importing = true
        notice = null
        scope.launch {
            try {
                val outcome = if (book) {
                    importBook(repository, context, uri, untitledBookTitle)
                } else {
                    importPdf(repository, context, uri)
                }
                notice = when (outcome) {
                    ImportOutcome.SUCCESS -> null
                    ImportOutcome.UNSUPPORTED -> importUnsupportedNotice
                    ImportOutcome.FAILED -> importFailedNotice
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = importFailedNotice
            } finally {
                importing = false
            }
        }
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        startImport(uri, book = false)
    }
    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        startImport(uri, book = true)
    }

    fun launchPicker(book: Boolean) {
        importMenuOpen = false
        if (importing) return
        if (repository.status.value != VaultStatus.UNLOCKED) return
        repository.beginExternalActivity()
        val launched = runCatching {
            if (book) {
                // Generic picker: Android's MIME database maps the book formats inconsistently
                // (an .epub/.cbz is frequently reported as application/zip or octet-stream and a
                // .fb2/.txt may map to nothing), so a narrowed filter makes valid books
                // unselectable. The content is the real validator (BookDetector/BookLoader):
                // an unreadable file is rejected and its placeholder item removed.
                bookPicker.launch(arrayOf("*/*"))
            } else {
                pdfPicker.launch(arrayOf("*/*"))
            }
        }
        if (launched.isFailure) repository.endExternalActivity()
    }

    /**
     * Creates, titles and opens a new standalone sketch.
     *
     * The item is created empty (repository `createItem`), titled with the next `Drawing N`
     * number and saved before the studio opens. If the save throws, the just-created row is
     * trashed exactly like the import failure path: a failed create never leaves an orphan row
     * on the shelf. The studio itself owns the encrypted vector + preview from then on.
     */
    fun startNewSketch() {
        importMenuOpen = false
        if (creatingSketch || importing) return
        if (repository.status.value != VaultStatus.UNLOCKED) return
        creatingSketch = true
        notice = null
        scope.launch {
            var createdId: String? = null
            var opened = false
            try {
                val created = repository.createItem(ItemKind.SKETCH_FILE)
                createdId = created.id
                // Count includes the just-created row, so the first drawing is "Drawing 1".
                val number = repository.items.value.count {
                    it.kind == ItemKind.SKETCH_FILE && !it.trashed
                }
                val saved = repository.saveItem(
                    created.copy(title = context.getString(R.string.sketch_new_name, number)),
                )
                openSketchId = saved.id
                opened = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = sketchSaveFailedNotice
            } finally {
                if (!opened) {
                    createdId?.let { id -> trashQuietly(repository, id) }
                }
                creatingSketch = false
            }
        }
    }

    LaunchedEffect(status) {
        if (status != VaultStatus.UNLOCKED) {
            openPdfId = null
            openBookId = null
            openSketchId = null
            previewTarget = null
            importing = false
            creatingSketch = false
        }
    }

    fun openEntry(entry: LibraryEntry) {
        when (entry.type) {
            LibraryEntryType.PDF -> openPdfId = entry.item.id
            LibraryEntryType.BOOK -> openBookId = entry.item.id
            LibraryEntryType.DRAWING -> {
                if (entry.item.kind == ItemKind.SKETCH_FILE) {
                    // A sketch is editable: open the studio, never the static preview.
                    openSketchId = entry.item.id
                } else if (entry.attachment != null) {
                    previewTarget = entry
                }
            }
        }
    }

    fun beginRename(entry: LibraryEntry) {
        renameTarget = entry.item
        renameInput = entry.item.title
    }

    BackHandler { onBack() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("library_screen"),
    ) {
        val pdfId = openPdfId
        val bookId = openBookId
        val sketchId = openSketchId
        when {
            pdfId != null -> PdfViewerScreen(
                repository = repository,
                itemId = pdfId,
                onClose = { openPdfId = null },
            )

            bookId != null -> BookReaderScreen(
                repository = repository,
                itemId = bookId,
                onClose = { openBookId = null },
            )

            sketchId != null -> SketchStudioScreen(
                repository = repository,
                itemId = sketchId,
                onClose = { openSketchId = null },
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = NoteZeroSpacing.s, end = NoteZeroSpacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NzIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_library_back),
                        onClick = onBack,
                        modifier = Modifier.testTag("library_back"),
                    )
                    Text(
                        text = stringResource(R.string.library_title),
                        style = NoteZeroType.Heading,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = NoteZeroSpacing.s),
                    )
                    Box {
                        NzIconButton(
                            icon = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.library_import),
                            onClick = { importMenuOpen = true },
                            enabled = !importing,
                            modifier = Modifier.testTag("library_import"),
                        )
                        DropdownMenu(
                            expanded = importMenuOpen,
                            onDismissRequest = { importMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.library_import_pdf),
                                        style = NoteZeroType.Callout,
                                    )
                                },
                                onClick = { launchPicker(book = false) },
                                modifier = Modifier.testTag("library_import_pdf"),
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.library_import_epub),
                                        style = NoteZeroType.Callout,
                                    )
                                },
                                onClick = { launchPicker(book = true) },
                                modifier = Modifier.testTag("library_import_epub"),
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.library_new_sketch),
                                        style = NoteZeroType.Callout,
                                    )
                                },
                                onClick = { startNewSketch() },
                                enabled = !creatingSketch && !importing,
                                modifier = Modifier
                                    .heightIn(min = NoteZeroSpacing.minTarget)
                                    .testTag("library_new_sketch"),
                            )
                        }
                    }
                }

                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NoteZeroSpacing.s, vertical = NoteZeroSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s),
                ) {
                    item(key = "library_filter_all") {
                        LibraryFilterChip(
                            label = stringResource(R.string.library_filter_all),
                            selected = filter == LibraryFilter.ALL,
                            tag = "library_filter_all",
                            onClick = { filter = LibraryFilter.ALL },
                        )
                    }
                    item(key = "library_filter_books") {
                        LibraryFilterChip(
                            label = stringResource(R.string.library_filter_books),
                            selected = filter == LibraryFilter.BOOKS,
                            tag = "library_filter_books",
                            onClick = { filter = LibraryFilter.BOOKS },
                        )
                    }
                    item(key = "library_filter_pdfs") {
                        LibraryFilterChip(
                            label = stringResource(R.string.library_filter_pdfs),
                            selected = filter == LibraryFilter.PDFS,
                            tag = "library_filter_pdfs",
                            onClick = { filter = LibraryFilter.PDFS },
                        )
                    }
                    item(key = "library_filter_drawings") {
                        LibraryFilterChip(
                            label = stringResource(R.string.library_filter_drawings),
                            selected = filter == LibraryFilter.DRAWINGS,
                            tag = "library_filter_drawings",
                            onClick = { filter = LibraryFilter.DRAWINGS },
                        )
                    }
                }

                if (importing) {
                    Text(
                        text = stringResource(R.string.library_importing),
                        style = NoteZeroType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.xs)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("library_importing"),
                    )
                }

                notice?.let { text ->
                    Text(
                        text = text,
                        style = NoteZeroType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.xs)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("library_notice"),
                    )
                }

                if (entries.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(NoteZeroSpacing.xxl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.library_empty_title),
                            style = NoteZeroType.Heading,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(NoteZeroSpacing.s))
                        Text(
                            text = stringResource(R.string.library_empty_message),
                            style = NoteZeroType.Callout,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("library_list"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = NoteZeroSpacing.m,
                            end = NoteZeroSpacing.m,
                            top = NoteZeroSpacing.xs,
                            bottom = if (filter == LibraryFilter.DRAWINGS) {
                                NoteZeroSpacing.xxxl + 72.dp
                            } else {
                                NoteZeroSpacing.xxxl
                            },
                        ),
                        verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
                    ) {
                        items(
                            items = entries,
                            key = { entry ->
                                "${entry.type.name}_${entry.item.id}_${entry.attachment?.id ?: "no-preview"}"
                            },
                        ) { entry ->
                            LibraryRow(
                                entry = entry,
                                onOpen = { openEntry(entry) },
                                onRename = { beginRename(entry) },
                                onLockToggle = {
                                    scope.launch {
                                        runCatching {
                                            repository.setItemLocked(entry.item.id, !entry.item.locked)
                                        }
                                    }
                                },
                                onTrash = {
                                    scope.launch { runCatching { repository.trashItem(entry.item.id) } }
                                },
                            )
                        }
                    }
                }
            }
        }

        // New-drawing action mirrored as a FAB while the Drawings shelf is open; the same
        // action also lives in the import menu for the other filters.
        if (pdfId == null && bookId == null && sketchId == null && previewTarget == null &&
            filter == LibraryFilter.DRAWINGS &&
            status == VaultStatus.UNLOCKED
        ) {
            NzFab(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.cd_library_new_sketch),
                onClick = { startNewSketch() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(NoteZeroSpacing.l)
                    .testTag("library_new_sketch_fab"),
            )
        }

        // Full-screen in-memory drawing preview: bytes are decoded from the vault into a
        // bitmap and dropped with the overlay. Nothing is ever written to a file or cache.
        val previewEntry = previewTarget
        if (previewEntry != null) {
            BackHandler { previewTarget = null }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .testTag("library_preview"),
            ) {
                when {
                    previewImage != null -> Image(
                        bitmap = previewImage!!,
                        contentDescription = stringResource(R.string.library_type_drawing),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(NoteZeroSpacing.l),
                    )

                    previewFailed -> Text(
                        text = stringResource(R.string.library_no_preview),
                        style = NoteZeroType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag("library_no_preview"),
                    )

                    else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                NzIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.common_close),
                    onClick = { previewTarget = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .testTag("library_preview_close"),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        LaunchedEffect(previewTarget) {
            val target = previewTarget ?: return@LaunchedEffect
            val attachment = target.attachment ?: return@LaunchedEffect
            previewImage = null
            previewFailed = false
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    repository.attachmentBytes(target.item.id, attachment.id)
                }.getOrNull()
            }
            val bitmap = if (bytes == null) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    AttachmentImages.decode(bytes, AttachmentImages.VIEWER_TARGET_PX)
                }
            }
            previewImage = bitmap?.asImageBitmap()
            previewFailed = bitmap == null
        }

        // Simple rename dialog reusing the notes rename label; only the encrypted item title
        // is changed (the attachment name stays import metadata).
        val rename = renameTarget
        if (rename != null) {
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                modifier = Modifier.testTag("library_rename_dialog"),
                title = {
                    Text(
                        text = stringResource(R.string.notes_rename_note),
                        style = NoteZeroType.Heading,
                    )
                },
                text = {
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it.take(MAX_TITLE_CHARS) },
                        singleLine = true,
                        label = {
                            Text(
                                text = stringResource(R.string.common_title),
                                style = NoteZeroType.Callout,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("library_rename_field"),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val trimmed = renameInput.trim()
                            renameTarget = null
                            scope.launch {
                                val fresh = repository.items.value.firstOrNull { it.id == rename.id }
                                    ?: return@launch
                                runCatching {
                                    repository.saveItem(
                                        fresh.copy(title = trimmed.ifBlank { rename.title }),
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag("library_rename_save"),
                    ) {
                        Text(stringResource(R.string.common_save), style = NoteZeroType.Button)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { renameTarget = null },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag("library_rename_cancel"),
                    ) {
                        Text(stringResource(R.string.common_cancel), style = NoteZeroType.Button)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                shape = NoteZeroShapes.sheet,
            )
        }
    }
}

/** One shelf filter chip; a 48dp target with the standard selected semantics. */
@Composable
private fun LibraryFilterChip(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = NoteZeroType.Caption) },
        modifier = Modifier
            .heightIn(min = NoteZeroSpacing.minTarget)
            .testTag(tag),
    )
}

/**
 * Distinguishes the supported e-book formats in the shelf's type label (EPUB/FB2/TXT/Comic).
 * Kept as a plain resource-id helper so the row can resolve it with `stringResource`.
 */
private fun bookFormatLabelRes(mimeType: String?): Int = when (mimeType) {
    "application/epub+zip" -> R.string.book_format_epub
    "application/x-fictionbook+xml" -> R.string.book_format_fb2
    "text/plain" -> R.string.book_format_txt
    "application/vnd.comicbook+zip", "application/zip", "application/x-cbz" -> R.string.book_format_cbz
    else -> R.string.library_type_book
}

/** One document row: title, type badge, progress and the overflow actions menu. */
@Composable
private fun LibraryRow(
    entry: LibraryEntry,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onLockToggle: () -> Unit,
    onTrash: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val item = entry.item
    val readingState = item.readingState
    // A standalone sketch row is a drawing too, but it is labelled as an editable sketch so the
    // user can tell it apart from the static drawings attached to notes.
    val isSketch = item.kind == ItemKind.SKETCH_FILE
    val typeLabel = when (entry.type) {
        LibraryEntryType.PDF -> stringResource(R.string.library_type_pdf)
        LibraryEntryType.BOOK -> stringResource(bookFormatLabelRes(entry.attachment?.mimeType))
        LibraryEntryType.DRAWING ->
            if (isSketch) {
                stringResource(R.string.library_type_sketch)
            } else {
                stringResource(R.string.library_type_drawing)
            }
    }
    val title = item.title.takeIf { it.isNotBlank() }
        ?: if (isSketch) {
            // A sketch without a title is still editable; it appears before any preview exists.
            stringResource(R.string.sketch_untitled)
        } else {
            entry.attachment?.displayName?.takeIf { it.isNotBlank() } ?: typeLabel
        }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("library_item")
            .clickable(onClick = onOpen),
        shape = NoteZeroShapes.row,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = NoteZeroSpacing.s, end = NoteZeroSpacing.xs, top = NoteZeroSpacing.s, bottom = NoteZeroSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = NoteZeroType.Body,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(NoteZeroSpacing.s))
                    Text(
                        text = typeLabel,
                        style = NoteZeroType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .testTag(
                                when (entry.type) {
                                    LibraryEntryType.PDF -> "library_type_pdf"
                                    LibraryEntryType.BOOK -> "library_type_book"
                                    LibraryEntryType.DRAWING ->
                                        if (isSketch) "library_type_sketch" else "library_type_drawing"
                                },
                            )
                            .semantics(mergeDescendants = true) {},
                    )
                }
                if (entry.type == LibraryEntryType.BOOK && readingState?.author != null) {
                    Text(
                        text = readingState.author,
                        style = NoteZeroType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.type != LibraryEntryType.DRAWING && readingState != null) {
                    LibraryProgress(entry = entry, readingState = readingState)
                }
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier
                        .size(NoteZeroSpacing.minTarget)
                        .testTag("library_actions"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.library_actions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_open), style = NoteZeroType.Callout) },
                        onClick = {
                            menuOpen = false
                            onOpen()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.notes_rename_note), style = NoteZeroType.Callout) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (item.locked) {
                                    stringResource(R.string.notes_remove_lock)
                                } else {
                                    stringResource(R.string.notes_lock_note)
                                },
                                style = NoteZeroType.Callout,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onLockToggle()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.notes_move_to_trash),
                                style = NoteZeroType.Callout,
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onTrash()
                        },
                    )
                }
            }
        }
    }
}

/** Progress line for a read PDF/book: page/chapter line, bar and percent. */
@Composable
private fun LibraryProgress(entry: LibraryEntry, readingState: ReadingState) {
    val total = readingState.total
    if (total <= 0) {
        Text(
            text = stringResource(R.string.library_continue),
            style = NoteZeroType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .testTag("library_continue")
                .semantics(mergeDescendants = true) {},
        )
        return
    }
    val position = (readingState.position + 1).coerceIn(1, total)
    val fraction = position.toFloat() / total
    val percent = (fraction * 100f).toInt().coerceIn(0, 100)
    // Comics are paged like PDFs, so their position must read "Page x of y" (books use chapters).
    val paged = entry.type == LibraryEntryType.PDF ||
        (entry.type == LibraryEntryType.BOOK &&
            bookFormatLabelRes(entry.attachment?.mimeType) == R.string.book_format_cbz)
    Text(
        text = if (paged) {
            stringResource(R.string.library_page_progress, position, total)
        } else {
            stringResource(R.string.library_chapter_progress, position, total)
        },
        style = NoteZeroType.Caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .testTag(if (paged) "library_page_progress" else "library_chapter_progress")
            // The row is clickable and therefore merges its descendants; this node merges
            // itself so its tag survives in the merged semantics tree (tests/a11y can find it).
            .semantics(mergeDescendants = true) {},
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NoteZeroSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .weight(1f)
                .height(4.dp),
        )
        Spacer(Modifier.width(NoteZeroSpacing.s))
        Text(
            text = stringResource(R.string.library_progress_percent, percent),
            style = NoteZeroType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .testTag("library_progress_percent")
                .semantics(mergeDescendants = true) {},
        )
    }
}

// ---- Pure helpers ---------------------------------------------------------

/** Builds the filtered, newest-first shelf from the decrypted index (fail-closed). */
internal fun buildLibraryEntries(
    items: List<Item>,
    folders: List<Folder>,
    verifiedLockedIds: Set<String>,
    filter: LibraryFilter,
): List<LibraryEntry> {
    val hiddenFolderIds = LockVisibility.hiddenFolderIds(folders, verifiedLockedIds)
    val entries = ArrayList<LibraryEntry>()
    for (item in items) {
        if (item.trashed) continue
        if (item.folderId in hiddenFolderIds) continue
        if (LockVisibility.isItemMasked(item, verifiedLockedIds)) continue
        when (item.kind) {
            ItemKind.PDF_FILE -> item.attachments
                .firstOrNull { it.mimeType == PDF_MIME }
                ?.let { entries += LibraryEntry(item, it, LibraryEntryType.PDF) }

            ItemKind.BOOK_FILE -> {
                // The format is content-detected at import and recorded as the attachment MIME;
                // a BOOK_FILE item has exactly one book attachment (prefer a known book MIME).
                val attachment = item.attachments.firstOrNull { it.mimeType in BOOK_MIME_TYPES }
                    ?: item.attachments.firstOrNull()
                attachment?.let { entries += LibraryEntry(item, it, LibraryEntryType.BOOK) }
            }

            ItemKind.NOTE -> item.attachments
                .filter {
                    it.mimeType == DrawingFormat.MIME_TYPE &&
                        it.displayName.startsWith(DrawingFormat.FILE_PREFIX)
                }
                .forEach { entries += LibraryEntry(item, it, LibraryEntryType.DRAWING) }

            ItemKind.SKETCH_FILE -> entries += LibraryEntry(
                item = item,
                // The flattened preview is optional: the sketch is listed (and editable) from
                // the moment its encrypted vector is created, before any preview is rendered.
                attachment = item.attachments.firstOrNull { it.mimeType == PNG_MIME },
                type = LibraryEntryType.DRAWING,
            )

            ItemKind.CODE_FILE -> Unit
        }
    }
    val filtered = when (filter) {
        LibraryFilter.ALL -> entries
        LibraryFilter.BOOKS -> entries.filter { it.type == LibraryEntryType.BOOK }
        LibraryFilter.PDFS -> entries.filter { it.type == LibraryEntryType.PDF }
        LibraryFilter.DRAWINGS -> entries.filter { it.type == LibraryEntryType.DRAWING }
    }
    return filtered.sortedByDescending { it.updatedAt }
}

/** File-name → display title (extension dropped, fallback when blank). */
internal fun titleFromFileName(fileName: String, fallback: String): String {
    val base = fileName.substringBeforeLast('.', fileName).trim()
    return base.ifBlank { fallback }
}

/** Non-secret import outcome; the UI maps it to a string resource. */
internal enum class ImportOutcome { SUCCESS, UNSUPPORTED, FAILED }

/** Metadata copied out of a loaded book before its engine handle is closed. */
private class ImportedBookData(
    val title: String,
    val author: String?,
    val language: String?,
    val total: Int,
)

/** Imports one SAF URI as an encrypted PDF_FILE item; trashes the item on any failure. */
private suspend fun importPdf(
    repository: NoteRepository,
    context: Context,
    uri: Uri,
): ImportOutcome {
    val displayName = resolveDisplayName(context, uri, FALLBACK_PDF_NAME)
    val created = repository.createItem(ItemKind.PDF_FILE)
    var succeeded = false
    try {
        val imported = withContext(Dispatchers.IO) {
            val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                ?: return@withContext null
            input.use {
                repository.importAttachment(created.id, displayName, PDF_MIME, it)
            }
        }
        if (imported !is AttachmentImport.Success) return ImportOutcome.FAILED
        repository.saveItem(created.copy(title = titleFromFileName(displayName, displayName)))
        succeeded = true
        return ImportOutcome.SUCCESS
    } finally {
        if (!succeeded) trashQuietly(repository, created.id)
    }
}

/** Imports one SAF URI as an encrypted BOOK_FILE item of any supported format. */
private suspend fun importBook(
    repository: NoteRepository,
    context: Context,
    uri: Uri,
    untitledBookTitle: String,
): ImportOutcome {
    val displayName = resolveDisplayName(context, uri, FALLBACK_BOOK_NAME)
    // Read the bytes before creating the item: the content decides the format and the stored
    // MIME, and an unreadable/oversized stream must not leave a row behind. The attachment
    // limit matches what the repository would enforce anyway, so a huge pick is rejected
    // before it can be buffered.
    val bytes = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                readBoundedBytes(input, AttachmentLimits.MAX_ATTACHMENT_BYTES)
            }
        }.getOrNull()
    } ?: return ImportOutcome.FAILED
    return withContext(Dispatchers.IO) {
        importBookFromBytes(repository, displayName, bytes, untitledBookTitle)
    }
}

/**
 * Testable import core (no SAF Context): detects the format of in-memory [bytes], derives the
 * title/author/language with [BookLoader], writes the encrypted attachment with the detected
 * MIME and stores the title plus encrypted [ReadingState] (author/language/total).
 *
 * An unsupported/corrupt/DRM-protected file returns [ImportOutcome.UNSUPPORTED] and the
 * just-created item is trashed, exactly like a failed attachment write: the shelf never shows
 * an orphan row. Unknown title metadata falls back to the cleaned SAF display name and then to
 * [UNTITLED_BOOK_TITLE]; the file extension is never trusted.
 */
internal suspend fun importBookFromBytes(
    repository: NoteRepository,
    displayName: String,
    bytes: ByteArray,
    untitledBookTitle: String,
): ImportOutcome {
    val created = repository.createItem(ItemKind.BOOK_FILE)
    var succeeded = false
    try {
        val fallbackName = titleFromFileName(displayName, "").ifBlank { untitledBookTitle }
        val detected = BookDetector.detect(bytes)
        val loaded = BookLoader.load(bytes, fallbackName)
        if (detected == null || loaded == null) {
            loaded?.closeBook()
            return ImportOutcome.UNSUPPORTED
        }
        val metadata = try {
            readBookMetadata(loaded, fallbackName)
        } finally {
            loaded.closeBook()
        }

        val imported = repository.importAttachment(
            created.id,
            displayName,
            detected.mimeType.ifBlank { FALLBACK_BOOK_MIME },
            ByteArrayInputStream(bytes),
        )
        if (imported !is AttachmentImport.Success) return ImportOutcome.FAILED

        // Re-read the item after the attachment write: the attachment list is repository-owned
        // (a stale copy would drop it on repository implementations that don't merge it).
        val withAttachment = repository.items.value.firstOrNull { it.id == created.id } ?: created
        repository.saveItem(withAttachment.copy(title = metadata.title))
        repository.saveReadingState(
            created.id,
            ReadingState(
                author = metadata.author,
                language = metadata.language,
                total = metadata.total,
            ),
        )
        succeeded = true
        return ImportOutcome.SUCCESS
    } finally {
        if (!succeeded) trashQuietly(repository, created.id)
    }
}

/** Copies title/author/language/total out of a loaded book before the engine handle closes. */
private fun readBookMetadata(loaded: LoadedBook, fallbackName: String): ImportedBookData =
    when (loaded) {
        is LoadedBook.Reflowable -> ImportedBookData(
            title = loaded.content.title.trim().ifBlank { fallbackName },
            author = sanitizeMetadata(loaded.content.author),
            language = sanitizeMetadata(loaded.content.language),
            total = loaded.content.chapters.size,
        )

        is LoadedBook.Comic -> ImportedBookData(
            // CBZ carries no metadata: the item/file display name is the only title source.
            title = loaded.comic.title.trim().ifBlank { fallbackName },
            author = null,
            language = null,
            total = loaded.comic.pageCount,
        )
    }

/** Closes whichever engine handle a loaded book owns (both close methods are idempotent). */
private fun LoadedBook.closeBook() {
    when (this) {
        is LoadedBook.Reflowable -> content.close()
        is LoadedBook.Comic -> comic.close()
    }
}

/**
 * Reads [input] fully, returning null instead of buffering past [maxBytes] (no allocation past
 * the cap). Mirrors the repository's own attachment size limit so ImportOutcome.FAILED is
 * reported for an oversized pick instead of an OOM.
 */
private fun readBoundedBytes(input: InputStream, maxBytes: Long): ByteArray? {
    if (maxBytes <= 0L || maxBytes > Int.MAX_VALUE) return null
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

/** Best-effort removal of the just-created item, even if the coroutine was cancelled. */
private suspend fun trashQuietly(repository: NoteRepository, itemId: String) {
    withContext(NonCancellable) {
        runCatching { repository.trashItem(itemId) }
    }
}

/** Reads the SAF display name from OpenableColumns; never a path, never logged. */
private fun resolveDisplayName(context: Context, uri: Uri, fallback: String): String {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0 || !cursor.moveToFirst()) {
                null
            } else {
                cursor.getString(index)?.trim()?.takeIf { it.isNotEmpty() }
            }
        } ?: fallback
    } catch (_: Exception) {
        fallback
    }
}

/** Book metadata is user content: bound it to what ReadingState accepts. */
private fun sanitizeMetadata(value: String?): String? =
    value?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_METADATA_CHARS)

private const val MAX_TITLE_CHARS = 512
