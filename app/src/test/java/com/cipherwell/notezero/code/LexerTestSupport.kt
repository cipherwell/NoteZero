package com.cipherwell.notezero.code

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Shared assertions for the pure-JVM lexer tests. Every token list produced by a [CodeLexer]
 * must satisfy the structural invariants checked by [assertTokenInvariants]; the helpers below
 * make per-language assertions read in terms of source text and token type.
 */

internal fun lex(language: CodeLanguage, text: String): List<SyntaxToken> =
    CodeLexers.forLanguage(language).tokenize(text)

/** Verifies bounds, ordering, non-overlap and the token budget. */
internal fun assertTokenInvariants(text: String, tokens: List<SyntaxToken>) {
    var previousEnd = 0
    for (token in tokens) {
        assertTrue("token start out of bounds: $token in ${text.length}-char text", token.start >= 0)
        assertTrue("token end must be > start: $token", token.end > token.start)
        assertTrue("token end out of bounds: $token in ${text.length}-char text", token.end <= text.length)
        assertTrue(
            "tokens must be ascending and non-overlapping: $token starts before $previousEnd",
            token.start >= previousEnd,
        )
        previousEnd = token.end
    }
    assertTrue("token budget exceeded: ${tokens.size} > $MAX_TOKENS", tokens.size <= MAX_TOKENS)
}

internal fun SyntaxToken.source(text: String): String = text.substring(start, end)

/** Asserts that a token with exactly [value] as source text exists with [type]; returns it. */
internal fun assertToken(
    text: String,
    tokens: List<SyntaxToken>,
    value: String,
    type: TokenType,
): SyntaxToken {
    val match = tokens.firstOrNull { text.substring(it.start, it.end) == value && it.type == type }
    assertNotNull(
        "expected $type token `$value`, got: ${describe(text, tokens)}",
        match,
    )
    return match!!
}

/** Asserts that a token with source text [value] exists with [type] at exactly [start]. */
internal fun assertTokenAt(
    text: String,
    tokens: List<SyntaxToken>,
    start: Int,
    value: String,
    type: TokenType,
) {
    val found = tokens.firstOrNull { it.start == start && it.type == type }
    assertNotNull("expected token at $start, got: ${describe(text, tokens)}", found)
    val match = found!!
    val actual = text.substring(match.start, match.end)
    assertTrue(
        "expected `$value` at $start, got `$actual` (${match.type})",
        actual == value,
    )
}

/** Asserts no token of [type] exists whose source text is [value]. */
internal fun assertNoToken(text: String, tokens: List<SyntaxToken>, value: String, type: TokenType) {
    val match = tokens.firstOrNull { text.substring(it.start, it.end) == value && it.type == type }
    if (match != null) fail("unexpected $type token `$value` at ${match.start}..${match.end}")
}

internal fun tokensOf(text: String, tokens: List<SyntaxToken>, type: TokenType): List<String> =
    tokens.filter { it.type == type }.map { text.substring(it.start, it.end) }

internal fun assertAllTokensWithin(text: String, tokens: List<SyntaxToken>) {
    for (token in tokens) {
        if (token.end > text.length || token.start < 0 || token.end <= token.start) {
            fail("invalid token $token for text of length ${text.length}")
        }
    }
}

/** Human-readable token dump used in assertion messages (capped so failures stay readable). */
internal fun describe(text: String, tokens: List<SyntaxToken>): String =
    tokens.take(60).joinToString(prefix = "[", postfix = if (tokens.size > 60) ", ...]" else "]") {
        "${it.type}(${quoted(it.source(text))}@${it.start})"
    }

private fun quoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
