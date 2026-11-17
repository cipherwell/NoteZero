package com.cipherwell.notezero.ui.pdf

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.cipherwell.notezero.model.BlankPdf

/**
 * Seam between the annotated-PDF export and the platform SAF `CreateDocument` picker.
 *
 * Production wiring is [rememberSafPdfExportGateway]; Robolectric tests inject a fake so the
 * "annotated copy" path can be asserted end to end (picker returns a fake destination, the bytes
 * are captured) without launching a system activity. Both calls are content-free apart from the
 * bytes themselves, which only exist in memory for the duration of the write.
 */
interface PdfExportGateway {

    /** Opens the create-document picker; [onPicked] receives the destination or null on cancel. */
    fun pickDestination(suggestedName: String, onPicked: (Uri?) -> Unit)

    /** Writes [bytes] to the user-chosen [uri]; false on any I/O problem (never throws). */
    fun write(uri: Uri, bytes: ByteArray): Boolean
}

/**
 * Production gateway: SAF `CreateDocument("application/pdf")` plus a binary-safe write into the
 * returned `content://` document. The write truncates (`"wt"`, falling back to the default mode
 * some providers require) so re-exporting never leaves stale trailing bytes.
 */
@Composable
internal fun rememberSafPdfExportGateway(): PdfExportGateway {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<((Uri?) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BlankPdf.MIME_TYPE),
    ) { uri ->
        val callback = pending
        pending = null
        callback?.invoke(uri)
    }
    return remember(context, launcher) {
        object : PdfExportGateway {
            override fun pickDestination(suggestedName: String, onPicked: (Uri?) -> Unit) {
                pending = onPicked
                launcher.launch(suggestedName)
            }

            override fun write(uri: Uri, bytes: ByteArray): Boolean =
                writePdfExportBytes(context.contentResolver, uri, bytes)
        }
    }
}

/**
 * Writes pre-rendered PDF [bytes] to the user-chosen [uri]. Binary-safe and truncating, mirroring
 * `writeNoteExportBytes` in the notes export flow but scoped to this package (no cross-feature
 * coupling). Returns false on any I/O problem; no exception escapes into the UI.
 */
internal fun writePdfExportBytes(
    resolver: ContentResolver,
    uri: Uri,
    bytes: ByteArray,
): Boolean = runCatching {
    val stream = resolver.openOutputStream(uri, "wt")
        ?: resolver.openOutputStream(uri)
        ?: return@runCatching false
    stream.use { output -> output.write(bytes) }
    true
}.getOrDefault(false)

/** Suggested SAF name for a flattened copy of [originalName] (never a path). */
internal fun annotatedPdfFileName(originalName: String): String {
    val trimmed = originalName.trim()
    val withoutExtension = if (trimmed.endsWith(".pdf", ignoreCase = true)) {
        trimmed.dropLast(PDF_EXTENSION.length)
    } else {
        trimmed
    }
    val base = withoutExtension.trim().ifBlank { "document" }
    return "${base.take(MAX_BASE_NAME_LENGTH)}-annotated.pdf"
}

private const val MAX_BASE_NAME_LENGTH = 64
private const val PDF_EXTENSION = ".pdf"
