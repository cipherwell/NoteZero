package com.cipherwell.notezero.ui.editor

import com.cipherwell.notezero.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.model.BlockDocument
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.MarkdownBlockCodec
import com.cipherwell.notezero.ui.attachments.AttachmentController
import com.cipherwell.notezero.ui.attachments.AttachmentStrip
import com.cipherwell.notezero.ui.attachments.AttachmentViewerScreen
import com.cipherwell.notezero.ui.locks.LockBiometricPrompt
import com.cipherwell.notezero.ui.locks.LockTargetKind
import com.cipherwell.notezero.ui.locks.LockedNoteGate
import com.cipherwell.notezero.ui.locks.LockedTarget
import com.cipherwell.notezero.ui.locks.ReauthDialog
import com.cipherwell.notezero.ui.locks.rememberLockReauthController
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import kotlinx.coroutines.launch

/**
 * Markdown note editor: source editing with a live block preview, autosave (≈800 ms
 * debounce) and an explicit save when leaving. The preview is rendered from
 * [MarkdownBlockCodec]; checklist taps rewrite the Markdown source (no side table).
 *
 * The attachment strip sits between the title and the body. Its [AttachmentController]
 * owns the encrypted import/view/export/delete flow and brackets every system picker
 * with the repository's external-activity deferral; tests may inject one, otherwise the
 * screen creates and closes its own.
 *
 * All decrypted content stays in [EditorViewModel] memory; this composable never logs it.
 *
 * Locked-content gate (decision #15): if the note is locked and this session has not
 * re-authenticated for it, the editor renders [LockedNoteGate] **before** creating the
 * [EditorViewModel] — the note's title/body are never loaded into editor state while it is
 * masked, and re-arming the lock from anywhere re-renders the gate immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    repository: NoteRepository,
    itemId: String,
    onClose: () -> Unit,
    attachmentController: AttachmentController? = null,
    /** Injectable only for tests; production uses the device presence prompt. */
    biometricPrompt: LockBiometricPrompt? = null,
) {
    val items by repository.items.collectAsStateWithLifecycle()
    val verifiedLockedIds by repository.verifiedLockedIds.collectAsStateWithLifecycle()
    val reauth = rememberLockReauthController(repository, biometricPrompt)
    val lockedGate = items.firstOrNull { it.id == itemId }?.locked == true &&
        itemId !in verifiedLockedIds
    if (lockedGate) {
        LockedNoteGate(
            onReveal = { reauth.request(LockedTarget(itemId, LockTargetKind.NOTE)) },
            onClose = onClose,
        )
        ReauthDialog(reauth)
        return
    }

    val viewModel: EditorViewModel = viewModel(
        key = "editor_$itemId",
        factory = EditorViewModelFactory(repository, itemId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val ownedAttachmentController = remember(repository) { AttachmentController(repository) }
    val attachmentsController = attachmentController ?: ownedAttachmentController
    DisposableEffect(ownedAttachmentController) {
        onDispose { ownedAttachmentController.close() }
    }

    val attachments: List<AttachmentRef> = remember(items, itemId) {
        // A locked+unverified note never reaches this branch (gated above), so this list
        // only ever contains attachment names the session is allowed to see.
        items.firstOrNull { it.id == itemId }?.attachments.orEmpty()
    }
    var viewingAttachment by remember { mutableStateOf<AttachmentRef?>(null) }

    var previewMode by rememberSaveable { mutableStateOf(false) }
    var monospace by rememberSaveable { mutableStateOf(false) }
    var showTableDialog by rememberSaveable { mutableStateOf(false) }

    // The body field keeps its own TextFieldValue so the table helpers can insert/edit at
    // the real cursor (a plain String value would always append at the end). External body
    // changes (load, undo/redo, checklist taps in preview, conflict reload) re-sync the
    // text while keeping the cursor inside the new text.
    var bodyField by remember { mutableStateOf(TextFieldValue(state.body, TextRange(state.body.length))) }
    LaunchedEffect(state.body) {
        if (bodyField.text != state.body) {
            val cursor = if (bodyField.text.isEmpty()) {
                state.body.length
            } else {
                bodyField.selection.start.coerceIn(0, state.body.length)
            }
            bodyField = TextFieldValue(state.body, TextRange(cursor))
        }
    }

    fun applyTableResult(result: MarkdownTableEdit.Result?) {
        if (result == null) return
        val cursor = result.cursor.coerceIn(0, result.text.length)
        bodyField = TextFieldValue(result.text, TextRange(cursor))
        viewModel.updateBody(result.text)
    }

    fun leave() {
        scope.launch {
            viewModel.saveOnLeave()
            val current = viewModel.state.value
            if (!current.loading && !current.missing && !current.sessionCleared &&
                current.title.isBlank() && current.body.isBlank()
            ) {
                // Never leave a brand-new empty note behind after quick capture is abandoned.
                // A cleared session is not "empty": the vault is locked and must not be touched.
                repository.trashItem(itemId)
            }
            onClose()
        }
    }

    BackHandler { leave() }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = { leave() },
                        modifier = Modifier.testTag("editor_back"),
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back_to_notes))
                    }
                },
                title = {
                    Text(
                        text = saveStateLabel(state),
                        style = NoteZeroType.Caption,
                        color = if (state.conflict || state.saveStatus == SaveStatus.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.testTag("editor_save_state"),
                    )
                },
                actions = {
                    var menuOpen by remember { mutableStateOf(false) }
                    // Direct, always-reachable table action (the overflow also exposes the
                    // row/column edits for the table under the caret).
                    TextButton(
                        onClick = { showTableDialog = true },
                        enabled = !previewMode && !state.loading && !state.sessionCleared,
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag("editor_insert_table"),
                    ) {
                        Text(stringResource(R.string.editor_insert_table), style = NoteZeroType.Caption)
                    }
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag("editor_more"),
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_editor_actions))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_undo), style = NoteZeroType.Callout) },
                            enabled = state.canUndo,
                            onClick = {
                                menuOpen = false
                                viewModel.undo()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_redo), style = NoteZeroType.Callout) },
                            enabled = state.canRedo,
                            onClick = {
                                menuOpen = false
                                viewModel.redo()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (monospace || state.kind == ItemKind.CODE_FILE) {
                                        stringResource(R.string.editor_font_proportional)
                                    } else {
                                        stringResource(R.string.editor_font_monospace)
                                    },
                                    style = NoteZeroType.Callout,
                                )
                            },
                            enabled = !previewMode,
                            onClick = {
                                menuOpen = false
                                monospace = !monospace
                            },
                        )
                        // Table row/column helpers operate on the table under the caret; they
                        // are unavailable in preview mode and when the caret is outside one.
                        val tableAtCaret = MarkdownTableEdit.tableAt(bodyField.text, bodyField.selection.start)
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editor_table_add_row), style = NoteZeroType.Callout) },
                            enabled = !previewMode && tableAtCaret != null,
                            modifier = Modifier.testTag("editor_table_add_row"),
                            onClick = {
                                menuOpen = false
                                applyTableResult(MarkdownTableEdit.addRow(bodyField.text, bodyField.selection.start))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editor_table_remove_row), style = NoteZeroType.Callout) },
                            enabled = !previewMode && tableAtCaret?.cursorOnHeaderOrDelimiter == false,
                            modifier = Modifier.testTag("editor_table_remove_row"),
                            onClick = {
                                menuOpen = false
                                applyTableResult(MarkdownTableEdit.removeRow(bodyField.text, bodyField.selection.start))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editor_table_add_column), style = NoteZeroType.Callout) },
                            enabled = !previewMode && tableAtCaret != null,
                            modifier = Modifier.testTag("editor_table_add_column"),
                            onClick = {
                                menuOpen = false
                                applyTableResult(MarkdownTableEdit.addColumn(bodyField.text, bodyField.selection.start))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editor_table_remove_column), style = NoteZeroType.Callout) },
                            enabled = !previewMode && (tableAtCaret?.columnCount ?: 0) > 1,
                            modifier = Modifier.testTag("editor_table_remove_column"),
                            onClick = {
                                menuOpen = false
                                applyTableResult(MarkdownTableEdit.removeColumn(bodyField.text, bodyField.selection.start))
                            },
                        )
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
            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                state.missing -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.editor_note_unavailable),
                        style = NoteZeroType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.sessionCleared -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("editor_session_cleared"),
                    contentAlignment = Alignment.Center,
                ) {
                    // Non-secret placeholder: the vault locked/re-armed while this editor was
                    // alive, so the draft, undo history and conflict state were dropped. A new
                    // session reloads the note; nothing decrypted is on screen (audit F1).
                    Text(
                        text = stringResource(R.string.common_session_cleared),
                        style = NoteZeroType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = viewModel::updateTitle,
                        placeholder = { Text(stringResource(R.string.common_title), style = NoteZeroType.Heading) },
                        singleLine = true,
                        textStyle = NoteZeroType.Heading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.s)
                            .testTag("editor_title"),
                    )

                    AttachmentStrip(
                        controller = attachmentsController,
                        noteId = itemId,
                        attachments = attachments,
                        onOpen = { viewingAttachment = it },
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NoteZeroSpacing.l),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s),
                    ) {
                        FilterChip(
                            selected = !previewMode,
                            onClick = { previewMode = false },
                            label = { Text(stringResource(R.string.common_edit), style = NoteZeroType.Caption) },
                            modifier = Modifier.testTag("editor_edit_toggle"),
                        )
                        FilterChip(
                            selected = previewMode,
                            onClick = { previewMode = true },
                            label = { Text(stringResource(R.string.common_preview), style = NoteZeroType.Caption) },
                            modifier = Modifier.testTag("editor_preview_toggle"),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.editor_count, state.wordCount, state.characterCount),
                            style = NoteZeroType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("editor_count"),
                        )
                    }

                    if (state.conflict) {
                        ConflictBanner(
                            onKeepMine = {
                                viewModel.overwriteWithLocalDraft()
                                previewMode = false
                            },
                            onReload = {
                                viewModel.reloadLatest()
                                previewMode = false
                            },
                        )
                    }

                    if (previewMode) {
                        val document = remember(state.body) {
                            runCatching { MarkdownBlockCodec.parse(state.body) }
                                .getOrDefault(BlockDocument(emptyList()))
                        }
                        MarkdownPreview(
                            document = document,
                            onToggleCheckItem = viewModel::toggleChecklistItem,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .testTag("editor_preview"),
                        )
                    } else {
                        TextField(
                            value = bodyField,
                            onValueChange = { value ->
                                bodyField = value
                                viewModel.updateBody(value.text)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .testTag("editor_body"),
                            textStyle = if (monospace || state.kind == ItemKind.CODE_FILE) {
                                NoteZeroType.Mono
                            } else {
                                NoteZeroType.Body
                            },
                            placeholder = {
                                Text(
                                    stringResource(R.string.editor_body_placeholder),
                                    style = NoteZeroType.Callout,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                    }
                }
            }
        }
    }

    // Full-screen viewer overlay: covers the editor; it is not a second route.
    val openAttachment = viewingAttachment
    if (openAttachment != null && attachments.any { it.id == openAttachment.id }) {
        AttachmentViewerScreen(
            controller = attachmentsController,
            noteId = itemId,
            attachment = openAttachment,
            onClose = { viewingAttachment = null },
        )
    }

    if (showTableDialog) {
        TableInsertDialog(
            onDismiss = { showTableDialog = false },
            onConfirm = { rows, cols, headers ->
                showTableDialog = false
                applyTableResult(
                    MarkdownTableEdit.insertTable(
                        text = bodyField.text,
                        selectionStart = bodyField.selection.start,
                        selectionEnd = bodyField.selection.end,
                        rows = rows,
                        cols = cols,
                        headers = headers,
                    ),
                )
            },
        )
    }
    } // end of the editor's overlay Box
}

@Composable
private fun ConflictBanner(
    onKeepMine: () -> Unit,
    onReload: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.xs)
            .testTag("editor_conflict_banner"),
        shape = com.cipherwell.notezero.ui.theme.NoteZeroShapes.row,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(NoteZeroSpacing.m)) {
            Text(
                text = stringResource(R.string.editor_conflict_title),
                style = NoteZeroType.Callout,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(
                modifier = Modifier.heightIn(min = NoteZeroSpacing.minTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onKeepMine,
                    modifier = Modifier.testTag("conflict_keep_mine"),
                ) { Text(stringResource(R.string.common_keep_my_edits)) }
                Spacer(Modifier.width(NoteZeroSpacing.s))
                TextButton(
                    onClick = onReload,
                    modifier = Modifier.testTag("conflict_reload"),
                ) { Text(stringResource(R.string.common_reload_latest)) }
            }
        }
    }
}

@Composable
private fun saveStateLabel(state: EditorViewModel.EditorState): String = when {
    state.loading -> stringResource(R.string.common_loading)
    state.sessionCleared -> stringResource(R.string.common_session_cleared_short)
    state.missing -> stringResource(R.string.editor_save_missing)
    state.conflict -> stringResource(R.string.common_conflict)
    state.saveStatus == SaveStatus.SAVING -> stringResource(R.string.common_saving)
    state.saveStatus == SaveStatus.ERROR -> stringResource(R.string.editor_save_error)
    state.dirty -> stringResource(R.string.common_unsaved_changes)
    state.saveStatus == SaveStatus.SAVED -> stringResource(R.string.common_saved)
    else -> stringResource(R.string.common_saved)
}
