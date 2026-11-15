package com.cipherwell.notezero.ui.components.common

import com.cipherwell.notezero.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * Confirmation dialog. [destructive] only changes the colour of the confirm label —
 * the label text always states the consequence, and the message does too, so meaning
 * never depends on colour.
 */
@Composable
fun NoteZeroConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = null,
    destructive: Boolean = false,
) {
    val resolvedDismissLabel = dismissLabel ?: stringResource(R.string.common_cancel)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(text = title, style = NoteZeroType.Heading) },
        text = { Text(text = message, style = NoteZeroType.Body) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = NoteZeroSpacing.minTarget),
            ) {
                Text(
                    text = confirmLabel,
                    style = NoteZeroType.Button,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        LocalNoteZeroColors.current.accentText
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = NoteZeroSpacing.minTarget),
            ) {
                Text(text = resolvedDismissLabel, style = NoteZeroType.Button)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = NoteZeroShapes.sheet,
    )
}

/**
 * Full-screen blocking progress overlay for vault operations. It consumes input and
 * the back gesture, exposes the label to accessibility services, and never shows
 * secret material.
 */
@Composable
fun NzLoadingOverlay(
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNoteZeroColors.current
    BackHandler(enabled = true) { /* swallow back while a vault operation is running */ }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.scrim)
            .pointerInput(Unit) { detectTapGestures { /* consume */ } }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = NoteZeroShapes.card,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(NoteZeroSpacing.xxl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = colors.accentIcon,
                )
                Spacer(Modifier.width(NoteZeroSpacing.l))
                Text(text = label, style = NoteZeroType.Body)
            }
        }
    }
}

/** Opaque scrim used while the vault transitions to LOCKED (no content flash-through). */
@Composable
fun NzScrim(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalNoteZeroColors.current.scrim),
        contentAlignment = Alignment.Center,
    ) {}
}

/** Consistent vertical gap between stacked form controls. */
@Composable
fun NzFormSpacer(height: Dp = NoteZeroSpacing.l) {
    Spacer(modifier = Modifier.height(height))
}

/** Bottom content inset for scrollable forms so the last control clears system bars. */
@Composable
fun NzBottomSpacer() {
    Spacer(modifier = Modifier.height(NoteZeroSpacing.xxxl))
}
