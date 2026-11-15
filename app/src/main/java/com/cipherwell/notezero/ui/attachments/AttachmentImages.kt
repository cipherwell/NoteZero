package com.cipherwell.notezero.ui.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * In-memory decoding for attachment previews and the full-screen viewer.
 *
 * Security: decrypted bytes are never written anywhere. There is no disk cache, no
 * thumbnail file and no `Bitmap.compress` here — a decoded [Bitmap] lives only as long
 * as the composition that requested it. Sampling is computed from the encoded bounds so
 * a large photo is never fully inflated into memory.
 */
object AttachmentImages {

    /** Target box for strip thumbnails, in pixels (a 44dp chip at ~3x density). */
    const val THUMBNAIL_TARGET_PX = 320

    /** Target box for the full-screen viewer; bounds memory for large camera photos. */
    const val VIEWER_TARGET_PX = 2_048

    /**
     * Decodes [bytes] with a power-of-two `inSampleSize` so the result fits
     * [targetPx] x [targetPx] while still having at least that many pixels on the short
     * edge. Returns null for empty/corrupt data or when the platform decoder refuses —
     * callers show a non-secret placeholder instead of failing the screen.
     */
    fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = AttachmentFormat.sampleSize(
                    outWidth = bounds.outWidth,
                    outHeight = bounds.outHeight,
                    reqWidth = targetPx,
                    reqHeight = targetPx,
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
    }
}
