package com.cipherwell.notezero.ui.attachments

import com.cipherwell.notezero.data.AttachmentError
import com.cipherwell.notezero.data.AttachmentImport
import com.cipherwell.notezero.data.AttachmentLimits
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/** Which system picker the user asked for (photo = no permission, file = any type). */
enum class AttachmentPick { PHOTO, FILE }

/**
 * Testable state machine for attachment import, export, deletion and decryption.
 *
 * The controller owns no Android types: the UI resolves a `content://` result to an
 * [AttachmentSource] (and SAF `CreateDocument` to an `OutputStream`) so the
 * security-relevant behaviour is unit-testable on the JVM.
 *
 * Lock handling — every system picker is a separate Activity, so launching one
 * backgrounds the app. Each picker is bracketed exactly once:
 *   [beginPicker] → [NoteRepository.beginExternalActivity]
 *   result or cancel → [NoteRepository.endExternalActivity]
 * That is what stops a picker from locking the vault mid-flow (decision 13), while the
 * idle auto-lock timer stays the backstop: if the picker returns after the vault locked,
 * the result is discarded with [AttachmentErrorKind.LOCKED] instead of touching keys.
 *
 * Limits — the picker's declared size is a fast reject only ([AttachmentSource.declaredSizeBytes]),
 * so a 40 MB file never opens a stream; the data layer still enforces the same limits
 * while reading, and its [AttachmentImport.Failure] is mapped 1:1 to a friendly kind.
 *
 * Security: never logs, and holds no bytes, names or paths between operations. Decrypted
 * bytes returned by [loadBytes] are for the caller's composition state only.
 */
class AttachmentController(
    private val repository: NoteRepository,
    scope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private enum class SystemPicker { PHOTO, FILE, EXPORT }

    private val ownsScope = scope == null
    private val scope: CoroutineScope =
        scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Guards [armed] and [busy]; callers are on the main thread, settle calls may not be. */
    private val lock = Any()

    private var armed: SystemPicker? = null
    private var busy = false

    private val _state = MutableStateFlow<AttachmentUiState>(AttachmentUiState.Idle)
    val state: StateFlow<AttachmentUiState> = _state.asStateFlow()

    // =========================================================================
    // Pickers (each one brackets an external activity)
    // =========================================================================

    /**
     * Arms the external-activity deferral for a photo/file picker. Call immediately
     * before `launch()`; returns false when the vault is locked, another operation is
     * running, or a picker is already open. On false nothing is armed and the UI must
     * not launch the picker.
     */
    fun beginPicker(pick: AttachmentPick): Boolean {
        val systemPicker = when (pick) {
            AttachmentPick.PHOTO -> SystemPicker.PHOTO
            AttachmentPick.FILE -> SystemPicker.FILE
        }
        if (!arm(systemPicker)) return false
        repository.beginExternalActivity()
        return true
    }

    /**
     * Arms the external-activity deferral for SAF `CreateDocument` (decrypted export).
     * Same contract as [beginPicker].
     */
    fun beginExport(): Boolean {
        if (!arm(SystemPicker.EXPORT)) return false
        repository.beginExternalActivity()
        return true
    }

    /** The picker returned a file. Ends the bracket, then imports. */
    fun onPickerResult(noteId: String, source: AttachmentSource) {
        if (!disarm(SystemPicker.PHOTO, SystemPicker.FILE)) return
        repository.endExternalActivity()
        if (!isUnlocked()) {
            _state.value = AttachmentUiState.Error(AttachmentErrorKind.LOCKED)
            return
        }
        importSource(noteId, source)
    }

    /** The picker was dismissed. Ends the bracket; nothing is touched. */
    fun onPickerCancelled() {
        if (!disarm(SystemPicker.PHOTO, SystemPicker.FILE, SystemPicker.EXPORT)) return
        repository.endExternalActivity()
        _state.value = AttachmentUiState.Idle
    }

    /** SAF `CreateDocument` was dismissed (or failed before a stream could be opened). */
    fun onExportPickerCancelled() {
        if (!disarm(SystemPicker.EXPORT)) return
        repository.endExternalActivity()
    }

    // =========================================================================
    // Import
    // =========================================================================

    /**
     * Imports [source] into the note. Applies the fast local guards (note exists,
     * per-note count, declared size) and relies on the repository for the authoritative
     * limits. Runs on the controller scope; state moves Importing → Imported/Error.
     */
    fun importSource(noteId: String, source: AttachmentSource) {
        if (!isUnlocked()) {
            _state.value = AttachmentUiState.Error(AttachmentErrorKind.LOCKED)
            return
        }
        val item = repository.items.value.firstOrNull { it.id == noteId }
        if (item == null) {
            _state.value = AttachmentUiState.Error(AttachmentErrorKind.NOTE_MISSING)
            return
        }
        if (item.attachments.size >= AttachmentLimits.MAX_ATTACHMENTS_PER_NOTE) {
            _state.value = AttachmentUiState.Error(AttachmentErrorKind.TOO_MANY)
            return
        }
        val declared = source.declaredSizeBytes
        if (declared != null && declared > AttachmentLimits.MAX_ATTACHMENT_BYTES) {
            // Fast reject: the source stream is never opened for an oversized file.
            _state.value = AttachmentUiState.Error(AttachmentErrorKind.TOO_LARGE)
            return
        }
        if (!beginOperation()) return
        _state.value = AttachmentUiState.Importing
        scope.launch {
            val result = try {
                withContext(ioDispatcher) { importOnIo(noteId, source) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AttachmentImport.Failure(AttachmentError.IO)
            }
            finishOperation()
            _state.value = when (result) {
                is AttachmentImport.Success -> AttachmentUiState.Imported(result.ref.id)
                is AttachmentImport.Failure -> AttachmentUiState.Error(result.error.toUiKind())
            }
        }
    }

    /**
     * Reads and stores the attachment. Runs on [ioDispatcher]; the stream is always
     * closed and a source that cannot be opened maps to a non-secret IO failure.
     */
    private suspend fun importOnIo(noteId: String, source: AttachmentSource): AttachmentImport {
        val stream: InputStream = try {
            source.open()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        } ?: return AttachmentImport.Failure(AttachmentError.IO)
        return stream.use {
            repository.importAttachment(
                noteId = noteId,
                displayName = source.displayName,
                mimeType = source.mimeType,
                source = it,
            )
        }
    }

    // =========================================================================
    // Drawing import (in-memory PNG, no picker)
    // =========================================================================

    /**
     * Imports PNG bytes rendered by the drawing canvas through the same encrypted path as a
     * picked file — with no system picker and no external-activity bracket, because no other
     * activity is launched.
     *
     * The bytes are handed over exactly once ([AttachmentSource.open] is lazy) and the
     * backing array is zeroed as soon as the import closes the stream, so the rendered
     * plaintext page does not outlive the operation. Nothing is written to disk at any point.
     * The 10 MiB cap, the per-note count and the vault quota are the same local pre-checks
     * [importSource] applies (the declared size is the rendered array's size), and the data
     * layer remains authoritative while reading.
     */
    fun importDrawing(noteId: String, pngBytes: ByteArray) {
        if (pngBytes.isEmpty()) {
            // A renderer that produced nothing must never create an empty attachment.
            _state.value = AttachmentUiState.Error(AttachmentErrorKind.IO)
            return
        }
        importSource(
            noteId,
            AttachmentSource(
                displayName = DrawingFormat.fileName(System.currentTimeMillis()),
                mimeType = DrawingFormat.MIME_TYPE,
                declaredSizeBytes = pngBytes.size.toLong(),
                open = { WipingByteArrayInputStream(pngBytes) },
            ),
        )
    }

    // =========================================================================
    // Decrypt for display (never cached)
    // =========================================================================

    /**
     * Decrypts one attachment for the viewer/thumbnail. Returns null when the vault is
     * locked, the note/ref is unknown, or the blob fails integrity. With [reportErrors]
     * the failure is also surfaced on [state] (thumbnail loads stay quiet so a single
     * stale ref does not banner the whole editor).
     *
     * The returned array is plaintext: callers must keep it in composition memory only
     * and never write it to disk.
     */
    suspend fun loadBytes(
        noteId: String,
        attachmentId: String,
        reportErrors: Boolean = false,
    ): ByteArray? {
        if (!isUnlocked()) {
            if (reportErrors) _state.value = AttachmentUiState.Error(AttachmentErrorKind.LOCKED)
            return null
        }
        val bytes = try {
            withContext(ioDispatcher) { repository.attachmentBytes(noteId, attachmentId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        }
        if (bytes == null && reportErrors) {
            val kind = if (!isUnlocked()) {
                AttachmentErrorKind.LOCKED
            } else {
                AttachmentErrorKind.INTEGRITY
            }
            _state.value = AttachmentUiState.Error(kind)
        }
        return bytes
    }

    // =========================================================================
    // Export (explicit unencrypted copy)
    // =========================================================================

    /**
     * Called with the SAF destination stream after the warning dialog was confirmed.
     * [openOut] is invoked on the IO dispatcher because opening a provider stream is
     * blocking; it may return null when the grant expired. [onFinished] reports the
     * outcome (true when the decrypted copy was written) for the viewer's status line.
     */
    fun onExportTarget(
        noteId: String,
        attachmentId: String,
        openOut: () -> OutputStream?,
        onFinished: ((Boolean) -> Unit)? = null,
    ) {
        if (!disarm(SystemPicker.EXPORT)) return
        repository.endExternalActivity()
        if (!isUnlocked()) {
            _state.value = AttachmentUiState.Error(AttachmentErrorKind.LOCKED)
            onFinished?.invoke(false)
            return
        }
        if (!beginOperation()) return
        scope.launch {
            val written = try {
                withContext(ioDispatcher) {
                    val out = try {
                        openOut()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        null
                    } ?: return@withContext false
                    out.use { repository.exportAttachment(noteId, attachmentId, it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                false
            }
            finishOperation()
            _state.value = if (written) {
                AttachmentUiState.Idle
            } else {
                AttachmentUiState.Error(
                    if (!isUnlocked()) AttachmentErrorKind.LOCKED else AttachmentErrorKind.IO,
                )
            }
            onFinished?.invoke(written)
        }
    }

    // =========================================================================
    // Delete
    // =========================================================================

    /** Removes the encrypted blob and its ref. Idempotent when the ref is already gone. */
    fun delete(noteId: String, attachmentId: String) {
        if (!isUnlocked()) {
            _state.value = AttachmentUiState.Error(AttachmentErrorKind.LOCKED)
            return
        }
        if (!beginOperation()) return
        scope.launch {
            val deleted = try {
                withContext(ioDispatcher) { repository.deleteAttachment(noteId, attachmentId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                false
            }
            finishOperation()
            val stillPresent = repository.items.value
                .firstOrNull { it.id == noteId }
                ?.attachments
                ?.any { it.id == attachmentId } == true
            _state.value = when {
                !isUnlocked() -> AttachmentUiState.Error(AttachmentErrorKind.LOCKED)
                stillPresent -> AttachmentUiState.Error(AttachmentErrorKind.IO)
                else -> AttachmentUiState.Idle
            }
        }
    }

    /** Cancels the controller-owned scope (only when this instance created it). */
    fun close() {
        if (ownsScope) scope.cancel()
    }

    // =========================================================================
    // Bracket bookkeeping
    // =========================================================================

    /** Arm one system picker; false when locked/busy/picker already open. */
    private fun arm(picker: SystemPicker): Boolean = synchronized(lock) {
        if (busy || armed != null) return false
        if (!isUnlocked()) {
            _state.value = AttachmentUiState.Error(AttachmentErrorKind.LOCKED)
            return false
        }
        armed = picker
        // A new attempt supersedes the previous result (status text returns to the count).
        _state.value = AttachmentUiState.Idle
        true
    }

    /**
     * Removes the armed picker and reports whether one of [pickers] was actually armed.
     * Only the first settlement wins, so a duplicate result/cancel can never call
     * endExternalActivity twice (which would lock on a stale background flag).
     */
    private fun disarm(vararg pickers: SystemPicker): Boolean = synchronized(lock) {
        val current = armed ?: return false
        if (pickers.isNotEmpty() && current !in pickers) return false
        armed = null
        true
    }

    private fun beginOperation(): Boolean = synchronized(lock) {
        if (busy) return false
        if (!isUnlocked()) {
            _state.value = AttachmentUiState.Error(AttachmentErrorKind.LOCKED)
            return false
        }
        busy = true
        true
    }

    private fun finishOperation() = synchronized(lock) { busy = false }

    private fun isUnlocked(): Boolean = repository.status.value == VaultStatus.UNLOCKED
}

/** Maps the data-layer failure to the friendly UI category. */
internal fun AttachmentError.toUiKind(): AttachmentErrorKind = when (this) {
    AttachmentError.TOO_LARGE -> AttachmentErrorKind.TOO_LARGE
    AttachmentError.QUOTA_EXCEEDED -> AttachmentErrorKind.QUOTA_EXCEEDED
    AttachmentError.NOTE_NOT_FOUND -> AttachmentErrorKind.NOTE_MISSING
    AttachmentError.LOCKED -> AttachmentErrorKind.LOCKED
    AttachmentError.IO -> AttachmentErrorKind.IO
    AttachmentError.INTEGRITY -> AttachmentErrorKind.INTEGRITY
}

/**
 * In-memory PNG stream for [AttachmentController.importDrawing]. Closing it zeroes the
 * backing array so the rendered plaintext page does not linger in the heap after the
 * repository has read it; the array is caller-owned and never used again.
 */
private class WipingByteArrayInputStream(private val backing: ByteArray) :
    ByteArrayInputStream(backing) {
    override fun close() {
        super.close()
        backing.fill(0)
    }
}

