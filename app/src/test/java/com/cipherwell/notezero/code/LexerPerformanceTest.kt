package com.cipherwell.notezero.code

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Performance guard for the contract target "tokenize 200 KB of source in < 250 ms".
 *
 * The assertion uses a deliberately generous bound (2 s) so CI machines under load stay green;
 * the measured wall time is printed (and captured in the JUnit XML report) for the record.
 * A warmup pass removes JIT/class-init noise from the measurement.
 */
class LexerPerformanceTest {

    private companion object {
        const val TARGET_BYTES = 200 * 1024
        const val GENEROUS_BOUND_MS = 2_000.0
    }

    private fun repeatBlock(block: String, targetLength: Int): String = buildString(targetLength + block.length) {
        while (length < targetLength) append(block)
    }

    private fun measure(label: String, language: CodeLanguage, source: String): Double {
        val lexer = CodeLexers.forLanguage(language)
        lexer.tokenize(source) // warmup: JIT + first-touch allocation
        val startedAt = System.nanoTime()
        val tokens = lexer.tokenize(source)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
        assertTokenInvariants(source, tokens)
        assertTrue("$label produced no tokens", tokens.isNotEmpty())
        println(
            "[perf] $label: ${source.length} chars, ${tokens.size} tokens, " +
                "${String.format(java.util.Locale.ROOT, "%.2f", elapsedMs)} ms " +
                "(target < 250 ms, asserted < ${GENEROUS_BOUND_MS.toInt()} ms)",
        )
        return elapsedMs
    }

    @Test
    fun tokenizesTwoHundredKilobytesOfKotlinQuickly() {
        val block = """
            package com.example.demo

            // A realistic line of Kotlin source with a few tokens.
            @Suppress("unused")
            class Sample(val id: Int, val name: String) {
                private val cache: MutableMap<String, Int> = mutableMapOf()
                fun compute(value: Int): String {
                    val factor = 0x1F + 1_000 + 3.5e2
                    val text = "value " + value + " for " + name
                    return if (value > factor) text else ""
                }
            }
        """.trimIndent() + "\n"
        val source = repeatBlock(block, TARGET_BYTES)
        assertTrue("sample must be at least 200 KB", source.length >= TARGET_BYTES)
        val elapsed = measure("Kotlin", CodeLanguage.KOTLIN, source)
        assertTrue("Kotlin 200 KB took $elapsed ms (bound $GENEROUS_BOUND_MS)", elapsed < GENEROUS_BOUND_MS)
    }

    @Test
    fun tokenizesTwoHundredKilobytesOfJavaScriptQuickly() {
        val block = """
            // Module with comments, templates and regexes.
            const cache = new Map();
            function compute(value) {
                const factor = 0x1F + 1000 + 3.5e2;
                const text = `value  for `;
                const re = /ab+c/gi;
                return value > factor && re.test(text) ? text : '';
            }
            export default compute;
        """.trimIndent() + "\n"
        val source = repeatBlock(block, TARGET_BYTES)
        val elapsed = measure("JavaScript", CodeLanguage.JAVASCRIPT, source)
        assertTrue("JavaScript 200 KB took $elapsed ms", elapsed < GENEROUS_BOUND_MS)
    }

    @Test
    fun tokenizesTwoHundredKilobytesOfXmlQuickly() {
        val block = """
            <?xml version="1.0" encoding="utf-8"?>
            <!-- layout row -->
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="wrap_content">
                <TextView android:id="@+id/title" android:text="Hello" />
                <TextView android:id="@+id/subtitle" android:text="World" />
            </LinearLayout>
        """.trimIndent() + "\n"
        val source = repeatBlock(block, TARGET_BYTES)
        val elapsed = measure("XML", CodeLanguage.XML, source)
        assertTrue("XML 200 KB took $elapsed ms", elapsed < GENEROUS_BOUND_MS)
    }
}
