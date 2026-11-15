package com.cipherwell.notezero.ui.code

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.cipherwell.notezero.code.TokenType
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NzThemeColors

/**
 * Syntax palette for the code editor.
 *
 * Every colour is derived from [MaterialTheme] and the theme's own semantic tokens
 * ([NzThemeColors]) — the ui/theme package stays the single source of colour, so no raw
 * hex literal exists here. Foregrounds keep body-text contrast against the editor
 * ground; hue is never the only signal (comments are italic, links underlined, headings
 * bold).
 */
data class CodeSyntaxPalette(
    val text: Color,
    val keyword: Color,
    val type: Color,
    val string: Color,
    val char: Color,
    val number: Color,
    val comment: Color,
    val annotation: Color,
    val operator: Color,
    val punctuation: Color,
    val identifier: Color,
    val property: Color,
    val tag: Color,
    val attribute: Color,
    val heading: Color,
    val link: Color,
    val codeSpan: Color,
    val error: Color,
    val currentLineBackground: Color,
    val bracketMatchBackground: Color,
    val findMatchBackground: Color,
    val findCurrentBackground: Color,
    val gutterText: Color,
    val gutterCurrentText: Color,
    val isDark: Boolean,
) {
    fun colorFor(type: TokenType): Color = when (type) {
        TokenType.KEYWORD -> keyword
        TokenType.TYPE -> this.type
        TokenType.STRING -> string
        TokenType.CHAR -> char
        TokenType.NUMBER -> number
        TokenType.COMMENT -> comment
        TokenType.ANNOTATION -> annotation
        TokenType.OPERATOR -> operator
        TokenType.PUNCTUATION -> punctuation
        TokenType.IDENTIFIER -> identifier
        TokenType.PROPERTY -> property
        TokenType.TAG -> tag
        TokenType.ATTRIBUTE -> attribute
        TokenType.HEADING -> heading
        TokenType.LINK -> link
        TokenType.CODE_SPAN -> codeSpan
        TokenType.ERROR -> error
    }

    /** Span style for one token type. Kept allocation-light: the editor reuses this. */
    fun styleFor(type: TokenType): SpanStyle = when (type) {
        TokenType.COMMENT -> SpanStyle(color = comment, fontStyle = FontStyle.Italic)
        TokenType.HEADING -> SpanStyle(color = heading, fontWeight = FontWeight.Bold)
        TokenType.LINK -> SpanStyle(color = link, textDecoration = TextDecoration.Underline)
        TokenType.ANNOTATION -> SpanStyle(color = annotation, fontStyle = FontStyle.Italic)
        else -> SpanStyle(color = colorFor(type))
    }
}

/** Builds the dark/light syntax palette from the active Material3 + NoteZero colours. */
fun codeSyntaxPaletteFor(scheme: ColorScheme, nz: NzThemeColors): CodeSyntaxPalette {
    val accent = nz.accentText
    val surfaceVariantText = scheme.onSurfaceVariant
    return CodeSyntaxPalette(
        text = scheme.onSurface,
        keyword = accent,
        type = lerp(accent, nz.successText, 0.55f),
        string = nz.successText,
        char = lerp(nz.successText, nz.warningText, 0.45f),
        number = nz.warningText,
        comment = surfaceVariantText,
        annotation = lerp(accent, nz.warningText, 0.5f),
        operator = scheme.onSurface,
        punctuation = surfaceVariantText,
        identifier = scheme.onSurface,
        property = lerp(accent, scheme.onSurfaceVariant, 0.55f),
        tag = accent,
        attribute = lerp(nz.warningText, scheme.onSurface, 0.2f),
        heading = accent,
        link = accent,
        codeSpan = nz.successText,
        error = nz.dangerText,
        currentLineBackground = scheme.primary.copy(alpha = if (nz.isDark) 0.12f else 0.07f),
        bracketMatchBackground = nz.accentFill.copy(alpha = if (nz.isDark) 0.30f else 0.24f),
        findMatchBackground = nz.warningText.copy(alpha = if (nz.isDark) 0.30f else 0.35f),
        findCurrentBackground = nz.accentFill.copy(alpha = if (nz.isDark) 0.45f else 0.35f),
        gutterText = surfaceVariantText.copy(alpha = 0.85f),
        gutterCurrentText = scheme.onSurface,
        isDark = nz.isDark,
    )
}

/** Theme-scoped palette; recomputed once per colour-scheme change. */
@Composable
fun rememberCodeSyntaxPalette(): CodeSyntaxPalette {
    val scheme = MaterialTheme.colorScheme
    val nz = LocalNoteZeroColors.current
    return remember(scheme, nz) { codeSyntaxPaletteFor(scheme, nz) }
}
