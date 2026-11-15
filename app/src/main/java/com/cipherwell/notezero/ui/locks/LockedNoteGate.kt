package com.cipherwell.notezero.ui.locks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.ui.components.common.NzPrimaryButton
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * Stand-in for the editor when a locked note has not been re-verified in this session
 * (decision #15). Nothing of the note is rendered — no title, no body, no attachment names,
 * no word count — because the editor screen only creates its ViewModel after this gate is
 * passed, so a locked note's content is never even loaded into editor state.
 */
@Composable
fun LockedNoteGate(
    onReveal: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("editor_locked_gate"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NoteZeroSpacing.xs, vertical = NoteZeroSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag("editor_locked_back"),
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to notes")
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = NoteZeroSpacing.xxl),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.m),
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = LockVisibility.MASKED_NOTE_TITLE,
                    style = NoteZeroType.Heading,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "This note is locked. Verify it's you to show its content; " +
                        "it stays encrypted on this device.",
                    style = NoteZeroType.Callout,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(NoteZeroSpacing.s))
                NzPrimaryButton(
                    text = "Reveal note",
                    onClick = onReveal,
                    modifier = Modifier.testTag("editor_locked_reveal"),
                )
            }
        }
    }
}
