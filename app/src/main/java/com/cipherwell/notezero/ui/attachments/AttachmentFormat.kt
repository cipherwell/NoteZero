package com.cipherwell.notezero.ui.attachments

import java.util.Locale
import kotlin.math.max

/**
 * Pure formatting/decoding arithmetic for the attachment UI.
 *
 * Deliberately free of `android.*` and data-layer imports so it runs as a plain JVM
 * unit test. Nothing here touches the filesystem: attachment names/sizes are display
 * data, never credentials, and are never written anywhere by this class.
 */
object AttachmentFormat {

    /** Longest display name we keep. Bounds the encrypted envelope and the chip layout. */
    const val MAX_DISPLAY_NAME_CHARS = 120

    /** Longest file name suggested to the SAF CreateDocument picker. */
    private const val MAX_EXPORT_NAME_CHARS = 80

    private const val KIB = 1_024L
    private const val MIB = KIB * 1_024L
    private const val GIB = MIB * 1_024L

    private val UNSAFE_FILE_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /** BitmapFactory would allocate a 1-pixel image at this factor; no caller needs more. */
    private const val MAX_SAMPLE = 1 shl 30

    /**
     * Turns whatever a content provider reports as `OpenableColumns.DISPLAY_NAME` into a
     * single safe display label: last path segment, control characters removed, trimmed,
     * bounded length, [fallback] when nothing usable remains.
     */
    fun displayName(raw: String?, fallback: String = "Attachment"): String {
        val candidate = raw
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.filterNot { it.isISOControl() }
            ?.trim()
            ?.take(MAX_DISPLAY_NAME_CHARS)
            .orEmpty()
        return candidate.ifBlank { fallback }
    }

    /**
     * File name suggested to SAF `CreateDocument`. The user explicitly exports a
     * decrypted copy, so the name is theirs, but device-unsafe characters are replaced
     * so providers do not reject the suggestion. Never used as a local path.
     */
    fun exportFileName(raw: String?, fallback: String = "attachment"): String {
        val base = displayName(raw, fallback)
        val replaced = buildString(base.length) {
            for (ch in base) {
                append(if (ch in UNSAFE_FILE_NAME_CHARS) '_' else ch)
            }
        }
        val trimmed = replaced.trim().take(MAX_EXPORT_NAME_CHARS).trim()
        return trimmed.ifBlank { fallback }
    }

    /** Human size: `812 B`, `4.2 KB`, `1.0 MB`, `512.0 GB` (locale-independent). */
    fun size(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        return when {
            value < KIB -> "$value B"
            value < MIB -> String.format(Locale.US, "%.1f KB", value.toDouble() / KIB)
            value < GIB -> String.format(Locale.US, "%.1f MB", value.toDouble() / MIB)
            else -> String.format(Locale.US, "%.1f GB", value.toDouble() / GIB)
        }
    }

    /**
     * Power-of-two `inSampleSize` that bounds the decoded image to the longest side of
     * the target box: the smallest `sample` whose longest sampled edge is <= the target.
     * This is what keeps a 24 MP camera photo from being fully inflated for a thumbnail
     * or the viewer (the short edge may end up below the target, which is fine for
     * `ContentScale.Crop` and `ContentScale.Fit`).
     *
     * Never 0 (BitmapFactory treats 0 as invalid); degenerate input returns 1.
     */
    fun sampleSize(outWidth: Int, outHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        if (outWidth <= 0 || outHeight <= 0) return 1
        if (reqWidth <= 0 || reqHeight <= 0) return 1
        val longestEdge = max(outWidth, outHeight).toLong()
        val target = max(reqWidth, reqHeight).toLong()
        var sample = 1
        while (longestEdge / sample > target && sample < MAX_SAMPLE) {
            sample *= 2
        }
        return sample
    }
}
