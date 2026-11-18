package com.cipherwell.notezero.book

/**
 * Bounded inline-run builder shared by the FB2 block mapper.
 *
 * Behaves like the EPUB engine's inline builder: XML whitespace runs collapse to a single
 * space, each block is trimmed at the edges, adjacent runs with identical formatting merge and
 * at most [maxChars] characters are accepted (further input is silently dropped, which keeps a
 * hostile document from allocating unbounded text). Formatting is reduced to the public
 * [BookInline] flags; links keep their label only.
 */
internal class InlineAccumulator(private val maxChars: Int) {

    private class Run(
        val bold: Boolean,
        val italic: Boolean,
        val code: Boolean,
        val link: Boolean,
    ) {
        val text = StringBuilder()
    }

    private val runs = ArrayList<Run>()
    private var lastWasSpace = true
    private var used = 0

    fun append(
        chars: CharArray,
        start: Int,
        end: Int,
        bold: Boolean,
        italic: Boolean,
        code: Boolean,
        link: Boolean,
    ) {
        var index = start
        while (index < end) {
            if (used >= maxChars) return
            appendChar(chars[index], bold, italic, code, link)
            index++
        }
    }

    fun append(
        text: String,
        bold: Boolean,
        italic: Boolean,
        code: Boolean,
        link: Boolean,
    ) {
        var index = 0
        while (index < text.length) {
            if (used >= maxChars) return
            appendChar(text[index], bold, italic, code, link)
            index++
        }
    }

    fun isEmpty(): Boolean = runs.all { it.text.isBlank() }

    fun build(): List<BookInline> {
        if (runs.isEmpty()) return emptyList()
        for (index in runs.indices.reversed()) {
            val run = runs[index]
            while (run.text.isNotEmpty() && run.text[run.text.length - 1] == ' ') {
                run.text.deleteCharAt(run.text.length - 1)
            }
            if (run.text.isEmpty()) runs.removeAt(index) else break
        }
        if (runs.isEmpty()) return emptyList()
        val result = ArrayList<BookInline>(runs.size)
        for (run in runs) {
            if (run.text.isEmpty()) continue
            result.add(
                BookInline(
                    text = run.text.toString(),
                    bold = run.bold,
                    italic = run.italic,
                    code = run.code,
                    link = run.link,
                ),
            )
        }
        return result
    }

    private fun appendChar(ch: Char, bold: Boolean, italic: Boolean, code: Boolean, link: Boolean) {
        if (isXmlWhitespace(ch)) {
            if (lastWasSpace) return
            appendRun(' ', bold, italic, code, link)
            lastWasSpace = true
        } else {
            appendRun(ch, bold, italic, code, link)
            lastWasSpace = false
        }
        used++
    }

    private fun appendRun(ch: Char, bold: Boolean, italic: Boolean, code: Boolean, link: Boolean) {
        val last = if (runs.isEmpty()) null else runs[runs.size - 1]
        if (last != null && last.bold == bold && last.italic == italic &&
            last.code == code && last.link == link
        ) {
            last.text.append(ch)
        } else {
            val created = Run(bold, italic, code, link)
            created.text.append(ch)
            runs.add(created)
        }
    }

    private fun isXmlWhitespace(ch: Char): Boolean =
        ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r'
}
