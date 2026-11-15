package com.cipherwell.notezero.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class ChecklistMarkdownTest {

    @Test
    fun `toggles the requested checklist item only`() {
        val markdown = "- [ ] Task one\n- [ ] Task two\n- [ ] Task three"
        assertEquals(
            "- [ ] Task one\n- [x] Task two\n- [ ] Task three",
            ChecklistMarkdown.toggle(markdown, 1),
        )
        assertEquals(
            "- [ ] Task one\n- [ ] Task two\n- [x] Task three",
            ChecklistMarkdown.toggle(markdown, 2),
        )
    }

    @Test
    fun `toggling a checked item unchecks it`() {
        assertEquals("- [ ] done", ChecklistMarkdown.toggle("- [x] done", 0))
        assertEquals("- [ ] DONE", ChecklistMarkdown.toggle("- [X] DONE", 0))
    }

    @Test
    fun `supports mixed list markers indentation and nested lists`() {
        val markdown = "* [ ] star\n  + [x] nested plus\n+ [ ] plus"
        assertEquals("* [ ] star\n  + [ ] nested plus\n+ [ ] plus", ChecklistMarkdown.toggle(markdown, 1))
    }

    @Test
    fun `only checklist lines are toggled`() {
        val markdown = "# Heading\n- bullet\n1. numbered\n- [ ] only checklist\n> quote"
        assertEquals(
            "# Heading\n- bullet\n1. numbered\n- [x] only checklist\n> quote",
            ChecklistMarkdown.toggle(markdown, 0),
        )
    }

    @Test
    fun `out of range indexes and checklist-free markdown are unchanged`() {
        val markdown = "- [ ] only"
        assertEquals(markdown, ChecklistMarkdown.toggle(markdown, 1))
        assertEquals(markdown, ChecklistMarkdown.toggle(markdown, -1))
        val noChecklist = "# heading\nplain"
        assertEquals(noChecklist, ChecklistMarkdown.toggle(noChecklist, 0))
        assertEquals("", ChecklistMarkdown.toggle("", 0))
    }

    @Test
    fun `count reports all checklist items in document order`() {
        assertEquals(3, ChecklistMarkdown.count("- [ ] a\n- [x] b\n  * [ ] c\nplain"))
        assertEquals(0, ChecklistMarkdown.count("plain text"))
    }

    @Test
    fun `checklist item without trailing text still toggles`() {
        assertEquals("- [x]", ChecklistMarkdown.toggle("- [ ]", 0))
    }
}
