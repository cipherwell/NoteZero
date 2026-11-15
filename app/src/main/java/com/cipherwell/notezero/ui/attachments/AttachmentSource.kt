package com.cipherwell.notezero.ui.attachments

import java.io.InputStream

/**
 * One user-picked source file, as the system picker describes it.
 *
 * Deliberately Android-free: the UI resolves a `content://` URI to this value (metadata
 * + a re-openable stream factory) so the controller and its state machine stay plain
 * JVM unit tests. [open] may return null when the grant expired or the provider failed;
 * the stream is opened lazily, exactly once, on the controller's IO dispatcher.
 *
 * Security: a source carries only non-secret picker metadata. No bytes are retained and
 * no path is derived from [displayName] — the name is display data only.
 */
data class AttachmentSource(
    /** User-visible file name (already sanitised by the resolver). */
    val displayName: String,
    /** Provider MIME type; `application/octet-stream` when the provider does not know. */
    val mimeType: String,
    /**
     * Fast pre-check value from `OpenableColumns.SIZE`. `null` when the provider does not
     * report a size; the data layer still enforces its own limit while reading.
     */
    val declaredSizeBytes: Long?,
    /** Opens the content stream; returns null when it cannot be opened. */
    val open: () -> InputStream?,
) {
    /** True for MIME types the strip may render as a thumbnail. */
    val isImage: Boolean get() = mimeType.startsWith("image/", ignoreCase = true)

    companion object {
        /** Convenience factory for an in-memory source (tests, retry flows). */
        fun fromBytes(
            bytes: ByteArray,
            displayName: String,
            mimeType: String,
        ): AttachmentSource = AttachmentSource(
            displayName = displayName,
            mimeType = mimeType,
            declaredSizeBytes = bytes.size.toLong(),
            open = { bytes.inputStream() },
        )
    }
}
