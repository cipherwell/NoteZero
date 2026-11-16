package com.cipherwell.notezero.ui.code

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
        onDismiss: () -> Unit = {},
    ) {
        setThemedContent {
            NewCodeFileDialog(
                title = "Create code",
                confirmLabel = "Create",
                onDismiss = onDismiss,
                onConfirm = onConfirm,
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
}
