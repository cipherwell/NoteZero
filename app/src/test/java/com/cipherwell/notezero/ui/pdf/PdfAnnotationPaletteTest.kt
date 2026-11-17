package com.cipherwell.notezero.ui.pdf

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.cipherwell.notezero.model.PdfAnnotations
import com.cipherwell.notezero.model.PdfInkKind
import com.cipherwell.notezero.ui.theme.NoteZeroPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared annotation palette must stay aligned with the frozen model bounds. */
class PdfAnnotationPaletteTest {

    @Test
    fun `palette and width ladders match the model bounds`() {
        assertEquals(PdfAnnotations.PALETTE_SIZE, PdfAnnotationPalette.PALETTE_SIZE)
        assertEquals(PdfAnnotations.WIDTH_LEVELS, PdfAnnotationPalette.WIDTH_LEVELS)
        assertEquals(PdfAnnotationPalette.PALETTE_SIZE, PdfAnnotationPalette.colors.size)
    }

    @Test
    fun `out of range indices clamp instead of throwing`() {
        assertEquals(PdfAnnotationPalette.color(0), PdfAnnotationPalette.color(-5))
        assertEquals(
            PdfAnnotationPalette.color(PdfAnnotationPalette.PALETTE_SIZE - 1),
            PdfAnnotationPalette.color(999),
        )
        assertTrue(PdfAnnotationPalette.strokeWidthPx(-10f, PdfInkKind.INK, -1) >= 1f)
        assertTrue(PdfAnnotationPalette.strokeWidthPx(1_000f, PdfInkKind.INK, 999) > 0f)
    }

    @Test
    fun `width ladder grows with the width index`() {
        val widths = (0 until PdfAnnotationPalette.WIDTH_LEVELS).map {
            PdfAnnotationPalette.strokeWidthPx(1_000f, PdfInkKind.INK, it)
        }
        assertEquals(widths.sorted(), widths)
        assertTrue(widths.toSet().size == PdfAnnotationPalette.WIDTH_LEVELS)
    }

    @Test
    fun `highlighter is translucent and wider than ink`() {
        assertTrue(PdfAnnotationPalette.INK_ALPHA > PdfAnnotationPalette.HIGHLIGHTER_ALPHA)
        assertTrue(
            PdfAnnotationPalette.strokeWidthPx(1_000f, PdfInkKind.HIGHLIGHTER, 1) >
                PdfAnnotationPalette.strokeWidthPx(1_000f, PdfInkKind.INK, 1),
        )
    }

    @Test
    fun `argb applies alpha and keeps the palette rgb`() {
        for (index in 0 until PdfAnnotationPalette.PALETTE_SIZE) {
            val opaque = PdfAnnotationPalette.argb(index, 1f)
            assertEquals(0xFF, (opaque ushr 24) and 0xFF)
            assertEquals(
                PdfAnnotationPalette.color(index).toArgb() and 0x00FFFFFF,
                opaque and 0x00FFFFFF,
            )
            val half = PdfAnnotationPalette.argb(index, 0.5f)
            assertEquals(128, (half ushr 24) and 0xFF)
        }
    }

    @Test
    fun `markers scale with the page short edge`() {
        assertTrue(PdfAnnotationPalette.markerRadiusPx(1_000f) > PdfAnnotationPalette.markerRadiusPx(500f))
        assertTrue(
            PdfAnnotationPalette.markerHitRadiusPx(1_000f) >
                PdfAnnotationPalette.markerRadiusPx(1_000f),
        )
    }

    @Test
    fun `colours derive from design tokens rather than new literals`() {
        // The two token-backed entries are the exact light-ground tokens; the derived hues just
        // have to be fully opaque. This guards against replacing tokens with hex literals.
        assertEquals(Color(NoteZeroPalette.TextPrimaryLight), PdfAnnotationPalette.color(0))
        assertEquals(Color(NoteZeroPalette.DangerLight), PdfAnnotationPalette.color(1))
        assertEquals(Color(NoteZeroPalette.SuccessTextLight), PdfAnnotationPalette.color(3))
        assertEquals(Color(NoteZeroPalette.AccentFillLight), PdfAnnotationPalette.color(4))
    }

    @Test
    fun `note marker colours come from tokens`() {
        assertEquals(Color(NoteZeroPalette.WarningTextLight), PdfAnnotationPalette.noteMarkerFill)
        assertEquals(Color(NoteZeroPalette.SurfaceLight), PdfAnnotationPalette.noteMarkerGlyph)
    }
}
