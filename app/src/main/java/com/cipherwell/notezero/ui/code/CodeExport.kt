package com.cipherwell.notezero.ui.code

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.ui.locks.LockVisibility

/** MIME type offered by SAF `CreateDocument` for an exported source file. */
internal const val CODE_EXPORT_MIME_TYPE: String = "text/plain"

/** Fallback suggested name when the editor has no usable file name yet. */
internal const val CODE_EXPORT_FALLBACK_NAME: String = "code.txt"

/**
 * Seam for the explicit "Export decrypted copy" action.
 *
 * The production implementation launches SAF `CreateDocument` and writes the confirmed draft
 * when the picker returns a location; tests inject a recorder so the confirmation gate and the
 * exported bytes can be asserted without a system picker. Nothing in the vault is modified and
 * nothing is exported unless the user confirms the UNENCRYPTED-copy warning.
 */
fun interface CodeExportTarget {
    /**
     * @param suggestedName file name offered to the picker (never a path).
     * @param content the confirmed plaintext draft.
     */
    fun requestExport(suggestedName: String, content: String)
}

/**
 * True only when the pending export may still be written: the vault is UNLOCKED **and**
 * [itemId] is still in the index and visible to this session (not locked+unverified).
 *
 * The file picker is a separate activity and may stay open past the idle timeout, and
 * `endExternalActivity()` can lock the vault as the result arrives. "Locked ⇒ no decrypted
 * output" must therefore be re-checked at result time, exactly like
 * [com.cipherwell.notezero.ui.attachments.AttachmentController.onExportTarget] does for
 * attachments (audit finding F6). Pure function so the decision is unit-tested on the JVM.
 */
internal fun canWriteCodeExport(repository: NoteRepository, itemId: String): Boolean {
    if (repository.status.value != VaultStatus.UNLOCKED) return false
    val item = repository.items.value.firstOrNull { it.id == itemId } ?: return false
    return !LockVisibility.isItemMasked(item, repository.verifiedLockedIds.value)
}

/**
 * Production export target: SAF `CreateDocument` bracketed with the repository's external
 * activity deferral (the picker is a separate activity; without the bracket the vault would
 * lock and lose the user's chosen location).
 *
 * The confirmed content is held in memory only until the picker returns; it is written to the
 * user-chosen `content://` URI, never into app storage. A cancelled/failed picker writes
 * nothing. The pending plaintext is also dropped as soon as the vault leaves UNLOCKED, and
 * the result path re-checks the session before writing anything (audit F6).
 */
@Composable
internal fun rememberSafCodeExportTarget(
    repository: NoteRepository,
    itemId: String,
): CodeExportTarget {
    val context = LocalContext.current
    val pending: MutableState<String?> = remember { mutableStateOf(null) }
    val status by repository.status.collectAsStateWithLifecycle()
    // Drop the pending plaintext when the vault locks; the result callback re-checks the
    // session anyway, because a stopped Activity pauses this observer while the picker is up.
    LaunchedEffect(status) {
        if (status != VaultStatus.UNLOCKED) pending.value = null
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(CODE_EXPORT_MIME_TYPE),
        onResult = { uri: Uri? ->
            val content = pending.value
            pending.value = null
            if (uri != null && content != null && canWriteCodeExport(repository, itemId)) {
                writePlaintextCopy(context.contentResolver, uri, content)
            }
            repository.endExternalActivity()
        },
    )
    return remember(launcher, repository, context, itemId) {
        CodeExportTarget { suggestedName, content ->
            if (!canWriteCodeExport(repository, itemId)) return@CodeExportTarget
            pending.value = content
            repository.beginExternalActivity()
            runCatching { launcher.launch(sanitizeExportName(suggestedName)) }
                .onFailure {
                    // No SAF handler on this device: settle the deferral, export nothing.
                    pending.value = null
                    repository.endExternalActivity()
                }
        }
    }
}

/**
 * Writes [content] as UTF-8 to the user-chosen [uri]. `wt` truncates so re-exporting to the
 * same document never leaves stale trailing bytes; providers that reject it fall back to the
 * default write mode. Returns false on any I/O problem — no exception escapes into the UI.
 */
internal fun writePlaintextCopy(resolver: ContentResolver, uri: Uri, content: String): Boolean =
    runCatching {
        val stream = resolver.openOutputStream(uri, "wt")
            ?: resolver.openOutputStream(uri)
            ?: return@runCatching false
        stream.use { out -> out.write(content.toByteArray(Charsets.UTF_8)) }
        true
    }.getOrDefault(false)

/**
 * Turns a file name into a safe picker suggestion: path separators and control characters
 * become `_`, and an empty result falls back to [CODE_EXPORT_FALLBACK_NAME].
 */
internal fun sanitizeExportName(fileName: String): String {
    val builder = StringBuilder(fileName.length)
    for (character in fileName.trim()) {
        builder.append(
            when {
                character == '/' || character == '\\' || character.isISOControl() -> '_'
                else -> character
            },
        )
    }
    val cleaned = builder.toString().trim()
    return cleaned.ifEmpty { CODE_EXPORT_FALLBACK_NAME }
}
