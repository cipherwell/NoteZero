package com.cipherwell.notezero.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * WCAG 2.2 AA proof for the design tokens.
 *
 * Every foreground/background pair the UI actually renders is asserted here:
 *  - normal text  >= 4.5:1 (AA 1.4.3)
 *  - large text / icons / component boundaries >= 3.0:1 (AA 1.4.3 / 1.4.11)
 *
 * The formula is the WCAG relative-luminance + contrast-ratio definition. Pure JVM:
 * it works on the ARGB token constants so it cannot be satisfied by a theme override.
 */
class TokenContrastTest {

    private fun luminance(argb: Long): Double {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private fun ratio(foreground: Long, background: Long): Double {
        val l1 = luminance(foreground)
        val l2 = luminance(background)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun assertAa(
        name: String,
        foreground: Long,
        background: Long,
        min: Double,
    ) {
        val actual = ratio(foreground, background)
        assertTrue(
            "%s contrast %.2f is below %.1f:1".format(name, actual, min),
            actual >= min,
        )
    }

    @Test
    fun `dark theme text and accents pass AA`() {
        val bg = NoteZeroPalette.CanvasDark
        assertAa("TextPrimaryDark on CanvasDark", NoteZeroPalette.TextPrimaryDark, bg, 7.0)
        assertAa("TextSecondaryDark on CanvasDark", NoteZeroPalette.TextSecondaryDark, bg, 4.5)
        assertAa("AccentTextDark on CanvasDark", NoteZeroPalette.AccentTextDark, bg, 4.5)
        assertAa("DangerTextDark on CanvasDark", NoteZeroPalette.DangerTextDark, bg, 4.5)
        assertAa("SuccessTextDark on CanvasDark", NoteZeroPalette.SuccessTextDark, bg, 4.5)
        assertAa("WarningTextDark on CanvasDark", NoteZeroPalette.WarningTextDark, bg, 4.5)
        assertAa(
            "TextPrimaryDark on SurfaceRaisedDark",
            NoteZeroPalette.TextPrimaryDark,
            NoteZeroPalette.SurfaceRaisedDark,
            7.0,
        )
        assertAa(
            "AccentTextDark on SurfaceRaisedDark",
            NoteZeroPalette.AccentTextDark,
            NoteZeroPalette.SurfaceRaisedDark,
            4.5,
        )
    }

    @Test
    fun `dark theme filled controls and containers pass AA`() {
        assertAa(
            "OnAccentFillDark on AccentFillDark",
            NoteZeroPalette.OnAccentFillDark,
            NoteZeroPalette.AccentFillDark,
            4.5,
        )
        assertAa(
            "OnDangerContainerDark on DangerContainerDark",
            NoteZeroPalette.OnDangerContainerDark,
            NoteZeroPalette.DangerContainerDark,
            4.5,
        )
        assertAa(
            "PillSelectedContentDark on PillSelectedDark",
            NoteZeroPalette.PillSelectedContentDark,
            NoteZeroPalette.PillSelectedDark,
            4.5,
        )
        assertAa(
            "CanvasDark on DangerDark (dark danger button label)",
            NoteZeroPalette.CanvasDark,
            NoteZeroPalette.DangerDark,
            4.5,
        )
    }

    @Test
    fun `dark theme non-text boundaries pass AA`() {
        assertAa("FieldBorderDark on CanvasDark", NoteZeroPalette.FieldBorderDark, NoteZeroPalette.CanvasDark, 3.0)
        assertAa("FieldBorderDark on SurfaceDark", NoteZeroPalette.FieldBorderDark, NoteZeroPalette.SurfaceDark, 3.0)
        assertAa("AccentFillDark on CanvasDark", NoteZeroPalette.AccentFillDark, NoteZeroPalette.CanvasDark, 3.0)
    }

    @Test
    fun `light theme text and accents pass AA`() {
        val bg = NoteZeroPalette.CanvasLight
        assertAa("TextPrimaryLight on CanvasLight", NoteZeroPalette.TextPrimaryLight, bg, 7.0)
        assertAa("TextSecondaryLight on CanvasLight", NoteZeroPalette.TextSecondaryLight, bg, 4.5)
        assertAa("AccentTextLight on CanvasLight", NoteZeroPalette.AccentTextLight, bg, 4.5)
        assertAa("DangerTextLight on CanvasLight", NoteZeroPalette.DangerTextLight, bg, 4.5)
        assertAa("SuccessTextLight on CanvasLight", NoteZeroPalette.SuccessTextLight, bg, 4.5)
        assertAa("WarningTextLight on CanvasLight", NoteZeroPalette.WarningTextLight, bg, 4.5)
        assertAa(
            "AccentTextLight on SurfaceLight",
            NoteZeroPalette.AccentTextLight,
            NoteZeroPalette.SurfaceLight,
            4.5,
        )
    }

    @Test
    fun `light theme filled controls and containers pass AA`() {
        assertAa(
            "OnAccentFillLight on AccentFillLight",
            NoteZeroPalette.OnAccentFillLight,
            NoteZeroPalette.AccentFillLight,
            4.5,
        )
        assertAa(
            "OnDangerContainerLight on DangerContainerLight",
            NoteZeroPalette.OnDangerContainerLight,
            NoteZeroPalette.DangerContainerLight,
            4.5,
        )
        assertAa(
            "PillSelectedContentLight on PillSelectedLight",
            NoteZeroPalette.PillSelectedContentLight,
            NoteZeroPalette.PillSelectedLight,
            4.5,
        )
        assertAa(
            "CanvasLight on DangerTextLight (light danger button label)",
            NoteZeroPalette.CanvasLight,
            NoteZeroPalette.DangerTextLight,
            4.5,
        )
    }

    @Test
    fun `light theme non-text boundaries pass AA`() {
        assertAa("FieldBorderLight on CanvasLight", NoteZeroPalette.FieldBorderLight, NoteZeroPalette.CanvasLight, 3.0)
        assertAa("FieldBorderLight on SurfaceLight", NoteZeroPalette.FieldBorderLight, NoteZeroPalette.SurfaceLight, 3.0)
        assertAa("AccentFillLight on CanvasLight", NoteZeroPalette.AccentFillLight, NoteZeroPalette.CanvasLight, 3.0)
    }

    @Test
    fun `true black and warm white grounds are the seeded brand values`() {
        assertTrue("dark ground must be true black", NoteZeroPalette.CanvasDark == 0xFF000000L)
        assertTrue("light ground must be #FAFAFA", NoteZeroPalette.CanvasLight == 0xFFFAFAFAL)
        // One accent hue family: the fill and text variants stay blue (B channel highest).
        listOf(
            NoteZeroPalette.Accent,
            NoteZeroPalette.AccentFillDark,
            NoteZeroPalette.AccentFillLight,
            NoteZeroPalette.AccentTextDark,
            NoteZeroPalette.AccentTextLight,
        ).forEach { accent ->
            val r = (accent shr 16) and 0xFF
            val g = (accent shr 8) and 0xFF
            val b = accent and 0xFF
            assertTrue("accent must stay blue: ${accent.toString(16)}", b > g && b > r)
        }
    }
}
