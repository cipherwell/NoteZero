package com.cipherwell.notezero.ui.locks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import com.cipherwell.notezero.ui.components.common.SecureTextField
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * Master-password fallback for revealing locked content (decision #15).
 *
 * Unlike the vault unlock screen this does **not** start a new session: the repository
 * verifies the password against the stored envelope and the UI simply records the id as
 * revealed for this session ([LockReauthController] -> `markLockVerified`). The password is
 * never logged; cancel keeps the note/folder masked.
 */
@Composable
fun ReauthDialog(controller: LockReauthController) {
    val state = controller.state
    val target = state.target ?: return
    if (!state.dialogVisible) return
    val danger = LocalNoteZeroColors.current.dangerText
    val lockable = when (target.kind) {
        LockTargetKind.NOTE -> "note"
        LockTargetKind.FOLDER -> "folder"
    }
    AlertDialog(
        onDismissRequest = controller::cancel,
        modifier = Modifier.testTag("reauth_dialog"),
        title = {
            Text(
                text = if (target.kind == LockTargetKind.NOTE) "Locked note" else "Locked folder",
                style = NoteZeroType.Heading,
            )
        },
        text = {
            Column {
                Text(
                    text = "Enter your master password to reveal this $lockable. " +
                        "It stays hidden until you verify; your vault stays unlocked.",
                    style = NoteZeroType.Callout,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(NoteZeroSpacing.m))
                SecureTextField(
                    value = state.password,
                    onValueChange = controller::updatePassword,
                    label = "Master password",
                    enabled = !state.verifying && !state.cooldown,
                    isError = state.error != null,
                    modifier = Modifier
                        .testTag("reauth_password"),
                    imeAction = ImeAction.Done,
                    onImeAction = controller::submitPassword,
                )
                val error = state.error
                if (error != null) {
                    Spacer(Modifier.height(NoteZeroSpacing.s))
                    Text(
                        text = error,
                        style = NoteZeroType.Caption,
                        color = danger,
                        modifier = Modifier.testTag("reauth_error"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = controller::submitPassword,
                enabled = !state.verifying && !state.cooldown,
                modifier = Modifier.testTag("reauth_confirm"),
            ) {
                Text(if (state.verifying) "Verifying…" else "Reveal")
            }
        },
        dismissButton = {
            TextButton(
                onClick = controller::cancel,
                modifier = Modifier.testTag("reauth_cancel"),
            ) { Text("Cancel") }
        },
    )
}
