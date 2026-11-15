package com.cipherwell.notezero.ui.code

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.theme.NoteZeroTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose semantics tests for the code workspace file tree. No crypto or
 * Room: the screen runs against [FakeCodeRepository], exactly as it does against the
 * encrypted repository in production (same frozen interface).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CodeWorkspaceScreenTest {

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

    @Test
    fun `renders the workspace tree with stable tags`() {
        repository.seedFolder(id = "docs", name = "Docs")
        repository.seedCodeFile(id = "f1", title = "Main.kt")
        repository.seedCodeFile(id = "f2", title = "Util.kt")
        repository.seedNote(id = "n1", title = "A note")

        setWorkspace()

        composeRule.onNodeWithTag(CodeTags.WORKSPACE).assertExists()
        composeRule.onNodeWithTag(CodeTags.FILE_TREE).assertExists()
        composeRule.onAllNodesWithTag(CodeTags.FILE_ROW).assertCountEquals(2)
        composeRule.onAllNodesWithTag(CodeTags.FOLDER_ROW).assertCountEquals(1)
        composeRule.onNodeWithTag(CodeTags.NEW_FILE).assertExists()
        composeRule.onNodeWithTag(CodeTags.NEW_FOLDER).assertExists()
        composeRule.onNodeWithTag(CodeTags.BACK).assertExists()
        // Notes must never leak into the code tree.
        composeRule.onNodeWithText("A note").assertDoesNotExist()
    }

    @Test
    fun `empty vault shows the empty state and create actions`() {
        setWorkspace()

        composeRule.onNodeWithTag(CodeTags.EMPTY_STATE).assertExists()
        composeRule.onNodeWithTag(CodeTags.WORKSPACE).assertExists()
        composeRule.onNodeWithText("Your code vault").assertExists()
    }

    @Test
    fun `header pluralizes file and folder counts`() {
        repository.seedCodeFile(id = "f1", title = "Only.kt")
        setWorkspace()
        composeRule.onNodeWithText("1 file · 0 folders · encrypted").assertExists()

        repository.seedCodeFile(id = "f2", title = "Second.kt")
        repository.seedFolder(id = "docs", name = "Docs")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("2 files · 1 folder · encrypted").assertExists()
    }

    @Test
    fun `folder row expands to reveal nested code files`() {
        repository.seedFolder(id = "docs", name = "Docs")
        repository.seedCodeFile(id = "f1", title = "Nested.kt", folderId = "docs")
        setWorkspace()

        composeRule.onAllNodesWithTag(CodeTags.FILE_ROW).assertCountEquals(0)
        composeRule.onAllNodesWithTag(CodeTags.FOLDER_ROW)[0].performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag(CodeTags.FILE_ROW).assertCountEquals(1)
        composeRule.onNodeWithText("Nested.kt").assertExists()
    }

    @Test
    fun `new file dialog creates a code file and opens the editor`() {
        setWorkspace()

        composeRule.onNodeWithTag(CodeTags.NEW_FILE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.DIALOG_FIELD).assertExists()
        composeRule.onNodeWithTag(CodeTags.DIALOG_FIELD).performTextInput("hello.kt")
        composeRule.onNodeWithTag(CodeTags.DIALOG_CONFIRM).performClick()
        composeRule.waitUntil(5_000) { repository.items.value.any { it.title == "hello.kt" } }
        composeRule.waitForIdle()

        val stored = repository.items.value.single()
        assertEquals(ItemKind.CODE_FILE, stored.kind)
        assertEquals("hello.kt", stored.title)
        composeRule.onNodeWithTag(CodeTags.EDITOR).assertExists()
        composeRule.onNodeWithTag(CodeTags.SAVE_STATE).assertTextContains("Saved")
    }

    @Test
    fun `new folder dialog creates a folder`() {
        setWorkspace()

        composeRule.onNodeWithTag(CodeTags.NEW_FOLDER).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.DIALOG_FIELD).performTextInput("Docs")
        composeRule.onNodeWithTag(CodeTags.DIALOG_CONFIRM).performClick()
        composeRule.waitUntil(5_000) { repository.folders.value.any { it.name == "Docs" } }
        composeRule.waitForIdle()

        assertEquals("Docs", repository.folders.value.single().name)
        composeRule.onAllNodesWithTag(CodeTags.FOLDER_ROW).assertCountEquals(1)
    }

    @Test
    fun `folder menu creates a file inside that folder`() {
        repository.seedFolder(id = "docs", name = "Docs")
        setWorkspace()

        composeRule.onAllNodesWithTag(CodeTags.FOLDER_MORE)[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("New file here").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.DIALOG_FIELD).performTextInput("Nested.kt")
        composeRule.onNodeWithTag(CodeTags.DIALOG_CONFIRM).performClick()
        composeRule.waitUntil(5_000) { repository.items.value.any { it.title == "Nested.kt" } }
        composeRule.waitForIdle()

        assertEquals("docs", repository.items.value.single().folderId)
    }

    @Test
    fun `opening a file and typing persists via save on leave`() {
        repository.seedCodeFile(id = "f1", title = "Main.kt", body = "")
        setWorkspace()

        composeRule.onAllNodesWithTag(CodeTags.FILE_ROW)[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.EDITOR).assertExists()

        composeRule.onNodeWithTag(CodeTags.EDITOR).performTextInput("println(42)")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CodeTags.BACK).performClick()
        composeRule.waitUntil(5_000) {
            repository.itemById("f1")?.body?.contains("println(42)") == true
        }
        composeRule.waitForIdle()

        // Leaving the editor returns to the tree without leaving the workspace.
        assertEquals(0, backCount)
        composeRule.onNodeWithTag(CodeTags.FILE_TREE).assertExists()
        composeRule.onAllNodesWithTag(CodeTags.FILE_ROW).assertCountEquals(1)
    }

    @Test
    fun `workspace back button delegates to the shell`() {
        setWorkspace()

        composeRule.onNodeWithTag(CodeTags.BACK).performClick()
        composeRule.waitForIdle()

        assertEquals(1, backCount)
    }

    @Test
    fun `file row opens the editor and can be renamed from the menu`() {
        repository.seedCodeFile(id = "f1", title = "Old.kt")
        setWorkspace()

        composeRule.onAllNodesWithTag(CodeTags.FILE_MORE)[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Rename file").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.DIALOG_FIELD).performTextClearance()
        composeRule.onNodeWithTag(CodeTags.DIALOG_FIELD).performTextInput("App.kt")
        composeRule.onNodeWithTag(CodeTags.DIALOG_CONFIRM).performClick()
        composeRule.waitUntil(5_000) { repository.itemById("f1")?.title == "App.kt" }
        composeRule.waitForIdle()

        assertEquals("App.kt", repository.itemById("f1")!!.title)
        assertFalse(repository.items.value.single().trashed)
        assertTrue(repository.folders.value.isEmpty())
    }

    @Test
    fun `delete from the file menu moves the file to the trash`() {
        repository.seedCodeFile(id = "f1", title = "Old.kt")
        setWorkspace()

        composeRule.onAllNodesWithTag(CodeTags.FILE_MORE)[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete file").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.DIALOG_CONFIRM).performClick()
        composeRule.waitUntil(5_000) { repository.itemById("f1")?.trashed == true }
        composeRule.waitForIdle()

        assertTrue(repository.itemById("f1")!!.trashed)
        composeRule.onAllNodesWithTag(CodeTags.FILE_ROW).assertCountEquals(0)
    }
}
