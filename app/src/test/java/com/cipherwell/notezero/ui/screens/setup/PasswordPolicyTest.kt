package com.cipherwell.notezero.ui.screens.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordPolicyTest {

    private val strong = "Correct-Horse-9!"

    @Test
    fun `strong password passes every rule`() {
        val result = PasswordPolicy.evaluate(strong)
        assertTrue("expected $strong to pass, unmet=${result.unmet}", result.isValid)
        assertTrue(result.unmet.isEmpty())
    }

    @Test
    fun `short password fails only the length rule`() {
        // 11 chars, has upper/lower/digit/symbol.
        val result = PasswordPolicy.evaluate("Correct-9!x")
        assertEquals(listOf(PasswordPolicy.Rule.LENGTH), result.unmet)
    }

    @Test
    fun `missing character classes are reported individually`() {
        val lower = PasswordPolicy.evaluate("correct-horse-9!")
        assertTrue(lower.unmet.contains(PasswordPolicy.Rule.UPPERCASE))

        val upper = PasswordPolicy.evaluate("CORRECT-HORSE-9!")
        assertTrue(upper.unmet.contains(PasswordPolicy.Rule.LOWERCASE))

        val noDigit = PasswordPolicy.evaluate("Correct-Horse-x!")
        assertTrue(noDigit.unmet.contains(PasswordPolicy.Rule.DIGIT))

        val noSymbol = PasswordPolicy.evaluate("CorrectHorse9x")
        assertTrue(noSymbol.unmet.contains(PasswordPolicy.Rule.SYMBOL))
    }

    @Test
    fun `whitespace does not count as a symbol`() {
        val result = PasswordPolicy.evaluate("CorrectHorse9 ")
        assertTrue(
            "a trailing space must not satisfy the symbol rule",
            result.unmet.contains(PasswordPolicy.Rule.SYMBOL),
        )
    }

    @Test
    fun `twelve characters is the minimum accepted length`() {
        assertTrue(PasswordPolicy.evaluate("Abcdefgh1!xy").isValid) // 12 chars
        assertFalse(PasswordPolicy.evaluate("Abcdefgh1!x").isValid) // 11 chars
    }

    @Test
    fun `over the maximum length is rejected`() {
        val tooLong = "Aa1!" + "b".repeat(PasswordPolicy.MAX_LENGTH)
        assertTrue(PasswordPolicy.evaluate(tooLong).unmet.contains(PasswordPolicy.Rule.LENGTH))
    }

    @Test
    fun `matches is exact and case sensitive`() {
        assertTrue(PasswordPolicy.matches("abc", "abc"))
        assertFalse(PasswordPolicy.matches("abc", "ABC"))
        assertFalse(PasswordPolicy.matches("abc", "abc "))
    }

    @Test
    fun `rule labels are non-secret guidance text`() {
        PasswordPolicy.Rule.entries.forEach { rule ->
            assertTrue(PasswordPolicy.label(rule).isNotBlank())
        }
        assertTrue(PasswordPolicy.label(PasswordPolicy.Rule.LENGTH).contains("12"))
    }
}
