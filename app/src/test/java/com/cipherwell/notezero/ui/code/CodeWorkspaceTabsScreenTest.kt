package com.cipherwell.notezero.ui.code

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.ui.theme.NoteZeroTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose semantics tests for the multi-file workspace tabs
 * (docs/CONTRACTS-PHASE14.md §A): open/switch/close, Back keeping tabs, per-tab draft and
 * undo state, and the 8-tab cap notice. Runs against [FakeCodeRepository].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CodeWorkspaceTabsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var repository: FakeCodeRepository
    private var backCount = 0

    @Before
    fun setUp() {
        repository = FakeCodeRepository()
        backCount = 0
    }

    private fun setWorkspace() {
        composeRule.setContent {
            NoteZeroTheme {
                CodeWorkspaceScreen(
                    repository = repository,
                    onBack = { backCount++ },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun openRow(index: Int) {
        composeRule.onAllNodesWithTag(CodeTags.FILE_ROW)[index].performClick()
        composeRule.waitForIdle()
    }

    private fun backToTree() {
        composeRule.onNodeWithTag(CodeTags.BACK).performClick()
        composeRule.waitForIdle()
    }

    /** Scrolls the lazy file tree to [title] and taps its row (rows beyond the viewport are
     * not composed, so a plain index cannot reach them). */
    private fun openFileTitled(title: String) {
        val row = hasTestTag(CodeTags.FILE_ROW) and hasText(title, substring = true)
        composeRule.onNodeWithTag(CodeTags.FILE_TREE).performScrollToNode(row)
        composeRule.onNode(row).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `opening a file from the tree creates one tab`() {
        repository.seedCodeFile(id = "f1", title = "Main.kt", body = "")
        setWorkspace()

        openRow(0)

        composeRule.onNodeWithTag(CodeTags.EDITOR).assertExists()
        composeRule.onNodeWithTag(CodeTags.TABS).assertExists()
        composeRule.onAllNodesWithTag(CodeTags.TAB).assertCountEquals(1)
        composeRule.onAllNodesWithTag(CodeTags.TAB_CLOSE).assertCountEquals(1)
        composeRule.onAllNodesWithTag(CodeTags.TAB)[0].assertIsSelected()
    }

    @Test
    fun `switching tabs preserves the first file draft and undo history`() {
        repository.seedCodeFile(id = "f1", title = "Alpha.kt", body = "")
        repository.seedCodeFile(id = "f2", title = "Beta.kt", body = "")
        setWorkspace()

        openRow(0)
        composeRule.onNodeWithTag(CodeTags.EDITOR).performTextInput("draft alpha")
        composeRule.waitForIdle()
        backToTree()

        openRow(1)
        composeRule.onAllNodesWithTag(CodeTags.TAB).assertCountEquals(2)
        composeRule.onAllNodesWithTag(CodeTags.TAB)[1].assertIsSelected()

        // Switch back to the first tab: its draft and undo stack survived the switch.
        composeRule.onAllNodesWithTag(CodeTags.TAB)[0].performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(CodeTags.TAB).assertCountEquals(2)
        composeRule.onAllNodesWithTag(CodeTags.TAB)[0].assertIsSelected()
        composeRule.onNodeWithTag(CodeTags.EDITOR).assertTextContains("draft alpha", substring = true)

        composeRule.onNodeWithTag(CodeTags.MORE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.UNDO).assertIsEnabled()
    }

    @Test
    fun `closing the active tab activates its neighbour and closing the last returns to the tree`() {
        repository.seedCodeFile(id = "f1", title = "Alpha.kt", body = "")
        repository.seedCodeFile(id = "f2", title = "Beta.kt", body = "")
        repository.seedCodeFile(id = "f3", title = "Gamma.kt", body = "")
        setWorkspace()

        openRow(0)
        backToTree()
        openRow(1)
        backToTree()
        openRow(2)
        composeRule.onAllNodesWithTag(CodeTags.TAB).assertCountEquals(3)

        // Close the active (third) tab: the neighbour to the left becomes active.
        composeRule.onAllNodesWithTag(CodeTags.TAB_CLOSE)[2].performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(CodeTags.TAB).assertCountEquals(2)
        composeRule.onAllNodesWithTag(CodeTags.TAB)[1].assertIsSelected()

        composeRule.onAllNodesWithTag(CodeTags.TAB_CLOSE)[1].performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(CodeTags.TAB).assertCountEquals(1)
        composeRule.onAllNodesWithTag(CodeTags.TAB)[0].assertIsSelected()

        composeRule.onAllNodesWithTag(CodeTags.TAB_CLOSE)[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.TABS).assertDoesNotExist()
        composeRule.onNodeWithTag(CodeTags.FILE_TREE).assertExists()
        assertEquals(0, backCount)
    }

    @Test
    fun `back from the editor keeps the tabs and reopening activates the existing tab`() {
        repository.seedCodeFile(id = "f1", title = "Main.kt", body = "")
        setWorkspace()

        openRow(0)
        backToTree()

        composeRule.onNodeWithTag(CodeTags.FILE_TREE).assertExists()
        composeRule.onNodeWithTag(CodeTags.TABS).assertDoesNotExist()

        // Re-opening an already-tabbed file activates that tab instead of adding another.
        openRow(0)
        composeRule.onAllNodesWithTag(CodeTags.TAB).assertCountEquals(1)
        composeRule.onAllNodesWithTag(CodeTags.TAB)[0].assertIsSelected()
        assertEquals(0, backCount)
    }

    @Test
    fun `opening more than the tab cap shows the limit notice and does not open`() {
        (1..9).forEach { index ->
            repository.seedCodeFile(id = "f$index", title = "File$index.kt", body = "")
        }
        setWorkspace()

        repeat(CodeTabs.MAX_OPEN_TABS) { index ->
            openFileTitled("File${index + 1}.kt")
            backToTree()
        }

        // The ninth file is refused: still on the tree, with the non-secret notice.
        openFileTitled("File9.kt")
        composeRule.onNodeWithTag(CodeTags.TAB_LIMIT).assertExists()
        composeRule.onNodeWithText("Close a tab before opening more files.").assertExists()
        composeRule.onNodeWithTag(CodeTags.EDITOR).assertDoesNotExist()
        composeRule.onNodeWithTag(CodeTags.FILE_TREE).assertExists()
    }

    @Test
    fun `a tab without a title falls back to the untitled label`() {
        repository.seedCodeFile(id = "f1", title = "", body = "")
        setWorkspace()

        openRow(0)

        composeRule.onAllNodesWithTag(CodeTags.TAB).assertCountEquals(1)
        composeRule.onNodeWithText("Untitled").assertExists()
    }
}
