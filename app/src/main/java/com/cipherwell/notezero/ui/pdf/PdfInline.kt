package com.cipherwell.notezero.ui.pdf

/**
 * One styled run of inline Markdown text destined for the PDF renderer.
 *
 * Pure Kotlin (no Android types) so the inline grammar is a plain JVM unit test. The PDF
 * renderer maps these runs to `Spannable` styles; the HTML/Markdown exporters do not use this
 * class (they keep the source syntax).
 */
data class PdfInlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    /** Link label: rendered in the link colour/underline, never as a clickable URL. */
    val link: Boolean = false,
)

/**
 * Inline Markdown → [PdfInlineRun] list for the PDF exporter.
 *
 * Supported: `**bold**`/`__bold__`, `*italic*`/`_italic_`, `` `code` ``, `~~strike~~`,
 * `[label](target)` / `![alt](target)` (the label is kept, the target is dropped — a PDF copy
 * is a static document, so exposing the URL as clickable text would only add an attack surface
 * to other viewers), and backslash escapes. Underscore markers only apply when at least one
 * side is not a letter/digit, so `snake_case_words` stay literal.
 *
 * Robustness: never throws on arbitrary input; unknown/unclosed markers are emitted as literal
 * text; runs are bounded ([MAX_RUNS]) and nesting is bounded ([MAX_DEPTH]) so a hostile note
 * cannot make the exporter allocate without limit.
 */
object PdfInlineMarkdown {

    private const val MAX_RUNS = 4_000
    private const val MAX_DEPTH = 4

    private const val ESCAPABLE = "\\`*_~[]()!#>-."

    fun parse(text: String): List<PdfInlineRun> = try {
        val runs = ArrayList<PdfInlineRun>()
        appendInline(text, start = 0, end = text.length, depth = 0, style = PdfInlineRun(""), out = runs)
        runs
    } catch (_: Throwable) {
        listOf(PdfInlineRun(text))
    }

    private fun appendInline(
        text: String,
        start: Int,
        end: Int,
        depth: Int,
        style: PdfInlineRun,
        out: MutableList<PdfInlineRun>,
    ) {
        if (out.size >= MAX_RUNS || start >= end) return
        if (depth > MAX_DEPTH) {
            emit(out, text.substring(start, end), style)
            return
        }
        var i = start
        val literal = StringBuilder()
        while (i < end) {
            if (out.size >= MAX_RUNS) break
            val c = text[i]
            if (c == '\\' && i + 1 < text.length && ESCAPABLE.indexOf(text[i + 1]) >= 0) {
                literal.append(text[i + 1])
                i += 2
                continue
            }
            if (c == '`') {
                val close = text.indexOf('`', i + 1).takeIf { it in (i + 2) until end } ?: -1
                if (close > i + 1) {
                    flush(literal, style, out)
                    emit(out, text.substring(i + 1, close), style.copy(code = true))
                    i = close + 1
                    continue
                }
            }
            if (c == '~' && i + 1 < end && text[i + 1] == '~' && !isEscaped(text, i)) {
                val close = text.indexOf("~~", i + 2).takeIf { it in (i + 3) until end } ?: -1
                if (close > i + 2) {
                    flush(literal, style, out)
                    appendInline(text, i + 2, end = close, depth = depth + 1, style = style.copy(strike = true), out = out)
                    i = close + 2
                    continue
                }
            }
            if ((c == '*' || c == '_') && !isEscaped(text, i) &&
                (c == '*' || atWordBoundary(text, i))
            ) {
                val bold = i + 1 < end && text[i + 1] == c
                val marker = if (bold) "$c$c" else c.toString()
                val close = findClose(text, i + marker.length, marker, end)
                if (close > i + marker.length) {
                    flush(literal, style, out)
                    val nested = if (bold) style.copy(bold = true) else style.copy(italic = true)
                    appendInline(text, i + marker.length, end = close, depth = depth + 1, style = nested, out = out)
                    i = close + marker.length
                    continue
                }
            }
            if (c == '[' || (c == '!' && i + 1 < end && text[i + 1] == '[')) {
                val labelStart = if (c == '!') i + 2 else i + 1
                val labelEnd = text.indexOf(']', labelStart).takeIf { it in labelStart until end } ?: -1
                if (labelEnd > labelStart && labelEnd + 1 < end && text[labelEnd + 1] == '(') {
                    val targetEnd = text.indexOf(')', labelEnd + 2).takeIf { it in (labelEnd + 2) until end } ?: -1
                    if (targetEnd > labelEnd + 1) {
                        flush(literal, style, out)
                        appendInline(text, labelStart, end = labelEnd, depth = depth + 1, style = style.copy(link = true), out = out)
                        i = targetEnd + 1
                        continue
                    }
                }
            }
            literal.append(c)
            i += 1
        }
        flush(literal, style, out)
    }

    /** Finds the closing [marker] index that is not escaped, or -1. */
    private fun findClose(text: String, from: Int, marker: String, end: Int): Int {
        var i = from
        while (i <= end - marker.length) {
            if (text.startsWith(marker, i) && !isEscaped(text, i)) {
                // A longer run of asterisks/underscores is not a closer for a single marker.
                val after = i + marker.length
                if (marker.length == 1 && after < end && text[after] == marker[0]) {
                    i += 1
                    continue
                }
                return i
            }
            i += 1
        }
        return -1
    }

    private fun isEscaped(text: String, index: Int): Boolean {
        var slashes = 0
        var j = index - 1
        while (j >= 0 && text[j] == '\\') {
            slashes += 1
            j -= 1
        }
        return slashes % 2 == 1
    }

    /** `_` markers only act when at least one neighbour is not a letter/digit. */
    private fun atWordBoundary(text: String, index: Int): Boolean {
        val before = if (index == 0) ' ' else text[index - 1]
        val after = if (index + 1 >= text.length) ' ' else text[index + 1]
        return !before.isLetterOrDigit() || !after.isLetterOrDigit()
    }

    private fun flush(literal: StringBuilder, style: PdfInlineRun, out: MutableList<PdfInlineRun>) {
        if (literal.isEmpty()) return
        emit(out, literal.toString(), style)
        literal.setLength(0)
    }

    private fun emit(out: MutableList<PdfInlineRun>, text: String, style: PdfInlineRun) {
        if (text.isEmpty()) return
        val plain = style.copy(text = "")
        val last = out.lastOrNull()
        if (last != null && last.copy(text = "") == plain) {
            out[out.size - 1] = last.copy(text = last.text + text)
        } else {
            out += style.copy(text = text)
        }
    }
}
