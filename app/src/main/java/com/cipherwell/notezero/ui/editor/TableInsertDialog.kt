package com.cipherwell.notezero.ui.editor

import com.cipherwell.notezero.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * "Insert table" dialog: rows/columns (1..[MarkdownTableEdit.MAX_SIZE], numeric keyboard)
 * and an optional header row. The header cells are generated from a string resource so no
 * user-visible English is hardcoded in the composable.
 *
 * Nothing is inserted unless `table_confirm` is pressed; the dialog only reports the
 * validated shape and header labels to the editor, which applies the pure text transform.
 */
@Composable
internal fun TableInsertDialog(
    onDismiss: () -> Unit,
    onConfirm: (rows: Int, cols: Int, headers: List<String>) -> Unit,
) {
    var rowsText by rememberSaveable { mutableStateOf("3") }
    var colsText by rememberSaveable { mutableStateOf("3") }
    var includeHeader by rememberSaveable { mutableStateOf(true) }

    val rows = rowsText.toIntOrNull()
    val cols = colsText.toIntOrNull()
    val rowsValid = rows != null && rows in 1..MarkdownTableEdit.MAX_SIZE
    val colsValid = cols != null && cols in 1..MarkdownTableEdit.MAX_SIZE
    val valid = rowsValid && colsValid

    // 20 translations at most; reused for whichever column count the user confirms.
    val headerLabels = List(MarkdownTableEdit.MAX_SIZE) { index ->
        stringResource(R.string.table_header_cell, index + 1)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("table_insert_dialog"),
        title = { Text(stringResource(R.string.table_insert_title), style = NoteZeroType.Heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = rowsText,
                        onValueChange = { value -> rowsText = digitsOnly(value) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.table_rows_label), style = NoteZeroType.Callout) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("table_rows_field"),
                    )
                    Spacer(Modifier.width(NoteZeroSpacing.s))
                    OutlinedTextField(
                        value = colsText,
                        onValueChange = { value -> colsText = digitsOnly(value) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.table_cols_label), style = NoteZeroType.Callout) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("table_cols_field"),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .clickable { includeHeader = !includeHeader }
                        .padding(vertical = NoteZeroSpacing.xs)
                        .testTag("table_header_toggle"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = includeHeader,
                        onCheckedChange = { includeHeader = it },
                    )
                    Spacer(Modifier.width(NoteZeroSpacing.s))
                    Text(stringResource(R.string.table_header_label), style = NoteZeroType.Callout)
                }
                if (!valid) {
                    Text(
                        text = stringResource(R.string.table_invalid),
                        style = NoteZeroType.Caption,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (rows != null && cols != null) {
                        onConfirm(
                            rows.coerceIn(1, MarkdownTableEdit.MAX_SIZE),
                            cols.coerceIn(1, MarkdownTableEdit.MAX_SIZE),
                            if (includeHeader) headerLabels.take(cols) else emptyList(),
                        )
                    }
                },
                enabled = valid,
                modifier = Modifier.testTag("table_confirm"),
            ) { Text(stringResource(R.string.table_confirm)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("table_cancel"),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Digits only, capped at two characters: the field can never exceed 99. */
private fun digitsOnly(value: String): String =
    value.filter { it in '0'..'9' }.take(2)
