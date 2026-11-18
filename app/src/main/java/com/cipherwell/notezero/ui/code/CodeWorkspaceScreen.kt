package com.cipherwell.notezero.ui.code

import com.cipherwell.notezero.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import kotlinx.coroutines.launch

/**
 * Code workspace: an encrypted file tree of [com.cipherwell.notezero.model.ItemKind.CODE_FILE]
 * items in the same folder tree as notes, opening the syntax-highlighted
 * [CodeEditorScreen].
 *
 * IDE upgrades (docs/CONTRACTS-PHASE14.md):
 *  - multi-file tabs: [openFileIds] (rememberSaveable, capped by
 *    [CodeTabs.MAX_OPEN_TABS]) plus [activeFileId]. Each tab's editor is keyed by its item id
 *    (`code_editor_<id>`), so switching tabs keeps the draft, undo history and per-tab
 *    ViewModel state; the workspace never clears the editor ViewModel on a tab switch.
 *  - quick-open switcher (`code_quick_open`) over the visible code files.
 *  - the new-file dialog returns a [NewCodeFileRequest] whose template body is saved here.
 *
 * Exact entry symbol required by docs/CONTRACTS-PHASE3.md section C. Decrypted file names and
 * source stay in memory here and in [CodeWorkspaceViewModel] — nothing is logged.
 */
@Composable
fun CodeWorkspaceScreen(
    repository: NoteRepository,
    onBack: () -> Unit,
) {
    val viewModel: CodeWorkspaceViewModel = viewModel(
        factory = CodeWorkspaceViewModelFactory(repository),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val items by repository.items.collectAsStateWithLifecycle()

    // Open tabs. A List<String> is not Bundle-safe on its own, so persist it through an
    // explicit list saver (same order as the strip). Active id/line/token are rememberSaveable
    // exactly like the single-file workspace before this increment.
    val openTabsSaver = remember { listSaver<List<String>, String>(save = { it }, restore = { it }) }
    var openFileIds by rememberSaveable(stateSaver = openTabsSaver) {
        mutableStateOf(emptyList<String>())
    }
    var activeFileId by rememberSaveable { mutableStateOf<String?>(null) }

    // Open-at-line request. [openToken] is bumped on every open so re-opening the same file
    // (a singleton editor ViewModel) still applies the new target line.
    var openFileLine by rememberSaveable { mutableStateOf<Int?>(null) }
    var openToken by rememberSaveable { mutableStateOf(0) }

    // Non-secret cap notice: shown when a new file would exceed the 8-tab limit.
    var tabLimitNotice by remember { mutableStateOf(false) }

    fun openFile(itemId: String, line: Int?) {
        val opened = CodeTabs.open(openFileIds, activeFileId, itemId)
        if (opened.limitReached) {
            tabLimitNotice = true
            return
        }
        tabLimitNotice = false
        openFileIds = opened.openFileIds
        activeFileId = opened.activeFileId
        openFileLine = line
        openToken += 1
    }

    fun selectTab(itemId: String) {
        if (itemId == activeFileId) return
        activeFileId = itemId
        // A plain tab switch never re-applies a stale search-hit line.
        openFileLine = null
        openToken += 1
    }

    fun closeTab(itemId: String) {
        val closed = CodeTabs.close(openFileIds, activeFileId, itemId)
        openFileIds = closed.openFileIds
        activeFileId = closed.activeFileId
        openFileLine = null
        tabLimitNotice = false
    }

    val tabs = openFileIds.map { id ->
        CodeTab(itemId = id, title = items.firstOrNull { it.id == id }?.title.orEmpty())
    }
    val saveableStateHolder = rememberSaveableStateHolder()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(CodeTags.WORKSPACE),
    ) {
        val activeId = activeFileId
        if (activeId == null || activeId !in openFileIds) {
            CodeTreeContent(
                state = state,
                viewModel = viewModel,
                limitNotice = tabLimitNotice,
                onBack = onBack,
                onOpenFile = { itemId, line -> openFile(itemId, line) },
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                CodeTabsStrip(
                    tabs = tabs,
                    activeFileId = activeId,
                    onSelect = { itemId -> selectTab(itemId) },
                    onClose = { itemId -> closeTab(itemId) },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    // Keep each file's editor rememberSaveable state (find bar, preview mode,
                    // focus/scroll chrome) alive across tab switches. The editor ViewModel is
                    // keyed per item and outlives this subtree (Activity/Nav ViewModel store).
                    saveableStateHolder.SaveableStateProvider(activeId) {
                        CodeEditorScreen(
                            repository = repository,
                            itemId = activeId,
                            initialLine = openFileLine,
                            openToken = openToken,
                            onBack = {
                                // Back to the file tree without closing any tab (§A).
                                activeFileId = null
                                openFileLine = null
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodeTreeContent(
    state: CodeWorkspaceUiState,
    viewModel: CodeWorkspaceViewModel,
    limitNotice: Boolean,
    onBack: () -> Unit,
    onOpenFile: (String, Int?) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var showNewFile by remember { mutableStateOf(false) }
    var newFileParent by remember { mutableStateOf<String?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderParent by remember { mutableStateOf<String?>(null) }
    var showQuickOpen by remember { mutableStateOf(false) }
    var renameFileTarget by remember { mutableStateOf<Item?>(null) }
    var renameFolderTarget by remember { mutableStateOf<Folder?>(null) }
    var moveFileTarget by remember { mutableStateOf<Item?>(null) }
    var deleteFileTarget by remember { mutableStateOf<Item?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<Folder?>(null) }

    fun openCreateFile(parentId: String?) {
        newFileParent = parentId
        showNewFile = true
    }

    fun openCreateFolder(parentId: String?) {
        newFolderParent = parentId
        showNewFolder = true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = { if (state.searchVisible) viewModel.closeSearch() else onBack() },
                        modifier = Modifier.testTag(CodeTags.BACK),
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back_to_notes))
                    }
                },
                title = {
                    Column {
                        val fileCountLabel = pluralStringResource(
                            R.plurals.code_file_count,
                            state.fileCount,
                            state.fileCount,
                        )
                        val folderCountLabel = pluralStringResource(
                            R.plurals.code_folder_count,
                            state.folderCount,
                            state.folderCount,
                        )
                        Text(stringResource(R.string.code_title), style = NoteZeroType.Heading)
                        Text(
                            text = stringResource(
                                R.string.code_header_counts,
                                fileCountLabel,
                                folderCountLabel,
                            ),
                            style = NoteZeroType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.lockedHiddenCount > 0) {
                            Text(
                                text = stringResource(R.string.code_locked_hidden, state.lockedHiddenCount),
                                style = NoteZeroType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag(CodeTags.LOCKED_HIDDEN),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showQuickOpen = true },
                        enabled = state.isUnlocked,
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag(CodeTags.QUICK_OPEN),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.cd_code_quick_open),
                        )
                    }
                    IconButton(
                        onClick = { viewModel.toggleSearch() },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag(CodeTags.SEARCH_TOGGLE),
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = if (state.searchVisible) {
                                stringResource(R.string.cd_code_close_workspace_search)
                            } else {
                                stringResource(R.string.cd_code_search)
                            },
                        )
                    }
                    TextButton(
                        onClick = { openCreateFolder(null) },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag(CodeTags.NEW_FOLDER),
                    ) { Text(stringResource(R.string.code_new_folder)) }
                    IconButton(
                        onClick = { openCreateFile(null) },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag(CodeTags.NEW_FILE),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_code_new_file))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (limitNotice) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.xs),
                ) {
                    Text(
                        text = stringResource(R.string.code_tab_limit),
                        style = NoteZeroType.Caption,
                        modifier = Modifier
                            .padding(NoteZeroSpacing.s)
                            .testTag(CodeTags.TAB_LIMIT),
                    )
                }
            }
            if (state.searchVisible) {
                CodeSearchBar(
                    query = state.searchQuery,
                    caseSensitive = state.searchCaseSensitive,
                    resultCount = state.searchResults.size,
                    onQueryChange = viewModel::setSearchQuery,
                    onToggleCase = viewModel::toggleSearchCaseSensitive,
                    onClose = viewModel::closeSearch,
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !state.isUnlocked -> CenteredMessage(
                        title = stringResource(R.string.code_locked_title),
                        subtitle = stringResource(R.string.code_locked_subtitle),
                    )

                    state.searchActive -> CodeSearchResults(
                        results = state.searchResults,
                        onOpen = { match -> onOpenFile(match.itemId, match.line) },
                    )

                    state.isEmpty -> EmptyCodeTree(
                        onCreateFile = { openCreateFile(null) },
                        onCreateFolder = { openCreateFolder(null) },
                    )

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(CodeTags.FILE_TREE),
                        contentPadding = PaddingValues(
                            start = NoteZeroSpacing.s,
                            end = NoteZeroSpacing.s,
                            top = NoteZeroSpacing.s,
                            bottom = NoteZeroSpacing.xxl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(state.rows, key = { it.key }) { row ->
                            when (row) {
                                is CodeTreeNode.FolderNode -> CodeFolderRow(
                                    node = row,
                                    onToggle = { viewModel.toggleFolder(row.folder.id) },
                                    onNewFile = { openCreateFile(row.folder.id) },
                                    onNewFolder = { openCreateFolder(row.folder.id) },
                                    onRename = { renameFolderTarget = row.folder },
                                    onDelete = { deleteFolderTarget = row.folder },
                                )

                                is CodeTreeNode.FileNode -> CodeFileRow(
                                    node = row,
                                    onOpen = { onOpenFile(row.item.id, null) },
                                    onRename = { renameFileTarget = row.item },
                                    onMove = { moveFileTarget = row.item },
                                    onDelete = { deleteFileTarget = row.item },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Dialogs -------------------------------------------------------------

    if (showQuickOpen && state.isUnlocked) {
        QuickOpenDialog(
            entries = state.quickOpenEntries,
            onDismiss = { showQuickOpen = false },
            onOpen = { entry ->
                showQuickOpen = false
                onOpenFile(entry.itemId, null)
            },
        )
    }

    if (showNewFile) {
        NewCodeFileDialog(
            title = stringResource(R.string.code_new_file_title),
            confirmLabel = stringResource(R.string.common_create),
            validate = { name -> CodeTree.validateName(CodeTree.normalizeNewFileName(name)) },
            onDismiss = { showNewFile = false },
            onConfirmRequest = { request ->
                val parent = newFileParent
                showNewFile = false
                scope.launch {
                    val created = viewModel.createFile(request.name, parent, request.body)
                    if (created != null) onOpenFile(created.id, null)
                }
            },
        )
    }

    if (showNewFolder) {
        CodeTextInputDialog(
            title = stringResource(R.string.code_new_folder),
            label = stringResource(R.string.common_folder_name),
            initialValue = "",
            confirmLabel = stringResource(R.string.common_create),
            validate = { CodeTree.validateName(it) },
            onDismiss = { showNewFolder = false },
            onConfirm = { name ->
                viewModel.createFolder(name, newFolderParent)
                showNewFolder = false
            },
        )
    }

    renameFileTarget?.let { item ->
        val target = item
        CodeTextInputDialog(
            title = stringResource(R.string.code_rename_file_title),
            label = stringResource(R.string.code_file_name),
            initialValue = target.title,
            confirmLabel = stringResource(R.string.common_rename),
            validate = { CodeTree.validateName(it) },
            onDismiss = { renameFileTarget = null },
            onConfirm = { name ->
                viewModel.renameFile(target, name)
                renameFileTarget = null
            },
        )
    }

    renameFolderTarget?.let { folder ->
        val target = folder
        CodeTextInputDialog(
            title = stringResource(R.string.code_rename_folder_title),
            label = stringResource(R.string.common_folder_name),
            initialValue = target.name,
            confirmLabel = stringResource(R.string.common_rename),
            validate = { CodeTree.validateName(it) },
            onDismiss = { renameFolderTarget = null },
            onConfirm = { name ->
                viewModel.renameFolder(target, name)
                renameFolderTarget = null
            },
        )
    }

    moveFileTarget?.let { item ->
        val target = item
        CodeMoveDialog(
            targets = state.moveTargets,
            onDismiss = { moveFileTarget = null },
            onSelect = { folderId ->
                viewModel.moveFile(target, folderId)
                moveFileTarget = null
            },
        )
    }

    deleteFileTarget?.let { item ->
        val target = item
        CodeConfirmDialog(
            title = stringResource(R.string.code_delete_file_title),
            message = stringResource(R.string.code_delete_file_message, target.title),
            confirmLabel = stringResource(R.string.code_delete_file),
            destructive = true,
            onDismiss = { deleteFileTarget = null },
            onConfirm = {
                viewModel.trashFile(target)
                deleteFileTarget = null
            },
        )
    }

    deleteFolderTarget?.let { folder ->
        val target = folder
        CodeConfirmDialog(
            title = stringResource(R.string.code_delete_folder_title),
            message = stringResource(R.string.code_delete_folder_message),
            confirmLabel = stringResource(R.string.code_delete_folder),
            destructive = true,
            onDismiss = { deleteFolderTarget = null },
            onConfirm = {
                viewModel.deleteFolder(target)
                deleteFolderTarget = null
            },
        )
    }
}

@Composable
private fun EmptyCodeTree(
    onCreateFile: () -> Unit,
    onCreateFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = NoteZeroSpacing.gutter)
            .testTag(CodeTags.EMPTY_STATE),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.code_empty_title),
            style = NoteZeroType.Title,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(NoteZeroSpacing.s))
        Text(
            text = stringResource(R.string.code_empty_subtitle),
            style = NoteZeroType.Callout,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(NoteZeroSpacing.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.m)) {
            Button(
                onClick = onCreateFile,
                modifier = Modifier.heightIn(min = NoteZeroSpacing.minTarget),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(NoteZeroSpacing.xs))
                Text(stringResource(R.string.code_new_file), style = NoteZeroType.Callout)
            }
            TextButton(
                onClick = onCreateFolder,
                modifier = Modifier.heightIn(min = NoteZeroSpacing.minTarget),
            ) { Text(stringResource(R.string.code_new_folder), style = NoteZeroType.Callout) }
        }
    }
}

@Composable
private fun CenteredMessage(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = NoteZeroSpacing.gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = NoteZeroType.Heading, textAlign = TextAlign.Center)
        Spacer(Modifier.size(NoteZeroSpacing.s))
        Text(
            text = subtitle,
            style = NoteZeroType.Callout,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
