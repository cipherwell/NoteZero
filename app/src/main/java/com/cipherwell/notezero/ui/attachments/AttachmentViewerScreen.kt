package com.cipherwell.notezero.ui.attachments

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen viewer for one decrypted attachment.
 *
 * The decrypted bytes and decoded bitmap live only in this composition's state: no file
 * is written when viewing (FLAG_SECURE is app-wide). "Export decrypted copy" is the one
 * explicit path that creates plaintext outside the vault, so it is gated behind a
 * warning dialog that says exactly that, and only then launches SAF `CreateDocument`.
 */
@Composable
fun AttachmentViewerScreen(
    controller: AttachmentController,
    noteId: String,
    attachment: AttachmentRef,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controllerState by controller.state.collectAsStateWithLifecycle()

    var bytes by remember(attachment.id) { mutableStateOf<ByteArray?>(null) }
    var bitmap by remember(attachment.id) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(attachment.id) { mutableStateOf(true) }
    var showExportWarning by remember(attachment.id) { mutableStateOf(false) }
    var exportNotice by remember(attachment.id) { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(exportMimeType(attachment.mimeType)),
    ) { uri ->
        if (uri == null) {
            controller.onExportPickerCancelled()
            exportNotice = "Export cancelled."
        } else {
            controller.onExportTarget(
                noteId = noteId,
                attachmentId = attachment.id,
                openOut = {
                    runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
                },
                onFinished = { written ->
                    exportNotice = if (written) {
                        "Decrypted copy written outside the vault."
                    } else {
                        "The decrypted copy could not be written."
                    }
                },
            )
        }
    }

    LaunchedEffect(attachment.id) {
        loading = true
        val data = controller.loadBytes(noteId, attachment.id, reportErrors = true)
        bytes = data
        bitmap = if (data != null) {
            withContext(Dispatchers.Default) {
                AttachmentImages.decode(data, AttachmentImages.VIEWER_TARGET_PX)
            }
        } else {
            null
        }
        loading = false
    }

    BackHandler { onClose() }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("attachment_viewer"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = NoteZeroSpacing.s,
                        end = NoteZeroSpacing.s,
                        top = NoteZeroSpacing.s,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(NoteZeroSpacing.minTarget)
                        .testTag("attachment_viewer_close"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close attachment viewer")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = attachment.displayName,
                        style = NoteZeroType.FieldLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("attachment_viewer_title"),
                    )
                    Text(
                        text = "${attachment.mimeType} · ${AttachmentFormat.size(attachment.sizeBytes)}",
                        style = NoteZeroType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(
                    onClick = { showExportWarning = true },
                    modifier = Modifier
                        .size(NoteZeroSpacing.minTarget)
                        .testTag("attachment_export"),
                ) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "Export decrypted copy",
                        tint = LocalNoteZeroColors.current.accentIcon,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(NoteZeroSpacing.l),
                contentAlignment = Alignment.Center,
            ) {
                val decoded = bitmap
                when {
                    loading -> CircularProgressIndicator()

                    decoded != null -> Image(
                        bitmap = decoded.asImageBitmap(),
                        contentDescription = attachment.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("attachment_viewer_image"),
                    )

                    bytes != null -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("attachment_viewer_image")
                            .semantics { contentDescription = attachment.displayName },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Preview unavailable — export a decrypted copy to open it.",
                            style = NoteZeroType.Callout,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> Text(
                        text = "This attachment could not be opened. Its bytes may be " +
                            "corrupt, or the vault locked.",
                        style = NoteZeroType.Callout,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("attachment_viewer_error"),
                    )
                }
            }

            val notice = exportNotice
            if (notice != null || controllerState is AttachmentUiState.Error) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.s),
                ) {
                    if (notice != null) {
                        Text(
                            text = notice,
                            style = NoteZeroType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("attachment_export_status"),
                        )
                    }
                    val error = controllerState as? AttachmentUiState.Error
                    if (error != null) {
                        Text(
                            text = error.message,
                            style = NoteZeroType.Caption,
                            color = LocalNoteZeroColors.current.dangerText,
                            modifier = Modifier
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("attachment_export_error"),
                        )
                    }
                }
            }
        }
    }

    if (showExportWarning) {
        AlertDialog(
            onDismissRequest = { showExportWarning = false },
            modifier = Modifier.testTag("attachment_export_warning"),
            title = { Text("Write an UNENCRYPTED copy?", style = NoteZeroType.Heading) },
            text = {
                Text(
                    text = "This writes an UNENCRYPTED copy outside the vault. Anyone with " +
                        "access to that file — or to the location you choose — can read it, " +
                        "and NoteZero cannot protect it once it leaves the vault.",
                    style = NoteZeroType.Body,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportWarning = false
                        // Bracket SAF CreateDocument: begin only right before launch.
                        if (controller.beginExport()) {
                            runCatching {
                                exportLauncher.launch(
                                    AttachmentFormat.exportFileName(attachment.displayName),
                                )
                            }.onFailure { controller.onExportPickerCancelled() }
                        } else {
                            exportNotice = "The vault is locked. Unlock to export a copy."
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("attachment_export_confirm"),
                ) {
                    Text("Choose location", style = NoteZeroType.Button)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExportWarning = false },
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("attachment_export_cancel"),
                ) {
                    Text("Cancel", style = NoteZeroType.Button)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = NoteZeroShapes.sheet,
        )
    }
}

/** SAF needs a concrete MIME type; unknown provider types fall back to a generic one. */
private fun exportMimeType(mimeType: String): String =
    mimeType.takeIf { it.isNotBlank() && it.contains('/') } ?: "application/octet-stream"
