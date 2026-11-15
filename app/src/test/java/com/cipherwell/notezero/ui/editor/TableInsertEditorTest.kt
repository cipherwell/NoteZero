package com.cipherwell.notezero.ui.editor

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.notes.FakeNoteRepository
import com.cipherwell.notezero.ui.theme.NoteZeroTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric UI tests for the editor's "Insert table" toolbar action and its dialog.
 * The pure insertion/edit transforms are covered by [MarkdownTablesTest].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TableInsertEditorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var repository: FakeNoteRepository
    private var closedCount = 0

    @Before
    fun setUp() {
        repository = FakeNoteRepository()
        closedCount = 0
    }

    private fun seed(body: String) {
        repository.setItems(
            listOf(
                Item(
                    id = "n1",
                    kind = ItemKind.NOTE,
                    title = "Document",
                    body = body,
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                ),
            ),
        )
    }

    private fun openEditor() {
        composeRule.setContent {
            NoteZeroTheme {
                NoteEditorScreen(
                    repository = repository,
                    itemId = "n1",
                    onClose = { closedCount++ },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun openDialog() {
        composeRule.onNodeWithTag("editor_insert_table").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("table_rows_field").assertExists()
        composeRule.onNodeWithTag("table_cols_field").assertExists()
    }

    @Test
    fun `insert table dialog inserts a markdown table at the cursor`() {
        seed(body = "start")
        openEditor()
        openDialog()

        composeRule.onNodeWithTag("table_rows_field").performTextReplacement("2")
        composeRule.onNodeWithTag("table_cols_field").performTextReplacement("3")
        composeRule.onNodeWithTag("table_confirm").performClick()
        composeRule.waitForIdle()

        val body = composeRule.onNodeWithTag("editor_body")
        body.assertTextContains("| Column 1 | Column 2 | Column 3 |", substring = true)
        body.assertTextContains("| --- | --- | --- |", substring = true)
        body.assertTextContains("|  |  |  |", substring = true)
        composeRule.onAllNodesWithTag("table_insert_dialog").assertCountEquals(0)

        composeRule.onNodeWithTag("editor_back").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { closedCount == 1 }
        assertTrue(repository.itemById("n1")!!.body.contains("| Column 1 |"))
    }

    @Test
    fun `cancelling the dialog inserts nothing`() {
        seed(body = "unchanged body")
        openEditor()
        openDialog()

        composeRule.onNodeWithTag("table_cancel").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("table_insert_dialog").assertCountEquals(0)

        composeRule.onNodeWithTag("editor_body").assertTextContains("unchanged body", substring = true)
        composeRule.onNodeWithTag("editor_back").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { closedCount == 1 }
        assertFalse(repository.itemById("n1")!!.body.contains("|"))
    }

    @Test
    fun `out of range sizes disable the confirm button`() {
        seed(body = "body")
        openEditor()
        openDialog()

        composeRule.onNodeWithTag("table_rows_field").performTextReplacement("0")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("table_confirm").assertIsNotEnabled()

        composeRule.onNodeWithTag("table_rows_field").performTextReplacement("21")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("table_confirm").assertIsNotEnabled()

        composeRule.onNodeWithTag("table_rows_field").performTextReplacement("2")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("table_confirm").assertIsEnabled()

        composeRule.onNodeWithTag("table_cancel").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `header toggle inserts a blank header row`() {
        seed(body = "body")
        openEditor()
        openDialog()

        composeRule.onNodeWithTag("table_header_toggle").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("table_rows_field").performTextReplacement("1")
        composeRule.onNodeWithTag("table_cols_field").performTextReplacement("2")
        composeRule.onNodeWithTag("table_confirm").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("editor_body").assertTextContains("|  |  |\n| --- | --- |", substring = true)
    }
}
