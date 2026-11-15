package com.cipherwell.notezero.ui.screens.settings

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.ui.FakeNoteRepository
import com.cipherwell.notezero.ui.UiTestBase
import com.cipherwell.notezero.ui.VaultViewModel
import com.cipherwell.notezero.ui.components.common.BiometricAvailability
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric integration test: the Settings screen renders the "Data & backup" section with
 * the contract tags and wires it to a real [BackupFlowController] (fake repository, no SAF
 * activity launched by the empty-vault guard). The per-state rendering is covered by
 * `BackupSectionContentTest`, the state machine by `BackupFlowControllerTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class BackupSettingsSectionTest : UiTestBase() {

    private fun render(repository: FakeNoteRepository) {
        val viewModel = VaultViewModel(repository, BiometricAvailability { false })
        setThemedContent {
            val state by viewModel.state.collectAsState()
            val settings by viewModel.settings.collectAsState()
            SettingsScreen(
                state = state,
                settings = settings,
                onBack = {},
                onChangePassword = viewModel::changePassword,
                onRotateRecoveryPhrase = viewModel::rotateRecoveryPhrase,
                onAcknowledgeRotatedPhrase = viewModel::acknowledgeRotatedPhrase,
                onEnableBiometric = {},
                onDisableBiometric = viewModel::disableBiometricUnlock,
                onSetAutoLockMinutes = viewModel::setAutoLockMinutes,
                onSetThemeMode = viewModel::setThemeMode,
                onLockNow = viewModel::lockNow,
                onClearMessage = viewModel::clearSettingsMessage,
                backupRepository = repository,
            )
        }
    }

    private fun unlockedVault() = FakeNoteRepository(
        initialStatus = VaultStatus.UNLOCKED,
        initialPassword = FakeNoteRepository.STRONG_PASSWORD,
    )

    @Test
    fun `settings shows the data and backup section with contract tags`() {
        render(unlockedVault())

        composeRule.onNodeWithTag("settings_backup_section").performScrollTo().assertExists()
        composeRule.onNodeWithTag("settings_backup_export").performScrollTo().assertExists()
        composeRule.onNodeWithTag("settings_backup_import").performScrollTo().assertExists()
        composeRule.onNodeWithTag("settings_backup_status").performScrollTo().assertExists()
        composeRule.onNodeWithText("Encrypted backup", substring = true).assertExists()
        // Existing sections are untouched by the insertion.
        composeRule.onNodeWithText("Appearance").assertExists()
        composeRule.onNodeWithText("About").assertExists()
    }

    @Test
    fun `exporting an empty vault reports a non-secret status instead of opening SAF`() {
        val repository = unlockedVault()
        render(repository)

        composeRule.onNodeWithTag("settings_backup_export").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("nothing to back up yet", substring = true)
            .performScrollTo()
            .assertExists()
        // The guard runs before the external-activity bracket: nothing to settle, no lock.
        assertEquals(VaultStatus.UNLOCKED, repository.status.value)
    }

    @Test
    fun `section explains that backups are encrypted and local-only`() {
        render(unlockedVault())

        composeRule.onNodeWithTag("settings_backup_note")
            .performScrollTo()
            .assert(hasText("AES-256-GCM", substring = true))
            .assert(hasText("except the file you pick", substring = true))
    }

    @Test
    fun `section falls back to the app-scoped repository when AppRoot does not inject one`() {
        // Zero-wiring path used by the production shell: SettingsScreen resolves the
        // process singleton through the Activity/Application context chain.
        val viewModel = VaultViewModel(unlockedVault(), BiometricAvailability { false })
        setThemedContent {
            val state by viewModel.state.collectAsState()
            val settings by viewModel.settings.collectAsState()
            SettingsScreen(
                state = state,
                settings = settings,
                onBack = {},
                onChangePassword = viewModel::changePassword,
                onRotateRecoveryPhrase = viewModel::rotateRecoveryPhrase,
                onAcknowledgeRotatedPhrase = viewModel::acknowledgeRotatedPhrase,
                onEnableBiometric = {},
                onDisableBiometric = viewModel::disableBiometricUnlock,
                onSetAutoLockMinutes = viewModel::setAutoLockMinutes,
                onSetThemeMode = viewModel::setThemeMode,
                onLockNow = viewModel::lockNow,
                onClearMessage = viewModel::clearSettingsMessage,
            )
        }

        composeRule.onNodeWithTag("settings_backup_section").performScrollTo().assertExists()
    }
}
