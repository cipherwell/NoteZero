package com.cipherwell.notezero.ui.locks

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.ui.components.common.NzCard
import com.cipherwell.notezero.ui.components.common.NzSecondaryButton
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * Settings action for the locked notes/folders gate: "Re-arm locked notes now".
 *
 * Calls [NoteRepository.clearLockVerifications], which re-masks every locked note/folder
 * immediately (the repository also does this on lock, background/screen-off and session
 * close; this is the manual escape hatch).
 *
 * This composable is owned by lock-ui but rendered by `ui/screens/settings` (ui-shell):
 * `SettingsScreen` inserts it in the Security section with the same app-scoped
 * `NoteRepository` it resolves for `BackupSection`. The contract tag `settings_relock`
 * lives on the button below and is covered by `ui/locks/LockSettingsSectionTest`; if the
 * settings call is ever removed, the E2E `settings_relock` action disappears with it.
 */
@Composable
fun LockSettingsSection(
    repository: NoteRepository,
    modifier: Modifier = Modifier,
) {
    val verifiedIds by repository.verifiedLockedIds.collectAsStateWithLifecycle()
    NzCard(modifier = modifier.testTag("settings_lock_section")) {
        Text(
            text = "Locked notes & folders",
            style = NoteZeroType.Body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(NoteZeroSpacing.xs))
        Text(
            text = if (verifiedIds.isEmpty()) {
                "No locked item is revealed right now. Locked notes and folders stay " +
                    "hidden until you verify with biometrics or your master password."
            } else {
                "${verifiedIds.size} locked item(s) revealed for this session. " +
                    "Re-arm to hide them again immediately."
            },
            style = NoteZeroType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NoteZeroSpacing.m))
        NzSecondaryButton(
            text = "Re-arm locked notes now",
            onClick = repository::clearLockVerifications,
            modifier = Modifier.testTag("settings_relock"),
        )
    }
}
