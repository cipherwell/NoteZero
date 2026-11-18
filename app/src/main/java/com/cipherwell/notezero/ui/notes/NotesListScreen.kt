package com.cipherwell.notezero.ui.notes

import com.cipherwell.notezero.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.code.CodeEditorScreen
import com.cipherwell.notezero.ui.code.CodeTree
import com.cipherwell.notezero.ui.code.NewCodeFileDialog
import com.cipherwell.notezero.ui.locks.LockBiometricPrompt
import com.cipherwell.notezero.ui.locks.LockReauthController
import com.cipherwell.notezero.ui.locks.LockTargetKind
import com.cipherwell.notezero.ui.locks.LockedTarget
import com.cipherwell.notezero.ui.locks.ReauthDialog
import com.cipherwell.notezero.ui.locks.rememberLockReauthController
import com.cipherwell.notezero.ui.components.common.NzFab
import com.cipherwell.notezero.ui.pdf.PdfViewerScreen
import com.cipherwell.notezero.ui.reader.BookReaderScreen
import com.cipherwell.notezero.ui.notes.components.FolderRow
import com.cipherwell.notezero.ui.notes.components.MoveToFolderDialog
import com.cipherwell.notezero.ui.notes.components.NoteRow
import com.cipherwell.notezero.ui.notes.components.NotesConfirmDialog
import com.cipherwell.notezero.ui.notes.components.NotesEmptyState
import com.cipherwell.notezero.ui.notes.components.NotesPillNav
import com.cipherwell.notezero.ui.notes.components.SectionHeader
import com.cipherwell.notezero.ui.notes.components.TagChip
import com.cipherwell.notezero.ui.notes.components.TextInputDialog
import com.cipherwell.notezero.ui.notes.components.TrashRow
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import kotlinx.coroutines.launch

/**
 * Notes MVP home: notes list (pinned / favourites / recent), nested folders, tag chips,
 * smart search, trash and quick-note capture. Opening a note swaps this screen for
 * [com.cipherwell.notezero.ui.editor.NoteEditorScreen]; back returns here.
 *
 * Rendered by ui-shell's AppRoot when the vault is UNLOCKED.
 */
@Composable
fun NotesListScreen(
    repository: NoteRepository,
    onOpenSettings: () -> Unit,
    onOpenCode: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenWorkspace: () -> Unit = {},
    /** Injectable only for tests; production uses the device presence prompt. */
    biometricPrompt: LockBiometricPrompt? = null,
    /** Injectable only for tests; production uses the SAF `CreateDocument` target. */
    noteExportTarget: NoteExportTarget? = null,
    /** Quick-capture request (launcher shortcut): create and open a new note once. */
    startNewNote: Boolean = false,
    onStartNewNoteConsumed: () -> Unit = {},
    /** Workspace deep link: open this note/code file once, then acknowledge it. */
    requestedItemId: String? = null,
    onRequestedItemConsumed: () -> Unit = {},
) {
    val viewModel: NotesViewModel = viewModel(factory = NotesViewModelFactory(repository))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val createError by viewModel.createError.collectAsStateWithLifecycle()
    var editorItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var pdfItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var codeItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var bookItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorOpenDrawing by rememberSaveable { mutableStateOf(false) }

    // Workspace deep link: a note/code file tapped in the Workspace opens in this shell.
    LaunchedEffect(requestedItemId) {
        val id = requestedItemId ?: return@LaunchedEffect
        when (repository.items.value.firstOrNull { it.id == id }?.kind) {
            ItemKind.NOTE -> editorItemId = id
            ItemKind.CODE_FILE -> codeItemId = id
            else -> Unit
        }
        onRequestedItemConsumed()
    }
    var showCreateSheet by rememberSaveable { mutableStateOf(false) }
    var showNewCodeDialog by rememberSaveable { mutableStateOf(false) }
    val createScope = rememberCoroutineScope()
    val folderForCreate = if (state.section == NotesSection.FOLDERS) state.currentFolderId else null

    // Quick capture (launcher shortcut / pending request after unlock): create the note only
    // through the normal unlocked repository path, then open the editor.
    LaunchedEffect(startNewNote) {
        if (!startNewNote) return@LaunchedEffect
        val created = viewModel.createNote(folderId = null)
        onStartNewNoteConsumed()
        if (created != null) editorItemId = created.id
    }
    // One re-auth flow for the whole list: masked rows and folder entries share it, and
    // it never stores content, only the pending target id until the repository verifies.
    val reauth = rememberLockReauthController(repository, biometricPrompt)

    val openPdfId = pdfItemId
    if (openPdfId != null) {
        PdfViewerScreen(
            repository = repository,
            itemId = openPdfId,
            onClose = { pdfItemId = null },
        )
        return
    }

    val openCodeId = codeItemId
    if (openCodeId != null) {
        CodeEditorScreen(
            repository = repository,
            itemId = openCodeId,
            onBack = { codeItemId = null },
            biometricPrompt = biometricPrompt,
        )
        return
    }

    val openBookId = bookItemId
    if (openBookId != null) {
        BookReaderScreen(
            repository = repository,
            itemId = openBookId,
            onClose = { bookItemId = null },
        )
        return
    }

    val openItemId = editorItemId
    if (openItemId != null) {
        com.cipherwell.notezero.ui.editor.NoteEditorScreen(
            repository = repository,
            itemId = openItemId,
            onClose = { editorItemId = null },
            biometricPrompt = biometricPrompt,
            openDrawingOnStart = editorOpenDrawing,
            onOpenDrawingConsumed = { editorOpenDrawing = false },
        )
        return
    }

    NotesListContent(
        state = state,
        viewModel = viewModel,
        reauth = reauth,
        onOpenCode = onOpenCode,
        onOpenLibrary = onOpenLibrary,
        onOpenWorkspace = onOpenWorkspace,
        onOpenEditor = { editorItemId = it },
        onOpenPdf = { pdfItemId = it },
        onOpenCodeItem = { codeItemId = it },
        onOpenBook = { bookItemId = it },
        onShowCreateSheet = { showCreateSheet = true },
        onOpenSettings = onOpenSettings,
        exportTarget = noteExportTarget ?: rememberSafNoteExportTarget(repository),
    )

    if (showCreateSheet) {
        CreateSheet(
            onDismiss = { showCreateSheet = false },
            onCreateNote = {
                showCreateSheet = false
                createScope.launch {
                    val created = viewModel.createNote(folderForCreate)
                    if (created != null) editorItemId = created.id
                }
            },
            onCreateDrawing = {
                showCreateSheet = false
                createScope.launch {
                    val created = viewModel.createNote(folderForCreate)
                    if (created != null) {
                        editorOpenDrawing = true
                        editorItemId = created.id
                    }
                }
            },
            onCreateCode = {
                showCreateSheet = false
                showNewCodeDialog = true
            },
            onCreatePdf = {
                showCreateSheet = false
                createScope.launch {
                    val created = viewModel.createPdf(folderForCreate)
                    if (created != null) pdfItemId = created.id
                }
            },
        )
    }

    if (showNewCodeDialog) {
        NewCodeFileDialog(
            title = stringResource(R.string.create_code),
            confirmLabel = stringResource(R.string.common_create),
            onDismiss = { showNewCodeDialog = false },
            validate = { name -> CodeTree.validateName(CodeTree.normalizeNewFileName(name)) },
            onConfirm = { name ->
                showNewCodeDialog = false
                createScope.launch {
                    val created = viewModel.createCodeFile(name, folderForCreate)
                    if (created != null) codeItemId = created.id
                }
            },
        )
    }

    if (createError) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCreateError,
            title = { Text(stringResource(R.string.create_sheet_title), style = NoteZeroType.Heading) },
            text = { Text(stringResource(R.string.create_failed), style = NoteZeroType.Body) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissCreateError) {
                    Text(stringResource(R.string.common_close), style = NoteZeroType.Button)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = NoteZeroShapes.sheet,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesListContent(
    state: NotesUiState,
    viewModel: NotesViewModel,
    reauth: LockReauthController,
    onOpenEditor: (String) -> Unit,
    onOpenPdf: (String) -> Unit,
    onOpenCodeItem: (String) -> Unit,
    onOpenBook: (String) -> Unit,
    onShowCreateSheet: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCode: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenWorkspace: () -> Unit,
    exportTarget: NoteExportTarget,
) {
    var renameTarget by remember { mutableStateOf<Item?>(null) }
    var moveTarget by remember { mutableStateOf<Item?>(null) }
    var exportItem by remember { mutableStateOf<Item?>(null) }
    var exportFormat by remember { mutableStateOf<NoteExportFormat?>(null) }
    var renameFolderTarget by remember { mutableStateOf<Folder?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<Folder?>(null) }
    var newFolderParent by remember { mutableStateOf<String?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()

    val isTrash = state.section == NotesSection.TRASH
    val codeWorkspaceLabel = stringResource(R.string.cd_notes_code_workspace)
    val libraryLabel = stringResource(R.string.cd_library)
    val workspaceLabel = stringResource(R.string.cd_workspace)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    BackHandler(enabled = isTrash) { viewModel.leaveTrash() }
    BackHandler(enabled = !isTrash && state.section == NotesSection.FOLDERS && state.currentFolderId != null) {
        viewModel.closeFolder()
    }

    // Local LazyListScope extension so every "section" of the list emits identical rows.
    fun LazyListScope.noteRows(
        rows: List<NoteRowModel>,
        keyPrefix: String,
    ) {
        items(rows, key = { "$keyPrefix${it.item.id}" }) { row ->
            NoteRow(
                row = row,
                onClick = {
                    val open = { id: String ->
                        when (row.item.kind) {
                            ItemKind.PDF_FILE -> onOpenPdf(id)
                            ItemKind.CODE_FILE -> onOpenCodeItem(id)
                            ItemKind.BOOK_FILE -> onOpenBook(id)
                            ItemKind.SKETCH_FILE -> onOpenLibrary()
                            else -> onOpenEditor(id)
                        }
                    }
                    if (row.masked) {
                        // Tapping a mask must never open the content: re-authenticate
                        // first, then open the revealed item.
                        reauth.request(LockedTarget(row.item.id, LockTargetKind.NOTE)) {
                            open(row.item.id)
                        }
                    } else {
                        open(row.item.id)
                    }
                },
                onTogglePin = { viewModel.togglePinned(row.item) },
                onToggleFavorite = { viewModel.toggleFavorite(row.item) },
                onRename = { renameTarget = row.item },
                onMove = { moveTarget = row.item },
                onTrash = { viewModel.moveToTrash(row.item) },
                onLockToggle = {
                    when {
                        !row.item.locked -> viewModel.setItemLocked(row.item, true)
                        // Removing a lock is a content operation on hidden data: require
                        // re-auth, then clear the flag.
                        row.masked -> reauth.request(LockedTarget(row.item.id, LockTargetKind.NOTE)) {
                            viewModel.setItemLocked(row.item, false)
                        }
                        else -> viewModel.setItemLocked(row.item, false)
                    }
                },
                onExport = { exportItem = row.item },
            )
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                LargeTopAppBar(
                    title = { Text(text = titleFor(state), style = NoteZeroType.LargeTitle) },
                    navigationIcon = {
                        if (isTrash) {
                            IconButton(
                                onClick = { viewModel.leaveTrash() },
                                modifier = Modifier.testTag("notes_back"),
                            ) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back_to_notes))
                            }
                        } else if (state.section == NotesSection.FOLDERS && state.currentFolderId != null) {
                            IconButton(
                                onClick = { viewModel.closeFolder() },
                                modifier = Modifier.testTag("notes_back"),
                            ) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back_to_parent_folder))
                            }
                        }
                    },
                    actions = {
                        if (isTrash) {
                            TextButton(
                                onClick = { confirmEmptyTrash = true },
                                modifier = Modifier.testTag("trash_empty_button"),
                            ) { Text(stringResource(R.string.notes_empty_trash)) }
                        } else {
                            IconButton(
                                onClick = onOpenWorkspace,
                                modifier = Modifier
                                    .testTag("workspace_button")
                                    .semantics { contentDescription = workspaceLabel },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_workspace),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = onOpenLibrary,
                                modifier = Modifier
                                    .testTag("library_button")
                                    .semantics { contentDescription = libraryLabel },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_library),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = onOpenCode,
                                modifier = Modifier
                                    .testTag("nav_code")
                                    .semantics { contentDescription = codeWorkspaceLabel },
                            ) {
                                Text(
                                    text = "</>",
                                    style = NoteZeroType.Caption.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { viewModel.showTrash() },
                                modifier = Modifier.testTag("trash_button"),
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_notes_trash))
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                // Non-secret export status/error line: it sits under the app bar so it
                // never covers note rows and disappears when dismissed.
                NoteExportStatusBar(
                    status = exportStatus,
                    onDismiss = viewModel::dismissExportStatus,
                )
            }
        },
        floatingActionButton = {
            if (!isTrash) {
                NzFab(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cd_notes_create),
                    onClick = onShowCreateSheet,
                    modifier = Modifier.testTag("new_note_button"),
                )
            }
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = NoteZeroSpacing.xxl, vertical = NoteZeroSpacing.s),
                contentAlignment = Alignment.Center,
            ) {
                NotesPillNav(
                    current = state.section,
                    onSectionSelected = { section ->
                        when (section) {
                            NotesSection.TRASH -> viewModel.showTrash()
                            else -> viewModel.setSection(section)
                        }
                    },
                    onOpenSettings = onOpenSettings,
                )
            }
        },
    ) { innerPadding ->
        when (state.section) {
            NotesSection.NOTES -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("notes_list"),
                contentPadding = PaddingValues(
                    start = NoteZeroSpacing.l,
                    end = NoteZeroSpacing.l,
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (state.totalNotes > 0) {
                    item(key = "filters") {
                        FiltersRow(
                            state = state,
                            onToggleFavorites = viewModel::toggleFavoritesOnly,
                            onToggleTag = viewModel::toggleTag,
                        )
                    }
                }
                if (state.hasNoNotes) {
                    item(key = "empty") {
                        NotesEmptyState(
                            title = stringResource(R.string.notes_empty_title),
                            subtitle = stringResource(R.string.notes_empty_subtitle),
                        )
                    }
                }
                if (state.pinned.isNotEmpty()) {
                    item(key = "header_pinned") { SectionHeader(stringResource(R.string.notes_section_pinned), state.pinned.size) }
                    noteRows(state.pinned, "pinned_")
                }
                if (state.favorites.isNotEmpty()) {
                    item(key = "header_favorites") { SectionHeader(stringResource(R.string.notes_section_favourites), state.favorites.size) }
                    noteRows(state.favorites, "favorite_")
                }
                if (state.recent.isNotEmpty()) {
                    item(key = "header_recent") { SectionHeader(stringResource(R.string.notes_section_recent), state.recent.size) }
                    noteRows(state.recent, "recent_")
                }
            }

            NotesSection.FOLDERS -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("folders_list"),
                contentPadding = PaddingValues(
                    start = NoteZeroSpacing.l,
                    end = NoteZeroSpacing.l,
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
            ) {
                item(key = "breadcrumb") {
                    FolderBreadcrumb(
                        breadcrumb = state.breadcrumb,
                        onOpenFolder = { folderId -> viewModel.openFolder(folderId) },
                        onNewFolder = {
                            newFolderParent = state.currentFolderId
                            showNewFolderDialog = true
                        },
                    )
                }
                if (state.folders.isEmpty() && state.folderItems.isEmpty()) {
                    item(key = "folders_empty") {
                        NotesEmptyState(
                            title = stringResource(R.string.notes_folders_empty_title),
                            subtitle = stringResource(R.string.notes_folders_empty_subtitle),
                        )
                    }
                }
                items(state.folders, key = { "folder_${it.folder.id}" }) { row ->
                    FolderRow(
                        row = row,
                        onClick = {
                            if (row.masked) {
                                reauth.request(LockedTarget(row.folder.id, LockTargetKind.FOLDER)) {
                                    viewModel.openFolder(row.folder.id)
                                }
                            } else {
                                viewModel.openFolder(row.folder.id)
                            }
                        },
                        onRename = { renameFolderTarget = row.folder },
                        onDelete = { deleteFolderTarget = row.folder },
                        onLockToggle = {
                            when {
                                !row.folder.locked -> viewModel.setFolderLocked(row.folder, true)
                                row.masked -> reauth.request(
                                    LockedTarget(row.folder.id, LockTargetKind.FOLDER),
                                ) {
                                    viewModel.setFolderLocked(row.folder, false)
                                }
                                else -> viewModel.setFolderLocked(row.folder, false)
                            }
                        },
                    )
                }
                if (state.folderItems.isNotEmpty()) {
                    item(key = "folder_items_header") {
                        SectionHeader(stringResource(R.string.notes_folder_items_header), state.folderItems.size)
                    }
                    noteRows(state.folderItems, "folder_")
                }
            }

            NotesSection.SEARCH -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("notes_search"),
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.s)
                        .testTag("search_field"),
                    placeholder = { Text(stringResource(R.string.notes_search_placeholder), style = NoteZeroType.Callout) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(
                                onClick = viewModel::clearQuery,
                                modifier = Modifier.testTag("search_clear"),
                            ) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_notes_clear_search))
                            }
                        }
                    },
                    singleLine = true,
                )
                if (state.tags.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = NoteZeroSpacing.l),
                        horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s),
                    ) {
                        items(state.tags, key = { "search_tag_$it" }) { tag ->
                            TagChip(
                                tag = tag,
                                selected = state.activeTag?.equals(tag, ignoreCase = true) == true,
                                onClick = { viewModel.toggleTag(tag) },
                            )
                        }
                    }
                }
                when {
                    state.query.isBlank() -> NotesEmptyState(
                        title = stringResource(R.string.notes_search_empty_title),
                        subtitle = stringResource(R.string.notes_search_empty_subtitle),
                    )

                    state.searchResults.isEmpty() -> NotesEmptyState(
                        title = stringResource(R.string.notes_search_no_matches),
                        subtitle = stringResource(R.string.notes_search_no_matches_subtitle, state.query.trim()),
                    )

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("search_results"),
                        contentPadding = PaddingValues(
                            start = NoteZeroSpacing.l,
                            end = NoteZeroSpacing.l,
                            bottom = innerPadding.calculateBottomPadding() + 96.dp,
                        ),
                    ) {
                        item(key = "results_header") {
                            SectionHeader(stringResource(R.string.notes_section_results), state.searchResults.size)
                        }
                        noteRows(state.searchResults, "search_")
                    }
                }
            }

            NotesSection.TRASH -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("trash_list"),
                contentPadding = PaddingValues(
                    start = NoteZeroSpacing.l,
                    end = NoteZeroSpacing.l,
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 48.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
            ) {
                if (state.trash.isEmpty()) {
                    item(key = "trash_empty") {
                        NotesEmptyState(
                            title = stringResource(R.string.notes_trash_empty_title),
                            subtitle = stringResource(R.string.notes_trash_empty_subtitle),
                        )
                    }
                } else {
                    item(key = "trash_header") { SectionHeader(stringResource(R.string.trash), state.trash.size) }
                    items(state.trash, key = { "trash_${it.item.id}" }) { row ->
                        TrashRow(
                            row = row,
                            onRestore = { viewModel.restoreFromTrash(row.item) },
                            onDeleteForever = { viewModel.deleteForever(row.item) },
                        )
                    }
                }
            }
        }
    }

    // ---- Dialogs -------------------------------------------------------------

    renameTarget?.let { item ->
        TextInputDialog(
            title = stringResource(R.string.notes_rename_note),
            label = stringResource(R.string.notes_title_label),
            initialValue = item.title,
            confirmLabel = stringResource(R.string.common_rename),
            dialogTestTag = "rename_note_dialog",
            onDismiss = { renameTarget = null },
            onConfirm = { value ->
                viewModel.renameItem(item, value)
                renameTarget = null
            },
        )
    }

    moveTarget?.let { item ->
        MoveToFolderDialog(
            folders = state.allFolders,
            onDismiss = { moveTarget = null },
            onSelect = { folderId ->
                viewModel.moveItem(item, folderId)
                moveTarget = null
            },
        )
    }

    renameFolderTarget?.let { folder ->
        TextInputDialog(
            title = stringResource(R.string.notes_rename_folder),
            label = stringResource(R.string.common_folder_name),
            initialValue = folder.name,
            confirmLabel = stringResource(R.string.common_rename),
            dialogTestTag = "rename_folder_dialog",
            onDismiss = { renameFolderTarget = null },
            onConfirm = { value ->
                viewModel.renameFolder(folder, value)
                renameFolderTarget = null
            },
        )
    }

    deleteFolderTarget?.let { folder ->
        NotesConfirmDialog(
            title = stringResource(R.string.notes_delete_folder_title),
            message = stringResource(R.string.notes_delete_folder_message),
            confirmLabel = stringResource(R.string.notes_delete_folder),
            destructive = true,
            dialogTestTag = "delete_folder_dialog",
            onDismiss = { deleteFolderTarget = null },
            onConfirm = {
                viewModel.deleteFolder(folder)
                deleteFolderTarget = null
            },
        )
    }

    if (showNewFolderDialog) {
        TextInputDialog(
            title = stringResource(R.string.notes_new_folder),
            label = stringResource(R.string.common_folder_name),
            initialValue = "",
            confirmLabel = stringResource(R.string.common_create),
            dialogTestTag = "new_folder_dialog",
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                viewModel.createFolder(name, newFolderParent)
                showNewFolderDialog = false
            },
        )
    }

    if (confirmEmptyTrash) {
        NotesConfirmDialog(
            title = stringResource(R.string.notes_empty_trash_title),
            message = stringResource(R.string.notes_empty_trash_message),
            confirmLabel = stringResource(R.string.notes_empty_trash_confirm),
            destructive = true,
            dialogTestTag = "empty_trash_dialog",
            onDismiss = { confirmEmptyTrash = false },
            onConfirm = {
                viewModel.emptyTrash()
                confirmEmptyTrash = false
            },
        )
    }

    // Export: format choice first, then the explicit UNENCRYPTED-copy warning. SAF is only
    // launched after `note_export_confirm`; cancelling either dialog writes nothing.
    val pendingExportItem = exportItem
    if (pendingExportItem != null) {
        val pendingFormat = exportFormat
        if (pendingFormat == null) {
            NoteExportFormatDialog(
                onDismiss = { exportItem = null },
                onSelect = { format -> exportFormat = format },
            )
        } else {
            NoteExportWarningDialog(
                onDismiss = {
                    exportItem = null
                    exportFormat = null
                },
                onConfirm = {
                    exportItem = null
                    exportFormat = null
                    viewModel.exportNote(pendingExportItem, pendingFormat, exportTarget)
                },
            )
        }
    }

    // Master-password fallback for locked notes/folders (biometric prompt is tried first).
    ReauthDialog(reauth)
}

@Composable
private fun FiltersRow(
    state: NotesUiState,
    onToggleFavorites: () -> Unit,
    onToggleTag: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = NoteZeroSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s),
    ) {
        item(key = "favorites_filter") {
            FilterChip(
                selected = state.favoritesOnly,
                onClick = onToggleFavorites,
                modifier = Modifier.testTag("favorites_filter"),
                label = { Text(stringResource(R.string.notes_section_favourites), style = NoteZeroType.Caption) },
            )
        }
        items(state.tags, key = { "filter_tag_$it" }) { tag ->
            TagChip(
                tag = tag,
                selected = state.activeTag?.equals(tag, ignoreCase = true) == true,
                onClick = { onToggleTag(tag) },
            )
        }
    }
}

@Composable
private fun FolderBreadcrumb(
    breadcrumb: List<Folder>,
    onOpenFolder: (String?) -> Unit,
    onNewFolder: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("folder_breadcrumb"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onOpenFolder(null) },
                modifier = Modifier.testTag("folder_root"),
            ) {
                Text(
                    if (breadcrumb.isEmpty()) {
                        stringResource(R.string.notes_all_folders)
                    } else {
                        stringResource(R.string.notes_nav_folders)
                    },
                    style = NoteZeroType.Callout,
                )
            }
            breadcrumb.forEachIndexed { index, folder ->
                Text(
                    text = "›",
                    style = NoteZeroType.Callout,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val isCurrent = index == breadcrumb.lastIndex
                TextButton(
                    onClick = { onOpenFolder(folder.id) },
                    enabled = !isCurrent,
                ) {
                    Text(
                        text = folder.name,
                        style = NoteZeroType.Callout,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(NoteZeroSpacing.s))
            TextButton(
                onClick = onNewFolder,
                modifier = Modifier.testTag("new_folder_button"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(NoteZeroSpacing.xxs))
                Text(stringResource(R.string.notes_new_folder), style = NoteZeroType.Callout)
            }
        }
    }
}

@Composable
private fun titleFor(state: NotesUiState): String = when (state.section) {
    NotesSection.NOTES -> stringResource(R.string.notes_title)
    NotesSection.FOLDERS -> state.breadcrumb.lastOrNull()?.name ?: stringResource(R.string.notes_nav_folders)
    NotesSection.SEARCH -> stringResource(R.string.notes_nav_search)
    NotesSection.TRASH -> stringResource(R.string.trash)
}
