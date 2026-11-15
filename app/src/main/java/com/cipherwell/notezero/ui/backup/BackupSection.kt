package com.cipherwell.notezero.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.ui.components.common.NzCard
import com.cipherwell.notezero.ui.components.common.NzFormSpacer
import com.cipherwell.notezero.ui.components.common.NzInfoBanner
import com.cipherwell.notezero.ui.components.common.NzPrimaryButton
import com.cipherwell.notezero.ui.components.common.NzSecondaryButton
import com.cipherwell.notezero.ui.components.common.NzSectionHeader
import com.cipherwell.notezero.ui.components.common.NzSwitchRow
import com.cipherwell.notezero.ui.components.common.NzTextButton
import com.cipherwell.notezero.ui.components.common.SecureTextField
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * "Data & backup" settings section: SAF export/import of the encrypted backup envelope.
 *
 * Security invariants:
 *  - the section only ever renders non-secret state (counts and error categories — never a
 *    path, file content, password or recovery word);
 *  - all plaintext stays inside the repository; this UI only hands it SAF streams;
 *  - every text field goes through [SecureTextField] and its String is cleared as soon as
 *    the controller owns the CharArray.
 *
 * The controller lives in an Activity-scoped [BackupFlowViewModel], not in `remember`: the
 * picker backgrounds the Activity and, if the idle timeout fires while it is open, the whole
 * Settings subtree is replaced by the unlock screen. The ViewModel keeps the chosen uri and
 * resumes the transfer after unlock.
 */
@Composable
fun BackupSection(
    repository: NoteRepository,
    modifier: Modifier = Modifier,
    picker: BackupFilePicker? = null,
) {
    val context = LocalContext.current
    val viewModel: BackupFlowViewModel = viewModel(
        factory = remember(repository, context) {
            BackupFlowViewModel.factory(repository, context.applicationContext)
        },
    )
    val safPicker = rememberSafBackupFilePicker(onResult = viewModel::onPickerResult)
    val activePicker = picker ?: safPicker
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackupSectionContent(
        state = state,
        onExport = { mode, password -> viewModel.requestExport(mode, password, activePicker) },
        onImport = { viewModel.requestImport(activePicker) },
        onSubmitCredential = { viewModel.controller.submitCredential(it) },
        onCancelFlow = viewModel::reset,
        onDismiss = viewModel::reset,
        modifier = modifier,
    )
}

/**
 * Stateless section body. Split from [BackupSection] so each [BackupUiState] (progress,
 * success counts, credential prompt, non-secret errors) is directly testable with Compose.
 */
@Composable
fun BackupSectionContent(
    state: BackupUiState,
    onExport: (mode: BackupMode, password: CharArray?) -> Unit,
    onImport: () -> Unit,
    onSubmitCredential: (String) -> Unit,
    onCancelFlow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Secrets stay in plain `remember` on purpose: rememberSaveable can be written to the
    // saved instance state, and a backup password / source phrase must not outlive the frame.
    var separatePassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    var credential by remember { mutableStateOf("") }

    val busy = state is BackupUiState.Running ||
        state is BackupUiState.PickingLocation ||
        state is BackupUiState.NeedsCredential
    val canStart = !busy

    Column(modifier = modifier.testTag(BACKUP_SECTION_TAG)) {
        NzSectionHeader(title = "Data & backup")
        NzCard {
            Text(
                text = "Encrypted backup",
                style = NoteZeroType.Body,
                color = MaterialTheme.colorScheme.onSurface,
            )
            NzFormSpacer(NoteZeroSpacing.xs)
            Text(
                text = "Backups are sealed with AES-256-GCM under your vault key. Nothing " +
                    "leaves this device except the file you pick.",
                style = NoteZeroType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_backup_note"),
            )
            NzFormSpacer(NoteZeroSpacing.m)
            NzSwitchRow(
                title = "Protect this backup with a separate password",
                description = "The file then needs that password to be restored anywhere.",
                checked = separatePassword,
                enabled = canStart,
                onCheckedChange = {
                    separatePassword = it
                    mismatch = false
                    if (!it) {
                        password = ""
                        confirmation = ""
                    }
                },
                modifier = Modifier.testTag("settings_backup_password_toggle"),
            )
            if (separatePassword) {
                NzFormSpacer(NoteZeroSpacing.s)
                SecureTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        mismatch = false
                    },
                    label = "Backup password",
                    modifier = Modifier.testTag("settings_backup_password"),
                    enabled = canStart,
                    imeAction = ImeAction.Next,
                )
                NzFormSpacer(NoteZeroSpacing.s)
                SecureTextField(
                    value = confirmation,
                    onValueChange = {
                        confirmation = it
                        mismatch = false
                    },
                    label = "Confirm backup password",
                    modifier = Modifier.testTag("settings_backup_password_confirm"),
                    enabled = canStart,
                    isError = mismatch,
                )
                if (mismatch) {
                    NzFormSpacer(NoteZeroSpacing.s)
                    NzInfoBanner(
                        text = "The two backup passwords do not match.",
                        tone = com.cipherwell.notezero.ui.components.common.NzBannerTone.DANGER,
                        modifier = Modifier.testTag("settings_backup_password_error"),
                    )
                }
            }
            NzFormSpacer(NoteZeroSpacing.m)
            NzPrimaryButton(
                text = "Export encrypted backup",
                onClick = {
                    if (separatePassword) {
                        if (password.isEmpty()) {
                            mismatch = false
                            return@NzPrimaryButton
                        }
                        if (password != confirmation) {
                            mismatch = true
                            return@NzPrimaryButton
                        }
                        val owned = password.toCharArray()
                        password = ""
                        confirmation = ""
                        mismatch = false
                        onExport(BackupMode.EXPORT_PASSWORD, owned)
                    } else {
                        onExport(BackupMode.EXPORT_VAULT_KEY, null)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_backup_export"),
                enabled = canStart && (!separatePassword || password.isNotEmpty()),
            )
            NzFormSpacer(NoteZeroSpacing.s)
            NzSecondaryButton(
                text = "Import encrypted backup",
                onClick = onImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_backup_import"),
                enabled = canStart,
            )
            NzFormSpacer(NoteZeroSpacing.s)
            Text(
                text = statusText(state),
                style = NoteZeroType.Caption,
                color = if (state is BackupUiState.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_backup_status")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            val terminal = state is BackupUiState.Success || state is BackupUiState.Error
            if (terminal) {
                NzFormSpacer(NoteZeroSpacing.xs)
                NzTextButton(
                    text = "Dismiss",
                    onClick = onDismiss,
                    modifier = Modifier.testTag("settings_backup_dismiss"),
                )
            }
            if (state is BackupUiState.NeedsCredential) {
                NzFormSpacer(NoteZeroSpacing.m)
                NzInfoBanner(
                    text = "This backup was created by another vault. Enter that vault's " +
                        "master password or its 12-word recovery phrase (words separated by " +
                        "single spaces).",
                    modifier = Modifier.testTag("settings_backup_credential_note"),
                )
                NzFormSpacer(NoteZeroSpacing.s)
                SecureTextField(
                    value = credential,
                    onValueChange = { credential = it },
                    label = "Source password or 12-word phrase",
                    modifier = Modifier.testTag("settings_backup_credential_field"),
                    imeAction = ImeAction.Done,
                    onImeAction = {
                        if (credential.isNotBlank()) {
                            val submitted = credential
                            credential = ""
                            onSubmitCredential(submitted)
                        }
                    },
                )
                NzFormSpacer(NoteZeroSpacing.s)
                Row(horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s)) {
                    NzSecondaryButton(
                        text = "Cancel",
                        onClick = {
                            credential = ""
                            onCancelFlow()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_backup_cancel"),
                    )
                    NzPrimaryButton(
                        text = "Restore",
                        onClick = {
                            val submitted = credential
                            credential = ""
                            onSubmitCredential(submitted)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_backup_confirm"),
                        enabled = credential.isNotBlank(),
                    )
                }
            }
        }
    }
}

/** Non-secret status copy; never includes a path, file name, title or credential. */
private fun statusText(state: BackupUiState): String = when (state) {
    BackupUiState.Idle ->
        "No backup in progress."
    BackupUiState.PickingLocation ->
        "Waiting for the file picker…"
    is BackupUiState.Running -> when (state.mode) {
        BackupMode.IMPORT -> "Opening the backup…"
        else -> "Encrypting your vault…"
    }
    is BackupUiState.Success -> when (state.mode) {
        BackupMode.IMPORT -> state.report?.let { report ->
            "Restored ${report.imported} notes and ${report.foldersImported} folders " +
                "· ${report.duplicated} duplicates ignored · ${report.skipped} conflicts skipped."
        } ?: "Backup restored."
        else -> "Encrypted backup saved."
    }
    BackupUiState.NeedsCredential ->
        "This backup belongs to another vault. Enter that vault's password or recovery phrase."
    is BackupUiState.Error -> when (state.kind) {
        BackupErrorKind.INVALID_FILE ->
            "That file is not a valid NoteZero backup."
        BackupErrorKind.WRONG_CREDENTIAL ->
            "The password or phrase does not open this backup."
        BackupErrorKind.IO ->
            "The backup file could not be read or written."
        BackupErrorKind.LOCKED ->
            "The vault locked before the backup finished. Unlock to resume — your chosen " +
                "file is still selected."
        BackupErrorKind.EMPTY_VAULT ->
            "There is nothing to back up yet. Create a note first."
    }
}

/** Production SAF launchers: CreateDocument for export, OpenDocument for import. */
@Composable
private fun rememberSafBackupFilePicker(onResult: (android.net.Uri?) -> Unit): BackupFilePicker {
    val createLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE),
        onResult = onResult,
    )
    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = onResult,
    )
    return remember(createLauncher, openLauncher) {
        object : BackupFilePicker {
            override fun createDocument(suggestedName: String) {
                createLauncher.launch(suggestedName)
            }

            override fun openDocument() {
                // .nzbk is ours, but providers may report an unknown MIME type for it.
                openLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            }
        }
    }
}

/** Stable E2E tag for the whole section. */
const val BACKUP_SECTION_TAG: String = "settings_backup_section"

/** MIME type written by SAF CreateDocument for the backup envelope. */
const val BACKUP_MIME_TYPE: String = "application/octet-stream"
