package com.cipherwell.notezero.ui.attachments

import com.cipherwell.notezero.data.AttachmentLimits

/**
 * UI-level attachment failure categories. They are derived from the data layer's
 * [com.cipherwell.notezero.data.AttachmentError] plus a fast local pre-check, and exist
 * so the composables never render a raw error enum or (worse) a provider message.
 *
 * Every message below is non-secret: no file name, note id, path or content is ever
 * included, so the strings are safe to show, screenshot (never — FLAG_SECURE) and test.
 */
enum class AttachmentErrorKind {
    /** The picked source is larger than [AttachmentLimits.MAX_ATTACHMENT_BYTES]. */
    TOO_LARGE,

    /** The note already has [AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE] attachments. */
    TOO_MANY,

    /** Per-note count or vault-wide [AttachmentLimits.MAX_TOTAL_BYTES] quota reached. */
    QUOTA_EXCEEDED,

    /** The vault locked (or locked out) before the operation could finish. */
    LOCKED,

    /** The note disappeared from the decrypted index. */
    NOTE_MISSING,

    /** The stored blob failed its SHA-256 / AEAD integrity check. */
    INTEGRITY,

    /** Opening/reading the source or writing the destination stream failed. */
    IO,
}

/** Friendly, non-secret copy for each failure kind. */
fun AttachmentErrorKind.message(): String = when (this) {
    AttachmentErrorKind.TOO_LARGE ->
        "That file is larger than ${AttachmentLimits.MAX_ATTACHMENT_BYTES / MIB} MiB, " +
            "the limit for one attachment. Pick a smaller file."
    AttachmentErrorKind.TOO_MANY ->
        "This note already holds ${AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE} attachments, " +
            "the maximum. Remove one before adding another."
    AttachmentErrorKind.QUOTA_EXCEEDED ->
        "Attachment storage is full. A note can hold up to " +
            "${AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE} files and the vault up to " +
            "${AttachmentLimits.MAX_TOTAL_BYTES / MIB} MiB in total — delete something " +
            "you no longer need and try again."
    AttachmentErrorKind.LOCKED ->
        "The vault locked before the attachment finished. Unlock and try again — " +
            "nothing was saved."
    AttachmentErrorKind.NOTE_MISSING ->
        "This note is no longer available, so the attachment was not saved."
    AttachmentErrorKind.INTEGRITY ->
        "That attachment failed its integrity check and was not opened. " +
            "The bytes may be corrupt or tampered with."
    AttachmentErrorKind.IO ->
        "The file could not be read or written. Try again."
}

/**
 * Non-secret state of the attachment surface. Bytes and decoded bitmaps deliberately
 * live only in the composables' composition state, never in this long-lived flow.
 */
sealed interface AttachmentUiState {
    /** Nothing in flight; the strip shows the current count. */
    data object Idle : AttachmentUiState

    /** A file is being encrypted and persisted. */
    data object Importing : AttachmentUiState

    /** The most recent import succeeded; [attachmentId] is its opaque id. */
    data class Imported(val attachmentId: String) : AttachmentUiState

    /** The most recent operation failed with a non-secret [kind]. */
    data class Error(val kind: AttachmentErrorKind) : AttachmentUiState {
        val message: String get() = kind.message()
    }
}

private const val MIB = 1024L * 1024L
