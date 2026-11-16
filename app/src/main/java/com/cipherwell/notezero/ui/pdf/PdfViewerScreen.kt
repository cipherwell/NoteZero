package com.cipherwell.notezero.ui.pdf

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cipherwell.notezero.R
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.ui.attachments.AttachmentController
import com.cipherwell.notezero.ui.components.common.NzIconButton
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * Full-screen viewer for an encrypted PDF item.
 *
 * The PDF bytes are decrypted from the vault, rasterised to in-memory bitmaps and shown on a
 * paged list; nothing is cached and no file survives viewing (see [AndroidPdfPageRenderer] for
 * the bounded ephemeral-render-file trade-off). "Export decrypted copy" is the only plaintext
 * egress and goes through the same SAF `CreateDocument` + explicit-warning flow as attachment
 * export, bracketed by [AttachmentController] so the picker does not lock the vault mid-export.
 *
 * A vault lock replaces this screen with the unlock UI at the shell level; bitmaps are
 * recycled as soon as the composition leaves.
 */
@Composable
fun PdfViewerScreen(
    repository: NoteRepository,
    itemId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    renderer: PdfPageRenderer = remember { AndroidPdfPageRenderer() },
) {
    val context = LocalContext.current
    val items by repository.items.collectAsStateWithLifecycle()
    val item = items.firstOrNull { it.id == itemId }
    val attachment = item?.attachments?.firstOrNull { it.mimeType == BlankPdf.MIME_TYPE }
        ?: item?.attachments?.firstOrNull()

    val controller = remember(repository) { AttachmentController(repository) }
    var loading by remember(itemId) { mutableStateOf(true) }
    var pages by remember(itemId) { mutableStateOf(PdfPages(emptyList(), 0)) }
    var failed by remember(itemId) { mutableStateOf(false) }
    var notice by remember(itemId) { mutableStateOf<String?>(null) }
    var showExportWarning by remember(itemId) { mutableStateOf(false) }

    val title = item?.title?.takeIf { it.isNotBlank() } ?: attachment?.displayName.orEmpty()
    val fileLabel = attachment?.displayName.orEmpty()
    val sizeLabel = attachment
        ?.let { com.cipherwell.notezero.ui.attachments.AttachmentFormat.size(it.sizeBytes) }
        .orEmpty()

    val exportCancelledNotice = stringResource(R.string.attach_export_cancelled)
    val exportWrittenNotice = stringResource(R.string.attach_export_written)
    val exportFailedNotice = stringResource(R.string.attach_export_failed)
    val exportLockedNotice = stringResource(R.string.attach_export_locked)

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BlankPdf.MIME_TYPE),
    ) { uri ->
        if (uri == null) {
            controller.onExportPickerCancelled()
            notice = exportCancelledNotice
        } else {
            controller.onExportTarget(
                noteId = itemId,
                attachmentId = attachment?.id.orEmpty(),
                openOut = {
                    runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
                },
                onFinished = { written ->
                    notice = if (written) exportWrittenNotice else exportFailedNotice
                },
            )
        }
    }

    LaunchedEffect(itemId, attachment?.id) {
        loading = true
        failed = false
        val bytes = if (attachment != null) {
            runCatching { repository.attachmentBytes(itemId, attachment.id) }.getOrNull()
        } else {
            null
        }
        val rendered = if (bytes != null) renderer.render(context, bytes) else PdfPages(emptyList(), 0)
        pages = rendered
        failed = bytes == null || rendered.bitmaps.isEmpty()
        loading = false
    }

    // Free the rendered page bitmaps as soon as the screen leaves (manually, not "eventually").
    DisposableEffect(pages) {
        val owned = pages.bitmaps
        onDispose { owned.forEach { it.recycle() } }
    }

    BackHandler { onClose() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("pdf_viewer")
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NoteZeroSpacing.s, vertical = NoteZeroSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NzIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_pdf_back),
                    onClick = onClose,
                    modifier = Modifier.testTag("pdf_back"),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = NoteZeroSpacing.s),
                ) {
                    Text(
                        text = title,
                        style = NoteZeroType.Callout,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("pdf_title"),
                    )
                    if (sizeLabel.isNotBlank()) {
                        Text(
                            text = "PDF · $sizeLabel",
                            style = NoteZeroType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                NzIconButton(
                    icon = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.cd_pdf_export),
                    onClick = { showExportWarning = true },
                    modifier = Modifier.testTag("pdf_export"),
                )
            }

            notice?.let { text ->
                Text(
                    text = text,
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NoteZeroSpacing.l)
                        .testTag("pdf_notice"),
                )
            }

            when {
                loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(NoteZeroSpacing.m))
                        Text(
                            text = stringResource(R.string.pdf_loading),
                            style = NoteZeroType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                failed || pages.bitmaps.isEmpty() -> Text(
                    text = stringResource(R.string.pdf_error),
                    style = NoteZeroType.Body,
                    color = com.cipherwell.notezero.ui.theme.LocalNoteZeroColors.current.dangerText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(NoteZeroSpacing.l)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("pdf_error"),
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("pdf_pages"),
                    contentPadding = PaddingValues(
                        start = NoteZeroSpacing.l,
                        end = NoteZeroSpacing.l,
                        top = NoteZeroSpacing.s,
                        bottom = NoteZeroSpacing.l + 48.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.m),
                ) {
                    itemsIndexed(pages.bitmaps, key = { index: Int, _: Bitmap -> "pdf_page_$index" }) { index, bitmap ->
                        PageImage(
                            bitmap = bitmap,
                            index = index,
                            total = pages.totalPages,
                        )
                    }
                    if (pages.isTruncated) {
                        item(key = "pdf_capped") {
                            Text(
                                text = stringResource(R.string.pdf_pages_capped, pages.renderedPages),
                                style = NoteZeroType.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("pdf_pages_capped"),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showExportWarning) {
        AlertDialog(
            onDismissRequest = { showExportWarning = false },
            modifier = Modifier.testTag("pdf_export_warning"),
            title = { Text(stringResource(R.string.attach_export_warning_title), style = NoteZeroType.Heading) },
            text = { Text(stringResource(R.string.attach_export_warning_message), style = NoteZeroType.Body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportWarning = false
                        if (controller.beginExport()) {
                            runCatching { exportLauncher.launch(fileLabel) }
                                .onFailure { controller.onExportPickerCancelled() }
                        } else {
                            notice = exportLockedNotice
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("pdf_export_confirm"),
                ) {
                    Text(stringResource(R.string.attach_choose_location), style = NoteZeroType.Button)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExportWarning = false },
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("pdf_export_cancel"),
                ) {
                    Text(stringResource(R.string.common_cancel), style = NoteZeroType.Button)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = com.cipherwell.notezero.ui.theme.NoteZeroShapes.sheet,
        )
    }
}

@Composable
private fun PageImage(bitmap: Bitmap, index: Int, total: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.pdf_page_indicator, index + 1, total),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NoteZeroSpacing.s))
                .testTag("pdf_page")
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        )
        Spacer(Modifier.height(NoteZeroSpacing.xxs))
        Text(
            text = stringResource(R.string.pdf_page_indicator, index + 1, total),
            style = NoteZeroType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
