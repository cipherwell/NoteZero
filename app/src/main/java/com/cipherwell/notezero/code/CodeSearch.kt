package com.cipherwell.notezero.code

import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import java.util.ArrayList

/**
 * One workspace-wide search hit in a decrypted code file.
 *
 * [line] and [column] are **1-based** (line 1, column 1 is the first character of the file) so
 * the UI can show them unchanged and feed [line] straight into the editor's `goToLine`.
 * [column] counts UTF-16 code units from the start of the line, tabs included, which is the
 * same unit the editor uses for a caret offset.
 *
 * [snippet] is a single line of text around the hit, bounded to [CodeSearch.MAX_SNIPPET_CHARS];
 * it is trimmed and may start/end with an ellipsis. A hit in the file *name* (title) reports
 * line 1 and a 1-based column in the name, with the name as its snippet.
 */
data class CodeSearchMatch(
    val itemId: String,
    val fileName: String,
    val line: Int,
    val column: Int,
    val snippet: String,
)

/**
 * Workspace-wide literal search over [ItemKind.CODE_FILE] items (names and source bodies).
 *
 * Pure Kotlin/JVM: no Android, Compose, regex or I/O. Operates on the already-decrypted
 * in-memory index and keeps nothing; the caller decides whether a hit may be shown.
 *
 * Guarantees:
 *  - trashed items and non-code items (notes) are never searched;
 *  - results are deterministic: items in the given order, a name hit before that file's body
 *    hits, body hits ascending by offset and non-overlapping;
 *  - at most [limit] hits are returned (`limit <= 0` returns empty);
 *  - an empty query returns empty; matching is literal — the query is never a pattern;
 *  - **never throws**: pathological input (enormous/minified lines, lone surrogates, newlines
 *    in the query, CRLF, NUL characters) degrades to fewer hits instead of an exception.
 *
 * Work is bounded by one line index per file plus one literal scan of each item's name/body,
 * capped at [limit] hits — no regex backtracking, no per-hit re-scan of the body.
 */
object CodeSearch {

    /** Default cap the workspace UI uses; the helper accepts any positive limit. */
    const val DEFAULT_LIMIT: Int = 200

    /** Hard cap on the characters of one snippet, so a minified file cannot explode a row. */
    const val MAX_SNIPPET_CHARS: Int = 160

    /** Characters of context kept before the hit when a line has to be windowed. */
    private const val SNIPPET_LEAD_CHARS: Int = 48

    /**
     * Returns up to [limit] hits for [query] in the decrypted [items].
     *
     * Each file contributes at most one name hit (its first match) plus its body hits. An
     * empty query, a non-positive limit or an empty item list returns an empty list.
     */
    fun search(
        items: List<Item>,
        query: String,
        caseSensitive: Boolean,
        limit: Int = DEFAULT_LIMIT,
    ): List<CodeSearchMatch> {
        if (query.isEmpty() || limit <= 0 || items.isEmpty()) return emptyList()
        val matches = ArrayList<CodeSearchMatch>(minOf(limit, 32))
        try {
            for (item in items) {
                if (matches.size >= limit) break
                if (item.kind != ItemKind.CODE_FILE || item.trashed) continue
                val fileName = item.title

                // A name hit is reported as line 1: opening it lands at the top of the file.
                val nameHit = CodeEditing.findMatches(
                    text = fileName,
                    query = query,
                    caseSensitive = caseSensitive,
                    limit = 1,
                ).firstOrNull()
                if (nameHit != null) {
                    matches.add(
                        CodeSearchMatch(
                            itemId = item.id,
                            fileName = fileName,
                            line = 1,
                            column = nameHit.start + 1,
                            snippet = snippetForLine(
                                text = fileName,
                                index = LineIndex.of(fileName),
                                line = 0,
                                hitOffset = nameHit.start,
                            ),
                        ),
                    )
                    if (matches.size >= limit) break
                }

                val body = item.body
                if (body.isEmpty()) continue
                val bodyHits = CodeEditing.findMatches(
                    text = body,
                    query = query,
                    caseSensitive = caseSensitive,
                    limit = limit - matches.size,
                )
                if (bodyHits.isEmpty()) continue
                val index = LineIndex.of(body)
                for (hit in bodyHits) {
                    val line = index.lineOf(hit.start)
                    matches.add(
                        CodeSearchMatch(
                            itemId = item.id,
                            fileName = fileName,
                            line = line + 1,
                            column = hit.start - index.startOfLine(line) + 1,
                            snippet = snippetForLine(body, index, line, hit.start),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            // Defensive: a workspace search must never take the screen down. Keep what we
            // already have (bounded by `limit`) and let the UI render a partial list.
            return matches
        }
        return matches
    }

    /**
     * Snippet for the line containing [hitOffset], windowed around the hit when the line is
     * longer than [MAX_SNIPPET_CHARS]. Line content comes from [index], so CRLF terminators and
     * a trailing newline never leak into the row. Never throws.
     */
    private fun snippetForLine(
        text: String,
        index: LineIndex,
        line: Int,
        hitOffset: Int,
    ): String {
        val lineStart = index.startOfLine(line)
        val lineEnd = index.endOfLine(text, line)
        if (lineEnd <= lineStart) return ""
        val content = text.substring(lineStart, lineEnd)
        val hitInContent = (hitOffset - lineStart).coerceIn(0, content.length)
        val trimmedStart = content.indexOfFirst { it != ' ' && it != '\t' }
        val visible = if (trimmedStart < 0) "" else content.substring(trimmedStart).trimEnd()
        if (visible.isEmpty()) return ""
        if (visible.length <= MAX_SNIPPET_CHARS) return sanitize(visible)

        // Keep the hit inside the window: start a little before it, then clamp.
        val hitInVisible = (hitInContent - maxOf(trimmedStart, 0)).coerceIn(0, visible.length - 1)
        var windowStart = maxOf(0, hitInVisible - SNIPPET_LEAD_CHARS)
        if (windowStart + MAX_SNIPPET_CHARS > visible.length) {
            windowStart = maxOf(0, visible.length - MAX_SNIPPET_CHARS)
        }
        var windowEnd = minOf(visible.length, windowStart + MAX_SNIPPET_CHARS)
        // Do not split a surrogate pair at either edge.
        if (windowStart > 0 && Character.isLowSurrogate(visible[windowStart])) windowStart++
        if (windowEnd < visible.length && Character.isLowSurrogate(visible[windowEnd])) windowEnd--
        if (windowEnd <= windowStart) return sanitize(visible.substring(0, MAX_SNIPPET_CHARS))
        val prefix = if (windowStart > 0) "…" else ""
        val suffix = if (windowEnd < visible.length) "…" else ""
        return sanitize(prefix + visible.substring(windowStart, windowEnd) + suffix)
    }

    /** A snippet is a single display line: embedded carriage returns become spaces. */
    private fun sanitize(snippet: String): String =
        if (snippet.indexOf('\r') < 0) snippet else snippet.replace('\r', ' ')
}
