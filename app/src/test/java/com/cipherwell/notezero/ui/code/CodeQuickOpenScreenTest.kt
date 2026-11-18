package com.cipherwell.notezero.ui.code

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.ui.theme.NoteZeroTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose semantics tests for the quick-open file switcher
 * (docs/CONTRACTS-PHASE14.md §B): filtering, empty state, locked-file exclusion, opening a
 * result as a tab and IME/keyboard activation. Runs against [FakeCodeRepository].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CodeQuickOpenScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var repository: FakeCodeRepository

    @Before
    fun setUp() {
        repository = FakeCodeRepository()
    }

    private fun setWorkspace() {
        composeRule.setContent {
            NoteZeroTheme {
                CodeWorkspaceScreen(
                    repository = repository,
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun openQuickOpen() {
        composeRule.onNodeWithTag(CodeTags.QUICK_OPEN).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.QUICK_OPEN_DIALOG).assertExists()
    }

    private fun typeQuery(query: String) {
        composeRule.onNodeWithTag(CodeTags.QUICK_OPEN_QUERY).performClick()
        composeRule.onNodeWithTag(CodeTags.QUICK_OPEN_QUERY).performTextInput(query)
        composeRule.waitForIdle()
    }

    @Test
    fun `toolbar action opens the dialog and lists visible code files only`() {
        repository.seedFolder(id = "docs", name = "Docs")
        repository.seedCodeFile(id = "f1", title = "Main.kt")
        repository.seedCodeFile(id = "f2", title = "Nested.kt", folderId = "docs")
        repository.seedNote(id = "n1", title = "Shopping")
        setWorkspace()

        openQuickOpen()

        composeRule.onNodeWithTag(CodeTags.QUICK_OPEN_QUERY).assertExists()
        composeRule.onNodeWithTag(CodeTags.QUICK_OPEN_RECENT).assertExists()
        composeRule.onAllNodesWithTag(CodeTags.QUICK_OPEN_RESULT).assertCountEquals(2)
        composeRule.onNodeWithText("Nested.kt").assertExists()
        composeRule.onNodeWithText("Shopping").assertDoesNotExist()
    }

    @Test
    fun `typing filters the results case-insensitively`() {
        repository.seedCodeFile(id = "f1", title = "MainActivity.kt")
        repository.seedCodeFile(id = "f2", title = "script.py")
        setWorkspace()
        openQuickOpen()

        typeQuery("SCRIPT")

        composeRule.onAllNodesWithTag(CodeTags.QUICK_OPEN_RESULT).assertCountEquals(1)
        // The dialog overlays the file tree, so assert on the result row, not on plain text.
        composeRule.onAllNodesWithTag(CodeTags.QUICK_OPEN_RESULT)[0]
            .assertTextContains("script.py", substring = true)
    }

    @Test
    fun `a query without matches shows the empty state`() {
        repository.seedCodeFile(id = "f1", title = "Main.kt")
        setWorkspace()
        openQuickOpen()

        typeQuery("zzz-no-such-file")

        composeRule.onAllNodesWithTag(CodeTags.QUICK_OPEN_RESULT).assertCountEquals(0)
        composeRule.onNodeWithTag(CodeTags.QUICK_OPEN_EMPTY).assertExists()
    }

    @Test
    fun `locked files never appear in quick open`() {
        repository.seedCodeFile(id = "public", title = "Public.kt")
        repository.seedCodeFile(id = "locked", title = "Locked.kt", locked = true)
        setWorkspace()

        openQuickOpen()

        composeRule.onAllNodesWithTag(CodeTags.QUICK_OPEN_RESULT).assertCountEquals(1)
        composeRule.onNodeWithText("Locked.kt").assertDoesNotExist()
    }

    @Test
    fun `tapping a result opens the file in a new tab`() {
        repository.seedCodeFile(id = "f1", title = "Main.kt", body = "fun main() {}")
        setWorkspace()
        openQuickOpen()

        composeRule.onAllNodesWithTag(CodeTags.QUICK_OPEN_RESULT)[0].performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CodeTags.EDITOR).assertExists()
        composeRule.onNodeWithTag(CodeTags.TABS).assertExists()
        composeRule.onAllNodesWithTag(CodeTags.TAB).assertCountEquals(1)
        composeRule.onNodeWithTag(CodeTags.QUICK_OPEN_DIALOG).assertDoesNotExist()
    }

    @Test
    fun `the ime done action opens the first match`() {
        repository.seedCodeFile(id = "f1", title = "Main.kt", body = "fun main() {}")
        setWorkspace()
        openQuickOpen()

        typeQuery("Main")
        composeRule.onNodeWithTag(CodeTags.QUICK_OPEN_QUERY).performImeAction()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CodeTags.EDITOR).assertExists()
        composeRule.onAllNodesWithTag(CodeTags.TAB).assertCountEquals(1)
    }

    @Test
    fun `opening an already tabbed file again keeps a single tab`() {
        repository.seedCodeFile(id = "f1", title = "Main.kt", body = "")
        setWorkspace()

        openQuickOpen()
        composeRule.onAllNodesWithTag(CodeTags.QUICK_OPEN_RESULT)[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.BACK).performClick()
        composeRule.waitForIdle()

        openQuickOpen()
        composeRule.onAllNodesWithTag(CodeTags.QUICK_OPEN_RESULT)[0].performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag(CodeTags.TAB).assertCountEquals(1)
    }
}
