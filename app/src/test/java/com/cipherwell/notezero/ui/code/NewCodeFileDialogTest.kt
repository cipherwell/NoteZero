package com.cipherwell.notezero.ui.code

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.cipherwell.notezero.ui.UiTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NewCodeFileDialogTest : UiTestBase() {

    private fun dialog(
        validate: (String) -> String? = { null },
        onConfirm: (String) -> Unit = {},
        onConfirmRequest: ((NewCodeFileRequest) -> Unit)? = null,
        onDismiss: () -> Unit = {},
    ) {
        setThemedContent {
            NewCodeFileDialog(
                title = "Create code",
                confirmLabel = "Create",
                onDismiss = onDismiss,
                onConfirm = onConfirm,
                onConfirmRequest = onConfirmRequest,
                validate = validate,
            )
        }
    }

    @Test
    fun `create with the default format produces a txt file`() {
        var created: String? = null
        dialog(onConfirm = { created = it })

        composeRule.onNodeWithTag(CodeTags.DIALOG_FIELD)
            .assertTextContains("untitled.txt", substring = true)
        composeRule.onNodeWithTag(CodeTags.DIALOG_CONFIRM).performClick()
        composeRule.waitForIdle()

        assertEquals("untitled.txt", created)
    }

    @Test
    fun `picking a format rewrites the extension and create uses it`() {
        var created: String? = null
        dialog(onConfirm = { created = it })

        composeRule.onNodeWithTag(CodeTags.DIALOG_FIELD).performTextReplacement("script")
        composeRule.onNodeWithTag("code_format_py").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.DIALOG_FIELD)
            .assertTextContains("script.py", substring = true)
        composeRule.onNodeWithTag(CodeTags.DIALOG_CONFIRM).performClick()
        composeRule.waitForIdle()

        assertEquals("script.py", created)
    }

    @Test
    fun `format chips cover well-known languages`() {
        dialog()
        listOf("txt", "md", "html", "css", "js", "py", "kt", "java", "go", "rs", "sql")
            .forEach { extension ->
                composeRule.onNodeWithTag("code_format_$extension").assertExists()
            }
    }

    @Test
    fun `validation failure keeps the dialog open with an inline error`() {
        var created: String? = null
        dialog(validate = { if (it.contains(" ")) "Name cannot contain spaces" else null }, onConfirm = { created = it })

        composeRule.onNodeWithTag(CodeTags.DIALOG_FIELD).performTextReplacement("my file")
        composeRule.onNodeWithTag(CodeTags.DIALOG_CONFIRM).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CodeTags.DIALOG_ERROR, useUnmergedTree = true).assertExists()
        assertTrue(created == null)
    }

    // ---- Templates (docs/CONTRACTS-PHASE14.md §C) ---------------------------

    @Test
    fun `blank template is the default and returns an empty body`() {
        var request: NewCodeFileRequest? = null
        dialog(onConfirmRequest = { request = it })

        composeRule.onNodeWithTag(CodeTags.TEMPLATE_LABEL).assertExists()
        composeRule.onNodeWithTag(CodeTags.TEMPLATE_BLANK).assertIsSelected()
        composeRule.onNodeWithTag(CodeTags.DIALOG_FIELD)
            .assertTextContains("untitled.txt", substring = true)

        composeRule.onNodeWithTag(CodeTags.DIALOG_CONFIRM).performClick()
        composeRule.waitForIdle()

        assertEquals("untitled.txt", request?.name)
        assertEquals("", request?.body)
    }

    @Test
    fun `template chips swap the suggested name extension and body`() {
        val expected = listOf(
            CodeTags.TEMPLATE_BLANK to CodeTemplates.blank,
            CodeTags.TEMPLATE_KOTLIN to CodeTemplates.kotlin,
            CodeTags.TEMPLATE_PYTHON to CodeTemplates.python,
            CodeTags.TEMPLATE_MARKDOWN to CodeTemplates.markdown,
            CodeTags.TEMPLATE_HTML to CodeTemplates.html,
            CodeTags.TEMPLATE_JSON to CodeTemplates.json,
        )
        val requests = mutableListOf<NewCodeFileRequest>()
        dialog(onConfirmRequest = { requests += it })

        expected.forEach { (tag, template) ->
            composeRule.onNodeWithTag(tag).performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(tag).assertIsSelected()
            composeRule.onNodeWithTag(CodeTags.DIALOG_FIELD)
                .assertTextContains(template.suggestedName, substring = true)
            composeRule.onNodeWithTag(CodeTags.DIALOG_CONFIRM).performClick()
            composeRule.waitForIdle()
        }

        assertEquals(expected.map { it.second.suggestedName }, requests.map { it.name })
        assertEquals(expected.map { it.second.body }, requests.map { it.body })
    }

    @Test
    fun `template extension lands in the created request name`() {
        var request: NewCodeFileRequest? = null
        dialog(onConfirmRequest = { request = it })

        composeRule.onNodeWithTag(CodeTags.TEMPLATE_PYTHON).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.DIALOG_FIELD)
            .assertTextContains(".py", substring = true)
        composeRule.onNodeWithTag(CodeTags.DIALOG_CONFIRM).performClick()
        composeRule.waitForIdle()

        assertEquals("script.py", request?.name)
        assertTrue("the python starter body must not be blank", request?.body?.isNotBlank() == true)
        assertTrue("the python starter body must stay valid Python", request?.body?.contains("def main") == true)
    }

    @Test
    fun `picking a mismatched format falls back to the blank body`() {
        var request: NewCodeFileRequest? = null
        dialog(onConfirmRequest = { request = it })

        composeRule.onNodeWithTag(CodeTags.TEMPLATE_KOTLIN).performScrollTo().performClick()
        composeRule.onNodeWithTag("code_format_py").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CodeTags.DIALOG_FIELD)
            .assertTextContains("Main.py", substring = true)
        composeRule.onNodeWithTag(CodeTags.DIALOG_CONFIRM).performClick()
        composeRule.waitForIdle()

        assertEquals("Main.py", request?.name)
        assertEquals("", request?.body)
    }

    @Test
    fun `cancel creates nothing`() {
        var requests = 0
        var dismissed = false
        dialog(onDismiss = { dismissed = true }, onConfirmRequest = { requests++ })

        composeRule.onNodeWithTag(CodeTags.DIALOG_CANCEL).performClick()
        composeRule.waitForIdle()

        assertTrue(dismissed)
        assertEquals(0, requests)
    }
}
