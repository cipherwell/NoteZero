package com.cipherwell.notezero.book

/**
 * Render-ready block model for one EPUB chapter. Inline styling is reduced to boolean
 * flags; links keep their label only (the reader never navigates externally).
 */
sealed interface BookBlock {
    data class Heading(val level: Int, val runs: List<BookInline>) : BookBlock
    data class Paragraph(val runs: List<BookInline>) : BookBlock
    data class ListBlock(val ordered: Boolean, val items: List<BookListItem>) : BookBlock
    data class Quote(val runs: List<BookInline>) : BookBlock
    data class Code(val text: String) : BookBlock
    data class Image(val entry: String, val alt: String) : BookBlock
    data object Rule : BookBlock
}

data class BookInline(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: Boolean = false,
)

data class BookListItem(val runs: List<BookInline>, val depth: Int = 0)

data class BookTocEntry(val title: String, val chapterIndex: Int, val depth: Int)

data class BookChapter(
    val index: Int,
    val title: String?,
    val href: String,
    val blocks: List<BookBlock>,
) {
    /**
     * Flat searchable text: every block's text joined by `\n`. Headings, quotes, list items,
     * code and rule-bearing text are included; images contribute their alt text.
     */
    fun plainText(): String = buildString {
        var first = true
        for (block in blocks) {
            val text = blockPlainText(block)
            if (text.isEmpty()) continue
            if (!first) append('\n')
            append(text)
            first = false
        }
    }
}

private fun blockPlainText(block: BookBlock): String = when (block) {
    is BookBlock.Heading -> inlineRunsText(block.runs)
    is BookBlock.Paragraph -> inlineRunsText(block.runs)
    is BookBlock.ListBlock -> block.items.joinToString("\n") { inlineRunsText(it.runs) }
    is BookBlock.Quote -> inlineRunsText(block.runs)
    is BookBlock.Code -> block.text
    is BookBlock.Image -> block.alt
    BookBlock.Rule -> ""
}

/** Concatenated text of inline runs; shared by `plainText` and chapter-title derivation. */
internal fun inlineRunsText(runs: List<BookInline>): String = buildString {
    for (run in runs) append(run.text)
}
