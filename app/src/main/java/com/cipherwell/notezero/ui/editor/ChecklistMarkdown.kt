package com.cipherwell.notezero.ui.editor

/**
 * Toggles checklist items directly in the Markdown source, so a tap in the preview
 * persists through the canonical Markdown body instead of a separate side table.
 *
 * Checklist lines are `- [ ]`, `* [x]`, `+ [X]` (indentation allowed). [index] is the
 * zero-based position of the item among ALL checklist items in document order.
 */
object ChecklistMarkdown {

    private val CHECKLIST_LINE = Regex("^(\\s*[-*+]\\s+\\[)([ xX])(])(?:\\s.*)?$")

    /** Returns the Markdown with item [index] flipped, or the original when out of range. */
    fun toggle(markdown: String, index: Int): String {
        if (index < 0 || markdown.isEmpty()) return markdown
        val lines = markdown.split("\n").toMutableList()
        var checklistIndex = -1
        lines.forEachIndexed { lineIndex, line ->
            val match = CHECKLIST_LINE.matchEntire(line) ?: return@forEachIndexed
            checklistIndex++
            if (checklistIndex != index) return@forEachIndexed
            val markerStart = match.groups[2]!!.range.first
            val checked = match.groupValues[2].equals("x", ignoreCase = true)
            val replacement = if (checked) ' ' else 'x'
            lines[lineIndex] = line.substring(0, markerStart) + replacement + line.substring(markerStart + 1)
            return lines.joinToString("\n")
        }
        return markdown
    }

    /** Number of checklist items in [markdown]. */
    fun count(markdown: String): Int =
        markdown.split("\n").count { CHECKLIST_LINE.matchEntire(it) != null }
}
