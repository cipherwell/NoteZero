package com.cipherwell.notezero.ui.pdf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.cipherwell.notezero.R
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/** Draft of the sticky-note dialog: a new note ([existingIndex] null) or an edited existing one. */
internal data class PdfNoteDraft(
    val page: Int,
    val x: Float,
    val y: Float,
    val text: String,
    val existingIndex: Int?,
)

/**
 * Sticky-note editor. Save stores/updates the note, Delete (only for a stored note) removes it,
 * dismissing writes nothing. The field is capped to the model's UTF-8 byte budget as the user
 * types, so a pasted block cannot make the save throw.
 */
@Composable
internal fun PdfNoteDialog(
    draft: PdfNoteDraft,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var text by remember(draft) { mutableStateOf(draft.text) }
    val existing = draft.existingIndex != null
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("pdf_note_dialog"),
        title = { Text(stringResource(R.string.pdf_note_dialog), style = NoteZeroType.Heading) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = PdfAnnotationEditor.boundedNoteText(it) },
                label = { Text(stringResource(R.string.pdf_note_text), style = NoteZeroType.Callout) },
                placeholder = {
                    Text(stringResource(R.string.pdf_note_placeholder), style = NoteZeroType.Callout)
                },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pdf_note_text"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier
                    .heightIn(min = NoteZeroSpacing.minTarget)
                    .testTag("pdf_note_save"),
            ) {
                Text(stringResource(R.string.pdf_note_save), style = NoteZeroType.Button)
            }
        },
        dismissButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
            ) {
                if (existing) {
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag("pdf_note_delete"),
                    ) {
                        Text(
                            text = stringResource(R.string.common_delete),
                            style = NoteZeroType.Button,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("pdf_note_cancel"),
                ) {
                    Text(stringResource(R.string.common_cancel), style = NoteZeroType.Button)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = NoteZeroShapes.sheet,
    )
}
