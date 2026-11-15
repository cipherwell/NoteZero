// NoteZero design tokens — single source of truth for the visual language.
// One UI-inspired: true-black / warm-white grounds, ONE accent blue, squircle
// containers, floating pill navigation, large collapsing titles.
// Rules: never hardcode a colour outside this file; every touch target >= 48dp.
package com.cipherwell.notezero.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object NoteZeroPalette {
    // Grounds
    val CanvasDark = 0xFF000000
    val CanvasLight = 0xFFFAFAFA
    val SurfaceDark = 0xFF111114
    val SurfaceRaisedDark = 0xFF1C1C1F
    val SurfaceLight = 0xFFFFFFFF
    val SurfaceRaisedLight = 0xFFF2F2F7

    // The one accent. Everything interactive uses this blue; nothing else is chromatic
    // except semantic danger/success states.
    val Accent = 0xFF0A84FF
    val AccentPressedDark = 0xFF409CFF
    val AccentPressedLight = 0xFF0070E0

    // Text
    val TextPrimaryDark = 0xFFF5F5F7
    val TextSecondaryDark = 0xFF98989F
    val TextPrimaryLight = 0xFF1C1C1E
    val TextSecondaryLight = 0xFF6C6C70

    // Lines and semantic states
    val SeparatorDark = 0xFF2C2C2E
    val SeparatorLight = 0xFFE5E5EA
    val DangerDark = 0xFFFF453A
    val DangerLight = 0xFFFF3B30
    val Success = 0xFF30D158
}

object NoteZeroSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    /** Minimum interactive target, WCAG 2.2 / Material. */
    val minTarget = 48.dp
}

object NoteZeroShapes {
    /** Squircle-ish cards (iOS "continuous corner" approximation). */
    val card = RoundedCornerShape(28.dp)
    val sheet = RoundedCornerShape(32.dp)
    val row = RoundedCornerShape(18.dp)
    val field = RoundedCornerShape(14.dp)
    val pill = RoundedCornerShape(percent = 50)
}

object NoteZeroMotion {
    const val QuickMs = 150
    const val StandardMs = 240
    const val SlowMs = 400
}

object NoteZeroType {
    private val family = FontFamily.SansSerif
    val LargeTitle = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp)
    val Title = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp)
    val Heading = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)
    val Body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp)
    val Callout = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)
    val Caption = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)
}

fun noteZeroShapes(): Shapes = Shapes(
    extraSmall = NoteZeroShapes.field,
    small = NoteZeroShapes.row,
    medium = NoteZeroShapes.card,
    large = NoteZeroShapes.sheet,
    extraLarge = NoteZeroShapes.sheet,
)

fun noteZeroTypography(): Typography = Typography(
    displayLarge = NoteZeroType.LargeTitle,
    headlineLarge = NoteZeroType.Title,
    headlineMedium = NoteZeroType.Heading,
    titleLarge = NoteZeroType.Heading,
    titleMedium = NoteZeroType.Body,
    bodyLarge = NoteZeroType.Body,
    bodyMedium = NoteZeroType.Callout,
    bodySmall = NoteZeroType.Caption,
    labelLarge = NoteZeroType.Callout,
    labelMedium = NoteZeroType.Caption,
    labelSmall = NoteZeroType.Caption,
)
