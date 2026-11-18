package com.cipherwell.notezero.ui.code

import com.cipherwell.notezero.R
import com.cipherwell.notezero.code.CodeFormat
import com.cipherwell.notezero.code.CodeFormats
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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

import com.cipherwell.notezero.ui.components.common.NzSelectableChip
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

/**
 * Template chip mapping: label resource + stable tag for each built-in template.
 * Kept next to the dialog so the snippets themselves stay a pure, JVM-tested object.
 */
private data class TemplateChip(
    val template: CodeTemplate,
    val tag: String,
    val labelRes: Int,
)

private val templateChips = listOf(
    TemplateChip(CodeTemplates.blank, CodeTags.TEMPLATE_BLANK, R.string.code_template_blank),
    TemplateChip(CodeTemplates.kotlin, CodeTags.TEMPLATE_KOTLIN, R.string.code_template_kotlin),
    TemplateChip(CodeTemplates.python, CodeTags.TEMPLATE_PYTHON, R.string.code_template_python),
    TemplateChip(CodeTemplates.markdown, CodeTags.TEMPLATE_MARKDOWN, R.string.code_template_markdown),
    TemplateChip(CodeTemplates.html, CodeTags.TEMPLATE_HTML, R.string.code_template_html),
    TemplateChip(CodeTemplates.json, CodeTags.TEMPLATE_JSON, R.string.code_template_json),
)

/**
 * New-code-file dialog: a name field, a horizontal format picker (`.txt`, `.md`, `.html`,
 * `.py`, `.kt`, …) and a template picker that also supplies the initial body.
 *
 * Picking a format rewrites the extension of the typed name, so the created item always gets
 * a well-known extension and the editor picks the matching syntax. Picking a template swaps
 * the suggested name, the extension and the starter body; picking a format that does not
 * match the template keeps the name/extension but falls back to the blank template body so a
 * Kotlin snippet can never be silently stored behind a `.py` name.
 *
 * Confirm reports a [NewCodeFileRequest] (name + body). The legacy `onConfirm(name)` callback
 * is still invoked when no request callback is supplied, so the not-yet-migrated notes-shell
 * call site keeps working while the Lead wires the body through.
 *
 * Pure format/template rules live in [CodeFormats]/[CodeTemplates] (unit-tested); this
 * composable only wires them to UI. Tags: [CodeTags.DIALOG_FIELD], [CodeTags.DIALOG_CONFIRM],
 * [CodeTags.DIALOG_CANCEL], `code_format_<extension>` for each format chip,
 * `code_new_file_format_row` for the format picker, [CodeTags.TEMPLATE_LABEL] for the
 * template label and one `code_template_<id>` tag per template chip.
 */
@Composable
fun NewCodeFileDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    validate: (String) -> String?,
    onConfirm: (String) -> Unit = {},
    onConfirmRequest: ((NewCodeFileRequest) -> Unit)? = null,
    initialFormat: CodeFormat = CodeFormats.default,
) {
    var selectedExtension by rememberSaveable { mutableStateOf(initialFormat.extension) }
    var name by rememberSaveable { mutableStateOf(CodeFormats.defaultName(initialFormat)) }
    var selectedTemplateId by rememberSaveable { mutableStateOf(CodeTemplates.blank.id) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun selectFormat(format: CodeFormat) {
        name = CodeFormats.applyFormat(name, format)
        selectedExtension = format.extension
        if (CodeTemplates.byId(selectedTemplateId).format.extension != format.extension) {
            selectedTemplateId = CodeTemplates.blank.id
        }
        error = null
    }

    fun selectTemplate(template: CodeTemplate) {
        selectedTemplateId = template.id
        selectedExtension = template.format.extension
        name = template.suggestedName
        error = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = NoteZeroType.Heading) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.code_file_name), style = NoteZeroType.Callout) },
                    placeholder = {
                        Text(stringResource(R.string.code_untitled_placeholder), style = NoteZeroType.Callout)
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CodeTags.DIALOG_FIELD),
                )
                Spacer(Modifier.size(NoteZeroSpacing.s))
                Text(
                    text = stringResource(R.string.code_new_file_format),
                    style = NoteZeroType.FieldLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(NoteZeroSpacing.xs))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag("code_new_file_format_row"),
                    horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
                ) {
                    CodeFormats.all.forEach { format ->
                        NzSelectableChip(
                            label = ".${format.extension}",
                            selected = format.extension == selectedExtension,
                            onClick = { selectFormat(format) },
                            modifier = Modifier.testTag("code_format_${format.extension}"),
                        )
                    }
                }
                Spacer(Modifier.size(NoteZeroSpacing.s))
                Text(
                    text = stringResource(R.string.code_template_label),
                    style = NoteZeroType.FieldLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(CodeTags.TEMPLATE_LABEL),
                )
                Spacer(Modifier.size(NoteZeroSpacing.xs))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
                ) {
                    templateChips.forEach { chip ->
                        NzSelectableChip(
                            label = stringResource(chip.labelRes),
                            selected = chip.template.id == selectedTemplateId,
                            onClick = { selectTemplate(chip.template) },
                            modifier = Modifier.testTag(chip.tag),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val resolved = CodeFormats.resolveName(name, formatFor(selectedExtension))
                    val problem = validate(resolved)
                    if (problem == null) {
                        val request = NewCodeFileRequest(
                            name = resolved,
                            body = CodeTemplates.byId(selectedTemplateId).body,
                        )
                        if (onConfirmRequest != null) {
                            onConfirmRequest(request)
                        } else {
                            onConfirm(request.name)
                        }
                    } else {
                        error = problem
                    }
                },
                modifier = Modifier
                    .heightIn(min = NoteZeroSpacing.minTarget)
                    .testTag(CodeTags.DIALOG_CONFIRM),
            ) {
                Text(confirmLabel, style = NoteZeroType.Button)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = NoteZeroSpacing.minTarget)
                    .testTag(CodeTags.DIALOG_CANCEL),
            ) {
                Text(stringResource(R.string.common_cancel), style = NoteZeroType.Button)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = com.cipherwell.notezero.ui.theme.NoteZeroShapes.sheet,
    )
}

/** The currently selected format, falling back to the default when the tag is unknown. */
private fun formatFor(extension: String): CodeFormat =
    CodeFormats.forExtension(extension) ?: CodeFormats.default
