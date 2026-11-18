package com.cipherwell.notezero.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cipherwell.notezero.R
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.ui.components.common.BiometricPromptError
import com.cipherwell.notezero.ui.components.common.BiometricPromptLauncher
import com.cipherwell.notezero.ui.components.common.NzBannerTone
import com.cipherwell.notezero.ui.components.common.NzInfoBanner
import com.cipherwell.notezero.ui.components.common.NzTextButton
import com.cipherwell.notezero.ui.components.common.rememberBiometricAvailability
import com.cipherwell.notezero.ui.code.CodeWorkspaceScreen
import com.cipherwell.notezero.ui.library.LibraryScreen
import com.cipherwell.notezero.ui.workspace.WorkspaceScreen
import com.cipherwell.notezero.ui.components.common.rememberBiometricPromptLauncher
import com.cipherwell.notezero.ui.notes.NotesListScreen
import com.cipherwell.notezero.ui.screens.recovery.RecoveryScreen
import com.cipherwell.notezero.ui.screens.settings.SettingsScreen
import com.cipherwell.notezero.ui.screens.setup.SetupScreen
import com.cipherwell.notezero.ui.screens.unlock.UnlockScreen
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroTheme

/**
 * App shell. Observes [NoteRepository.status] and routes:
 *
 *  SETUP_REQUIRED            -> setup flow (password -> phrase -> confirmation)
 *  UNLOCKED (phrase pending) -> continues the setup flow (phrase is shown exactly once)
 *  LOCKED                    -> unlock, or phrase-only recovery
 *  UNLOCKED                  -> notes (owned by ui-notes), with a settings route
 *
 * It also applies the persisted theme mode and is the only place that knows about the
 * lock/settings navigation state. The repository instance comes from the app-level
 * provider (`NoteZeroApp.repository`), never from a composable constructor.
 */
@Composable
fun AppRoot(
    repository: NoteRepository,
    newNoteRequest: Boolean = false,
    onNewNoteRequestConsumed: () -> Unit = {},
) {
    val availability = rememberBiometricAvailability()
    val viewModel: VaultViewModel = viewModel(
        factory = remember(repository, availability) {
            VaultViewModel.Factory(repository, availability)
        },
    )
    AppRoot(
        repository = repository,
        viewModel = viewModel,
        newNoteRequest = newNoteRequest,
        onNewNoteRequestConsumed = onNewNoteRequestConsumed,
    )
}

/**
 * Testable overload: the caller owns the [VaultViewModel] (and therefore the fake
 * repository / biometric availability). Used by the Robolectric Compose tests.
 */
@Composable
fun AppRoot(
    repository: NoteRepository,
    viewModel: VaultViewModel,
    newNoteRequest: Boolean = false,
    onNewNoteRequestConsumed: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    val unlock by viewModel.unlock.collectAsStateWithLifecycle()
    val recovery by viewModel.recovery.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val recoveryResetNotice by viewModel.recoveryResetNotice.collectAsStateWithLifecycle()
    val biometricController = rememberBiometricController(viewModel)
    // Quick capture survives a locked vault: the request is honoured after unlock.
    var pendingNewNote by rememberSaveable { mutableStateOf(newNoteRequest) }
    LaunchedEffect(newNoteRequest) { if (newNoteRequest) pendingNewNote = true }
    // Deep link used by the Workspace to open a note/code file in the notes shell.
    var pendingNotesItemId by rememberSaveable { mutableStateOf<String?>(null) }

    var wasUnlocked by rememberSaveable { mutableStateOf(false) }
    var lockedRoute by rememberSaveable { mutableStateOf(ROUTE_UNLOCK) }
    var unlockedRoute by rememberSaveable { mutableStateOf(ROUTE_NOTES) }

    LaunchedEffect(state.status) {
        when (state.status) {
            VaultStatus.UNLOCKED -> {
                wasUnlocked = true
                lockedRoute = ROUTE_UNLOCK
                unlockedRoute = ROUTE_NOTES
            }
            VaultStatus.SETUP_REQUIRED -> {
                wasUnlocked = false
                lockedRoute = ROUTE_UNLOCK
                unlockedRoute = ROUTE_NOTES
            }
            VaultStatus.LOCKED -> {
                // Immediate re-lock: never leave an unlocked destination mounted.
                unlockedRoute = ROUTE_NOTES
                pendingNotesItemId = null
            }
        }
    }

    NoteZeroTheme(themeMode = state.themeMode) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .testTag("app_root"),
            ) {
                val setupInProgress = state.status == VaultStatus.SETUP_REQUIRED ||
                    (state.status == VaultStatus.UNLOCKED && setup.phrase != null)

                when {
                    setupInProgress -> SetupScreen(
                        setup = setup,
                        onCreateVault = viewModel::createVault,
                        onAcknowledgePhrase = viewModel::acknowledgeRecoveryPhrase,
                        onConfirmPhrase = viewModel::confirmRecoveryPhrase,
                    )

                    state.status == VaultStatus.LOCKED -> {
                        val lockedOverlayTag = if (wasUnlocked) {
                            Modifier.testTag("locked_overlay")
                        } else {
                            Modifier
                        }
                        Box(Modifier.fillMaxSize().then(lockedOverlayTag)) {
                            if (lockedRoute == ROUTE_RECOVERY) {
                                RecoveryScreen(
                                    recovery = recovery,
                                    onSubmitPhrase = viewModel::checkRecoveryPhrase,
                                    onSubmitNewPassword = viewModel::resetWithRecoveryPhrase,
                                    onBack = {
                                        viewModel.resetRecoveryState()
                                        lockedRoute = ROUTE_UNLOCK
                                    },
                                    onBackToPhrase = viewModel::backToPhraseStep,
                                )
                            } else {
                                UnlockScreen(
                                    unlock = unlock,
                                    // Offer the CTA only when a wrapped VMK actually exists
                                    // (known even while locked); otherwise the button could
                                    // only fail (device QA finding).
                                    showBiometric = state.biometricAvailable && state.biometricEnrolled,
                                    onUnlock = viewModel::unlock,
                                    onBiometricUnlock = {
                                        val controller = biometricController
                                        if (controller != null) {
                                            controller.unlockWithBiometrics()
                                        } else {
                                            viewModel.onBiometricPromptError(BiometricPromptError.UNAVAILABLE)
                                        }
                                    },
                                    onUseRecoveryPhrase = {
                                        viewModel.resetRecoveryState()
                                        lockedRoute = ROUTE_RECOVERY
                                    },
                                )
                            }
                        }
                    }

                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        if (recoveryResetNotice) {
                            RecoveryResetNotice(
                                onDismiss = viewModel::dismissRecoveryResetNotice,
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            when (unlockedRoute) {
                                ROUTE_SETTINGS -> SettingsScreen(
                                    state = state,
                                    settings = settings,
                                    backupRepository = repository,
                                    onBack = { unlockedRoute = ROUTE_NOTES },
                                    onChangePassword = viewModel::changePassword,
                                    onRotateRecoveryPhrase = viewModel::rotateRecoveryPhrase,
                                    onAcknowledgeRotatedPhrase = viewModel::acknowledgeRotatedPhrase,
                                    onEnableBiometric = {
                                        val controller = biometricController
                                        if (controller != null) {
                                            controller.enableBiometrics()
                                        } else {
                                            viewModel.onBiometricEnrollError(BiometricPromptError.UNAVAILABLE)
                                        }
                                    },
                                    onDisableBiometric = viewModel::disableBiometricUnlock,
                                    onSetAutoLockMinutes = viewModel::setAutoLockMinutes,
                                    onSetThemeMode = viewModel::setThemeMode,
                                    onLockNow = viewModel::lockNow,
                                    onClearMessage = viewModel::clearSettingsMessage,
                                )

                                ROUTE_CODE -> CodeWorkspaceScreen(
                                    repository = repository,
                                    onBack = { unlockedRoute = ROUTE_NOTES },
                                )

                                ROUTE_LIBRARY -> LibraryScreen(
                                    repository = repository,
                                    onBack = { unlockedRoute = ROUTE_NOTES },
                                )

                                ROUTE_WORKSPACE -> WorkspaceScreen(
                                    repository = repository,
                                    onBack = { unlockedRoute = ROUTE_NOTES },
                                    onOpenNote = { itemId ->
                                        pendingNotesItemId = itemId
                                        unlockedRoute = ROUTE_NOTES
                                    },
                                )

                                else -> NotesListScreen(
                                    repository = repository,
                                    onOpenSettings = {
                                        // A stale success/error banner from a previous visit
                                        // must not greet the user on a fresh entry.
                                        viewModel.clearSettingsMessage()
                                        unlockedRoute = ROUTE_SETTINGS
                                    },
                                    onOpenCode = { unlockedRoute = ROUTE_CODE },
                                    onOpenLibrary = { unlockedRoute = ROUTE_LIBRARY },
                                    onOpenWorkspace = { unlockedRoute = ROUTE_WORKSPACE },
                                    requestedItemId = pendingNotesItemId,
                                    onRequestedItemConsumed = { pendingNotesItemId = null },
                                    startNewNote = pendingNewNote,
                                    onStartNewNoteConsumed = {
                                        pendingNewNote = false
                                        onNewNoteRequestConsumed()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Non-secret confirmation after a recovery reset (audit F2, decision #12): the reset
 * re-wraps the existing phrase, it does not generate a new one. Shown on the unlocked
 * surface, dismissed explicitly, and dropped on the next lock by the ViewModel.
 */
@Composable
private fun RecoveryResetNotice(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NoteZeroSpacing.m, vertical = NoteZeroSpacing.s)
            .testTag("recovery_reset_notice"),
    ) {
        NzInfoBanner(
            text = stringResource(R.string.recovery_reset_notice),
            tone = NzBannerTone.SUCCESS,
        )
        NzTextButton(
            text = stringResource(R.string.common_dismiss),
            onClick = onDismiss,
            modifier = Modifier.testTag("recovery_reset_notice_dismiss"),
        )
    }
}

private class BiometricController(
    private val viewModel: VaultViewModel,
    private val launcher: BiometricPromptLauncher,
) {
    fun unlockWithBiometrics() {
        val decrypt = viewModel.prepareBiometricDecrypt() ?: return
        launcher.authenticateDecrypt(
            cipher = decrypt.cipher,
            onAuthenticated = { viewModel.completeBiometricUnlock(decrypt) },
            onError = viewModel::onBiometricPromptError,
        )
    }

    fun enableBiometrics() {
        val enroll = viewModel.prepareBiometricEnroll() ?: return
        launcher.authenticateEnroll(
            cipher = enroll.cipher,
            onAuthenticated = { viewModel.completeBiometricEnroll(enroll) },
            onError = viewModel::onBiometricEnrollError,
        )
    }
}

@Composable
private fun rememberBiometricController(viewModel: VaultViewModel): BiometricController? {
    val launcher = rememberBiometricPromptLauncher()
    return remember(viewModel, launcher) {
        launcher?.let { BiometricController(viewModel, it) }
    }
}

private const val ROUTE_UNLOCK = "unlock"
private const val ROUTE_RECOVERY = "recovery"
private const val ROUTE_NOTES = "notes"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_CODE = "code"
private const val ROUTE_LIBRARY = "library"
private const val ROUTE_WORKSPACE = "workspace"
