package com.cipherwell.notezero.ui.pdf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cipherwell.notezero.R
import com.cipherwell.notezero.ui.components.common.NzBannerTone
import com.cipherwell.notezero.ui.components.common.NzIconButton
import com.cipherwell.notezero.ui.components.common.NzInfoBanner
import com.cipherwell.notezero.ui.components.common.NzPrimaryButton
import com.cipherwell.notezero.ui.components.common.NzTextButton
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * Full-screen PDF page-tools overlay: reorder, rotate, delete and insert-blank pages of the open
 * document, then save the result as a new flattened encrypted PDF copy.
 *
 * The screen is **controlled**: the caller hoists the [plan], the in-flight save flag [saving]
 * and any save-result [notice] (the viewer owns the export/save coroutines because only it has
 * the decrypted attachment bytes and the repository). All edits are pure [PdfPagePlan]
 * operations, so this composable is a plain state renderer and a plain Robolectric test.
 *
 * The original document is never touched: the overlay only edits the plan, the warning banner
 * states the copy will be flattened and image-only, and saving goes back through the viewer.
 *
 * Accessibility: every page control has a content description and a 48dp target, controls are
 * disabled (with a muted tint) where the operation is meaningless (move up on the first page,
 * move down on the last page, insert blank at the cap), and every notice uses a live region so
 * screen readers announce it.
 */
@Composable
fun PdfPageToolsScreen(
    plan: PdfPagePlan,
    saving: Boolean,
    notice: String?,
    onPlanChange: (PdfPagePlan) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local notices (last-page refusal / blank added) are screen state; save failures come from
    // the caller so a failed export keeps the overlay open with the reason visible.
    var localNotice by remember { mutableStateOf<String?>(null) }
    val lastPageNotice = stringResource(R.string.pdf_pages_last_page)
    val blankAddedNotice = stringResource(R.string.pdf_pages_blank_added)
    val changedNotice = stringResource(R.string.pdf_pages_changed)
    val tooManyNotice = stringResource(R.string.pdf_pages_too_many, PdfPagePlan.MAX_PAGES)
    val tooMany = plan.isTooLarge || plan.size > PdfPagePlan.MAX_PAGES
    val shownNotice = notice ?: localNotice
    // Content descriptions are resolved once here (instead of inside every row) so the row
    // composable stays cheap while each control still names its operation for screen readers.
    val moveUpDescription = stringResource(R.string.cd_pdf_pages_move_up)
    val moveDownDescription = stringResource(R.string.cd_pdf_pages_move_down)
    val rotateLeftDescription = stringResource(R.string.cd_pdf_pages_rotate_left)
    val rotateRightDescription = stringResource(R.string.cd_pdf_pages_rotate_right)
    val deleteDescription = stringResource(R.string.cd_pdf_pages_delete)
    val cancelLabel = stringResource(R.string.pdf_pages_cancel)
    val resetLabel = stringResource(R.string.pdf_pages_reset)
    val insertBlankLabel = stringResource(R.string.pdf_pages_insert_blank)
    val saveLabel = stringResource(R.string.pdf_pages_save_copy)
    val savingLabel = stringResource(R.string.pdf_pages_saving)

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            // The content applies WindowInsets.safeDrawing itself; letting the window also fit
            // system windows double-insets and clips the bottom actions on tall devices.
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .testTag("pdf_page_tools_screen"),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.m),
                ) {
                    Text(
                        text = stringResource(R.string.pdf_pages_title),
                        style = NoteZeroType.Heading,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("pdf_pages_title"),
                    )
                    Spacer(Modifier.height(NoteZeroSpacing.xxs))
                    // The count is the planned output page count; a too-large source has no plan
                    // to count (the page-cap message explains the situation instead).
                    if (!tooMany) {
                        Text(
                            text = stringResource(R.string.pdf_pages_count, plan.size),
                            style = NoteZeroType.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("pdf_pages_count"),
                        )
                    }
                    Spacer(Modifier.height(NoteZeroSpacing.s))
                    // Kept in the fixed header (not inside the scrolling page list) so the
                    // "flattened image-only copy" warning is always visible without scrolling.
                    NzInfoBanner(
                        text = stringResource(R.string.pdf_pages_warning),
                        tone = NzBannerTone.WARNING,
                        modifier = Modifier.testTag("pdf_pages_warning"),
                    )
                    Spacer(Modifier.height(NoteZeroSpacing.s))
                    Text(
                        text = stringResource(R.string.pdf_pages_hint),
                        style = NoteZeroType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("pdf_pages_hint"),
                    )
                    if (shownNotice != null) {
                        Text(
                            text = shownNotice,
                            style = NoteZeroType.Caption,
                            color = LocalNoteZeroColors.current.warningText,
                            modifier = Modifier
                                .padding(top = NoteZeroSpacing.xs)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("pdf_pages_notice"),
                        )
                    }
                    if (plan.hasChanges() && !tooMany) {
                        Text(
                            text = changedNotice,
                            style = NoteZeroType.Caption,
                            color = LocalNoteZeroColors.current.accentText,
                            modifier = Modifier
                                .padding(top = NoteZeroSpacing.xs)
                                .testTag("pdf_pages_changed"),
                        )
                    }
                }

                if (tooMany) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = NoteZeroSpacing.l),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tooManyNotice,
                            style = NoteZeroType.Body,
                            color = LocalNoteZeroColors.current.dangerText,
                            modifier = Modifier
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("pdf_pages_too_many"),
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("pdf_pages_list"),
                        contentPadding = PaddingValues(horizontal = NoteZeroSpacing.l),
                    ) {
                        itemsIndexed(
                            plan.entries,
                            key = { index: Int, _: PdfPageEntry -> "pdf_page_row_$index" },
                        ) { index, entry ->
                            PageRow(
                                index = index,
                                entry = entry,
                                total = plan.size,
                                onRotateLeft = {
                                    localNotice = null
                                    onPlanChange(plan.rotateLeft(index))
                                },
                                onRotateRight = {
                                    localNotice = null
                                    onPlanChange(plan.rotateRight(index))
                                },
                                onMoveUp = {
                                    localNotice = null
                                    onPlanChange(plan.moveUp(index))
                                },
                                onMoveDown = {
                                    localNotice = null
                                    onPlanChange(plan.moveDown(index))
                                },
                                onDelete = {
                                    val deletion = plan.delete(index)
                                    if (deletion.deleted) {
                                        localNotice = null
                                        onPlanChange(deletion.plan)
                                    } else {
                                        localNotice = lastPageNotice
                                    }
                                },
                                rotateLeftDescription = rotateLeftDescription,
                                rotateRightDescription = rotateRightDescription,
                                moveUpDescription = moveUpDescription,
                                moveDownDescription = moveDownDescription,
                                deleteDescription = deleteDescription,
                            )
                            HorizontalDivider()
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.s),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NzTextButton(
                            text = insertBlankLabel,
                            onClick = {
                                localNotice = blankAddedNotice
                                onPlanChange(plan.insertBlank())
                            },
                            enabled = !saving && !tooMany && plan.size < PdfPagePlan.MAX_PAGES,
                            modifier = Modifier.testTag("pdf_page_insert_blank"),
                        )
                        Spacer(Modifier.weight(1f))
                        NzTextButton(
                            text = resetLabel,
                            onClick = {
                                localNotice = null
                                onPlanChange(plan.reset())
                            },
                            enabled = !saving && !tooMany && plan.hasChanges(),
                            modifier = Modifier.testTag("pdf_pages_reset"),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NzTextButton(
                            text = cancelLabel,
                            onClick = onCancel,
                            enabled = !saving,
                            modifier = Modifier.testTag("pdf_pages_cancel"),
                        )
                        Spacer(Modifier.weight(1f))
                        NzPrimaryButton(
                            text = if (saving) savingLabel else saveLabel,
                            onClick = {
                                localNotice = null
                                onSave()
                            },
                            enabled = !saving && !tooMany && plan.isValid,
                            loading = saving,
                            modifier = Modifier.testTag("pdf_pages_save_copy"),
                        )
                    }
                }
            }
        }
    }
}

/** One planned page: position, source/blank, rotation and the 48dp page controls. */
@Composable
private fun PageRow(
    index: Int,
    entry: PdfPageEntry,
    total: Int,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    rotateLeftDescription: String,
    rotateRightDescription: String,
    moveUpDescription: String,
    moveDownDescription: String,
    deleteDescription: String,
) {
    val sourceLabel = if (entry.sourcePage == null) {
        stringResource(R.string.pdf_pages_blank)
    } else {
        stringResource(R.string.pdf_pages_source, entry.sourcePage + 1)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pdf_page_row")
            .padding(vertical = NoteZeroSpacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.pdf_pages_position, index + 1),
                style = NoteZeroType.Callout,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.pdf_pages_rotation, entry.quarterTurns * 90),
                style = NoteZeroType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = sourceLabel,
            style = NoteZeroType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Row-scoped tag: the five controls repeat their contract tags on every page, so tests
        // address one page's control with hasTestTag + hasAnyAncestor(pdf_page_row_<index>).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pdf_page_row_$index"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageControl(
                icon = Icons.Filled.Refresh,
                contentDescription = rotateLeftDescription,
                onClick = onRotateLeft,
                enabled = true,
                tag = "pdf_page_rotate_left",
                mirrorHorizontally = true,
            )
            PageControl(
                icon = Icons.Filled.Refresh,
                contentDescription = rotateRightDescription,
                onClick = onRotateRight,
                enabled = true,
                tag = "pdf_page_rotate_right",
            )
            PageControl(
                icon = Icons.Filled.KeyboardArrowUp,
                contentDescription = moveUpDescription,
                onClick = onMoveUp,
                enabled = index > 0,
                tag = "pdf_page_move_up",
            )
            PageControl(
                icon = Icons.Filled.KeyboardArrowDown,
                contentDescription = moveDownDescription,
                onClick = onMoveDown,
                enabled = index < total - 1,
                tag = "pdf_page_move_down",
            )
            // Delete stays enabled on the last page on purpose: the action is a refusal with a
            // visible notice (pdf_pages_last_page), not a silently dead button.
            PageControl(
                icon = Icons.Filled.Delete,
                contentDescription = deleteDescription,
                onClick = onDelete,
                enabled = true,
                tag = "pdf_page_delete",
            )
        }
    }
}

/** One 48dp icon control with an explicit disabled tint (the 48dp target never shrinks). */
@Composable
private fun PageControl(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean,
    tag: String,
    mirrorHorizontally: Boolean = false,
) {
    val tint = if (enabled) {
        LocalNoteZeroColors.current.accentIcon
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    NzIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        enabled = enabled,
        tint = tint,
        modifier = Modifier
            .testTag(tag)
            .then(if (mirrorHorizontally) Modifier.scale(-1f, 1f) else Modifier),
    )
}