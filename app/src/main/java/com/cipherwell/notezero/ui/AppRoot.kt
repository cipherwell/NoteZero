package com.cipherwell.notezero.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.ui.components.common.BiometricPromptError
import com.cipherwell.notezero.ui.components.common.BiometricPromptLauncher
import com.cipherwell.notezero.ui.components.common.rememberBiometricAvailability
import com.cipherwell.notezero.ui.components.common.rememberBiometricPromptLauncher
import com.cipherwell.notezero.ui.notes.NotesListScreen
import com.cipherwell.notezero.ui.screens.recovery.RecoveryScreen
import com.cipherwell.notezero.ui.screens.settings.SettingsScreen
import com.cipherwell.notezero.ui.screens.setup.SetupScreen
import com.cipherwell.notezero.ui.screens.unlock.UnlockScreen
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
fun AppRoot(repository: NoteRepository) {
    val availability = rememberBiometricAvailability()
    val viewModel: VaultViewModel = viewModel(
        factory = remember(repository, availability) {
            VaultViewModel.Factory(repository, availability)
        },
    )
    AppRoot(repository = repository, viewModel = viewModel)
}

/**
 * Testable overload: the caller owns the [VaultViewModel] (and therefore the fake
 * repository / biometric availability). Used by the Robolectric Compose tests.
 */
@Composable
fun AppRoot(repository: NoteRepository, viewModel: VaultViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    val unlock by viewModel.unlock.collectAsStateWithLifecycle()
    val recovery by viewModel.recovery.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val biometricController = rememberBiometricController(viewModel)

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
                                    showBiometric = state.biometricAvailable &&
                                        (state.biometricEnabled || state.vaultInfo == null),
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

                    else -> when (unlockedRoute) {
                        ROUTE_SETTINGS -> SettingsScreen(
                            state = state,
                            settings = settings,
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

                        else -> NotesListScreen(
                            repository = repository,
                            onOpenSettings = { unlockedRoute = ROUTE_SETTINGS },
                        )
                    }
                }
            }
        }
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
