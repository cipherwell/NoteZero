package com.cipherwell.notezero.ui.notes

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
) {
    val viewModel: NotesViewModel = viewModel(factory = NotesViewModelFactory(repository))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editorItemId by rememberSaveable { mutableStateOf<String?>(null) }

    val openItemId = editorItemId
    if (openItemId != null) {
        com.cipherwell.notezero.ui.editor.NoteEditorScreen(
            repository = repository,
            itemId = openItemId,
            onClose = { editorItemId = null },
        )
        return
    }

    NotesListContent(
        state = state,
        viewModel = viewModel,
        onOpenCode = onOpenCode,
        onOpenEditor = { editorItemId = it },
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesListContent(
    state: NotesUiState,
    viewModel: NotesViewModel,
    onOpenEditor: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCode: () -> Unit,
) {
    var renameTarget by remember { mutableStateOf<Item?>(null) }
    var moveTarget by remember { mutableStateOf<Item?>(null) }
    var renameFolderTarget by remember { mutableStateOf<Folder?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<Folder?>(null) }
    var newFolderParent by remember { mutableStateOf<String?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val isTrash = state.section == NotesSection.TRASH
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
                onClick = { onOpenEditor(row.item.id) },
                onTogglePin = { viewModel.togglePinned(row.item) },
                onToggleFavorite = { viewModel.toggleFavorite(row.item) },
                onRename = { renameTarget = row.item },
                onMove = { moveTarget = row.item },
                onTrash = { viewModel.moveToTrash(row.item) },
            )
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(text = titleFor(state), style = NoteZeroType.LargeTitle) },
                navigationIcon = {
                    if (isTrash) {
                        IconButton(
                            onClick = { viewModel.leaveTrash() },
                            modifier = Modifier.testTag("notes_back"),
                        ) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back to notes")
                        }
                    } else if (state.section == NotesSection.FOLDERS && state.currentFolderId != null) {
                        IconButton(
                            onClick = { viewModel.closeFolder() },
                            modifier = Modifier.testTag("notes_back"),
                        ) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back to parent folder")
                        }
                    }
                },
                actions = {
                    if (isTrash) {
                        TextButton(
                            onClick = { confirmEmptyTrash = true },
                            modifier = Modifier.testTag("trash_empty_button"),
                        ) { Text("Empty") }
                    } else {
                        IconButton(
                            onClick = onOpenCode,
                            modifier = Modifier
                                .testTag("nav_code")
                                .semantics { contentDescription = "Code workspace" },
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
                            Icon(Icons.Filled.Delete, contentDescription = "Trash")
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (!isTrash) {
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            val folderId = if (state.section == NotesSection.FOLDERS) {
                                state.currentFolderId
                            } else {
                                null
                            }
                            val created = viewModel.createNote(folderId)
                            if (created != null) onOpenEditor(created.id)
                        }
                    },
                    modifier = Modifier.testTag("new_note_button"),
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New Note", style = NoteZeroType.Callout) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
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
                            title = "No notes yet",
                            subtitle = "Tap New Note to capture your first thought. Everything is encrypted on this device.",
                        )
                    }
                }
                if (state.pinned.isNotEmpty()) {
                    item(key = "header_pinned") { SectionHeader("Pinned", state.pinned.size) }
                    noteRows(state.pinned, "pinned_")
                }
                if (state.favorites.isNotEmpty()) {
                    item(key = "header_favorites") { SectionHeader("Favourites", state.favorites.size) }
                    noteRows(state.favorites, "favorite_")
                }
                if (state.recent.isNotEmpty()) {
                    item(key = "header_recent") { SectionHeader("Recent", state.recent.size) }
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
                            title = "This folder is empty",
                            subtitle = "Create a subfolder, or open a note from the Notes tab and move it here.",
                        )
                    }
                }
                items(state.folders, key = { "folder_${it.folder.id}" }) { row ->
                    FolderRow(
                        row = row,
                        onClick = { viewModel.openFolder(row.folder.id) },
                        onRename = { renameFolderTarget = row.folder },
                        onDelete = { deleteFolderTarget = row.folder },
                    )
                }
                if (state.folderItems.isNotEmpty()) {
                    item(key = "folder_items_header") {
                        SectionHeader("Notes in this folder", state.folderItems.size)
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
                    placeholder = { Text("Search title, body, tags, folders", style = NoteZeroType.Callout) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(
                                onClick = viewModel::clearQuery,
                                modifier = Modifier.testTag("search_clear"),
                            ) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear search")
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
                        title = "Search your vault",
                        subtitle = "Find notes by title, body, tag or folder. Searches stay in memory — nothing leaves the device.",
                    )

                    state.searchResults.isEmpty() -> NotesEmptyState(
                        title = "No matches",
                        subtitle = "No note matches \"${state.query.trim()}\".",
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
                            SectionHeader("Results", state.searchResults.size)
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
                            title = "Trash is empty",
                            subtitle = "Notes you delete appear here until the trash is emptied.",
                        )
                    }
                } else {
                    item(key = "trash_header") { SectionHeader("Trash", state.trash.size) }
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
            title = "Rename note",
            label = "Title",
            initialValue = item.title,
            confirmLabel = "Rename",
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
            title = "Rename folder",
            label = "Folder name",
            initialValue = folder.name,
            confirmLabel = "Rename",
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
            title = "Delete folder?",
            message = "Notes and subfolders inside move to the parent folder. Nothing is permanently deleted.",
            confirmLabel = "Delete folder",
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
            title = "New folder",
            label = "Folder name",
            initialValue = "",
            confirmLabel = "Create",
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
            title = "Empty trash?",
            message = "Every note in the trash will be permanently deleted. This cannot be undone.",
            confirmLabel = "Empty trash",
            destructive = true,
            dialogTestTag = "empty_trash_dialog",
            onDismiss = { confirmEmptyTrash = false },
            onConfirm = {
                viewModel.emptyTrash()
                confirmEmptyTrash = false
            },
        )
    }
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
                label = { Text("Favourites", style = NoteZeroType.Caption) },
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
                Text(if (breadcrumb.isEmpty()) "All folders" else "Folders", style = NoteZeroType.Callout)
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
                Text("New folder", style = NoteZeroType.Callout)
            }
        }
    }
}

private fun titleFor(state: NotesUiState): String = when (state.section) {
    NotesSection.NOTES -> "Notes"
    NotesSection.FOLDERS -> state.breadcrumb.lastOrNull()?.name ?: "Folders"
    NotesSection.SEARCH -> "Search"
    NotesSection.TRASH -> "Trash"
}
