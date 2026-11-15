package com.cipherwell.notezero.code

/**
 * Token categories produced by the pure-Kotlin code lexers.
 *
 * The set is deliberately small and UI-agnostic: it maps 1:1 onto a syntax colour palette
 * (the dispatcher in `ui/code` decides actual colours). Whitespace is never tokenized, so
 * renderers must fall back to the base text colour for any span not covered by a token.
 *
 * [ERROR] is reserved for text a lexer can positively identify as invalid for its language
 * (currently only JSON, for characters that are legal in no JSON token). Unterminated
 * strings/comments are still reported as their construct type — they never throw.
 */
enum class TokenType {
    /** Language keyword (`fun`, `class`, `if`, `true`, ...). */
    KEYWORD,

    /** Built-in / primitive / standard-library type name (`Int`, `void`, `string`, ...). */
    TYPE,

    /** String literal, including its delimiters. */
    STRING,

    /** Character literal, including its delimiters. */
    CHAR,

    /** Numeric literal. */
    NUMBER,

    /** Comment, including delimiters. */
    COMMENT,

    /** Annotation / decorator, including the leading `@`. */
    ANNOTATION,

    /** Operator (`+`, `==`, `->`, `|`, ...). */
    OPERATOR,

    /** Punctuation (`(`, `{`, `,`, `:`, `<`, `>`, ...). */
    PUNCTUATION,

    /** Ordinary identifier: variable, function, command or key name. */
    IDENTIFIER,

    /** Property-like symbol: JSON object keys, shell parameters/expansions. */
    PROPERTY,

    /** Element name inside markup (`<tag>`). */
    TAG,

    /** Attribute name inside a markup tag. */
    ATTRIBUTE,

    /** Markdown heading line (`# Title`). */
    HEADING,

    /** Markdown link/image or autolink. */
    LINK,

    /** Markdown inline code span or fenced code block. */
    CODE_SPAN,

    /** Invalid input for this language (see [CodeLanguage] notes in docs/EDITOR-SPEC.md). */
    ERROR,
}

/**
 * A highlighted span of source text.
 *
 * Offsets are UTF-16 code-unit indices into the original [String], matching
 * `CharSequence`/Compose text offsets. The span is half-open: [start, end). The
 * invariants for every list returned by a [CodeLexer] are:
 *
 * - `0 <= start < end <= text.length`;
 * - non-overlapping and ascending: `tokens[i].end <= tokens[i + 1].start`;
 * - at most [MAX_TOKENS] entries.
 */
data class SyntaxToken(val start: Int, val end: Int, val type: TokenType) {
    init {
        require(start >= 0) { "start must be >= 0: $start" }
        require(end > start) { "end must be > start ($start, $end)" }
    }

    val length: Int get() = end - start
}

/** Hard upper bound on the number of tokens any lexer will return for one input. */
const val MAX_TOKENS: Int = 200_000

/**
 * A language-specific lexical analyzer.
 *
 * Implementations are stateless from the caller's point of view and safe to reuse. They must
 * never throw on arbitrary input: malformed or truncated source degrades to a shorter (or less
 * precise) token list. Scanning is single-pass and stops once [MAX_TOKENS] tokens are produced,
 * so hostile input cannot cause unbounded work.
 */
interface CodeLexer {
    val language: CodeLanguage

    /** Non-overlapping, ascending spans; never throws, bounded work. */
    fun tokenize(text: String): List<SyntaxToken>
}
