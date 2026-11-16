package com.cipherwell.notezero.ui.attachments

import com.cipherwell.notezero.R
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.model.AttachmentRef
import com.cipherwell.notezero.ui.components.common.NzIconButton
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Attachment row shown between the note title and the body.
 *
 * Images are decrypted and decoded **in memory only** — [AttachmentImages] never writes
 * a thumbnail/cache file, so there is no plaintext preview on disk. Non-image files show
 * a type badge, name and size. Every action goes through [AttachmentController], which
 * brackets the system pickers with the repository's external-activity deferral.
 *
 * The third add-menu entry opens [DrawingCanvasScreen]: a hand-drawn page is rendered to an
 * in-memory PNG and imported through the same encrypted pipeline (no picker, no bracket, no
 * file), so it then behaves exactly like a picked image.
 *
 * Accessibility: 48dp targets, image thumbnails carry the display name as their content
 * description, and errors are announced politely (the text itself carries the meaning,
 * never colour alone).
 */
@Composable
fun AttachmentStrip(
    controller: AttachmentController,
    noteId: String,
    attachments: List<AttachmentRef>,
    onOpen: (AttachmentRef) -> Unit,
    modifier: Modifier = Modifier,
    /** True when the editor was opened by "Create > Drawing": open the canvas immediately. */
    startDrawing: Boolean = false,
    onStartDrawingConsumed: () -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AttachmentRef?>(null) }
    var drawingOpen by remember(noteId) { mutableStateOf(false) }

    // "Create > Drawing" opens a fresh note with the canvas already visible; the request is
    // one-shot so a rotation/backout never re-opens it unexpectedly.
    LaunchedEffect(startDrawing) {
        if (!startDrawing) return@LaunchedEffect
        drawingOpen = true
        onStartDrawingConsumed()
    }

    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) {
            controller.onPickerCancelled()
        } else {
            controller.onPickerResult(noteId, AttachmentSourceResolver.fromUri(context, uri))
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            controller.onPickerCancelled()
        } else {
            controller.onPickerResult(noteId, AttachmentSourceResolver.fromUri(context, uri))
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("note_attachments"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = NoteZeroSpacing.l, end = NoteZeroSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.attach_title),
                style = NoteZeroType.FieldLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(NoteZeroSpacing.s))
            Text(
                text = statusLabel(state, attachments.size),
                style = NoteZeroType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("attachment_status"),
            )
            Box {
                NzIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cd_attach_add),
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag("attachment_add"),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_photo), style = NoteZeroType.Callout) },
                        modifier = Modifier.testTag("attachment_add_photo"),
                        onClick = {
                            menuOpen = false
                            // PickVisualMedia needs no storage/camera permission.
                            if (controller.beginPicker(AttachmentPick.PHOTO)) {
                                // A missing picker activity must release the bracket too.
                                runCatching {
                                    photoLauncher.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                                        ),
                                    )
                                }.onFailure { controller.onPickerCancelled() }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_file), style = NoteZeroType.Callout) },
                        modifier = Modifier.testTag("attachment_add_file"),
                        onClick = {
                            menuOpen = false
                            if (controller.beginPicker(AttachmentPick.FILE)) {
                                runCatching { fileLauncher.launch(arrayOf("*/*")) }
                                    .onFailure { controller.onPickerCancelled() }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.attach_drawing), style = NoteZeroType.Callout) },
                        modifier = Modifier.testTag("attachment_add_drawing"),
                        onClick = {
                            menuOpen = false
                            // No system picker, so no external-activity bracket: the page is
                            // drawn and encrypted entirely in this process.
                            drawingOpen = true
                        },
                    )
                }
            }
        }

        if (attachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
                contentPadding = PaddingValues(
                    start = NoteZeroSpacing.l,
                    end = NoteZeroSpacing.l,
                    top = NoteZeroSpacing.xs,
                    bottom = NoteZeroSpacing.xs,
                ),
                horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.s),
            ) {
                items(attachments, key = { it.id }) { ref ->
                    AttachmentChip(
                        controller = controller,
                        noteId = noteId,
                        ref = ref,
                        onOpen = { onOpen(ref) },
                        onDelete = { pendingDelete = ref },
                    )
                }
            }
        }

        if (state is AttachmentUiState.Error) {
            Text(
                text = (state as AttachmentUiState.Error).message,
                style = NoteZeroType.Caption,
                color = LocalNoteZeroColors.current.dangerText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.xs)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("attachment_error"),
            )
        }
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            modifier = Modifier.testTag("attachment_delete_dialog"),
            title = { Text(stringResource(R.string.attach_remove_title), style = NoteZeroType.Heading) },
            text = {
                Text(
                    text = stringResource(R.string.attach_remove_message, target.displayName),
                    style = NoteZeroType.Body,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        controller.delete(noteId, target.id)
                    },
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("attachment_delete_confirm"),
                ) {
                    Text(
                        text = stringResource(R.string.common_delete),
                        style = NoteZeroType.Button,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("attachment_delete_cancel"),
                ) {
                    Text(stringResource(R.string.common_cancel), style = NoteZeroType.Button)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = NoteZeroShapes.sheet,
        )
    }

    if (drawingOpen) {
        DrawingCanvasScreen(
            onCancel = { drawingOpen = false },
            onSave = { pngBytes ->
                // Hand the rendered page to the encrypted pipeline BEFORE closing the canvas:
                // the import is the only plaintext consumer, and closing first would make the
                // dialog disappear while the bytes were still uncommitted.
                // The controller zeroes the array once the repository has read it.
                controller.importDrawing(noteId, pngBytes)
                drawingOpen = false
            },
        )
    }
}

@Composable
private fun AttachmentChip(
    controller: AttachmentController,
    noteId: String,
    ref: AttachmentRef,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(200.dp)
            .heightIn(min = 56.dp)
            .clip(NoteZeroShapes.row)
            .clickable(onClick = onOpen)
            .testTag("attachment_item_${ref.id}"),
        shape = NoteZeroShapes.row,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(NoteZeroSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AttachmentThumbnail(controller = controller, noteId = noteId, ref = ref)
            Spacer(Modifier.width(NoteZeroSpacing.s))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ref.displayName,
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = AttachmentFormat.size(ref.sizeBytes),
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            NzIconButton(
                icon = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.cd_attach_delete),
                onClick = onDelete,
                modifier = Modifier.testTag("attachment_delete"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttachmentThumbnail(
    controller: AttachmentController,
    noteId: String,
    ref: AttachmentRef,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, ref.id, noteId) {
        val bytes = controller.loadBytes(noteId, ref.id)
        value = if (bytes != null) {
            withContext(Dispatchers.Default) {
                AttachmentImages.decode(bytes, AttachmentImages.THUMBNAIL_TARGET_PX)
            }
        } else {
            null
        }
    }
    val decoded = bitmap
    val shape = RoundedCornerShape(NoteZeroSpacing.s)
    if (decoded != null) {
        Image(
            bitmap = decoded.asImageBitmap(),
            contentDescription = ref.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(shape)
                .testTag("attachment_thumbnail"),
        )
    } else {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = typeBadge(ref),
                style = NoteZeroType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Short badge for the non-image (or not-yet-decoded) placeholder. */
private fun typeBadge(ref: AttachmentRef): String {
    val extension = ref.displayName.substringAfterLast('.', missingDelimiterValue = "")
    return when {
        extension.isNotBlank() && extension.length <= 4 -> extension.uppercase()
        ref.mimeType.startsWith("image/") -> "IMG"
        else -> "FILE"
    }
}

@Composable
private fun statusLabel(state: AttachmentUiState, count: Int): String = when {
    state is AttachmentUiState.Importing -> stringResource(R.string.attach_importing)
    state is AttachmentUiState.Error -> stringResource(R.string.attach_error)
    count > 0 -> pluralStringResource(R.plurals.attach_count, count, count)
    else -> stringResource(R.string.attach_none)
}
