package com.cipherwell.notezero.ui.notes

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherwell.notezero.R
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.ui.locks.LockVisibility
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/** Format offered by the export dialog. The MIME type is what SAF `CreateDocument` gets. */
enum class NoteExportFormat(val mimeType: String, val extension: String) {
    MARKDOWN(mimeType = "text/markdown", extension = "md"),
    HTML(mimeType = "text/html", extension = "html"),
}

/**
 * A confirmed export, ready for the SAF picker.
 *
 * [content] is decrypted note text held in memory only until the picker returns; it is
 * never logged, cached or written anywhere except the user-chosen target URI.
 */
data class NoteExportRequest(
    val itemId: String,
    val format: NoteExportFormat,
    val suggestedName: String,
    val content: String,
)

/** Non-secret outcome of the SAF round trip (never carries note content). */
sealed interface NoteExportResult {
    data object Written : NoteExportResult

    data object Cancelled : NoteExportResult

    data object Failed : NoteExportResult

    /** The vault locked (or the note re-masked) before the bytes could be written. */
    data object Locked : NoteExportResult
}

/**
 * Seam between the notes ViewModel and the platform file picker. Production wires
 * [rememberSafNoteExportTarget]; tests inject a recorder so the warning gate, converted
 * content and status handling are assertable without a system activity.
 */
fun interface NoteExportTarget {
    fun requestExport(request: NoteExportRequest, onResult: (NoteExportResult) -> Unit)
}

/** UI-facing outcome kinds; mapped to string resources by [NoteExportStatusBar]. */
enum class NoteExportOutcome {
    NONE,
    EXPORTED,
    CANCELLED,
    FAILED,
    LOCKED,
    UNAVAILABLE,
}

/**
 * Non-secret export status. [sequence] increments on every completed attempt so the UI can
 * show the same message twice and never exposes a title, body or file name.
 */
data class NoteExportStatus(
    val outcome: NoteExportOutcome = NoteExportOutcome.NONE,
    val sequence: Long = 0L,
)

/** Maps a target result to the status enum (kept pure for the ViewModel tests). */
internal fun NoteExportResult.toOutcome(): NoteExportOutcome = when (this) {
    NoteExportResult.Written -> NoteExportOutcome.EXPORTED
    NoteExportResult.Cancelled -> NoteExportOutcome.CANCELLED
    NoteExportResult.Failed -> NoteExportOutcome.FAILED
    NoteExportResult.Locked -> NoteExportOutcome.LOCKED
}

/**
 * True only when an export may still be written: the vault is UNLOCKED, [itemId] is still
 * in the index, and its content is revealed to this session (not masked by the locked-note
 * gate). Re-checked at picker-result time because the SAF activity can outlive the idle
 * timeout and a lock may land while it is open.
 */
internal fun canWriteNoteExport(repository: NoteRepository, itemId: String): Boolean {
    if (repository.status.value != VaultStatus.UNLOCKED) return false
    val item = repository.items.value.firstOrNull { it.id == itemId } ?: return false
    return !LockVisibility.isItemMasked(item, repository.verifiedLockedIds.value)
}

/**
 * Sanitises the title into a suggested file name for the picker: path separators, Windows
 * reserved characters and control characters become `_`, the name is length-bounded and
 * the extension is appended exactly once. Never a path, never blank.
 */
internal fun sanitizeNoteExportName(title: String, extension: String): String {
    val cleaned = buildString(title.length) {
        title.trim().forEach { character ->
            val illegal = character == '/' || character == '\\' || character == ':' ||
                character == '*' || character == '?' || character == '"' ||
                character == '<' || character == '>' || character == '|' ||
                character.isISOControl()
            append(if (illegal) '_' else character)
        }
    }.trim().trimEnd('.').take(MAX_EXPORT_NAME_LENGTH).trim()

    val base = cleaned.ifEmpty { "note" }
    val suffix = ".${extension.lowercase()}"
    return if (base.endsWith(suffix, ignoreCase = true)) base else base + suffix
}

private const val MAX_EXPORT_NAME_LENGTH = 80

/**
 * Production target: SAF `CreateDocument` in the requested MIME type, bracketed with the
 * repository's external-activity deferral exactly like the code export. The pending
 * plaintext is dropped as soon as the vault leaves UNLOCKED, a cancelled/failed picker
 * writes nothing, and the result path re-checks the session before writing.
 */
@Composable
internal fun rememberSafNoteExportTarget(repository: NoteRepository): NoteExportTarget {
    val context = LocalContext.current
    val pending: MutableState<Pair<NoteExportRequest, (NoteExportResult) -> Unit>?> =
        remember { mutableStateOf(null) }
    val status by repository.status.collectAsStateWithLifecycle()
    LaunchedEffect(status) {
        if (status != VaultStatus.UNLOCKED) pending.value = null
    }

    fun finish(uri: Uri?, expected: NoteExportFormat) {
        val requestAndCallback = pending.value
        pending.value = null
        if (requestAndCallback == null) {
            repository.endExternalActivity()
            return
        }
        val (request, onResult) = requestAndCallback
        if (request.format != expected) {
            onResult(NoteExportResult.Cancelled)
        } else if (uri == null) {
            onResult(NoteExportResult.Cancelled)
        } else if (!canWriteNoteExport(repository, request.itemId)) {
            onResult(NoteExportResult.Locked)
        } else {
            val written = writeNoteExportCopy(context.contentResolver, uri, request.content)
            onResult(if (written) NoteExportResult.Written else NoteExportResult.Failed)
        }
        repository.endExternalActivity()
    }

    val markdownLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(NoteExportFormat.MARKDOWN.mimeType),
        onResult = { uri -> finish(uri, NoteExportFormat.MARKDOWN) },
    )
    val htmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(NoteExportFormat.HTML.mimeType),
        onResult = { uri -> finish(uri, NoteExportFormat.HTML) },
    )

    return remember(markdownLauncher, htmlLauncher, repository, context) {
        NoteExportTarget { request, onResult ->
            if (!canWriteNoteExport(repository, request.itemId)) {
                onResult(NoteExportResult.Locked)
                return@NoteExportTarget
            }
            if (pending.value != null) {
                // One picker at a time: a second request before the first returns fails
                // closed rather than leaking the first export's plaintext into it.
                onResult(NoteExportResult.Failed)
                return@NoteExportTarget
            }
            pending.value = request to onResult
            repository.beginExternalActivity()
            val launched = runCatching {
                when (request.format) {
                    NoteExportFormat.MARKDOWN -> markdownLauncher.launch(request.suggestedName)
                    NoteExportFormat.HTML -> htmlLauncher.launch(request.suggestedName)
                }
            }
            if (launched.isFailure) {
                pending.value = null
                repository.endExternalActivity()
                onResult(NoteExportResult.Failed)
            }
        }
    }
}

/**
 * Writes [content] as UTF-8 to the user-chosen [uri] using `wt` (truncate) so a re-export
 * never leaves stale trailing bytes; providers that reject `wt` fall back to the default
 * mode. Returns false on any I/O problem — no exception escapes into the UI.
 */
internal fun writeNoteExportCopy(
    resolver: ContentResolver,
    uri: Uri,
    content: String,
): Boolean = runCatching {
    val stream = resolver.openOutputStream(uri, "wt")
        ?: resolver.openOutputStream(uri)
        ?: return@runCatching false
    stream.use { out -> out.write(content.toByteArray(Charsets.UTF_8)) }
    true
}.getOrDefault(false)

// ---- UI ----------------------------------------------------------------------

/** Format chooser shown before the unencrypted-copy warning. */
@Composable
internal fun NoteExportFormatDialog(
    onDismiss: () -> Unit,
    onSelect: (NoteExportFormat) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("note_export_format_dialog"),
        title = { Text(stringResource(R.string.note_export_format_title), style = NoteZeroType.Heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs)) {
                Text(
                    text = stringResource(R.string.note_export_format_message),
                    style = NoteZeroType.Callout,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { onSelect(NoteExportFormat.MARKDOWN) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("note_export_markdown"),
                ) { Text(stringResource(R.string.note_export_markdown), style = NoteZeroType.Callout) }
                TextButton(
                    onClick = { onSelect(NoteExportFormat.HTML) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("note_export_html"),
                ) { Text(stringResource(R.string.note_export_html), style = NoteZeroType.Callout) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("note_export_format_cancel"),
            ) { Text(stringResource(R.string.note_export_cancel)) }
        },
    )
}

/** Explicit "this writes an UNENCRYPTED copy outside the vault" gate before SAF. */
@Composable
internal fun NoteExportWarningDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("note_export_warning"),
        title = { Text(stringResource(R.string.note_export_warning_title), style = NoteZeroType.Heading) },
        text = { Text(stringResource(R.string.note_export_warning_message), style = NoteZeroType.Callout) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("note_export_confirm"),
            ) {
                Text(
                    text = stringResource(R.string.note_export_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("note_export_cancel"),
            ) { Text(stringResource(R.string.note_export_cancel)) }
        },
    )
}

/**
 * Non-secret status/error line for the notes screen (success, cancel, failure, locked,
 * unavailable). Never contains note content, a title or a file name.
 */
@Composable
internal fun NoteExportStatusBar(
    status: NoteExportStatus,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = exportStatusMessage(status.outcome) ?: return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("note_export_status"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NoteZeroSpacing.minTarget)
                .padding(start = NoteZeroSpacing.l),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = NoteZeroType.Caption,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("note_export_status_dismiss"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Clear,
                    contentDescription = stringResource(R.string.common_dismiss),
                )
            }
        }
    }
}

@Composable
private fun exportStatusMessage(outcome: NoteExportOutcome): String? = when (outcome) {
    NoteExportOutcome.NONE -> null
    NoteExportOutcome.EXPORTED -> stringResource(R.string.note_export_status_saved)
    NoteExportOutcome.CANCELLED -> stringResource(R.string.note_export_status_cancelled)
    NoteExportOutcome.FAILED -> stringResource(R.string.note_export_status_failed)
    NoteExportOutcome.LOCKED -> stringResource(R.string.note_export_status_locked)
    NoteExportOutcome.UNAVAILABLE -> stringResource(R.string.note_export_status_unavailable)
}
