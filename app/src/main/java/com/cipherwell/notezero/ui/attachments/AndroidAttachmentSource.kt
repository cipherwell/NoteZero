package com.cipherwell.notezero.ui.attachments

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Resolves a system-picker `content://` result into an [AttachmentSource].
 *
 * This is the only Android-aware part of the attachment INPUT path (a real
 * `ContentResolver` cannot be faked usefully), so it stays tiny: query the two
 * `OpenableColumns` metadata fields once and hand the controller a lazy stream factory.
 * No bytes are read here, no path is derived from the provider's name, and a provider
 * that misbehaves (throws, returns no cursor, unknown MIME) degrades to safe defaults
 * instead of crashing the editor.
 */
object AttachmentSourceResolver {

    private const val DEFAULT_MIME = "application/octet-stream"

    fun fromUri(context: Context, uri: Uri): AttachmentSource {
        val resolver = context.contentResolver
        val mimeType = runCatching { resolver.getType(uri) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MIME

        var rawName: String? = null
        var declaredSize: Long? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        rawName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        declaredSize = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                    }
                }
            }
        }

        val fallbackName = if (mimeType.startsWith("image/", ignoreCase = true)) {
            "Photo"
        } else {
            "Attachment"
        }
        return AttachmentSource(
            displayName = AttachmentFormat.displayName(rawName, fallback = fallbackName),
            mimeType = mimeType,
            declaredSizeBytes = declaredSize,
            open = {
                // The read grant may expire (or never have been usable): null → IO error.
                runCatching { resolver.openInputStream(uri) }.getOrNull()
            },
        )
    }
}
