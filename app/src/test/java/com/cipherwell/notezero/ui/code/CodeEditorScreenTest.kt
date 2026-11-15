package com.cipherwell.notezero.ui.code

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cipherwell.notezero.ui.components.common.SensitiveClipboard
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
 * Robolectric + Compose semantics tests for the code editor chrome: stable tags, find /
 * replace bar, go-to-line, font/wrap controls, markdown preview and save-on-leave.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CodeEditorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var repository: FakeCodeRepository
    private var backCount = 0

    @Before
    fun setUp() {
        repository = FakeCodeRepository()
        backCount = 0
    }

    private fun openEditor(
        title: String = "Main.kt",
        body: String = "fun main() {\n    println(\"hi\")\n}",
        initialLine: Int? = null,
        exportTarget: CodeExportTarget? = null,
    ) {
        repository.seedCodeFile(id = "f1", title = title, body = body)
        composeRule.setContent {
            NoteZeroTheme {
                if (exportTarget == null) {
                    CodeEditorScreen(
                        repository = repository,
                        itemId = "f1",
                        initialLine = initialLine,
                        onBack = { backCount++ },
                    )
                } else {
                    CodeEditorScreen(
                        repository = repository,
                        itemId = "f1",
                        initialLine = initialLine,
                        onBack = { backCount++ },
                        exportTarget = exportTarget,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `editor exposes the contract tags and a clean save state`() {
        openEditor()

        composeRule.onNodeWithTag(CodeTags.EDITOR).assertExists()
        composeRule.onNodeWithTag(CodeTags.LINE_NUMBERS).assertExists()
        composeRule.onNodeWithTag(CodeTags.SAVE_STATE).assertTextEquals("Saved")
        composeRule.onNodeWithTag(CodeTags.FIND_TOGGLE).assertExists()
        composeRule.onNodeWithTag(CodeTags.GOTO_LINE).assertExists()
        composeRule.onNodeWithTag(CodeTags.FONT_DECREASE).assertExists()
        composeRule.onNodeWithTag(CodeTags.FONT_INCREASE).assertExists()
        composeRule.onNodeWithTag(CodeTags.WRAP_TOGGLE).assertExists()
        composeRule.onNodeWithTag(CodeTags.STATUS).assertTextContains("Kotlin", substring = true)
        composeRule.onNodeWithTag(CodeTags.BACK).assertExists()
    }

    @Test
    fun `find and replace all rewrite the source`() {
        openEditor(body = "fun main() {}\nfun other() {}")
        composeRule.onNodeWithTag(CodeTags.FIND_TOGGLE).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CodeTags.FIND_QUERY).assertExists()
        composeRule.onNodeWithTag(CodeTags.FIND_QUERY).performTextInput("fun")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.FIND_MATCH_COUNT).assertTextContains("1/2")
        composeRule.onNodeWithTag(CodeTags.FIND_NEXT).assertExists()
        composeRule.onNodeWithTag(CodeTags.FIND_PREV).assertExists()
        composeRule.onNodeWithTag(CodeTags.FIND_CASE).assertExists()

        composeRule.onNodeWithTag(CodeTags.REPLACE_FIELD).performTextInput("fn")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.REPLACE_ALL).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CodeTags.EDITOR).assertTextContains("fn main() {}", substring = true)
        composeRule.onNodeWithTag(CodeTags.FIND_MATCH_COUNT).assertTextContains("No matches")
    }

    @Test
    fun `go to line dialog moves to the requested line`() {
        openEditor(body = "line one\nline two\nline three")
        composeRule.onNodeWithTag(CodeTags.GOTO_LINE).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CodeTags.DIALOG_FIELD).performTextInput("2")
        composeRule.onNodeWithTag(CodeTags.DIALOG_CONFIRM).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CodeTags.STATUS).assertTextContains("Ln 2", substring = true)
    }

    @Test
    fun `font size controls stay within the 12 to 24 sp range`() {
        openEditor()
        composeRule.onNodeWithText("14sp").assertExists()

        composeRule.onNodeWithTag(CodeTags.FONT_INCREASE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("15sp").assertExists()

        composeRule.onNodeWithTag(CodeTags.FONT_DECREASE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("14sp").assertExists()
    }

    @Test
    fun `wrap toggle flips its selected state`() {
        openEditor()
        composeRule.onNodeWithTag(CodeTags.WRAP_TOGGLE).assertIsSelected()
        composeRule.onNodeWithTag(CodeTags.WRAP_TOGGLE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.WRAP_TOGGLE).assertIsNotSelected()
    }

    @Test
    fun `markdown file exposes the shared preview toggle`() {
        openEditor(title = "README.md", body = "# Heading\n\nSome **bold** text.")
        composeRule.onNodeWithTag(CodeTags.PREVIEW_TOGGLE).assertExists()

        composeRule.onNodeWithTag(CodeTags.PREVIEW_TOGGLE).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CodeTags.PREVIEW).assertExists()
        composeRule.onNodeWithText("Heading").assertExists()
    }

    @Test
    fun `kotlin files have no preview toggle`() {
        openEditor()
        composeRule.onNodeWithTag(CodeTags.PREVIEW_TOGGLE).assertDoesNotExist()
    }

    @Test
    fun `file over two mebibytes refuses to open`() {
        openEditor(body = "a".repeat(CodeEditorViewModel.MAX_OPEN_BYTES + 1))

        composeRule.onNodeWithTag(CodeTags.TOO_LARGE).assertExists()
        composeRule.onNodeWithTag(CodeTags.EDITOR).assertDoesNotExist()
    }

    @Test
    fun `typing then back flushes the draft before leaving`() {
        openEditor(body = "")
        composeRule.onNodeWithTag(CodeTags.EDITOR).performTextInput("val answer = 42")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CodeTags.BACK).performClick()
        composeRule.waitUntil(5_000) {
            repository.itemById("f1")?.body?.contains("val answer = 42") == true && backCount == 1
        }

        assertEquals(1, backCount)
        assertTrue(repository.itemById("f1")!!.body.contains("val answer = 42"))
        assertTrue(repository.saveCount >= 1)
    }

    // ---- Copy / export / indent polish ---------------------------------------

    private fun clipboard(): ClipboardManager {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return requireNotNull(context.getSystemService(ClipboardManager::class.java)) {
            "no clipboard service in this environment"
        }
    }

    @Test
    fun `copy puts the draft on a clipboard marked sensitive`() {
        openEditor(body = "fun main() {}")

        composeRule.onNodeWithTag(CodeTags.COPY).performClick()
        composeRule.waitForIdle()

        val clip = requireNotNull(clipboard().primaryClip) { "expected the draft on the clipboard" }
        assertEquals("fun main() {}", clip.getItemAt(0).text.toString())
        assertTrue(
            "the code clip must be marked sensitive for clipboard previews/history",
            clip.description.extras.getBoolean(SensitiveClipboard.SENSITIVE_EXTRA),
        )
    }

    @Test
    fun `copy exports the selection when one is active`() {
        openEditor(body = "abcdef")
        composeRule.onNodeWithTag(CodeTags.EDITOR)
            .performTextInputSelection(TextRange(1, 4))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CodeTags.COPY).performClick()
        composeRule.waitForIdle()

        val clip = requireNotNull(clipboard().primaryClip)
        assertEquals("bcd", clip.getItemAt(0).text.toString())
    }

    @Test
    fun `export requires the unencrypted warning before writing anything`() {
        val writes = mutableListOf<Pair<String, String>>()
        openEditor(
            body = "val secret = 1",
            exportTarget = CodeExportTarget { name, content -> writes += name to content },
        )

        composeRule.onNodeWithTag(CodeTags.EXPORT).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CodeTags.EXPORT_DIALOG).assertExists()
        composeRule.onNodeWithText("UNENCRYPTED", substring = true).assertExists()
        composeRule.onNodeWithTag(CodeTags.EXPORT_CONFIRM).assertExists()
        composeRule.onNodeWithTag(CodeTags.EXPORT_CANCEL).assertExists()
        assertTrue("nothing may be exported before confirmation", writes.isEmpty())

        // Cancel dismisses the gate and writes nothing.
        composeRule.onNodeWithTag(CodeTags.EXPORT_CANCEL).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.EXPORT_CONFIRM).assertDoesNotExist()
        assertTrue(writes.isEmpty())

        // Confirming hands the current draft (whole file) to the SAF target.
        composeRule.onNodeWithTag(CodeTags.EXPORT).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.EXPORT_CONFIRM).performClick()
        composeRule.waitForIdle()

        assertEquals(1, writes.size)
        assertEquals("Main.kt", writes.single().first)
        assertEquals("val secret = 1", writes.single().second)
        composeRule.onNodeWithTag(CodeTags.EXPORT_CONFIRM).assertDoesNotExist()
    }

    @Test
    fun `indent and outdent toolbar buttons rewrite the selection`() {
        openEditor(body = "a\nb")
        composeRule.onNodeWithTag(CodeTags.EDITOR)
            .performTextInputSelection(TextRange(0, 3))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CodeTags.INDENT_INCREASE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.EDITOR).assertTextEquals("    a\n    b")

        composeRule.onNodeWithTag(CodeTags.INDENT_DECREASE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.EDITOR).assertTextEquals("a\nb")
    }

    @Test
    fun `tab indents the caret line instead of moving focus`() {
        openEditor(body = "a\nb")
        composeRule.onNodeWithTag(CodeTags.EDITOR)
            .performTextInputSelection(TextRange(0, 3))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CodeTags.EDITOR).performKeyPress(
            androidx.compose.ui.input.key.KeyEvent(
                android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_TAB,
                ),
            ),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CodeTags.EDITOR).assertTextEquals("    a\n    b")
    }

    @Test
    fun `opening at an initial line positions the caret there`() {
        openEditor(body = "one\ntwo\nthree", initialLine = 3)

        composeRule.onNodeWithTag(CodeTags.STATUS).assertTextContains("Ln 3, Col 1", substring = true)
        assertFalse(repository.itemById("f1")!!.body.isEmpty())
    }
}
