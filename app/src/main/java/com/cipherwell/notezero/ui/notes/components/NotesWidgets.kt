package com.cipherwell.notezero.ui.notes.components

import com.cipherwell.notezero.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.ui.notes.FolderRowModel
import com.cipherwell.notezero.ui.notes.NoteFormatting
import com.cipherwell.notezero.ui.notes.NoteRowModel
import com.cipherwell.notezero.ui.notes.NotesSection
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults

/**
 * Reusable notes widgets (ui-notes scope). All colours come from the theme
 * ([MaterialTheme.colorScheme]) or design tokens — no hex literals here.
 */

/** Floating pill navigation. Settings is delegated to ui-shell via [onOpenSettings]. */
@Composable
fun NotesPillNav(
    current: NotesSection,
    onSectionSelected: (NotesSection) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = NoteZeroShapes.pill,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NoteZeroSpacing.xs, vertical = NoteZeroSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillNavItem(
                selected = current == NotesSection.NOTES,
                label = stringResource(R.string.notes_title),
                testTag = "nav_notes",
                onClick = { onSectionSelected(NotesSection.NOTES) },
            ) { tint ->
                Icon(Icons.Filled.List, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            PillNavItem(
                selected = current == NotesSection.FOLDERS,
                label = stringResource(R.string.notes_nav_folders),
                testTag = "folder_nav",
                onClick = { onSectionSelected(NotesSection.FOLDERS) },
            ) { tint -> FolderGlyph(tint = tint, modifier = Modifier.size(20.dp)) }
            PillNavItem(
                selected = current == NotesSection.SEARCH,
                label = stringResource(R.string.notes_nav_search),
                testTag = "nav_search",
                onClick = { onSectionSelected(NotesSection.SEARCH) },
            ) { tint ->
                Icon(Icons.Filled.Search, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            PillNavItem(
                selected = false,
                label = stringResource(R.string.settings),
                testTag = "settings_button",
                onClick = onOpenSettings,
            ) { tint ->
                Icon(Icons.Filled.Settings, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun PillNavItem(
    selected: Boolean,
    label: String,
    testTag: String,
    onClick: () -> Unit,
    icon: @Composable (tint: Color) -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(NoteZeroShapes.pill)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .then(Modifier.heightIn(min = NoteZeroSpacing.minTarget))
            .testTag(testTag)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = NoteZeroSpacing.m, vertical = NoteZeroSpacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon(tint)
            Text(
                text = label,
                style = NoteZeroType.Caption,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/** Custom vector folder glyph (material-icons-core has no folder icon). */
@Composable
fun FolderGlyph(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val path = Path().apply {
            moveTo(width * 0.06f, height * 0.82f)
            lineTo(width * 0.06f, height * 0.32f)
            lineTo(width * 0.40f, height * 0.32f)
            lineTo(width * 0.50f, height * 0.18f)
            lineTo(width * 0.94f, height * 0.18f)
            lineTo(width * 0.94f, height * 0.82f)
            close()
        }
        drawPath(path, color = tint)
    }
}

@Composable
fun SectionHeader(
    title: String,
    count: Int? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = NoteZeroSpacing.xs, top = NoteZeroSpacing.l, bottom = NoteZeroSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = NoteZeroType.Caption,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (count != null) {
            Text(
                text = "  $count",
                style = NoteZeroType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun TagChip(
    tag: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        modifier = modifier.testTag("tag_chip"),
        selected = selected,
        onClick = onClick,
        label = { Text(text = tag, style = NoteZeroType.Caption, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/**
 * A note card row: title, decrypted preview, folder path, relative edit time and
 * pin/favourite indicators. Long-press opens the context actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteRow(
    row: NoteRowModel,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
    onLockToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = row.item
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            // Distinct tag for the masked state so tests/E2E can assert a locked note is
            // rendered without its content (the tag is not a second visual element).
            .testTag(if (row.masked) "locked_note_row" else "note_row")
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
            ),
        shape = NoteZeroShapes.row,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = NoteZeroSpacing.s, vertical = NoteZeroSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = NoteFormatting.displayTitle(item.title),
                        style = NoteZeroType.Body,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.pinned) {
                        Spacer(Modifier.width(NoteZeroSpacing.xs))
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = stringResource(R.string.cd_notes_pinned),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (item.favorite) {
                        Spacer(Modifier.width(NoteZeroSpacing.xs))
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = stringResource(R.string.cd_notes_favourite),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (item.locked) {
                        Spacer(Modifier.width(NoteZeroSpacing.xs))
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = if (row.masked) {
                                stringResource(R.string.cd_notes_locked)
                            } else {
                                stringResource(R.string.cd_notes_locked_note)
                            },
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                // A masked row carries an empty preview by construction; keep the guard so
                // no future mask regression can render body text.
                val preview = if (row.masked) "" else NoteFormatting.preview(item.body)
                if (preview.isNotEmpty()) {
                    Text(
                        text = preview,
                        style = NoteZeroType.Callout,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = NoteFormatting.relativeTime(item.updatedAt, System.currentTimeMillis()),
                        style = NoteZeroType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (row.folderPath != null) {
                        Text(
                            text = stringResource(R.string.notes_folder_path_suffix, row.folderPath),
                            style = NoteZeroType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag("note_row_menu"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.cd_notes_note_actions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NoteContextMenu(
                    expanded = menuOpen,
                    item = item,
                    masked = row.masked,
                    onDismiss = { menuOpen = false },
                    onTogglePin = { menuOpen = false; onTogglePin() },
                    onToggleFavorite = { menuOpen = false; onToggleFavorite() },
                    onRename = { menuOpen = false; onRename() },
                    onMove = { menuOpen = false; onMove() },
                    onTrash = { menuOpen = false; onTrash() },
                    onLockToggle = { menuOpen = false; onLockToggle() },
                )
            }
        }
    }
}

@Composable
private fun NoteContextMenu(
    expanded: Boolean,
    item: Item,
    masked: Boolean,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
    onLockToggle: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Content actions are unavailable while masked: renaming a mask would write the
        // placeholder over the real title, and move/trash would act on hidden content the
        // user cannot identify. Revealing or removing the lock needs re-auth first.
        if (!masked) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (item.pinned) stringResource(R.string.notes_unpin) else stringResource(R.string.notes_pin),
                        style = NoteZeroType.Callout,
                    )
                },
                onClick = onTogglePin,
            )
            DropdownMenuItem(
                text = {
                    Text(
                        if (item.favorite) {
                            stringResource(R.string.notes_remove_favourite)
                        } else {
                            stringResource(R.string.notes_add_favourite)
                        },
                        style = NoteZeroType.Callout,
                    )
                },
                onClick = onToggleFavorite,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_rename), style = NoteZeroType.Callout) },
                onClick = onRename,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notes_move_to_folder), style = NoteZeroType.Callout) },
                onClick = onMove,
            )
        }
        DropdownMenuItem(
            text = {
                Text(
                    if (item.locked) stringResource(R.string.notes_remove_lock) else stringResource(R.string.notes_lock_note),
                    style = NoteZeroType.Callout,
                )
            },
            onClick = onLockToggle,
            modifier = Modifier.testTag("note_lock_action"),
        )
        if (!masked) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notes_move_to_trash), style = NoteZeroType.Callout, color = MaterialTheme.colorScheme.error) },
                onClick = onTrash,
            )
        }
    }
}

/**
 * Folder row in the Folders tab. Tapping opens the folder; long-press renames/deletes.
 *
 * A locked folder that this session has not verified is rendered as a mask
 * (`locked_folder_row`): its name and counts are hidden and its children are not listed
 * until the user re-authenticates. Removing the lock while masked also requires re-auth.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderRow(
    row: FolderRowModel,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onLockToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(if (row.masked) "locked_folder_row" else "folder_row")
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
        shape = NoteZeroShapes.row,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NoteZeroSpacing.minTarget)
                .padding(horizontal = NoteZeroSpacing.m, vertical = NoteZeroSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    FolderGlyph(
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(NoteZeroSpacing.m))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.folder.name,
                        style = NoteZeroType.Callout,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.folder.locked) {
                        Spacer(Modifier.width(NoteZeroSpacing.xs))
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = if (row.masked) {
                                stringResource(R.string.cd_notes_locked)
                            } else {
                                stringResource(R.string.cd_notes_locked_folder)
                            },
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                val noteCount = pluralStringResource(
                    R.plurals.notes_note_count,
                    row.itemCount,
                    row.itemCount,
                )
                Text(
                    text = if (row.masked) {
                        stringResource(R.string.notes_locked_verify)
                    } else if (row.childFolderCount > 0) {
                        "$noteCount · " + pluralStringResource(
                            R.plurals.notes_folder_count,
                            row.childFolderCount,
                            row.childFolderCount,
                        )
                    } else {
                        noteCount
                    },
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag("folder_row_menu"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.cd_notes_folder_actions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!row.masked) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notes_rename_folder), style = NoteZeroType.Callout) },
                            onClick = { menuOpen = false; onRename() },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (row.folder.locked) {
                                    stringResource(R.string.notes_remove_lock)
                                } else {
                                    stringResource(R.string.notes_lock_folder)
                                },
                                style = NoteZeroType.Callout,
                            )
                        },
                        onClick = { menuOpen = false; onLockToggle() },
                        modifier = Modifier.testTag("folder_lock_action"),
                    )
                    if (!row.masked) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.notes_delete_folder),
                                    style = NoteZeroType.Callout,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Trash row: restore or delete forever. */
@Composable
fun TrashRow(
    row: NoteRowModel,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("trash_row"),
        shape = NoteZeroShapes.row,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NoteZeroSpacing.minTarget)
                .padding(horizontal = NoteZeroSpacing.s, vertical = NoteZeroSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = NoteFormatting.displayTitle(row.item.title),
                        style = NoteZeroType.Callout,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.masked) {
                        Spacer(Modifier.width(NoteZeroSpacing.xs))
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.cd_notes_locked),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Text(
                    text = NoteFormatting.relativeTime(row.item.updatedAt, System.currentTimeMillis()),
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onRestore,
                modifier = Modifier.testTag("trash_restore"),
            ) { Text(stringResource(R.string.common_restore)) }
            IconButton(
                onClick = onDeleteForever,
                modifier = Modifier.testTag("trash_delete_forever"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.cd_notes_delete_forever),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Empty-state block used by all list tabs. */
@Composable
fun NotesEmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NoteZeroSpacing.xxl, vertical = NoteZeroSpacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s),
    ) {
        Text(
            text = title,
            style = NoteZeroType.Heading,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = NoteZeroType.Callout,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) action()
    }
}

/** Simple row action used inside empty states. */
@Composable
fun NotesActionButton(
    label: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = NoteZeroSpacing.minTarget)
            .testTag(testTag),
        contentPadding = PaddingValues(horizontal = NoteZeroSpacing.xl, vertical = NoteZeroSpacing.s),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(NoteZeroSpacing.xs))
        Text(label, style = NoteZeroType.Callout)
    }
}

