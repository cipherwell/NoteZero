package com.cipherwell.notezero.ui.code

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
 * Phase 3 code workspace: an encrypted file tree of [com.cipherwell.notezero.model.ItemKind.CODE_FILE]
 * items in the same folder tree as notes, opening the syntax-highlighted
 * [CodeEditorScreen] for one file.
 *
 * Exact entry symbol required by docs/CONTRACTS-PHASE3.md section C. The Lead wires this
 * into ui/AppRoot; the notes screen gets the "Code" entry. Decrypted file names/source
 * stay in memory here and in [CodeWorkspaceViewModel] — nothing is logged.
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
    var openFileId by rememberSaveable { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(CodeTags.WORKSPACE),
    ) {
        val fileId = openFileId
        if (fileId == null) {
            CodeTreeContent(
                state = state,
                viewModel = viewModel,
                onBack = onBack,
                onOpenFile = { openFileId = it },
            )
        } else {
            CodeEditorScreen(
                repository = repository,
                itemId = fileId,
                onBack = { openFileId = null },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodeTreeContent(
    state: CodeWorkspaceUiState,
    viewModel: CodeWorkspaceViewModel,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var showNewFile by remember { mutableStateOf(false) }
    var newFileParent by remember { mutableStateOf<String?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderParent by remember { mutableStateOf<String?>(null) }
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
                        onClick = onBack,
                        modifier = Modifier.testTag(CodeTags.BACK),
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back to notes")
                    }
                },
                title = {
                    Column {
                        Text("Code", style = NoteZeroType.Heading)
                        Text(
                            text = "${countLabel(state.fileCount, "file")} · " +
                                "${countLabel(state.folderCount, "folder")} · encrypted",
                            style = NoteZeroType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { openCreateFolder(null) },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag(CodeTags.NEW_FOLDER),
                    ) { Text("New folder") }
                    IconButton(
                        onClick = { openCreateFile(null) },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag(CodeTags.NEW_FILE),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "New code file")
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            !state.isUnlocked -> CenteredMessage(
                title = "Vault is locked",
                subtitle = "Unlock the vault to open the code workspace.",
                modifier = Modifier.padding(innerPadding),
            )

            state.isEmpty -> EmptyCodeTree(
                onCreateFile = { openCreateFile(null) },
                onCreateFolder = { openCreateFolder(null) },
                modifier = Modifier.padding(innerPadding),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
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
                            onOpen = { onOpenFile(row.item.id) },
                            onRename = { renameFileTarget = row.item },
                            onMove = { moveFileTarget = row.item },
                            onDelete = { deleteFileTarget = row.item },
                        )
                    }
                }
            }
        }
    }

    // ---- Dialogs -------------------------------------------------------------

    if (showNewFile) {
        CodeTextInputDialog(
            title = "New code file",
            label = "File name",
            initialValue = "",
            placeholder = "untitled.kt",
            confirmLabel = "Create",
            validate = { raw ->
                val name = CodeTree.normalizeNewFileName(raw)
                CodeTree.validateName(name)
            },
            onDismiss = { showNewFile = false },
            onConfirm = { raw ->
                val parent = newFileParent
                showNewFile = false
                scope.launch {
                    val created = viewModel.createFile(raw, parent)
                    if (created != null) onOpenFile(created.id)
                }
            },
        )
    }

    if (showNewFolder) {
        CodeTextInputDialog(
            title = "New folder",
            label = "Folder name",
            initialValue = "",
            confirmLabel = "Create",
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
            title = "Rename file",
            label = "File name",
            initialValue = target.title,
            confirmLabel = "Rename",
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
            title = "Rename folder",
            label = "Folder name",
            initialValue = target.name,
            confirmLabel = "Rename",
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
            title = "Delete file?",
            message = "\"${target.title}\" moves to the trash. Nothing is permanently deleted from this screen.",
            confirmLabel = "Delete file",
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
            title = "Delete folder?",
            message = "Files and subfolders inside move to the parent folder. Nothing is permanently deleted.",
            confirmLabel = "Delete folder",
            destructive = true,
            onDismiss = { deleteFolderTarget = null },
            onConfirm = {
                viewModel.deleteFolder(target)
                deleteFolderTarget = null
            },
        )
    }
}

/** "1 file" / "2 files" — a count is never rendered with a stray plural. */
internal fun countLabel(count: Int, singular: String): String =
    "$count ${if (count == 1) singular else singular + "s"}"

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
            text = "Your code vault",
            style = NoteZeroType.Title,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(NoteZeroSpacing.s))
        Text(
            text = "Create a code file to start writing. Every keystroke is encrypted on this device.",
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
                Text("New file", style = NoteZeroType.Callout)
            }
            TextButton(
                onClick = onCreateFolder,
                modifier = Modifier.heightIn(min = NoteZeroSpacing.minTarget),
            ) { Text("New folder", style = NoteZeroType.Callout) }
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
