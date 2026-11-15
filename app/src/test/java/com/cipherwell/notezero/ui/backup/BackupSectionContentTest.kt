package com.cipherwell.notezero.ui.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.cipherwell.notezero.data.BackupImportReport
import com.cipherwell.notezero.ui.UiTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose tests for every [BackupUiState] rendering the "Data & backup" section
 * shows: progress, success counts, cross-vault credential prompt and non-secret error copy.
 * The state machine itself is covered by [BackupFlowControllerTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class BackupSectionContentTest : UiTestBase() {

    private var exportMode: BackupMode? = null
    private var exportedPassword: String? = null
    private var submittedCredential: String? = null
    private var cancelled = 0
    private var dismissed = 0

    private fun render(state: BackupUiState) {
        setThemedContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                BackupSectionContent(
                    state = state,
                    onExport = { mode, password ->
                        exportMode = mode
                        exportedPassword = password?.concatToString()
                    },
                    onImport = { },
                    onSubmitCredential = { submittedCredential = it },
                    onCancelFlow = { cancelled += 1 },
                    onDismiss = { dismissed += 1 },
                )
            }
        }
    }

    @Test
    fun `idle state renders the section, both actions and the encryption explanation`() {
        render(BackupUiState.Idle)

        composeRule.onNodeWithTag("settings_backup_section").assertExists()
        composeRule.onNodeWithTag("settings_backup_export").performScrollTo().assertExists()
        composeRule.onNodeWithTag("settings_backup_import").performScrollTo().assertExists()
        composeRule.onNodeWithTag("settings_backup_status").assertExists()
        composeRule.onNodeWithText("AES-256-GCM", substring = true).assertExists()
        composeRule.onNodeWithText("except the file you pick", substring = true).assertExists()
        assertNull(exportMode)
    }

    @Test
    fun `export button requests vault key export without a credential`() {
        render(BackupUiState.Idle)

        composeRule.onNodeWithTag("settings_backup_export").performScrollTo().performClick()

        assertEquals(BackupMode.EXPORT_VAULT_KEY, exportMode)
        assertNull(exportedPassword)
    }

    @Test
    fun `separate password flow requires matching confirmation before exporting`() {
        render(BackupUiState.Idle)

        composeRule.onNodeWithTag("settings_backup_password_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_backup_password").performTextInput("backup-secret")
        composeRule.onNodeWithTag("settings_backup_password_confirm").performTextInput("different")

        composeRule.onNodeWithTag("settings_backup_export").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings_backup_password_error").assertExists()
        assertNull(exportMode) // mismatch blocks the export

        composeRule.onNodeWithTag("settings_backup_password_confirm").performTextClearance()
        composeRule.onNodeWithTag("settings_backup_password_confirm").performTextInput("backup-secret")
        composeRule.onNodeWithTag("settings_backup_export").performScrollTo().performClick()

        assertEquals(BackupMode.EXPORT_PASSWORD, exportMode)
        assertEquals("backup-secret", exportedPassword)
    }

    @Test
    fun `picking location shows the picker status and disables both actions`() {
        render(BackupUiState.PickingLocation)

        composeRule.onNodeWithTag("settings_backup_status").performScrollTo()
        composeRule.onNodeWithText("Waiting for the file picker", substring = true).assertExists()
        composeRule.onNodeWithTag("settings_backup_export").assertIsNotEnabled()
        composeRule.onNodeWithTag("settings_backup_import").assertIsNotEnabled()
    }

    @Test
    fun `import success renders the non-secret counts and can be dismissed`() {
        render(
            BackupUiState.Success(
                BackupMode.IMPORT,
                BackupImportReport(imported = 3, duplicated = 1, skipped = 2, foldersImported = 1),
            ),
        )

        composeRule.onNodeWithText("Restored 3 notes and 1 folders", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("1 duplicates ignored", substring = true).assertExists()
        composeRule.onNodeWithText("2 conflicts skipped", substring = true).assertExists()

        composeRule.onNodeWithTag("settings_backup_dismiss").performScrollTo().performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `export success states the encrypted file was saved`() {
        render(BackupUiState.Success(BackupMode.EXPORT_VAULT_KEY, null))

        composeRule.onNodeWithText("Encrypted backup saved.", substring = true)
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun `cross-vault import shows the credential prompt and submits the typed credential`() {
        render(BackupUiState.NeedsCredential)

        composeRule.onNodeWithTag("settings_backup_credential_note").performScrollTo().assertExists()
        composeRule.onNodeWithTag("settings_backup_credential_field").performScrollTo().assertExists()
        composeRule.onNodeWithTag("settings_backup_confirm").performScrollTo().assertExists()
        composeRule.onNodeWithTag("settings_backup_cancel").performScrollTo().assertExists()

        val phrase = "word1 word2 word3 word4 word5 word6 word7 word8 word9 word10 word11 word12"
        composeRule.onNodeWithTag("settings_backup_credential_field").performScrollTo().performTextInput(phrase)
        composeRule.onNodeWithTag("settings_backup_confirm").performScrollTo().performClick()

        assertEquals(phrase, submittedCredential)
    }

    @Test
    fun `cancelling the credential prompt cancels the flow`() {
        render(BackupUiState.NeedsCredential)

        composeRule.onNodeWithTag("settings_backup_cancel").performScrollTo().performClick()

        assertEquals(1, cancelled)
    }

    @Test
    fun `invalid file error renders non-secret actionable wording`() {
        render(BackupUiState.Error(BackupErrorKind.INVALID_FILE))

        composeRule.onNodeWithText("not a valid NoteZero backup", substring = true)
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithTag("settings_backup_dismiss").performScrollTo().assertExists()
    }

    @Test
    fun `locked error tells the user the chosen file survives the unlock`() {
        render(BackupUiState.Error(BackupErrorKind.LOCKED))

        composeRule.onNodeWithText("Unlock to resume", substring = true).assertExists()
    }

    @Test
    fun `wrong credential and empty vault errors use plain wording`() {
        render(BackupUiState.Error(BackupErrorKind.WRONG_CREDENTIAL))

        composeRule.onNodeWithText("does not open this backup", substring = true).assertExists()
    }

    @Test
    fun `empty vault error explains there is nothing to back up`() {
        render(BackupUiState.Error(BackupErrorKind.EMPTY_VAULT))

        composeRule.onNodeWithText("nothing to back up yet", substring = true).assertExists()
    }

    @Test
    fun `io error wording never leaks a path`() {
        render(BackupUiState.Error(BackupErrorKind.IO))

        composeRule.onNodeWithText("could not be read or written", substring = true).assertExists()
        composeRule.onNodeWithText("content://", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("nzbk", substring = true).assertDoesNotExist()
    }
}
