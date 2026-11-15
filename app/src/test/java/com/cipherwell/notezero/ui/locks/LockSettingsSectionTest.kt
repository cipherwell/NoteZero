package com.cipherwell.notezero.ui.locks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.notes.FakeNoteRepository
import com.cipherwell.notezero.ui.theme.NoteZeroTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose tests for the "Re-arm locked notes now" settings action.
 *
 * `ui/screens/settings` is ui-shell-owned, so this is the wiring-ready section the Lead
 * drops into the Security group; the contract tag `settings_relock` lives on its button.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LockSettingsSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var repository: FakeNoteRepository

    @Before
    fun setUp() {
        repository = FakeNoteRepository()
        repository.setItems(
            listOf(
                Item(
                    id = "n1",
                    kind = ItemKind.NOTE,
                    title = "Secret",
                    body = "",
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                    locked = true,
                ),
            ),
        )
    }

    private fun setScreen() {
        composeRule.setContent {
            NoteZeroTheme {
                LockSettingsSection(repository = repository)
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `section exposes the re-arm action`() {
        setScreen()

        composeRule.onNodeWithTag("settings_lock_section").assertExists()
        composeRule.onNodeWithTag("settings_relock").assertExists()
        composeRule.onNodeWithText("Re-arm locked notes now").assertExists()
    }

    @Test
    fun `re-arm clears every verified locked id`() {
        repository.markLockVerified("n1")
        setScreen()

        composeRule.onNodeWithText("1 locked item(s) revealed for this session. Re-arm to hide them again immediately.")
            .assertExists()
        composeRule.onNodeWithTag("settings_relock").performClick()
        composeRule.waitForIdle()

        assertTrue(repository.verifiedLockedIds.value.isEmpty())
        composeRule.onNodeWithText("No locked item is revealed right now.", substring = true)
            .assertExists()
    }
}
