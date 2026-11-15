package com.cipherwell.notezero.ui.code

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cipherwell.notezero.code.LineIndex
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.model.BlockDocument
import com.cipherwell.notezero.model.MarkdownBlockCodec
import com.cipherwell.notezero.ui.components.common.SensitiveClipboard
import com.cipherwell.notezero.ui.editor.ChecklistMarkdown
import com.cipherwell.notezero.ui.editor.MarkdownPreview
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import kotlinx.coroutines.launch

/**
 * Full-screen editor for one encrypted code file: line-number gutter, syntax colours,
 * current-line + bracket highlights, find/replace, go-to-line, bounded undo/redo,
 * font-size/wrap preferences, autosave (600 ms) and conflict handling.
 *
 * Polish additions (docs/CONTRACTS-PHASE4.md §C): multi-line indent/outdent from the toolbar
 * and Tab/Shift+Tab, Copy (selection-or-all) to a clipboard marked sensitive, and an explicit
 * "Export decrypted copy" SAF flow behind an UNENCRYPTED-copy confirmation. Decrypted source
 * only ever reaches memory, the clipboard the user asked for, or the file the user picked.
 *
 * [initialLine] (1-based) opens the file at a workspace search hit; [openToken] lets a
 * re-open of the same file apply a new target line without recreating the ViewModel.
 * [exportTarget] is injectable so the confirmation gate is testable without a system picker.
 *
 * Markdown files additionally offer a preview that reuses
 * [com.cipherwell.notezero.ui.editor.MarkdownPreview] — no duplicate renderer exists.
 * Back always flushes pending edits before leaving (no keystroke is lost).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    repository: NoteRepository,
    itemId: String,
    onBack: () -> Unit,
    initialLine: Int? = null,
    openToken: Int = 0,
    exportTarget: CodeExportTarget = rememberSafCodeExportTarget(repository),
) {
    val context = LocalContext.current
    val preferences = remember(context) { CodeEditorPrefs(context) }
    val viewModel: CodeEditorViewModel = viewModel(
        key = "code_editor_$itemId",
        factory = CodeEditorViewModelFactory(repository, itemId, preferences, initialLine),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val palette = rememberCodeSyntaxPalette()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var findVisible by rememberSaveable { mutableStateOf(false) }
    var previewMode by rememberSaveable { mutableStateOf(false) }
    var showGotoDialog by remember { mutableStateOf(false) }
    var showExportConfirm by remember { mutableStateOf(false) }

    // Open-at-line request: applied once the draft is loaded, and re-applied on every
    // re-open of this file (new [openToken]) even though the ViewModel survives.
    var pendingRevealLine by remember(initialLine, openToken) { mutableStateOf(initialLine) }
    LaunchedEffect(pendingRevealLine, state.loading, state.missing, state.tooLarge) {
        val line = pendingRevealLine ?: return@LaunchedEffect
        if (state.loading || state.missing || state.tooLarge) return@LaunchedEffect
        viewModel.goToLine(line)
        pendingRevealLine = null
    }

    fun leave() {
        scope.launch {
            viewModel.saveOnLeave()
            onBack()
        }
    }

    fun copyDraft() {
        val payload = viewModel.copySelectionOrAll()
        if (payload.isEmpty()) return
        // Memory + clipboard only, never a file. The clip is marked sensitive so Android 13+
        // hides it from the copy overlay and clipboard previews (backported extra below 33).
        SensitiveClipboard.copy(context, "NoteZero code", payload)
    }

    BackHandler { leave() }

    // Switching to a different file must not inherit the previous file's view mode.
    LaunchedEffect(itemId) {
        findVisible = false
        previewMode = false
        showGotoDialog = false
    }

    LaunchedEffect(state.loading) {
        if (!state.loading && !state.missing && !state.tooLarge) {
            // Wait one frame so the field's focus modifier is attached before requesting
            // focus (requesting during the composition window throws).
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }

    val textFieldValue = remember(state.body, state.cursor, state.selectionEnd) {
        TextFieldValue(
            text = state.body,
            selection = TextRange(state.cursor, state.selectionEnd),
        )
    }
    val lineIndex = remember(state.body) { LineIndex.of(state.body) }
    val cursorLine = remember(lineIndex, state.cursor, state.body) {
        lineIndex.lineOf(state.cursor.coerceIn(0, state.body.length))
    }
    val cursorColumn = remember(lineIndex, cursorLine, state.cursor, state.body) {
        state.cursor - lineIndex.startOfLine(cursorLine) + 1
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = { leave() },
                        modifier = Modifier.testTag(CodeTags.BACK),
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back to code files")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = state.fileName.ifEmpty { "Code file" },
                            style = NoteZeroType.Callout,
                            maxLines = 1,
                        )
                        Text(
                            text = saveStateLabel(state),
                            style = NoteZeroType.Caption,
                            color = when {
                                state.conflict || state.saveStatus == CodeSaveStatus.ERROR ->
                                    MaterialTheme.colorScheme.error

                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.testTag(CodeTags.SAVE_STATE),
                        )
                    }
                },
                actions = {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag(CodeTags.MORE),
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Editor actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Undo", style = NoteZeroType.Callout) },
                            enabled = state.canUndo,
                            modifier = Modifier.testTag(CodeTags.UNDO),
                            onClick = {
                                menuOpen = false
                                viewModel.undo()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Redo", style = NoteZeroType.Callout) },
                            enabled = state.canRedo,
                            modifier = Modifier.testTag(CodeTags.REDO),
                            onClick = {
                                menuOpen = false
                                viewModel.redo()
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
                .padding(innerPadding)
                .imePadding(),
        ) {
            when {
                state.loading -> CenteredEditorBox {
                    CircularProgressIndicator()
                }

                state.missing -> CenteredEditorBox {
                    Text(
                        text = "This code file is no longer available.",
                        style = NoteZeroType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                state.tooLarge -> CenteredEditorBox {
                    Text(
                        text = "This file is too large to open here (limit 2 MiB). " +
                            "It stays encrypted in the vault.",
                        style = NoteZeroType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag(CodeTags.TOO_LARGE),
                    )
                }

                else -> {
                    EditorToolbar(
                        state = state,
                        previewSelected = previewMode,
                        onFindToggle = { findVisible = !findVisible },
                        onGotoLine = { showGotoDialog = true },
                        onFontDecrease = viewModel::decreaseFontSize,
                        onFontIncrease = viewModel::increaseFontSize,
                        onWrapToggle = viewModel::toggleWordWrap,
                        onPreviewToggle = { previewMode = !previewMode },
                    )
                    EditorActionBar(
                        onIndent = viewModel::indentSelection,
                        onOutdent = viewModel::outdentSelection,
                        onCopy = { copyDraft() },
                        onExport = { showExportConfirm = true },
                    )
                    if (findVisible) {
                        FindReplaceBar(
                            state = state,
                            onQueryChange = viewModel::setFindQuery,
                            onReplaceQueryChange = viewModel::setReplaceQuery,
                            onToggleCase = viewModel::toggleCaseSensitive,
                            onNext = { viewModel.findNext() },
                            onPrevious = { viewModel.findPrevious() },
                            onReplace = { viewModel.replaceCurrent() },
                            onReplaceAll = { viewModel.replaceAll() },
                        )
                    }
                    if (state.conflict) {
                        ConflictBanner(
                            onKeepMine = viewModel::overwriteWithLocalDraft,
                            onReload = {
                                previewMode = false
                                viewModel.reloadLatest()
                            },
                        )
                    }
                    if (previewMode && state.isMarkdown) {
                        val document = remember(state.body) {
                            runCatching { MarkdownBlockCodec.parse(state.body) }
                                .getOrDefault(BlockDocument(emptyList()))
                        }
                        MarkdownPreview(
                            document = document,
                            onToggleCheckItem = { index ->
                                val updated = ChecklistMarkdown.toggle(state.body, index)
                                if (updated != state.body) {
                                    viewModel.onTextChanged(updated, state.cursor, state.selectionEnd)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .testTag(CodeTags.PREVIEW),
                        )
                    } else {
                        CodeEditorField(
                            value = textFieldValue,
                            onValueChange = { new ->
                                viewModel.onTextChanged(new.text, new.selection.start, new.selection.end)
                            },
                            state = state,
                            palette = palette,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            focusRequester = focusRequester,
                            onIndent = viewModel::indentSelection,
                            onOutdent = viewModel::outdentSelection,
                        )
                    }
                    EditorStatusRow(
                        state = state,
                        cursorLine = cursorLine + 1,
                        cursorColumn = cursorColumn,
                    )
                }
            }
        }
    }

    if (showGotoDialog) {
        val lineCount = lineIndex.lineCount
        CodeTextInputDialog(
            title = "Go to line",
            label = "Line number",
            initialValue = "",
            confirmLabel = "Go",
            keyboardType = KeyboardType.Number,
            validate = { raw ->
                val number = raw.trim().toIntOrNull()
                when {
                    number == null -> "Enter a line number"
                    number < 1 || number > lineCount -> "Enter a line between 1 and $lineCount"
                    else -> null
                }
            },
            onDismiss = { showGotoDialog = false },
            onConfirm = { raw ->
                showGotoDialog = false
                raw.trim().toIntOrNull()?.let(viewModel::goToLine)
            },
        )
    }

    if (showExportConfirm) {
        CodeExportConfirmDialog(
            fileName = state.fileName,
            onDismiss = { showExportConfirm = false },
            onConfirm = {
                showExportConfirm = false
                // Whole current draft: the user exports what they are looking at.
                exportTarget.requestExport(state.fileName, state.body)
            },
        )
    }
}

@Composable
private fun EditorToolbar(
    state: CodeEditorViewModel.State,
    previewSelected: Boolean,
    onFindToggle: () -> Unit,
    onGotoLine: () -> Unit,
    onFontDecrease: () -> Unit,
    onFontIncrease: () -> Unit,
    onWrapToggle: () -> Unit,
    onPreviewToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = NoteZeroSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xxs),
    ) {
        IconButton(
            onClick = onFindToggle,
            modifier = Modifier.testTag(CodeTags.FIND_TOGGLE),
        ) {
            Icon(Icons.Filled.Search, contentDescription = "Find and replace")
        }
        TextButton(
            onClick = onGotoLine,
            modifier = Modifier
                .heightIn(min = NoteZeroSpacing.minTarget)
                .testTag(CodeTags.GOTO_LINE),
        ) { Text("Ln", style = NoteZeroType.Callout) }
        TextButton(
            onClick = onFontDecrease,
            enabled = state.fontSizeSp > CodeEditorPrefs.MIN_FONT_SIZE_SP,
            modifier = Modifier
                .heightIn(min = NoteZeroSpacing.minTarget)
                .testTag(CodeTags.FONT_DECREASE),
        ) { Text("A-", style = NoteZeroType.Callout) }
        TextButton(
            onClick = onFontIncrease,
            enabled = state.fontSizeSp < CodeEditorPrefs.MAX_FONT_SIZE_SP,
            modifier = Modifier
                .heightIn(min = NoteZeroSpacing.minTarget)
                .testTag(CodeTags.FONT_INCREASE),
        ) { Text("A+", style = NoteZeroType.Callout) }
        Text(
            text = "${state.fontSizeSp}sp",
            style = NoteZeroType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilterChip(
            selected = state.wordWrap,
            onClick = onWrapToggle,
            modifier = Modifier
                .heightIn(min = NoteZeroSpacing.minTarget)
                .testTag(CodeTags.WRAP_TOGGLE),
            label = { Text("Wrap", style = NoteZeroType.Caption) },
        )
        if (state.isMarkdown) {
            FilterChip(
                selected = previewSelected,
                onClick = onPreviewToggle,
                modifier = Modifier
                    .heightIn(min = NoteZeroSpacing.minTarget)
                    .testTag(CodeTags.PREVIEW_TOGGLE),
                label = { Text("Preview", style = NoteZeroType.Caption) },
            )
        }
    }
}

/**
 * Second action row under the main toolbar: multi-line indent/outdent, clipboard copy and the
 * explicit decrypted export. Kept separate from [EditorToolbar] so the long-lived
 * find/font/wrap/preview controls keep their positions (and their stable E2E taps).
 */
@Composable
private fun EditorActionBar(
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = NoteZeroSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xxs),
    ) {
        TextButton(
            onClick = onIndent,
            modifier = Modifier
                .heightIn(min = NoteZeroSpacing.minTarget)
                .testTag(CodeTags.INDENT_INCREASE),
        ) { Text("Indent", style = NoteZeroType.Callout) }
        TextButton(
            onClick = onOutdent,
            modifier = Modifier
                .heightIn(min = NoteZeroSpacing.minTarget)
                .testTag(CodeTags.INDENT_DECREASE),
        ) { Text("Outdent", style = NoteZeroType.Callout) }
        TextButton(
            onClick = onCopy,
            modifier = Modifier
                .heightIn(min = NoteZeroSpacing.minTarget)
                .testTag(CodeTags.COPY),
        ) { Text("Copy", style = NoteZeroType.Callout) }
        TextButton(
            onClick = onExport,
            modifier = Modifier
                .heightIn(min = NoteZeroSpacing.minTarget)
                .testTag(CodeTags.EXPORT),
        ) { Text("Export", style = NoteZeroType.Callout) }
    }
}

@Composable
private fun FindReplaceBar(
    state: CodeEditorViewModel.State,
    onQueryChange: (String) -> Unit,
    onReplaceQueryChange: (String) -> Unit,
    onToggleCase: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(modifier = Modifier.padding(horizontal = NoteZeroSpacing.s, vertical = NoteZeroSpacing.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.findQuery,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = NoteZeroType.Mono,
                    placeholder = { Text("Find", style = NoteZeroType.Caption) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(CodeTags.FIND_QUERY),
                )
                Text(
                    text = state.matchLabel,
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = NoteZeroSpacing.xs)
                        .testTag(CodeTags.FIND_MATCH_COUNT),
                )
                IconButton(
                    onClick = onPrevious,
                    enabled = state.matches.isNotEmpty(),
                    modifier = Modifier.testTag(CodeTags.FIND_PREV),
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
                }
                IconButton(
                    onClick = onNext,
                    enabled = state.matches.isNotEmpty(),
                    modifier = Modifier.testTag(CodeTags.FIND_NEXT),
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
                }
                FilterChip(
                    selected = state.caseSensitive,
                    onClick = onToggleCase,
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag(CodeTags.FIND_CASE),
                    label = { Text("Aa", style = NoteZeroType.Caption) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.replaceQuery,
                    onValueChange = onReplaceQueryChange,
                    singleLine = true,
                    textStyle = NoteZeroType.Mono,
                    placeholder = { Text("Replace", style = NoteZeroType.Caption) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(CodeTags.REPLACE_FIELD),
                )
                TextButton(
                    onClick = onReplace,
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag(CodeTags.REPLACE),
                ) { Text("Replace", style = NoteZeroType.Callout) }
                TextButton(
                    onClick = onReplaceAll,
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag(CodeTags.REPLACE_ALL),
                ) { Text("All", style = NoteZeroType.Callout) }
            }
        }
    }
}

@Composable
private fun EditorStatusRow(
    state: CodeEditorViewModel.State,
    cursorLine: Int,
    cursorColumn: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NoteZeroSpacing.m, vertical = NoteZeroSpacing.xxs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Ln $cursorLine, Col $cursorColumn · ${languageLabel(state.language)}",
            style = NoteZeroType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(CodeTags.STATUS),
        )
        if (!state.syntaxHighlighting) {
            Text(
                text = "Plain text — file over 256 KiB",
                style = NoteZeroType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
            .testTag(CodeTags.CONFLICT_BANNER),
        shape = NoteZeroShapes.row,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(NoteZeroSpacing.m)) {
            Text(
                text = "This code file changed elsewhere.",
                style = NoteZeroType.Callout,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onKeepMine,
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag(CodeTags.CONFLICT_KEEP),
                ) { Text("Keep my edits") }
                Spacer(Modifier.padding(horizontal = NoteZeroSpacing.xxs))
                TextButton(
                    onClick = onReload,
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag(CodeTags.CONFLICT_RELOAD),
                ) { Text("Reload latest") }
            }
        }
    }
}

@Composable
private fun CenteredEditorBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(NoteZeroSpacing.gutter),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Save label shown in the top bar. The E2E contract requires the clean state to read
 * exactly "Saved"; the notes editor deliberately uses the same visible convention.
 */
internal fun saveStateLabel(state: CodeEditorViewModel.State): String = when {
    state.loading -> "Loading…"
    state.missing -> "File unavailable"
    state.tooLarge -> "File too large"
    state.conflict -> "Conflict — unsaved edits"
    state.saveStatus == CodeSaveStatus.SAVING -> "Saving…"
    state.saveStatus == CodeSaveStatus.ERROR -> "Save failed"
    state.dirty -> "Unsaved changes"
    else -> "Saved"
}
