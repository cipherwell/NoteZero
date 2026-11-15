package com.cipherwell.notezero.ui.editor

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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    repository: NoteRepository,
    itemId: String,
    onClose: () -> Unit,
    attachmentController: AttachmentController? = null,
) {
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

    val items by repository.items.collectAsStateWithLifecycle()
    val attachments: List<AttachmentRef> = remember(items, itemId) {
        items.firstOrNull { it.id == itemId }?.attachments.orEmpty()
    }
    var viewingAttachment by remember { mutableStateOf<AttachmentRef?>(null) }

    var previewMode by rememberSaveable { mutableStateOf(false) }
    var monospace by rememberSaveable { mutableStateOf(false) }

    fun leave() {
        scope.launch {
            viewModel.saveOnLeave()
            val current = viewModel.state.value
            if (!current.loading && !current.missing && current.title.isBlank() && current.body.isBlank()) {
                // Never leave a brand-new empty note behind after quick capture is abandoned.
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
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back to notes")
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
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag("editor_more"),
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Editor actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Undo", style = NoteZeroType.Callout) },
                            enabled = state.canUndo,
                            onClick = {
                                menuOpen = false
                                viewModel.undo()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Redo", style = NoteZeroType.Callout) },
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
                                        "Use proportional font"
                                    } else {
                                        "Use monospace font"
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
                        text = "This note is no longer available.",
                        style = NoteZeroType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = viewModel::updateTitle,
                        placeholder = { Text("Title", style = NoteZeroType.Heading) },
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
                            label = { Text("Edit", style = NoteZeroType.Caption) },
                            modifier = Modifier.testTag("editor_edit_toggle"),
                        )
                        FilterChip(
                            selected = previewMode,
                            onClick = { previewMode = true },
                            label = { Text("Preview", style = NoteZeroType.Caption) },
                            modifier = Modifier.testTag("editor_preview_toggle"),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${state.wordCount} words · ${state.characterCount} chars",
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
                            value = state.body,
                            onValueChange = viewModel::updateBody,
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
                                    "Write in Markdown. Type - [ ] for a checklist.",
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
                text = "This note changed elsewhere.",
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
                ) { Text("Keep my edits") }
                Spacer(Modifier.width(NoteZeroSpacing.s))
                TextButton(
                    onClick = onReload,
                    modifier = Modifier.testTag("conflict_reload"),
                ) { Text("Reload latest") }
            }
        }
    }
}

private fun saveStateLabel(state: EditorViewModel.EditorState): String = when {
    state.loading -> "Loading…"
    state.missing -> "Note unavailable"
    state.conflict -> "Conflict — unsaved edits"
    state.saveStatus == SaveStatus.SAVING -> "Saving…"
    state.saveStatus == SaveStatus.ERROR -> "Save failed — retrying on next edit"
    state.dirty -> "Unsaved changes"
    state.saveStatus == SaveStatus.SAVED -> "Saved"
    else -> "Saved"
}
