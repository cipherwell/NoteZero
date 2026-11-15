package com.cipherwell.notezero.ui.notes.components

import com.cipherwell.notezero.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.ui.notes.FolderRowModel
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/** Single-line text input dialog (rename note, rename folder, create folder). */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String,
    confirmLabel: String,
    dialogTestTag: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(dialogTestTag),
        title = { Text(title, style = NoteZeroType.Heading) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(label, style = NoteZeroType.Callout) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dialog_text_field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                modifier = Modifier.testTag("dialog_confirm"),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_cancel"),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Destructive/neutral confirmation dialog. */
@Composable
fun NotesConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean,
    dialogTestTag: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(dialogTestTag),
        title = { Text(title, style = NoteZeroType.Heading) },
        text = { Text(message, style = NoteZeroType.Callout) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("dialog_confirm"),
            ) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_cancel"),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Folder picker for moving a note; "No folder" puts it back at the vault root. */
@Composable
fun MoveToFolderDialog(
    folders: List<FolderRowModel>,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("move_dialog"),
        title = { Text(stringResource(R.string.notes_move_to_folder), style = NoteZeroType.Heading) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                item(key = "no_folder") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .clickable { onSelect(null) }
                            .padding(vertical = NoteZeroSpacing.s)
                            .testTag("move_target_none"),
                    ) {
                        Text(stringResource(R.string.notes_no_folder), style = NoteZeroType.Callout)
                    }
                }
                items(folders, key = { it.folder.id }) { row ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = NoteZeroSpacing.minTarget)
                                .clickable { onSelect(row.folder.id) }
                                .padding(
                                    start = NoteZeroSpacing.s + (NoteZeroSpacing.l * row.depth),
                                    top = NoteZeroSpacing.s,
                                    bottom = NoteZeroSpacing.s,
                                )
                                .testTag("move_target_folder"),
                        ) {
                            Text(row.folder.name, style = NoteZeroType.Callout)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("dialog_cancel")) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
