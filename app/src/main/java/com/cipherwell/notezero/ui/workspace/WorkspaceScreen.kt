package com.cipherwell.notezero.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherwell.notezero.R
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.ui.attachments.AttachmentFormat
import com.cipherwell.notezero.ui.components.common.NzIconButton
import com.cipherwell.notezero.ui.components.common.NzTextButton
import com.cipherwell.notezero.ui.locks.LockBiometricPrompt
import com.cipherwell.notezero.ui.locks.LockTargetKind
import com.cipherwell.notezero.ui.locks.LockedTarget
import com.cipherwell.notezero.ui.locks.ReauthDialog
import com.cipherwell.notezero.ui.locks.rememberLockReauthController
import com.cipherwell.notezero.ui.notes.NoteFormatting
import com.cipherwell.notezero.ui.notes.components.MoveToFolderDialog
import com.cipherwell.notezero.ui.pdf.PdfViewerScreen
import com.cipherwell.notezero.ui.reader.BookReaderScreen
import com.cipherwell.notezero.ui.sketch.SketchStudioScreen
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Universal encrypted file workspace ("Files"): one file-manager/IDE view over every
 * [com.cipherwell.notezero.model.ItemKind] — notes, code files, PDFs, EPUB books and
 * standalone sketches.
 *
 * Read-only browse + filter/search/sort, multi-select bulk operations (move to folder,
 * lock/unlock, move to trash) and a storage summary computed from the decrypted index only.
 * It does not replace the Notes home, the Library shelf or the readers: NOTE/CODE rows route
 * back through [onOpenNote] into the editor hosted by the notes shell, while PDF/BOOK/SKETCH
 * rows open the existing viewers hosted here.
 *
 * Fail-closed visibility (decision #15): locked items whose id is absent from
 * [NoteRepository.verifiedLockedIds] and items inside a hidden locked folder are excluded
 * entirely; their title/body/folder/tags never enter the row model, the search index or the
 * storage summary. Verified locked item ids still route through the standard
 * [LockReauthController] before content opens, exactly like the Notes list.
 *
 * @param repository the encrypted data layer (only the documented index APIs are used).
 * @param onBack leaves the workspace route.
 * @param onOpenNote opens a NOTE / CODE_FILE in the editor hosted by the notes shell.
 */
@Composable
fun WorkspaceScreen(
    repository: NoteRepository,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    WorkspaceScreenContent(
        repository = repository,
        onBack = onBack,
        onOpenNote = onOpenNote,
        modifier = modifier,
    )
}

/**
 * Test seam for the hosted document screens: the three [@Composable] hosts are injectable so
 * Robolectric tests can verify the routing contract (open the right item, close back to the
 * list) without inflating the platform PDF renderer or the full drawing canvas. Production
 * passes the existing screens through the default arguments.
 */
@Composable
internal fun WorkspaceScreenContent(
    repository: NoteRepository,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
    pdfHost: @Composable (itemId: String, onClose: () -> Unit) -> Unit = { itemId, onClose ->
        PdfViewerScreen(repository = repository, itemId = itemId, onClose = onClose)
    },
    bookHost: @Composable (itemId: String, onClose: () -> Unit) -> Unit = { itemId, onClose ->
        BookReaderScreen(repository = repository, itemId = itemId, onClose = onClose)
    },
    sketchHost: @Composable (itemId: String, onClose: () -> Unit) -> Unit = { itemId, onClose ->
        SketchStudioScreen(repository = repository, itemId = itemId, onClose = onClose)
    },
    biometrics: LockBiometricPrompt? = null,
) {
    val items by repository.items.collectAsStateWithLifecycle()
    val folders by repository.folders.collectAsStateWithLifecycle()
    val verifiedLockedIds by repository.verifiedLockedIds.collectAsStateWithLifecycle()
    val status by repository.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val reauth = rememberLockReauthController(repository, biometrics)
    val dangerColor = LocalNoteZeroColors.current.dangerText

    var filter by remember { mutableStateOf(WorkspaceFilter.ALL) }
    var sort by remember { mutableStateOf(WorkspaceSort.UPDATED) }
    var query by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var actionsMenuOpen by remember { mutableStateOf(false) }
    var moveDialogOpen by remember { mutableStateOf(false) }
    var trashDialogOpen by remember { mutableStateOf(false) }
    var bulkFailure by remember { mutableStateOf(false) }
    var openPdfId by remember { mutableStateOf<String?>(null) }
    var openBookId by remember { mutableStateOf<String?>(null) }
    var openSketchId by remember { mutableStateOf<String?>(null) }

    // One frame of pure query logic. While the vault is locked nothing is visible, even if the
    // repository still holds a stale index (the real one empties it, test doubles may not).
    val index = remember(items, folders, verifiedLockedIds, status, filter, query, sort) {
        if (status != VaultStatus.UNLOCKED) {
            WorkspaceIndex()
        } else {
            WorkspaceQuery.build(
                items = items,
                folders = folders,
                verifiedLockedIds = verifiedLockedIds,
                filter = filter,
                query = query,
                sort = sort,
            )
        }
    }

    // Selected ids only ever refer to currently visible rows. A repository refresh that hides
    // an item (trashed/locked/folder locked elsewhere) silently drops it from the selection.
    val visibleIds = remember(index.allRows) {
        index.allRows.mapTo(mutableSetOf()) { it.id }
    }
    LaunchedEffect(visibleIds) {
        if (selection.isNotEmpty()) {
            val retained = selection.intersect(visibleIds)
            if (retained.size != selection.size) selection = retained
        }
    }

    val selectedRows = remember(index.allRows, selection) {
        if (selection.isEmpty()) emptyList() else index.allRows.filter { it.id in selection }
    }
    val selectionMode = selection.isNotEmpty()
    val allSelectedLocked = selectedRows.isNotEmpty() && selectedRows.all { it.locked }
    val folderChoices = remember(folders, verifiedLockedIds, items) {
        WorkspaceQuery.folderChoices(folders, verifiedLockedIds, items)
    }

    /** Runs [operation] over every selected visible id; one failure reports the shared notice. */
    fun performBulk(operation: suspend (String) -> Boolean) {
        val ids = WorkspaceQuery.selectedVisibleIds(selection, index.allRows)
        selection = emptySet()
        bulkFailure = false
        if (ids.isEmpty()) return
        scope.launch {
            var failed = false
            for (id in ids) {
                val ok = try {
                    operation(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The vault can lock mid-flight: repository calls then throw/fail closed.
                    // Never crash the selection UI and never surface repository detail.
                    false
                }
                if (!ok) failed = true
            }
            if (failed) bulkFailure = true
        }
    }

    fun lockSelection() = performBulk { id -> repository.setItemLocked(id, true) }

    fun unlockSelection() {
        val firstLocked = selectedRows.firstOrNull { it.locked } ?: return
        // Standard re-auth path: no unlocked content is touched before verification. Verified
        // ids short-circuit inside the controller, so this is a no-op for already-revealed rows.
        reauth.request(LockedTarget(firstLocked.id, LockTargetKind.NOTE)) {
            performBulk { id -> repository.setItemLocked(id, false) }
        }
    }

    fun moveSelection(folderId: String?) = performBulk { id ->
        repository.moveItem(id, folderId)
        true
    }

    fun trashSelection() = performBulk { id ->
        repository.trashItem(id)
        true
    }

    fun openRow(row: WorkspaceRow) {
        val open: () -> Unit = {
            when (row.type) {
                WorkspaceType.NOTE, WorkspaceType.CODE -> onOpenNote(row.id)
                WorkspaceType.PDF -> openPdfId = row.id
                WorkspaceType.BOOK -> openBookId = row.id
                WorkspaceType.SKETCH -> openSketchId = row.id
            }
        }
        if (row.locked) {
            // Never bypass the lock gate: even a session-verified id goes through the controller.
            reauth.request(LockedTarget(row.id, LockTargetKind.NOTE)) { open() }
        } else {
            open()
        }
    }

    // A lock mid-session closes every hosted surface, dialog and the selection.
    LaunchedEffect(status) {
        if (status != VaultStatus.UNLOCKED) {
            selection = emptySet()
            query = ""
            moveDialogOpen = false
            trashDialogOpen = false
            sortMenuOpen = false
            actionsMenuOpen = false
            bulkFailure = false
            openPdfId = null
            openBookId = null
            openSketchId = null
        }
    }

    val pdfId = openPdfId
    val bookId = openBookId
    val sketchId = openSketchId
    val hosting = pdfId != null || bookId != null || sketchId != null

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("workspace_screen"),
    ) {
        when {
            pdfId != null -> pdfHost(pdfId) { openPdfId = null }

            bookId != null -> bookHost(bookId) { openBookId = null }

            sketchId != null -> sketchHost(sketchId) { openSketchId = null }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding(),
            ) {
                WorkspaceTopBar(
                    selectionMode = selectionMode,
                    selectedCount = selectedRows.size,
                    onBack = onBack,
                    sortMenuOpen = sortMenuOpen,
                    onSortClick = { sortMenuOpen = true },
                    onSortDismiss = { sortMenuOpen = false },
                    onSortSelected = { chosen ->
                        sort = chosen
                        sortMenuOpen = false
                    },
                    actionsMenuOpen = actionsMenuOpen,
                    onActionsClick = { actionsMenuOpen = true },
                    onActionsDismiss = { actionsMenuOpen = false },
                    onSelectAll = {
                        actionsMenuOpen = false
                        selection = WorkspaceQuery.selectAll(index.rows)
                    },
                )

                if (selectionMode) {
                    WorkspaceBulkActions(
                        allLocked = allSelectedLocked,
                        onSelectAll = { selection = WorkspaceQuery.selectAll(index.rows) },
                        onClearSelection = { selection = emptySet() },
                        onMove = { moveDialogOpen = true },
                        onLockToggle = { if (allSelectedLocked) unlockSelection() else lockSelection() },
                        onTrash = { trashDialogOpen = true },
                    )
                } else {
                    WorkspaceSearchField(
                        query = query,
                        onQueryChange = { value ->
                            query = value
                            bulkFailure = false
                        },
                    )
                    WorkspaceFilterRow(
                        filter = filter,
                        onFilterChange = { chosen ->
                            filter = chosen
                            bulkFailure = false
                        },
                    )
                    Text(
                        text = stringResource(
                            R.string.workspace_storage_summary,
                            index.storage.items,
                            index.storage.attachments,
                            AttachmentFormat.size(index.storage.attachmentBytes),
                        ),
                        style = NoteZeroType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = NoteZeroSpacing.l,
                                end = NoteZeroSpacing.l,
                                top = NoteZeroSpacing.xs,
                                bottom = NoteZeroSpacing.xs,
                            )
                            .testTag("workspace_storage_summary"),
                    )
                }

                if (bulkFailure) {
                    Text(
                        text = stringResource(R.string.workspace_bulk_failed),
                        style = NoteZeroType.Caption,
                        color = dangerColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.xs)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("workspace_bulk_failed"),
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when {
                        index.isEmpty -> WorkspaceEmptyState()

                        index.hasNoMatches -> Text(
                            text = stringResource(R.string.workspace_no_matches),
                            style = NoteZeroType.Callout,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(NoteZeroSpacing.xxl)
                                .testTag("workspace_no_matches"),
                        )

                        else -> LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("workspace_list"),
                            contentPadding = PaddingValues(
                                start = NoteZeroSpacing.l,
                                end = NoteZeroSpacing.l,
                                top = NoteZeroSpacing.xs,
                                bottom = NoteZeroSpacing.xxl,
                            ),
                            verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
                        ) {
                            items(index.rows, key = { it.id }) { row ->
                                WorkspaceRowView(
                                    row = row,
                                    selectionMode = selectionMode,
                                    selected = row.id in selection,
                                    onClick = {
                                        if (selectionMode) {
                                            selection = WorkspaceQuery.toggle(selection, row.id)
                                        } else {
                                            openRow(row)
                                        }
                                    },
                                    onLongClick = {
                                        selection = selection + row.id
                                    },
                                    onToggleSelection = {
                                        selection = WorkspaceQuery.toggle(selection, row.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (moveDialogOpen) {
            MoveToFolderDialog(
                folders = folderChoices,
                onDismiss = { moveDialogOpen = false },
                onSelect = { folderId ->
                    moveDialogOpen = false
                    moveSelection(folderId)
                },
            )
        }

        if (trashDialogOpen) {
            WorkspaceTrashDialog(
                count = selectedRows.size,
                onDismiss = { trashDialogOpen = false },
                onConfirm = {
                    trashDialogOpen = false
                    trashSelection()
                },
            )
        }

        ReauthDialog(reauth)

        BackHandler(enabled = !hosting) {
            if (selectionMode) {
                selection = emptySet()
            } else {
                onBack()
            }
        }
    }
}

// ---- Top bar --------------------------------------------------------------

@Composable
private fun WorkspaceTopBar(
    selectionMode: Boolean,
    selectedCount: Int,
    onBack: () -> Unit,
    sortMenuOpen: Boolean,
    onSortClick: () -> Unit,
    onSortDismiss: () -> Unit,
    onSortSelected: (WorkspaceSort) -> Unit,
    actionsMenuOpen: Boolean,
    onActionsClick: () -> Unit,
    onActionsDismiss: () -> Unit,
    onSelectAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = NoteZeroSpacing.s, end = NoteZeroSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NzIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.cd_workspace_back),
            onClick = onBack,
            modifier = Modifier.testTag("workspace_back"),
        )
        if (selectionMode) {
            Text(
                text = stringResource(R.string.workspace_selected_count, selectedCount),
                style = NoteZeroType.Heading,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = NoteZeroSpacing.s)
                    .testTag("workspace_selected_count"),
            )
        } else {
            Text(
                text = stringResource(R.string.workspace_title),
                style = NoteZeroType.Heading,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = NoteZeroSpacing.s)
                    .testTag("workspace_title"),
            )
            Box {
                NzTextButton(
                    text = stringResource(R.string.workspace_sort),
                    onClick = onSortClick,
                    modifier = Modifier.testTag("workspace_sort"),
                )
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = onSortDismiss,
                    modifier = Modifier.testTag("workspace_sort_menu"),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.workspace_sort_updated), style = NoteZeroType.Callout) },
                        onClick = { onSortSelected(WorkspaceSort.UPDATED) },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag("workspace_sort_updated"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.workspace_sort_name), style = NoteZeroType.Callout) },
                        onClick = { onSortSelected(WorkspaceSort.NAME) },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag("workspace_sort_name"),
                    )
                }
            }
            Box {
                NzIconButton(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.workspace_more_actions),
                    onClick = onActionsClick,
                    modifier = Modifier.testTag("workspace_more_actions"),
                )
                DropdownMenu(
                    expanded = actionsMenuOpen,
                    onDismissRequest = onActionsDismiss,
                    modifier = Modifier.testTag("workspace_actions_menu"),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.cd_workspace_select_all), style = NoteZeroType.Callout) },
                        onClick = onSelectAll,
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag("workspace_select_all"),
                    )
                }
            }
        }
    }
}

// ---- Search / filter ------------------------------------------------------

@Composable
private fun WorkspaceSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.s)
            .testTag("workspace_search"),
        placeholder = {
            Text(
                text = stringResource(R.string.workspace_search_placeholder),
                style = NoteZeroType.Callout,
            )
        },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.testTag("workspace_clear_search"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.cd_workspace_clear_search),
                    )
                }
            }
        },
        singleLine = true,
    )
}

@Composable
private fun WorkspaceFilterRow(
    filter: WorkspaceFilter,
    onFilterChange: (WorkspaceFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = NoteZeroSpacing.l),
        horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkspaceFilter.entries.forEach { candidate ->
            FilterChip(
                selected = filter == candidate,
                onClick = { onFilterChange(candidate) },
                modifier = Modifier.testTag(filterTag(candidate)),
                label = {
                    Text(
                        text = stringResource(filterLabel(candidate)),
                        style = NoteZeroType.Caption,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

// ---- Selection actions ----------------------------------------------------

@Composable
private fun WorkspaceBulkActions(
    allLocked: Boolean,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onMove: () -> Unit,
    onLockToggle: () -> Unit,
    onTrash: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = NoteZeroSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NzTextButton(
            text = stringResource(R.string.cd_workspace_select_all),
            onClick = onSelectAll,
            modifier = Modifier.testTag("cd_workspace_select_all"),
        )
        NzTextButton(
            text = stringResource(R.string.workspace_clear_selection),
            onClick = onClearSelection,
            modifier = Modifier.testTag("workspace_clear_selection"),
        )
        NzTextButton(
            text = stringResource(R.string.workspace_move),
            onClick = onMove,
            modifier = Modifier.testTag("workspace_move"),
        )
        if (allLocked) {
            NzTextButton(
                text = stringResource(R.string.workspace_unlock),
                onClick = onLockToggle,
                modifier = Modifier.testTag("workspace_unlock"),
            )
        } else {
            NzTextButton(
                text = stringResource(R.string.workspace_lock),
                onClick = onLockToggle,
                modifier = Modifier.testTag("workspace_lock"),
            )
        }
        NzTextButton(
            text = stringResource(R.string.workspace_trash),
            onClick = onTrash,
            modifier = Modifier.testTag("workspace_trash"),
        )
    }
}

// ---- Rows -----------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceRowView(
    row: WorkspaceRow,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    val typeText = stringResource(typeLabel(row.type))
    val title = row.title.ifBlank { typeText }
    val selectLabel = stringResource(R.string.cd_workspace_select, title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NoteZeroSpacing.minTarget)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = NoteZeroSpacing.s, vertical = NoteZeroSpacing.s)
            .testTag("workspace_item"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Box(
                modifier = Modifier.size(NoteZeroSpacing.minTarget),
                contentAlignment = Alignment.Center,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier
                        .semantics { contentDescription = selectLabel }
                        .testTag("workspace_item_checkbox"),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = typeText,
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.testTag(typeTag(row.type)),
                )
                Spacer(Modifier.width(NoteZeroSpacing.s))
                Text(
                    text = title,
                    style = NoteZeroType.Body,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.locked) {
                    Spacer(Modifier.width(NoteZeroSpacing.xs))
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = stringResource(R.string.workspace_locked_content),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(14.dp)
                            .testTag("workspace_locked_glyph"),
                    )
                }
            }
            Text(
                text = row.folderName ?: stringResource(R.string.workspace_no_folder),
                style = NoteZeroType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("workspace_folder"),
            )
            val metadata = rowMetadata(row)
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata,
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("workspace_metadata"),
                )
            }
            val snippet = row.snippet
            if (snippet != null) {
                Text(
                    text = snippet,
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("workspace_snippet"),
                )
            }
        }
    }
}

/** `Locked · 5 min ago · 2 attachments · 1.0 KB` — all content already decrypted and visible. */
@Composable
private fun rowMetadata(row: WorkspaceRow): String {
    val now = remember { System.currentTimeMillis() }
    val parts = ArrayList<String>(4)
    if (row.locked) parts += stringResource(R.string.workspace_locked)
    val time = NoteFormatting.relativeTime(row.updatedAt, now)
    if (time.isNotEmpty()) parts += time
    if (row.attachmentCount > 0) {
        parts += pluralStringResource(
            R.plurals.attach_count,
            row.attachmentCount,
            row.attachmentCount,
        )
    }
    if (row.attachmentBytes > 0) parts += AttachmentFormat.size(row.attachmentBytes)
    return parts.joinToString(" · ")
}

// ---- Empty state ----------------------------------------------------------

@Composable
private fun WorkspaceEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(NoteZeroSpacing.xxl)
            .testTag("workspace_empty"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.workspace_empty_title),
            style = NoteZeroType.Heading,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(NoteZeroSpacing.s))
        Text(
            text = stringResource(R.string.workspace_empty_message),
            style = NoteZeroType.Callout,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---- Dialogs --------------------------------------------------------------

@Composable
private fun WorkspaceTrashDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("workspace_trash_dialog"),
        title = {
            Text(
                text = pluralStringResource(R.plurals.workspace_trash_confirm_title, count, count),
                style = NoteZeroType.Heading,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.workspace_trash_confirm_message),
                style = NoteZeroType.Callout,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("workspace_trash_confirm"),
            ) {
                Text(stringResource(R.string.workspace_trash_confirm_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("workspace_cancel"),
            ) {
                Text(stringResource(R.string.workspace_cancel))
            }
        },
    )
}

// ---- Pure resource mapping ------------------------------------------------

private fun filterTag(filter: WorkspaceFilter): String = when (filter) {
    WorkspaceFilter.ALL -> "workspace_filter_all"
    WorkspaceFilter.NOTES -> "workspace_filter_notes"
    WorkspaceFilter.CODE -> "workspace_filter_code"
    WorkspaceFilter.PDFS -> "workspace_filter_pdfs"
    WorkspaceFilter.BOOKS -> "workspace_filter_books"
    WorkspaceFilter.DRAWINGS -> "workspace_filter_drawings"
}

private fun filterLabel(filter: WorkspaceFilter): Int = when (filter) {
    WorkspaceFilter.ALL -> R.string.workspace_filter_all
    WorkspaceFilter.NOTES -> R.string.workspace_filter_notes
    WorkspaceFilter.CODE -> R.string.workspace_filter_code
    WorkspaceFilter.PDFS -> R.string.workspace_filter_pdfs
    WorkspaceFilter.BOOKS -> R.string.workspace_filter_books
    WorkspaceFilter.DRAWINGS -> R.string.workspace_filter_drawings
}

private fun typeTag(type: WorkspaceType): String = when (type) {
    WorkspaceType.NOTE -> "workspace_type_note"
    WorkspaceType.CODE -> "workspace_type_code"
    WorkspaceType.PDF -> "workspace_type_pdf"
    WorkspaceType.BOOK -> "workspace_type_book"
    WorkspaceType.SKETCH -> "workspace_type_sketch"
}

private fun typeLabel(type: WorkspaceType): Int = when (type) {
    WorkspaceType.NOTE -> R.string.workspace_type_note
    WorkspaceType.CODE -> R.string.workspace_type_code
    WorkspaceType.PDF -> R.string.workspace_type_pdf
    WorkspaceType.BOOK -> R.string.workspace_type_book
    WorkspaceType.SKETCH -> R.string.workspace_type_sketch
}
