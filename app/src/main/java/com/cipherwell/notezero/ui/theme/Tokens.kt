// NoteZero design tokens — single source of truth for the visual language.
// One UI-inspired: true-black / warm-white grounds, ONE accent blue, squircle
// containers, floating pill navigation, large collapsing titles.
// Rules: never hardcode a colour outside this file; every touch target >= 48dp.
//
// CONTRAST: every foreground pair in [NzThemeColors] is checked against WCAG 2.2 AA
// by `TokenContrastTest` (app/src/test/.../ui/theme/TokenContrastTest.kt):
//   * body text  >= 4.5:1  (AA normal)
//   * large text >= 3.0:1  (AA large)
//   * icons / UI component boundaries / focus rings >= 3.0:1 (AA non-text)
package com.cipherwell.notezero.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Raw colour palette. Nothing outside `ui/theme` should reference these directly. */
object NoteZeroPalette {
    // Grounds
    val CanvasDark = 0xFF000000
    val CanvasLight = 0xFFFAFAFA
    val SurfaceDark = 0xFF111114
    val SurfaceRaisedDark = 0xFF1C1C1F
    val SurfaceLight = 0xFFFFFFFF
    val SurfaceRaisedLight = 0xFFF2F2F7

    // The one accent. Everything interactive uses this blue; nothing else is chromatic
    // except semantic danger/success/warning states.
    val Accent = 0xFF0A84FF
    val AccentPressedDark = 0xFF409CFF
    val AccentPressedLight = 0xFF0070E0

    /** Accent used for *text/icons on the ground*. Chosen for AA contrast per mode. */
    val AccentTextDark = 0xFF409CFF // 7.4:1 on true black
    val AccentTextLight = 0xFF0062CC // 5.6:1 on #FAFAFA

    /** Accent used for *filled controls*; `OnAccent*` passes AA on top of it. */
    val AccentFillDark = 0xFF0A84FF
    val OnAccentFillDark = 0xFF000000 // 5.8:1 on AccentFillDark
    val AccentFillLight = 0xFF0062CC
    val OnAccentFillLight = 0xFFFFFFFF // 5.6:1 on AccentFillLight

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

    /** Form-field outlines: 3:1 against their ground so the control is perceivable. */
    val FieldBorderDark = 0xFF6A6A6F // >= 3:1 on both true black and raised dark surfaces
    val FieldBorderLight = 0xFF8E8E93

    // Semantic text that must be readable on the ground (not only colour-coded; the
    // message text always accompanies the colour).
    val DangerTextDark = 0xFFFF9F9A // 8.2:1 on true black (softer than DangerDark for AA)
    val DangerTextLight = 0xFFB3261E // 5.9:1 on #FAFAFA
    val DangerContainerDark = 0xFF3A1512
    val DangerContainerLight = 0xFFFFE5E3
    val OnDangerContainerDark = 0xFFFFB4AB
    val OnDangerContainerLight = 0xFF8C1105
    val SuccessTextDark = 0xFF30D158 // 10.4:1 on true black
    val SuccessTextLight = 0xFF1A7F37 // 4.9:1 on #FAFAFA
    val WarningTextDark = 0xFFFFD60A // 14.7:1 on true black
    val WarningTextLight = 0xFF7A5200 // 6.3:1 on #FAFAFA

    // Scrims (always dark: they dim whatever is behind, in both themes)
    val Scrim = 0xB3000000

    // Selected pill background behind the one accent (One UI floating pill).
    val PillSelectedDark = 0xFF1F3A5F
    val PillSelectedLight = 0xFFD6E9FF
    // Accent-tinted label readable on each selected-pill background (AA at caption size).
    val PillSelectedContentDark = 0xFFA6C8FF
    val PillSelectedContentLight = 0xFF0062CC
}

/** How a "light" or "dark" scheme was chosen; persisted by the data layer. */
// (ThemeMode lives in model/ — re-exported here would cross packages, so callers use
//  com.cipherwell.notezero.model.ThemeMode directly.)

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

    /** Comfortable filled-control height; never below [minTarget]. */
    val controlHeight = 52.dp

    /** Floating pill navigation bar height (target, not text height). */
    val pillNavHeight = 64.dp

    /** Screen gutter for phone layouts. */
    val gutter = 20.dp

    /** Max readable content width on tablets (dynamic-type friendly). */
    val maxContentWidth = 560.dp
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

    /** Filled-control labels: semibold at body size, still AA on the accent fill. */
    val Button = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
    val FieldLabel = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)

    /** Recovery words: monospaced, generous tracking, large enough to read aloud. */
    val RecoveryWord = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp)
}

/**
 * Extended semantic colours that Material3's [androidx.compose.material3.ColorScheme]
 * does not model. Screens read these through `LocalNoteZeroColors` (see Theme.kt).
 *
 * Because tinted pairs are easy to get wrong by hand, every pair used as text-on-X is
 * asserted in `TokenContrastTest`.
 */
data class NzThemeColors(
    /** For text links / accented labels on the app ground. AA (>= 4.5:1). */
    val accentText: Color,
    /** For icons and thin accent details on the ground. AA non-text (>= 3:1). */
    val accentIcon: Color,
    /** Filled-control background (buttons, selected toggles). */
    val accentFill: Color,
    val onAccentFill: Color,
    /** 3:1 form-field outline against the ground. */
    val fieldBorder: Color,
    val fieldBorderFocused: Color,
    /** Semantic text colours, always paired with a text label (never colour-only). */
    val dangerText: Color,
    val successText: Color,
    val warningText: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    /** Full-screen scrim for lock/loading overlays. */
    val scrim: Color,
    /** Floating pill nav selected background. */
    val pillSelected: Color,
    val pillSelectedContent: Color,
    /** True when this is the dark scheme (screens occasionally branch on it). */
    val isDark: Boolean,
)

fun nzDarkColors(): NzThemeColors = NzThemeColors(
    accentText = Color(NoteZeroPalette.AccentTextDark),
    accentIcon = Color(NoteZeroPalette.AccentTextDark),
    accentFill = Color(NoteZeroPalette.AccentFillDark),
    onAccentFill = Color(NoteZeroPalette.OnAccentFillDark),
    fieldBorder = Color(NoteZeroPalette.FieldBorderDark),
    fieldBorderFocused = Color(NoteZeroPalette.AccentTextDark),
    dangerText = Color(NoteZeroPalette.DangerTextDark),
    successText = Color(NoteZeroPalette.SuccessTextDark),
    warningText = Color(NoteZeroPalette.WarningTextDark),
    dangerContainer = Color(NoteZeroPalette.DangerContainerDark),
    onDangerContainer = Color(NoteZeroPalette.OnDangerContainerDark),
    scrim = Color(NoteZeroPalette.Scrim),
    pillSelected = Color(NoteZeroPalette.PillSelectedDark),
    pillSelectedContent = Color(NoteZeroPalette.PillSelectedContentDark),
    isDark = true,
)

fun nzLightColors(): NzThemeColors = NzThemeColors(
    accentText = Color(NoteZeroPalette.AccentTextLight),
    accentIcon = Color(NoteZeroPalette.AccentTextLight),
    accentFill = Color(NoteZeroPalette.AccentFillLight),
    onAccentFill = Color(NoteZeroPalette.OnAccentFillLight),
    fieldBorder = Color(NoteZeroPalette.FieldBorderLight),
    fieldBorderFocused = Color(NoteZeroPalette.AccentTextLight),
    dangerText = Color(NoteZeroPalette.DangerTextLight),
    successText = Color(NoteZeroPalette.SuccessTextLight),
    warningText = Color(NoteZeroPalette.WarningTextLight),
    dangerContainer = Color(NoteZeroPalette.DangerContainerLight),
    onDangerContainer = Color(NoteZeroPalette.OnDangerContainerLight),
    scrim = Color(NoteZeroPalette.Scrim),
    pillSelected = Color(NoteZeroPalette.PillSelectedLight),
    pillSelectedContent = Color(NoteZeroPalette.PillSelectedContentLight),
    isDark = false,
)

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
    labelLarge = NoteZeroType.Button,
    labelMedium = NoteZeroType.Caption,
    labelSmall = NoteZeroType.Caption,
)
