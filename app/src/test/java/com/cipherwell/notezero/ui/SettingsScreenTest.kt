package com.cipherwell.notezero.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.ThemeMode
import com.cipherwell.notezero.ui.components.common.BiometricAvailability
import com.cipherwell.notezero.ui.screens.settings.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsScreenTest : UiTestBase() {

    private fun unlockedVault() = FakeNoteRepository(
        initialStatus = VaultStatus.UNLOCKED,
        initialPassword = FakeNoteRepository.STRONG_PASSWORD,
    )

    private fun render(
        viewModel: VaultViewModel,
        onEnableBiometric: () -> Unit = {},
    ) {
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
                onEnableBiometric = onEnableBiometric,
                onDisableBiometric = viewModel::disableBiometricUnlock,
                onSetAutoLockMinutes = viewModel::setAutoLockMinutes,
                onSetThemeMode = viewModel::setThemeMode,
                onLockNow = viewModel::lockNow,
                onClearMessage = viewModel::clearSettingsMessage,
            )
        }
    }

    @Test
    fun `settings shows non-secret vault info and stable tags`() {
        val repository = unlockedVault()
        val viewModel = VaultViewModel(repository, BiometricAvailability { true })
        render(viewModel)

        composeRule.onNodeWithTag("settings_screen").assertExists()
        composeRule.onNodeWithTag("settings_biometric_switch").assertExists()
        composeRule.onNodeWithTag("settings_lock_now").assertExists()
        composeRule.onNodeWithText("PBKDF2-HMAC-SHA256 · 600000 iterations").assertExists()
        composeRule.onNodeWithText("600000", substring = true).assertExists()
        composeRule.onNodeWithText("AES-256-GCM").assertExists()
        // Never render secret material on this screen.
        composeRule.onNodeWithText(FakeNoteRepository.STRONG_PASSWORD).assertDoesNotExist()
    }

    @Test
    fun `theme and auto-lock chips persist through the repository`() {
        val repository = unlockedVault()
        val viewModel = VaultViewModel(repository, BiometricAvailability { false })
        render(viewModel)

        composeRule.onNodeWithTag("settings_theme_dark").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(ThemeMode.DARK, repository.themeMode.value)
        composeRule.onNodeWithTag("settings_theme_dark").assert(isSelected())

        composeRule.onNodeWithTag("settings_autolock_15").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(15, repository.autoLockMinutes.value)
        composeRule.onNodeWithTag("settings_autolock_15").assert(isSelected())
    }

    @Test
    fun `biometric switch drives the two-phase enrollment flow`() {
        val repository = unlockedVault()
        val viewModel = VaultViewModel(repository, BiometricAvailability { true })
        // Stands in for the BiometricPrompt success callback the shell wires in AppRoot.
        render(
            viewModel = viewModel,
            onEnableBiometric = {
                viewModel.prepareBiometricEnroll()?.let(viewModel::completeBiometricEnroll)
            },
        )

        composeRule.onNodeWithTag("settings_biometric_switch").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertTrue(repository.biometricEnrolled)
        composeRule.onNodeWithText("Biometric unlock enabled.").assertExists()

        composeRule.onNodeWithTag("settings_biometric_switch").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertFalse(repository.biometricEnrolled)
    }

    @Test
    fun `lock now locks the vault immediately`() {
        val repository = unlockedVault()
        val viewModel = VaultViewModel(repository, BiometricAvailability { false })
        render(viewModel)

        composeRule.onNodeWithTag("settings_lock_now").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(VaultStatus.LOCKED, repository.status.value)
        assertTrue(repository.calls.contains("lock"))
    }

    @Test
    fun `rotated recovery phrase is shown once and cleared on acknowledgement`() {
        val repository = unlockedVault()
        val viewModel = VaultViewModel(repository, BiometricAvailability { false })
        render(viewModel)

        composeRule.onNodeWithTag("settings_rotate_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_rotate_password")
            .performTextInput(FakeNoteRepository.STRONG_PASSWORD)
        composeRule.onNodeWithTag("settings_rotate_button").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_rotated_phrase").assertExists()
        composeRule.onNodeWithText("zebra").assertExists()
        assertEquals(FakeNoteRepository.ROTATED_PHRASE, viewModel.settings.value.rotatedPhrase)

        composeRule.onNodeWithText("I've saved it").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_rotated_phrase").assertDoesNotExist()
        assertNull(viewModel.settings.value.rotatedPhrase)
    }
}
