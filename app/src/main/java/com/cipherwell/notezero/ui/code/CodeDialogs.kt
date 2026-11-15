package com.cipherwell.notezero.ui.code

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * Shared single-line input dialog for create-file, create-folder, rename and go-to-line.
 *
 * The stable E2E tags [CodeTags.DIALOG_FIELD]/[CodeTags.DIALOG_CONFIRM]/[CodeTags.DIALOG_CANCEL]
 * live here and are never duplicated: only one of these dialogs can be visible at a time.
 * [validate] returns an error message (shown inline, dialog stays open) or null.
 */
@Composable
fun CodeTextInputDialog(
    title: String,
    label: String,
    initialValue: String,
    confirmLabel: String,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDismiss: () -> Unit,
    validate: (String) -> String?,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = NoteZeroType.Heading) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = null
                    },
                    singleLine = true,
                    label = { Text(label, style = NoteZeroType.Callout) },
                    placeholder = placeholder?.let { hint ->
                        { Text(hint, style = NoteZeroType.Callout) }
                    },
                    isError = error != null,
                    supportingText = {
                        if (error != null) {
                            Text(
                                text = error.orEmpty(),
                                style = NoteZeroType.Caption,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag(CodeTags.DIALOG_ERROR),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CodeTags.DIALOG_FIELD),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val problem = validate(value)
                    if (problem == null) {
                        onConfirm(value)
                    } else {
                        error = problem
                    }
                },
                modifier = Modifier.testTag(CodeTags.DIALOG_CONFIRM),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(CodeTags.DIALOG_CANCEL),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Destructive confirmation dialog (delete file, delete folder). */
@Composable
fun CodeConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(CodeTags.DELETE_DIALOG),
        title = { Text(title, style = NoteZeroType.Heading) },
        text = { Text(message, style = NoteZeroType.Callout) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(CodeTags.DIALOG_CONFIRM),
            ) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(CodeTags.DIALOG_CANCEL),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Explicit gate for the "Export decrypted copy" action: the user must acknowledge that the
 * copy is written UNENCRYPTED outside the vault before the SAF picker is launched.
 *
 * The dedicated [CodeTags.EXPORT_CONFIRM]/[CodeTags.EXPORT_CANCEL] tags are contract-stable —
 * they are deliberately not the shared dialog tags, because this warning is a security gate.
 */
@Composable
fun CodeExportConfirmDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(CodeTags.EXPORT_DIALOG),
        title = { Text(stringResource(R.string.code_export_title), style = NoteZeroType.Heading) },
        text = {
            Text(
                text = stringResource(R.string.code_export_message, fileName),
                style = NoteZeroType.Callout,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(CodeTags.EXPORT_CONFIRM),
            ) {
                Text(stringResource(R.string.code_export_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(CodeTags.EXPORT_CANCEL),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Folder picker for moving a code file. "Vault root" moves the file out of any folder;
 * rows are indented by tree depth.
 */
@Composable
fun CodeMoveDialog(
    targets: List<FolderChoice>,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(CodeTags.MOVE_DIALOG),
        title = { Text(stringResource(R.string.code_move_file), style = NoteZeroType.Heading) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                item(key = "root") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .clickable { onSelect(null) }
                            .padding(vertical = NoteZeroSpacing.s)
                            .testTag(CodeTags.MOVE_ROOT),
                    ) {
                        Text(stringResource(R.string.code_vault_root), style = NoteZeroType.Callout)
                    }
                }
                items(targets, key = { it.folder.id }) { choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .clickable { onSelect(choice.folder.id) }
                            .padding(
                                start = NoteZeroSpacing.s + (NoteZeroSpacing.l * choice.depth),
                                top = NoteZeroSpacing.s,
                                bottom = NoteZeroSpacing.s,
                            )
                            .testTag(CodeTags.MOVE_TARGET),
                    ) {
                        Text(choice.folder.name, style = NoteZeroType.Callout)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(CodeTags.DIALOG_CANCEL),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
