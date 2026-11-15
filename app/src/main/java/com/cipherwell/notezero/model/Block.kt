package com.cipherwell.notezero.model

/**
 * Rich-text document model for notes (decision 2026-11-15: custom Compose block
 * model, not a third-party rich-text engine).
 *
 * Markdown is the canonical on-disk/export representation; [BlockDocument] is the
 * parsed, rendered view used by the Compose editor and preview. Inline formatting
 * inside [Block.Paragraph], [Block.Heading] and list items is kept as Markdown
 * inline syntax and rendered by the UI's inline renderer — this keeps the document
 * model small, lossless and fully auditable.
 */
sealed interface Block {
    /** ATX heading, [level] in 1..6. */
    data class Heading(val level: Int, val text: String) : Block

    /** Consecutive lines of text; inline markdown (**bold**, *italic*, `code`, [link](url)). */
    data class Paragraph(val text: String) : Block

    /** Bulleted list. Items may be nested via [ListItem.depth]. */
    data class Bullets(val items: List<ListItem>) : Block

    /** Numbered list. Items may be nested via [ListItem.depth]. */
    data class Numbered(val items: List<ListItem>) : Block

    /** Task list: `- [ ]` / `- [x]`. */
    data class Checklist(val items: List<CheckItem>) : Block

    /** Fenced code block, optional language tag for the highlighter. */
    data class Code(val language: String?, val code: String) : Block

    /** Block quote (single level in the MVP). */
    data class Quote(val text: String) : Block

    /** Horizontal rule. */
    data object Rule : Block

    /** Simple table; headers + rows of cells. */
    data class Table(val headers: List<String>, val rows: List<List<String>>) : Block
}

data class ListItem(val text: String, val depth: Int = 0)

data class CheckItem(val text: String, val checked: Boolean)

/** Parsed view of a note body. */
data class BlockDocument(val blocks: List<Block>)

/**
 * Markdown <-> [BlockDocument] codec. Pure Kotlin (JVM-testable, no Android deps).
 *
 * Round-trip requirement: `render(parse(md))` must be byte-identical to `md` for
 * documents the editor itself produced, and parse must never throw on arbitrary
 * user input (unknown constructs become [Block.Paragraph]).
 */
interface BlockMarkdownCodec {
    fun parse(markdown: String): BlockDocument

    fun render(document: BlockDocument): String
}
