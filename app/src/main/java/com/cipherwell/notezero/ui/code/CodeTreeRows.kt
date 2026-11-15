package com.cipherwell.notezero.ui.code

import com.cipherwell.notezero.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.code.CodeLanguage
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/** Indent step per tree level; keeps nested rows visually scannable at large type scales. */
private val IndentStep = 18.dp

/** Friendly display name for a language (never derived from file content). */
fun languageLabel(language: CodeLanguage): String = when (language) {
    CodeLanguage.KOTLIN -> "Kotlin"
    CodeLanguage.JAVA -> "Java"
    CodeLanguage.JAVASCRIPT -> "JavaScript"
    CodeLanguage.TYPESCRIPT -> "TypeScript"
    CodeLanguage.PYTHON -> "Python"
    CodeLanguage.JSON -> "JSON"
    CodeLanguage.XML -> "XML"
    CodeLanguage.MARKDOWN -> "Markdown"
    CodeLanguage.SHELL -> "Shell"
    CodeLanguage.PLAIN -> "Plain text"
}

/**
 * One folder row in the code file tree: 48dp target, expand/collapse on the whole row,
 * overflow menu for create/rename/delete. Icon-only buttons carry content descriptions.
 */
@Composable
fun CodeFolderRow(
    node: CodeTreeNode.FolderNode,
    onToggle: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NoteZeroSpacing.minTarget)
            .padding(start = IndentStep * node.depth)
            .clickable(
                onClickLabel = if (node.expanded) {
                    stringResource(R.string.cd_code_collapse_folder)
                } else {
                    stringResource(R.string.cd_code_expand_folder)
                },
                onClick = onToggle,
            )
            .testTag(CodeTags.FOLDER_ROW),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (node.expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
            contentDescription = if (node.expanded) {
                stringResource(R.string.cd_code_collapse_folder)
            } else {
                stringResource(R.string.cd_code_expand_folder)
            },
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = node.folder.name,
            style = NoteZeroType.Body,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = NoteZeroSpacing.xs),
        )
        Text(
            text = pluralStringResource(
                R.plurals.code_file_count,
                node.fileCount,
                node.fileCount,
            ),
            style = NoteZeroType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.testTag(CodeTags.FOLDER_MORE),
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_code_folder_actions))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_new_file_here), style = NoteZeroType.Callout) },
                onClick = {
                    menuOpen = false
                    onNewFile()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_new_folder_here), style = NoteZeroType.Callout) },
                onClick = {
                    menuOpen = false
                    onNewFolder()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_rename_folder_title), style = NoteZeroType.Callout) },
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_delete_folder), style = NoteZeroType.Callout) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/**
 * One code-file row: opens the editor on tap, overflow menu for rename/move/delete.
 * The language badge is derived from the file extension only.
 */
@Composable
fun CodeFileRow(
    node: CodeTreeNode.FileNode,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NoteZeroSpacing.minTarget)
            .padding(start = IndentStep * node.depth)
            .clickable(onClickLabel = stringResource(R.string.cd_code_open_file), onClick = onOpen)
            .testTag(CodeTags.FILE_ROW),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(24.dp))
        Text(
            text = node.item.title,
            style = NoteZeroType.Mono,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = NoteZeroSpacing.xs),
        )
        Text(
            text = languageLabel(CodeTree.languageFor(node.item.title)),
            style = NoteZeroType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.testTag(CodeTags.FILE_MORE),
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_code_file_actions))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_rename_file_title), style = NoteZeroType.Callout) },
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_move_file), style = NoteZeroType.Callout) },
                onClick = {
                    menuOpen = false
                    onMove()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_delete_file), style = NoteZeroType.Callout) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}
