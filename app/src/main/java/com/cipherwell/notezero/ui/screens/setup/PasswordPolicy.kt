package com.cipherwell.notezero.ui.screens.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * Master-password rules. Pure Kotlin so they are unit-testable without Android and so
 * the exact same policy is enforced in setup, recovery reset and change-password.
 *
 * Rules (deliberately conservative, NIST-friendly: length first, no forced rotation):
 *  - 12..128 characters (chars, not bytes; the KDF handles unicode)
 *  - at least one lowercase letter, one uppercase letter, one digit, one symbol
 *  - symbols must be non-whitespace; whitespace-only "symbols" do not count
 *
 * CharArray-based evaluation lets callers wipe their buffer immediately afterwards.
 */
object PasswordPolicy {

    const val MIN_LENGTH = 12
    const val MAX_LENGTH = 128

    enum class Rule { LENGTH, LOWERCASE, UPPERCASE, DIGIT, SYMBOL }

    data class Result(val unmet: List<Rule>) {
        val isValid: Boolean get() = unmet.isEmpty()
    }

    fun evaluate(password: CharArray): Result {
        val unmet = ArrayList<Rule>(4)
        if (password.size < MIN_LENGTH || password.size > MAX_LENGTH) unmet += Rule.LENGTH
        if (password.none { it.isLowerCase() }) unmet += Rule.LOWERCASE
        if (password.none { it.isUpperCase() }) unmet += Rule.UPPERCASE
        if (password.none { it.isDigit() }) unmet += Rule.DIGIT
        if (password.none { !it.isLetterOrDigit() && !it.isWhitespace() }) unmet += Rule.SYMBOL
        return Result(unmet)
    }

    fun evaluate(password: String): Result = evaluate(password.toCharArray())

    fun isValid(password: CharArray): Boolean = evaluate(password).isValid

    fun isValid(password: String): Boolean = evaluate(password).isValid

    /** Short, non-secret rule copy shared by the screens. */
    fun label(rule: Rule): String = when (rule) {
        Rule.LENGTH -> "At least $MIN_LENGTH characters"
        Rule.LOWERCASE -> "A lowercase letter"
        Rule.UPPERCASE -> "An uppercase letter"
        Rule.DIGIT -> "A number"
        Rule.SYMBOL -> "A symbol (not a space)"
    }

    /** True when the two entries are exactly equal; comparison is time-constant-ish and local. */
    fun matches(first: String, second: String): Boolean = first == second
}

/**
 * Checklist of [PasswordPolicy] rules. Met rules show a check glyph and the success
 * token colour; unmet rules show a ring glyph and secondary text. No colour-only state.
 */
@Composable
fun PasswordRulesChecklist(
    password: String,
    modifier: Modifier = Modifier,
) {
    val result = remember(password) { PasswordPolicy.evaluate(password) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xxs),
    ) {
        PasswordPolicy.Rule.entries.forEach { rule ->
            val met = rule !in result.unmet
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (met) "✓" else "○",
                    style = NoteZeroType.Callout,
                    color = if (met) {
                        LocalNoteZeroColors.current.successText
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.width(NoteZeroSpacing.xl),
                )
                Text(
                    text = PasswordPolicy.label(rule),
                    style = NoteZeroType.Caption,
                    color = if (met) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
