package com.cipherwell.notezero.ui.attachments

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pixel dimensions of the rendered drawing page. */
data class DrawingSize(val widthPx: Int, val heightPx: Int)

/**
 * Pure formatting/geometry rules for the drawing attachment. No `android.*` imports, so it
 * runs as a plain JVM unit test.
 *
 * A drawing is always saved as a **PNG image attachment**, through the same encrypted
 * attachment pipeline as a picked photo: the page is a fixed 3:4 portrait sheet whose long
 * edge never exceeds [MAX_DIMENSION_PX] (a 2048 px page is 1536x2048x4 = 12 MiB of ARGB
 * pixels, entirely in memory — no file, no cache, no plaintext on disk).
 */
object DrawingFormat {

    /** Saved MIME type; the data layer stores it in the encrypted ref, never on disk. */
    const val MIME_TYPE = "image/png"

    /** Longest page edge in pixels; a drawing can never allocate a larger bitmap. */
    const val MAX_DIMENSION_PX = 2_048

    /** Page aspect: 3 units wide, 4 units tall (portrait sheet). */
    const val ASPECT_WIDTH = 3
    const val ASPECT_HEIGHT = 4

    /** Width / height, for `Modifier.aspectRatio` on the canvas. */
    val aspectRatio: Float get() = ASPECT_WIDTH.toFloat() / ASPECT_HEIGHT

    /** File-name prefix, e.g. `drawing-20261115-181500.png`. */
    const val FILE_PREFIX = "drawing-"

    private const val TIMESTAMP_PATTERN = "yyyyMMdd-HHmmss"

    /**
     * Page size for a requested long edge. The result is clamped to
     * [1, MAX_DIMENSION_PX] so a caller can ask for a small test render but never for an
     * unbounded bitmap.
     */
    fun pixelSize(maxDimensionPx: Int = MAX_DIMENSION_PX): DrawingSize {
        val height = maxDimensionPx.coerceIn(1, MAX_DIMENSION_PX)
        val width = (height.toLong() * ASPECT_WIDTH / ASPECT_HEIGHT).toInt().coerceAtLeast(1)
        return DrawingSize(widthPx = width, heightPx = height)
    }

    /**
     * Deterministic display name for a saved drawing. ASCII digits via [Locale.US] so the
     * name is identical in every locale, and [timeZone] is explicit so tests do not depend
     * on the host clock. The name is display data only — never a path.
     */
    fun fileName(
        timestampMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val format = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
        format.timeZone = timeZone
        return FILE_PREFIX + format.format(Date(timestampMillis)) + ".png"
    }
}
