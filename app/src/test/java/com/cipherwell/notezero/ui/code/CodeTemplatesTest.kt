package com.cipherwell.notezero.ui.code

import com.cipherwell.notezero.code.CodeFormats
import com.cipherwell.notezero.code.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the built-in new-file templates (docs/CONTRACTS-PHASE14.md §C): unique ids,
 * known extensions, a blank default and non-empty starter bodies for the body templates.
 */
class CodeTemplatesTest {

    @Test
    fun `blank is the first template and carries no body`() {
        assertSame(CodeTemplates.blank, CodeTemplates.all.first())
        assertEquals(CodeTemplates.blank.id, CodeTemplates.byId(CodeTemplates.blank.id).id)
        assertTrue(CodeTemplates.blank.body.isEmpty())
        assertEquals("untitled.txt", CodeTemplates.blank.suggestedName)
        assertEquals("txt", CodeTemplates.blank.format.extension)
    }

    @Test
    fun `template ids and suggested names are unique`() {
        val ids = CodeTemplates.all.map { it.id }
        val names = CodeTemplates.all.map { it.suggestedName }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `every template uses a known format matching its suggested name`() {
        CodeTemplates.all.forEach { template ->
            val format = CodeFormats.forExtension(template.format.extension)
            assertNotNull("Unknown extension for ${template.id}", format)
            assertEquals(
                "Suggested name must end with the template extension",
                template.format.extension,
                template.suggestedName.substringAfterLast('.'),
            )
            assertEquals(template.format.language, Languages.fromFileName(template.suggestedName))
        }
    }

    @Test
    fun `every non-blank template contributes a non-empty starter body`() {
        CodeTemplates.all
            .filter { it.id != CodeTemplates.blank.id }
            .forEach { template ->
                assertTrue("Template ${template.id} has no body", template.body.isNotBlank())
            }
    }

    @Test
    fun `unknown template ids fall back to blank`() {
        assertSame(CodeTemplates.blank, CodeTemplates.byId("does-not-exist"))
        assertSame(CodeTemplates.blank, CodeTemplates.byId(""))
    }
}
