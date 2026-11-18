package com.cipherwell.notezero.book

/**
 * Plain-text (TXT) parser: decodes the file and auto-chapters it so the existing reader gets
 * TOC/progress/search without any manual splitting.
 *
 * Mapping rules:
 * - blank-line-separated blocks become one [BookBlock.Paragraph] each; single newlines inside
 *   a block are joined with a space (wrapped prose stays one paragraph);
 * - a single-line block that starts with a heading word (`chapter`, `part`, `book`, `prologue`,
 *   `epilogue`, `introduction`, `appendix`, case-insensitive, at most 120 characters) starts a
 *   new chapter, becomes its `Heading(1)` block and its title;
 * - otherwise a new "Part N" chapter starts at the next paragraph boundary once the current
 *   chapter passes [BookLimits.TXT_PART_TARGET_CHARS] characters;
 * - TOC = one entry per chapter.
 *
 * Caps: [BookLimits.MAX_REFOWABLE_CHAPTERS] chapters, [BookLimits.MAX_BLOCKS_PER_CHAPTER]
 * blocks / [BookLimits.MAX_TEXT_PER_CHAPTER] chars per chapter, [BookLimits.MAX_TOTAL_BLOCKS] /
 * [BookLimits.MAX_TOTAL_TEXT_CHARS] per book. Truncation is clean and silent.
 *
 * The parser never throws; invalid/binary input, empty text and whitespace-only files return
 * null.
 */
object TxtBookParser {

    private val HEADING_PATTERN = Regex("(?i)^(chapter|part|book|prologue|epilogue|introduction|appendix)\\b")

    fun parse(bytes: ByteArray, fallbackTitle: String): BookContent? {
        return try {
            val decoded = TxtDecoder.decode(bytes) ?: return null
            val text = decoded.text
            if (text.isBlank()) {
                null
            } else {
                val chapters = TxtSplitter(text).split()
                if (chapters.isEmpty()) {
                    null
                } else {
                    val toc = chapters.map { chapter ->
                        BookTocEntry(
                            title = chapter.title ?: "Part ${chapter.index + 1}",
                            chapterIndex = chapter.index,
                            depth = 0,
                        )
                    }
                    InMemoryDocument(
                        title = BookText.fallbackTitle(fallbackTitle),
                        author = null,
                        language = null,
                        chapters = chapters,
                        toc = toc,
                        coverBytes = null,
                        images = emptyMap(),
                    )
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private class TxtSplitter(private val text: String) {

        private class Draft {
            var title: String? = null
            val blocks = ArrayList<BookBlock>()
            var chars: Int = 0
        }

        private val chapters = ArrayList<BookChapter>()
        private var draft: Draft? = null
        private var partCounter: Int = 0
        private var totalBlocks: Int = 0
        private var totalChars: Int = 0
        private var stopped: Boolean = false
        private val paragraph = StringBuilder()
        private var paragraphLines: Int = 0

        fun split(): List<BookChapter> {
            var index = 0
            val length = text.length
            while (index < length && !stopped) {
                var end = index
                while (end < length) {
                    val ch = text[end]
                    if (ch == '\n' || ch == '\r') break
                    end++
                }
                handleLine(index, end)
                index = when {
                    end >= length -> length
                    text[end] == '\r' && end + 1 < length && text[end + 1] == '\n' -> end + 2
                    else -> end + 1
                }
            }
            flushParagraph()
            flushDraft()
            return chapters
        }

        private fun handleLine(start: Int, end: Int) {
            val line = collapseLine(start, end)
            if (line.isEmpty()) {
                flushParagraph()
                return
            }
            if (paragraphLines > 0) paragraph.append(' ')
            paragraph.append(line)
            paragraphLines++
        }

        private fun collapseLine(start: Int, end: Int): String {
            val builder = StringBuilder(end - start)
            var pendingSpace = false
            var index = start
            while (index < end) {
                val ch = text[index]
                if (ch.isWhitespace()) {
                    if (builder.isNotEmpty()) pendingSpace = true
                } else {
                    if (pendingSpace) {
                        builder.append(' ')
                        pendingSpace = false
                    }
                    builder.append(ch)
                }
                index++
            }
            return builder.toString()
        }

        private fun flushParagraph() {
            if (paragraphLines == 0) return
            val singleLine = paragraphLines == 1
            val content = paragraph.toString()
            paragraph.setLength(0)
            paragraphLines = 0
            if (stopped || content.isEmpty()) return
            if (singleLine && content.length <= BookLimits.MAX_DERIVED_TITLE_CHARS &&
                HEADING_PATTERN.containsMatchIn(content)
            ) {
                startHeadingChapter(content)
            } else {
                addParagraph(content)
            }
        }

        private fun startHeadingChapter(heading: String) {
            flushDraft()
            if (stopped) return
            val current = beginDraft() ?: return
            current.title = heading
            if (current.blocks.size >= BookLimits.MAX_BLOCKS_PER_CHAPTER ||
                totalBlocks >= BookLimits.MAX_TOTAL_BLOCKS ||
                totalChars >= BookLimits.MAX_TOTAL_TEXT_CHARS
            ) {
                return
            }
            current.blocks.add(BookBlock.Heading(1, listOf(BookInline(heading))))
            current.chars += heading.length
            totalBlocks++
            totalChars += heading.length
        }

        private fun addParagraph(content: String) {
            if (stopped) return
            var current = draft
            if (current != null && current.blocks.isNotEmpty() &&
                current.chars >= BookLimits.TXT_PART_TARGET_CHARS
            ) {
                flushDraft()
                if (stopped) return
                current = draft
            }
            if (current == null) current = beginDraft() ?: return
            if (current.blocks.size >= BookLimits.MAX_BLOCKS_PER_CHAPTER ||
                totalBlocks >= BookLimits.MAX_TOTAL_BLOCKS
            ) {
                return
            }
            val remaining = minOf(
                BookLimits.MAX_TEXT_PER_CHAPTER - current.chars,
                BookLimits.MAX_TOTAL_TEXT_CHARS - totalChars,
            )
            if (remaining <= 0) return
            val body = if (content.length <= remaining) content else content.substring(0, remaining)
            if (body.isEmpty()) return
            current.blocks.add(BookBlock.Paragraph(listOf(BookInline(body))))
            current.chars += body.length
            totalChars += body.length
            totalBlocks++
            if (totalChars >= BookLimits.MAX_TOTAL_TEXT_CHARS || totalBlocks >= BookLimits.MAX_TOTAL_BLOCKS) {
                stopped = true
            }
        }

        private fun beginDraft(): Draft? {
            draft?.let { return it }
            if (chapters.size >= BookLimits.MAX_REFOWABLE_CHAPTERS) {
                stopped = true
                return null
            }
            val created = Draft()
            draft = created
            return created
        }

        private fun flushDraft() {
            val current = draft ?: return
            draft = null
            if (current.blocks.isEmpty()) return
            val index = chapters.size
            if (index >= BookLimits.MAX_REFOWABLE_CHAPTERS) {
                stopped = true
                return
            }
            val title = current.title ?: "Part ${++partCounter}"
            chapters.add(
                BookChapter(
                    index = index,
                    title = title,
                    href = "txt#chapter-${index + 1}",
                    blocks = current.blocks.toList(),
                ),
            )
        }
    }
}
