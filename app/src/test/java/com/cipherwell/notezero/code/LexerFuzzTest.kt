package com.cipherwell.notezero.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Adversarial-input tests: lexers must never throw, must return ordered in-bounds spans and must
 * stop working once the token budget is exhausted, whatever bytes the user throws at them.
 */
class LexerFuzzTest {

    private val alphabet =
        "abcXYZ019 _\t\n\r{}()[]<>/*'\"`\$#@:;,.-+=!?&|%\\~^_".toCharArray()

    private val fragments = listOf(
        "\"", "'", "\"\"\"", "'''", "`", "/*", "*/", "//", "<!--", "-->", "<![CDATA[[",
        "]]>", "```", "~~~", "<<EOF\n", "<<-EOF\n", "\${", "\$(", "\$", "{", "}", "(",
        ")", "[", "]", "<tag", "</tag", ">", "\\", "#", "@", "0x", "1e", "f\"{", "<?", "<!",
        "|", "&&", "x=", ":", ";", ",", "a", "1", "\n", " ", "rb'",
    )

    @Test
    fun randomTextNeverThrowsAndKeepsInvariants() {
        val random = Random(20261115)
        for (language in CodeLanguage.entries) {
            val lexer = CodeLexers.forLanguage(language)
            repeat(120) {
                val length = random.nextInt(0, 400)
                val text = buildString(length) {
                    repeat(length) { append(alphabet[random.nextInt(alphabet.size)]) }
                }
                val tokens = lexer.tokenize(text)
                assertTokenInvariants(text, tokens)
            }
        }
    }

    @Test
    fun randomizedConstructSequencesNeverThrow() {
        val random = Random(20261116)
        for (language in CodeLanguage.entries) {
            val lexer = CodeLexers.forLanguage(language)
            repeat(150) {
                val text = buildString {
                    repeat(random.nextInt(1, 12)) {
                        append(fragments[random.nextInt(fragments.size)])
                    }
                }
                val tokens = lexer.tokenize(text)
                assertTokenInvariants(text, tokens)
            }
        }
    }

    @Test
    fun tokenBudgetCapsDenseTokenStreams() {
        val dense = ",".repeat(250_000)
        assertEquals(250_000, dense.length)
        for (language in listOf(
            CodeLanguage.KOTLIN,
            CodeLanguage.JAVA,
            CodeLanguage.JAVASCRIPT,
            CodeLanguage.TYPESCRIPT,
            CodeLanguage.JSON,
        )) {
            val tokens = lex(language, dense)
            assertEquals("$language must stop exactly at the token budget", MAX_TOKENS, tokens.size)
            assertTokenInvariants(dense, tokens)
        }
        for (language in listOf(CodeLanguage.XML, CodeLanguage.MARKDOWN, CodeLanguage.SHELL)) {
            assertTrue(lex(language, dense).size <= MAX_TOKENS)
        }
    }

    @Test
    fun giganticSingleTokensStayCheapAndBounded() {
        val huge = "x".repeat(1_000_000)
        for (language in listOf(
            CodeLanguage.KOTLIN,
            CodeLanguage.JAVA,
            CodeLanguage.JAVASCRIPT,
            CodeLanguage.PYTHON,
            CodeLanguage.JSON,
            CodeLanguage.SHELL,
        )) {
            val tokens = lex(language, huge)
            assertTrue("$language produced ${tokens.size} tokens", tokens.size <= 4)
            assertTokenInvariants(huge, tokens)
        }
    }

    @Test
    fun longUnterminatedConstructsTerminateAtEndOfInput() {
        val comment = "/*" + "x".repeat(500_000)
        val commentTokens = lex(CodeLanguage.KOTLIN, comment)
        assertEquals(1, commentTokens.size)
        assertEquals(comment.length, commentTokens[0].end)

        val string = "x = \"" + "a".repeat(500_000)
        val stringTokens = lex(CodeLanguage.PYTHON, string)
        assertTokenInvariants(string, stringTokens)
        assertEquals(string.length, stringTokens.last().end)

        val xmlTag = "<div class=\"" + "a".repeat(500_000)
        val xmlTokens = lex(CodeLanguage.XML, xmlTag)
        assertTokenInvariants(xmlTag, xmlTokens)
        assertEquals(xmlTag.length, xmlTokens.last().end)
    }

    @Test
    fun pathologicalQuoteRunsStayWithinBudget() {
        val quotes = "\"".repeat(200_000)
        for (language in listOf(CodeLanguage.KOTLIN, CodeLanguage.PYTHON, CodeLanguage.JSON)) {
            val tokens = lex(language, quotes)
            assertTrue(tokens.size <= MAX_TOKENS)
            assertTokenInvariants(quotes, tokens)
        }
    }

    @Test
    fun emptyAndWhitespaceOnlyTextsProduceNoOrHarmlessTokens() {
        for (language in CodeLanguage.entries) {
            assertEquals(emptyList<SyntaxToken>(), lex(language, ""))
            val whitespace = " \t\r\n\u000B\u000C "
            val tokens = lex(language, whitespace)
            assertTokenInvariants(whitespace, tokens)
        }
    }
}
