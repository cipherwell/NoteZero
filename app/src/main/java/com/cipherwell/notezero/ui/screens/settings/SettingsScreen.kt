package com.cipherwell.notezero.ui.screens.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.BuildConfig
import com.cipherwell.notezero.NoteZeroApp
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.model.ThemeMode
import com.cipherwell.notezero.ui.SettingsMessage
import com.cipherwell.notezero.ui.backup.BackupSection
import com.cipherwell.notezero.ui.SettingsUiState
import com.cipherwell.notezero.ui.VaultUiState
import com.cipherwell.notezero.ui.VaultViewModel
import com.cipherwell.notezero.ui.components.common.NzBackButton
import com.cipherwell.notezero.ui.components.common.NzBannerTone
import com.cipherwell.notezero.ui.components.common.NzBottomSpacer
import com.cipherwell.notezero.ui.components.common.NzCard
import com.cipherwell.notezero.ui.components.common.NzFormSpacer
import com.cipherwell.notezero.ui.components.common.NzInfoBanner
import com.cipherwell.notezero.ui.components.common.NzLoadingOverlay
import com.cipherwell.notezero.ui.components.common.NzPrimaryButton
import com.cipherwell.notezero.ui.components.common.NzSecondaryButton
import com.cipherwell.notezero.ui.components.common.NzSectionHeader
import com.cipherwell.notezero.ui.components.common.NzSelectableChip
import com.cipherwell.notezero.ui.components.common.NzSwitchRow
import com.cipherwell.notezero.ui.components.common.NzTextButton
import com.cipherwell.notezero.ui.components.common.RecoveryPhraseCopyControl
import com.cipherwell.notezero.ui.components.common.RecoveryPhraseGrid
import com.cipherwell.notezero.ui.components.common.SecureTextField
import com.cipherwell.notezero.ui.screens.setup.PasswordPolicy
import com.cipherwell.notezero.ui.screens.setup.PasswordRulesChecklist
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Unlocked-vault settings: non-secret vault info, master-password change, recovery-kit
 * rotation, biometric unlock, auto-lock and appearance.
 *
 * Invariants:
 *  - No secret is ever rendered except the freshly rotated recovery phrase, which is
 *    shown once in a modal and dropped from memory on acknowledgement.
 *  - Every password field hands a CharArray to the ViewModel, which wipes it.
 */
@Composable
fun SettingsScreen(
    state: VaultUiState,
    settings: SettingsUiState,
    onBack: () -> Unit,
    onChangePassword: (current: CharArray, next: CharArray) -> Unit,
    onRotateRecoveryPhrase: (currentPassword: CharArray) -> Unit,
    onAcknowledgeRotatedPhrase: () -> Unit,
    onEnableBiometric: () -> Unit,
    onDisableBiometric: () -> Unit,
    onSetAutoLockMinutes: (Int) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onLockNow: () -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The app-scoped repository for the "Data & backup" section. When null the section
     * resolves the process singleton from the Application ([NoteZeroApp]) — AppRoot passes
     * the same instance it renders with, so explicit injection is preferred and equivalent.
     */
    backupRepository: NoteRepository? = null,
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val resolvedBackupRepository = backupRepository ?: remember(context) {
        findAppScopedRepository(context)
    }

    var changePasswordExpanded by rememberSaveable { mutableStateOf(false) }
    var rotateExpanded by rememberSaveable { mutableStateOf(false) }

    val message = settings.message
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        if (message == SettingsMessage.PASSWORD_CHANGED ||
            message == SettingsMessage.RECOVERY_ROTATED ||
            message == SettingsMessage.RECOVERY_SAVED
        ) {
            changePasswordExpanded = false
            rotateExpanded = false
        }
        // Auto-hide transient confirmations; errors stay until the user acts.
        if (!settings.isError) {
            delay(MESSAGE_AUTO_HIDE_MILLIS)
            onClearMessage()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NoteZeroSpacing.gutter)
                .testTag("settings_screen"),
        ) {
            Spacer(Modifier.height(NoteZeroSpacing.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NzBackButton(onClick = onBack)
                Spacer(Modifier.padding(start = NoteZeroSpacing.s))
                Text(
                    text = "Settings",
                    style = NoteZeroType.Title,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            if (message != null) {
                NzFormSpacer(NoteZeroSpacing.m)
                NzInfoBanner(
                    text = settings.messageFor(message),
                    tone = if (settings.isError) NzBannerTone.DANGER else NzBannerTone.SUCCESS,
                    modifier = Modifier.testTag("settings_message"),
                )
            }

            // ---- Vault info (non-secret only) ------------------------------
            NzSectionHeader(title = "Vault")
            VaultInfoCard(state = state)

            // ---- Security --------------------------------------------------
            NzSectionHeader(title = "Security")
            ChangePasswordCard(
                expanded = changePasswordExpanded,
                onToggle = { changePasswordExpanded = !changePasswordExpanded },
                onChangePassword = onChangePassword,
                isWorking = settings.isWorking,
                errorText = when {
                    settings.isError && settings.message == SettingsMessage.PASSWORD_REJECTED ->
                        "The new password does not meet the requirements below."
                    settings.isError && settings.message == SettingsMessage.PASSWORD_WRONG ->
                        "The current password is incorrect."
                    else -> null
                },
            )
            NzFormSpacer(NoteZeroSpacing.m)
            RotateRecoveryCard(
                expanded = rotateExpanded,
                onToggle = { rotateExpanded = !rotateExpanded },
                onRotateRecoveryPhrase = onRotateRecoveryPhrase,
                isWorking = settings.isWorking,
            )
            NzFormSpacer(NoteZeroSpacing.l)
            NzCard {
                NzSwitchRow(
                    title = "Biometric unlock",
                    description = when {
                        !state.biometricAvailable ->
                            "Not available on this device. Password unlock always works."
                        state.biometricEnabled ->
                            "Enabled. Uses the Android Keystore; re-enrolling biometrics invalidates it."
                        else ->
                            "Unlock with your fingerprint or face; the key stays in the Keystore."
                    },
                    checked = state.biometricEnabled,
                    enabled = state.biometricAvailable && !settings.isWorking,
                    onCheckedChange = { enabled ->
                        if (enabled) onEnableBiometric() else onDisableBiometric()
                    },
                    modifier = Modifier.testTag("settings_biometric_switch"),
                )
                NzFormSpacer(NoteZeroSpacing.s)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NzFormSpacer(NoteZeroSpacing.s)
                NzSecondaryButton(
                    text = "Lock vault now",
                    onClick = onLockNow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_lock_now"),
                    enabled = !settings.isWorking,
                )
            }

            // ---- Data & backup ----------------------------------------------
            if (resolvedBackupRepository != null) {
                BackupSection(repository = resolvedBackupRepository)
            }

            // ---- Appearance -------------------------------------------------
            NzSectionHeader(title = "Appearance")
            NzCard {
                Text(
                    text = "Theme",
                    style = NoteZeroType.Body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                NzFormSpacer(NoteZeroSpacing.s)
                Row(horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s)) {
                    ThemeMode.entries.forEach { mode ->
                        NzSelectableChip(
                            label = when (mode) {
                                ThemeMode.SYSTEM -> "System"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            },
                            selected = state.themeMode == mode,
                            onClick = { onSetThemeMode(mode) },
                            modifier = Modifier.testTag(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "settings_theme_system"
                                    ThemeMode.LIGHT -> "settings_theme_light"
                                    ThemeMode.DARK -> "settings_theme_dark"
                                },
                            ),
                        )
                    }
                }
                NzFormSpacer(NoteZeroSpacing.xl)
                Text(
                    text = "Auto-lock after inactivity",
                    style = NoteZeroType.Body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                NzFormSpacer(NoteZeroSpacing.s)
                VaultViewModel.AUTO_LOCK_OPTIONS.chunked(3).forEach { rowOptions ->
                    Row(horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s)) {
                        rowOptions.forEach { minutes ->
                            NzSelectableChip(
                                label = if (minutes == 60) "1 hour" else "$minutes min",
                                selected = state.autoLockMinutes == minutes,
                                onClick = { onSetAutoLockMinutes(minutes) },
                                modifier = Modifier.testTag("settings_autolock_$minutes"),
                            )
                        }
                    }
                    Spacer(Modifier.height(NoteZeroSpacing.s))
                }
                Text(
                    text = "The vault also locks immediately when the app goes to the background.",
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- About ------------------------------------------------------
            NzSectionHeader(title = "About")
            NzCard {
                Text(
                    text = "Offline by design",
                    style = NoteZeroType.Body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                NzFormSpacer(NoteZeroSpacing.xs)
                Text(
                    text = "No network permission, no account, no telemetry. Notes are encrypted " +
                        "with AES-256-GCM; the vault key is wrapped with PBKDF2-HMAC-SHA256.",
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NzFormSpacer(NoteZeroSpacing.m)
                Text(
                    text = "NoteZero ${BuildConfig.VERSION_NAME}",
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NzBottomSpacer()
        }

        if (settings.isWorking) {
            NzLoadingOverlay(label = "Updating your vault…")
        }
    }

    val rotatedPhrase = settings.rotatedPhrase
    if (rotatedPhrase != null) {
        RotatedPhraseDialog(
            words = rotatedPhrase,
            onAcknowledge = onAcknowledgeRotatedPhrase,
        )
    }
}

@Composable
private fun VaultInfoCard(state: VaultUiState) {
    val info = state.vaultInfo
    NzCard {
        if (info == null) {
            Text(
                text = "Vault information is available after unlock.",
                style = NoteZeroType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            InfoRow(label = "Created", value = formatDate(info.createdAt))
            InfoRow(label = "Items", value = info.itemCount.toString())
            InfoRow(label = "Folders", value = info.folderCount.toString())
            InfoRow(
                label = "Key derivation",
                value = "PBKDF2-HMAC-SHA256 · ${info.kdfIterations} iterations",
            )
            InfoRow(label = "Encryption", value = "AES-256-GCM")
            InfoRow(label = "Schema", value = "v${info.schemaVersion}")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = NoteZeroSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = NoteZeroType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(start = NoteZeroSpacing.m))
        Text(
            text = value,
            style = NoteZeroType.Callout,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ChangePasswordCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    onChangePassword: (current: CharArray, next: CharArray) -> Unit,
    isWorking: Boolean,
    errorText: String?,
) {
    var current by rememberSaveable { mutableStateOf("") }
    var next by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    var mismatch by rememberSaveable { mutableStateOf(false) }

    NzCard {
        Text(
            text = "Master password",
            style = NoteZeroType.Body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        NzFormSpacer(NoteZeroSpacing.xs)
        Text(
            text = "Changing it rewraps the vault key; your notes are never re-encrypted.",
            style = NoteZeroType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NzFormSpacer(NoteZeroSpacing.s)
        NzTextButton(
            text = if (expanded) "Close" else "Change master password",
            onClick = onToggle,
            modifier = Modifier.testTag("settings_change_password_toggle"),
        )
        if (expanded) {
            NzFormSpacer(NoteZeroSpacing.s)
            SecureTextField(
                value = current,
                onValueChange = { current = it },
                label = "Current password",
                modifier = Modifier.testTag("settings_current_password"),
                enabled = !isWorking,
                imeAction = ImeAction.Next,
            )
            NzFormSpacer(NoteZeroSpacing.s)
            SecureTextField(
                value = next,
                onValueChange = {
                    next = it
                    mismatch = false
                },
                label = "New password",
                modifier = Modifier.testTag("settings_new_password"),
                enabled = !isWorking,
                isError = errorText != null,
                imeAction = ImeAction.Next,
            )
            NzFormSpacer(NoteZeroSpacing.s)
            SecureTextField(
                value = confirmation,
                onValueChange = {
                    confirmation = it
                    mismatch = false
                },
                label = "Confirm new password",
                modifier = Modifier.testTag("settings_confirm_password"),
                enabled = !isWorking,
                isError = mismatch,
            )
            NzFormSpacer(NoteZeroSpacing.s)
            PasswordRulesChecklist(password = next)
            if (mismatch || errorText != null) {
                NzFormSpacer(NoteZeroSpacing.s)
                NzInfoBanner(
                    text = if (mismatch) "The two new passwords do not match." else errorText.orEmpty(),
                    tone = NzBannerTone.DANGER,
                    modifier = Modifier.testTag("settings_change_password_error"),
                )
            }
            NzFormSpacer(NoteZeroSpacing.m)
            NzPrimaryButton(
                text = "Change password",
                onClick = {
                    if (current.isEmpty() || next.isEmpty()) return@NzPrimaryButton
                    if (!PasswordPolicy.matches(next, confirmation)) {
                        mismatch = true
                    } else {
                        onChangePassword(current.toCharArray(), next.toCharArray())
                        current = ""
                        next = ""
                        confirmation = ""
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_change_password_button"),
                enabled = current.isNotEmpty() && next.isNotEmpty() && !isWorking,
                loading = isWorking,
            )
        }
    }
}

@Composable
private fun RotateRecoveryCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    onRotateRecoveryPhrase: (currentPassword: CharArray) -> Unit,
    isWorking: Boolean,
) {
    var current by rememberSaveable { mutableStateOf("") }

    NzCard {
        Text(
            text = "Recovery kit",
            style = NoteZeroType.Body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        NzFormSpacer(NoteZeroSpacing.xs)
        Text(
            text = "Rotate the 12-word phrase if it may have been seen by someone else. " +
                "The old phrase stops working immediately.",
            style = NoteZeroType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NzFormSpacer(NoteZeroSpacing.s)
        NzTextButton(
            text = if (expanded) "Close" else "Rotate recovery phrase",
            onClick = onToggle,
            modifier = Modifier.testTag("settings_rotate_toggle"),
        )
        if (expanded) {
            NzFormSpacer(NoteZeroSpacing.s)
            SecureTextField(
                value = current,
                onValueChange = { current = it },
                label = "Current password",
                modifier = Modifier.testTag("settings_rotate_password"),
                enabled = !isWorking,
                imeAction = ImeAction.Done,
                onImeAction = {
                    if (current.isNotEmpty()) {
                        onRotateRecoveryPhrase(current.toCharArray())
                        current = ""
                    }
                },
            )
            NzFormSpacer(NoteZeroSpacing.m)
            NzPrimaryButton(
                text = "Rotate phrase",
                onClick = {
                    if (current.isEmpty()) return@NzPrimaryButton
                    onRotateRecoveryPhrase(current.toCharArray())
                    current = ""
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_rotate_button"),
                enabled = current.isNotEmpty() && !isWorking,
                loading = isWorking,
            )
        }
    }
}

@Composable
private fun RotatedPhraseDialog(
    words: List<String>,
    onAcknowledge: () -> Unit,
) {
    AlertDialog(
        // The phrase must be acknowledged before it is dropped from memory.
        onDismissRequest = { },
        title = { Text(text = "New recovery phrase", style = NoteZeroType.Heading) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Write these 12 words down in order. This is the only time they are " +
                        "shown. The previous phrase no longer works.",
                    style = NoteZeroType.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NzFormSpacer(NoteZeroSpacing.m)
                RecoveryPhraseGrid(
                    words = words,
                    modifier = Modifier.testTag("settings_rotated_phrase"),
                )
                NzFormSpacer(NoteZeroSpacing.m)
                RecoveryPhraseCopyControl(words = words)
            }
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) {
                Text(
                    text = "I've saved it",
                    style = NoteZeroType.Button,
                    color = com.cipherwell.notezero.ui.theme.LocalNoteZeroColors.current.accentText,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = NoteZeroShapes.sheet,
    )
}

/**
 * Finds the process-scoped repository through the Activity/Application context chain.
 * Used when the caller (AppRoot) has not injected [SettingsScreen.backupRepository]; the
 * app never constructs a second repository here — it reads [NoteZeroApp.repository].
 */
private fun findAppScopedRepository(context: Context): NoteRepository? {
    var current: Context? = context
    val visited = HashSet<Context>()
    while (current != null && visited.add(current)) {
        if (current is NoteZeroApp) return current.repository
        current = (current as? ContextWrapper)?.baseContext
    }
    return (context.applicationContext as? NoteZeroApp)?.repository
}

private fun formatDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return "—"
    return runCatching {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))
    }.getOrDefault("—")
}

private const val MESSAGE_AUTO_HIDE_MILLIS = 4_000L
