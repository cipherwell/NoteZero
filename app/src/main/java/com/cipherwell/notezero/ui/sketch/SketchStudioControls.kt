package com.cipherwell.notezero.ui.sketch

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.R
import com.cipherwell.notezero.model.SketchInk
import com.cipherwell.notezero.model.SketchShapeKind
import com.cipherwell.notezero.model.SketchTemplate
import com.cipherwell.notezero.model.SketchWidth
import com.cipherwell.notezero.ui.components.common.NzIconButton
import com.cipherwell.notezero.ui.components.common.NzTextButton
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * Chrome for the drawing studio: top bar, tool/ink/paper rows, zoom overlay and the two
 * confirmation dialogs.
 *
 * Every interactive control is at least 48dp, every icon-only control carries a
 * `contentDescription`, selection is exposed through `selectable`/`FilterChip` semantics (never
 * colour alone — the selected swatch also shows a check glyph), and the rows scroll horizontally
 * so a large system font cannot push a control off screen. All visible text comes from
 * `stringResource`; tags are the frozen contract ids used by the tests.
 */

/** Top bar: back (flushes the pending save), current title, rename. */
@Composable
internal fun SketchTopBar(
    title: String,
    onBack: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NoteZeroSpacing.s, vertical = NoteZeroSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NzIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.cd_sketch_back),
            onClick = onBack,
            modifier = Modifier.testTag("sketch_back"),
        )
        Text(
            text = title,
            style = NoteZeroType.Heading,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = NoteZeroSpacing.s)
                .testTag("sketch_title"),
        )
        NzIconButton(
            icon = Icons.Filled.Edit,
            contentDescription = stringResource(R.string.sketch_rename),
            onClick = onRename,
            modifier = Modifier.testTag("sketch_rename"),
        )
    }
}

/** Non-blocking status line under the top bar (save failure / clear confirmation). */
@Composable
internal fun SketchStatusLine(
    text: String,
    tone: SketchStatusTone,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = NoteZeroType.Caption,
        color = when (tone) {
            SketchStatusTone.ERROR -> LocalNoteZeroColors.current.dangerText
            SketchStatusTone.NOTICE -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NoteZeroSpacing.l)
            .testTag(
                when (tone) {
                    SketchStatusTone.ERROR -> "sketch_save_error"
                    SketchStatusTone.NOTICE -> "sketch_cleared_notice"
                },
            ),
    )
}

/** Semantic tone of [SketchStatusLine]. */
internal enum class SketchStatusTone { ERROR, NOTICE }

/** Tool selection row: pen, highlighter, eraser, lasso and the shape menu. */
@Composable
internal fun SketchToolRow(
    tool: SketchTool,
    onToolSelected: (SketchTool) -> Unit,
    onShapeSelected: (SketchShapeKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    var shapeMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = NoteZeroSpacing.m),
        horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SketchToolChip(
            label = stringResource(R.string.sketch_tool_pen),
            selected = tool == SketchTool.PEN,
            tag = "sketch_tool_pen",
            onClick = { onToolSelected(SketchTool.PEN) },
        )
        SketchToolChip(
            label = stringResource(R.string.sketch_tool_highlighter),
            selected = tool == SketchTool.HIGHLIGHTER,
            tag = "sketch_tool_highlighter",
            onClick = { onToolSelected(SketchTool.HIGHLIGHTER) },
        )
        SketchToolChip(
            label = stringResource(R.string.sketch_tool_eraser),
            selected = tool == SketchTool.ERASER,
            tag = "sketch_tool_eraser",
            onClick = { onToolSelected(SketchTool.ERASER) },
        )
        SketchToolChip(
            label = stringResource(R.string.sketch_tool_lasso),
            selected = tool == SketchTool.LASSO,
            tag = "sketch_tool_lasso",
            onClick = { onToolSelected(SketchTool.LASSO) },
        )
        Box {
            SketchToolChip(
                label = stringResource(R.string.sketch_tool_shape),
                selected = tool == SketchTool.SHAPE,
                tag = "sketch_tool_shape",
                onClick = { shapeMenuOpen = true },
            )
            DropdownMenu(
                expanded = shapeMenuOpen,
                onDismissRequest = { shapeMenuOpen = false },
            ) {
                SketchShapeKind.entries.forEach { kind ->
                    DropdownMenuItem(
                        text = {
                            Text(text = shapeLabel(kind), style = NoteZeroType.Callout)
                        },
                        onClick = {
                            shapeMenuOpen = false
                            onShapeSelected(kind)
                        },
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .testTag(shapeTag(kind)),
                    )
                }
            }
        }
    }
}

@Composable
private fun SketchToolChip(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label, style = NoteZeroType.Caption) },
        modifier = Modifier
            .heightIn(min = NoteZeroSpacing.minTarget)
            .testTag(tag),
    )
}

/** Ink palette (7 swatches) and pen widths (3), both horizontally scrollable. */
@Composable
internal fun SketchInkRow(
    ink: SketchInk,
    width: SketchWidth,
    onInk: (SketchInk) -> Unit,
    onWidth: (SketchWidth) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = NoteZeroSpacing.m),
        horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SketchInk.entries.forEachIndexed { index, entry ->
            InkSwatch(
                ink = entry,
                selected = ink == entry,
                tag = "sketch_colour_$index",
                onClick = { onInk(entry) },
            )
        }
        Spacer(modifier = Modifier.width(NoteZeroSpacing.s))
        SketchWidth.entries.forEachIndexed { index, entry ->
            WidthSwatch(
                width = entry,
                selected = width == entry,
                tag = "sketch_width_$index",
                onClick = { onWidth(entry) },
            )
        }
    }
}

/** Paper template chips plus undo/redo/clear actions. */
@Composable
internal fun SketchPageActionsRow(
    template: SketchTemplate,
    canUndo: Boolean,
    canRedo: Boolean,
    canClear: Boolean,
    onTemplate: (SketchTemplate) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = NoteZeroSpacing.m),
        horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SketchTemplate.entries.forEach { entry ->
            FilterChip(
                selected = template == entry,
                onClick = { onTemplate(entry) },
                label = { Text(text = templateLabel(entry), style = NoteZeroType.Caption) },
                modifier = Modifier
                    .heightIn(min = NoteZeroSpacing.minTarget)
                    .testTag(templateTag(entry)),
            )
        }
        Spacer(modifier = Modifier.width(NoteZeroSpacing.s))
        NzTextButton(
            text = stringResource(R.string.sketch_undo),
            onClick = onUndo,
            enabled = canUndo,
            modifier = Modifier.testTag("sketch_undo"),
        )
        NzTextButton(
            text = stringResource(R.string.sketch_redo),
            onClick = onRedo,
            enabled = canRedo,
            modifier = Modifier.testTag("sketch_redo"),
        )
        NzTextButton(
            text = stringResource(R.string.sketch_clear),
            onClick = onClear,
            enabled = canClear,
            modifier = Modifier.testTag("sketch_clear"),
        )
    }
}

/** Floating zoom controls pinned to the top-right corner of the page area. */
@Composable
internal fun SketchZoomOverlay(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoomInDescription = stringResource(R.string.sketch_zoom_in)
    val zoomOutDescription = stringResource(R.string.sketch_zoom_out)
    Surface(
        modifier = modifier.testTag("sketch_zoom_controls"),
        shape = NoteZeroShapes.pill,
        color = MaterialTheme.colorScheme.surface.copy(alpha = ZOOM_SURFACE_ALPHA),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = NoteZeroSpacing.xxs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NzTextButton(
                text = PLUS_GLYPH,
                onClick = onZoomIn,
                modifier = Modifier
                    .semantics { contentDescription = zoomInDescription }
                    .testTag("sketch_zoom_in"),
            )
            NzIconButton(
                icon = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.sketch_zoom_reset),
                onClick = onFit,
                modifier = Modifier.testTag("sketch_zoom_reset"),
            )
            NzTextButton(
                text = MINUS_GLYPH,
                onClick = onZoomOut,
                modifier = Modifier
                    .semantics { contentDescription = zoomOutDescription }
                    .testTag("sketch_zoom_out"),
            )
        }
    }
}

/** "Clear the whole drawing?" confirmation; the clear itself is undoable. */
@Composable
internal fun SketchClearDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("sketch_clear_dialog"),
        title = { Text(text = stringResource(R.string.sketch_clear), style = NoteZeroType.Heading) },
        text = {
            Text(text = stringResource(R.string.sketch_clear_confirm), style = NoteZeroType.Body)
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier
                    .heightIn(min = NoteZeroSpacing.minTarget)
                    .testTag("sketch_clear_confirm"),
            ) {
                Text(text = stringResource(R.string.sketch_clear_action), style = NoteZeroType.Button)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = NoteZeroSpacing.minTarget)
                    .testTag("sketch_clear_cancel"),
            ) {
                Text(text = stringResource(R.string.common_cancel), style = NoteZeroType.Button)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = NoteZeroShapes.sheet,
    )
}

/** Rename dialog; an empty name is allowed and displays as the untitled fallback. */
@Composable
internal fun SketchRenameDialog(
    initialTitle: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("sketch_rename_dialog"),
        title = { Text(text = stringResource(R.string.sketch_rename), style = NoteZeroType.Heading) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(MAX_TITLE_CHARS) },
                singleLine = true,
                label = {
                    Text(text = stringResource(R.string.sketch_rename_hint), style = NoteZeroType.Callout)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sketch_rename_field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(input.trim()) },
                modifier = Modifier
                    .heightIn(min = NoteZeroSpacing.minTarget)
                    .testTag("sketch_rename_save"),
            ) {
                Text(text = stringResource(R.string.sketch_rename_save), style = NoteZeroType.Button)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = NoteZeroSpacing.minTarget)
                    .testTag("sketch_rename_cancel"),
            ) {
                Text(text = stringResource(R.string.common_cancel), style = NoteZeroType.Button)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = NoteZeroShapes.sheet,
    )
}

/** One ink choice: a 48dp target with a check glyph when selected (not colour-only). */
@Composable
private fun InkSwatch(
    ink: SketchInk,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val description = stringResource(inkDescription(ink))
    val colors = LocalNoteZeroColors.current
    Box(
        modifier = Modifier
            .size(NoteZeroSpacing.minTarget)
            .clip(NoteZeroShapes.pill)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(NoteZeroShapes.pill)
                .background(SketchPalette.ink(ink))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.accentIcon else colors.fieldBorder,
                    shape = NoteZeroShapes.pill,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text(
                    text = CHECK_GLYPH,
                    style = NoteZeroType.Caption,
                    color = SketchPalette.paper,
                )
            }
        }
    }
}

/** One width choice: a dot whose size previews the stroke, 48dp target. */
@Composable
private fun WidthSwatch(
    width: SketchWidth,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val description = stringResource(widthDescription(width))
    val colors = LocalNoteZeroColors.current
    Box(
        modifier = Modifier
            .size(NoteZeroSpacing.minTarget)
            .clip(NoteZeroShapes.pill)
            .background(if (selected) colors.pillSelected else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(widthDotSize(width))
                .clip(NoteZeroShapes.pill)
                .background(MaterialTheme.colorScheme.onSurface),
        )
    }
}

@Composable
private fun shapeLabel(kind: SketchShapeKind): String = stringResource(
    when (kind) {
        SketchShapeKind.LINE -> R.string.sketch_shape_line
        SketchShapeKind.RECT -> R.string.sketch_shape_rect
        SketchShapeKind.ELLIPSE -> R.string.sketch_shape_ellipse
        SketchShapeKind.ARROW -> R.string.sketch_shape_arrow
    },
)

@Composable
private fun templateLabel(template: SketchTemplate): String = stringResource(
    when (template) {
        SketchTemplate.BLANK -> R.string.sketch_template_blank
        SketchTemplate.RULED -> R.string.sketch_template_ruled
        SketchTemplate.GRID -> R.string.sketch_template_grid
        SketchTemplate.DOTTED -> R.string.sketch_template_dotted
    },
)

internal fun shapeTag(kind: SketchShapeKind): String = when (kind) {
    SketchShapeKind.LINE -> "sketch_shape_line"
    SketchShapeKind.RECT -> "sketch_shape_rect"
    SketchShapeKind.ELLIPSE -> "sketch_shape_ellipse"
    SketchShapeKind.ARROW -> "sketch_shape_arrow"
}

internal fun templateTag(template: SketchTemplate): String = when (template) {
    SketchTemplate.BLANK -> "sketch_template_blank"
    SketchTemplate.RULED -> "sketch_template_ruled"
    SketchTemplate.GRID -> "sketch_template_grid"
    SketchTemplate.DOTTED -> "sketch_template_dotted"
}

@StringRes
private fun inkDescription(ink: SketchInk): Int = when (ink) {
    SketchInk.BLACK -> R.string.cd_drawing_ink_black
    SketchInk.GREY -> R.string.cd_drawing_ink_grey
    SketchInk.RED -> R.string.cd_drawing_ink_red
    SketchInk.ORANGE -> R.string.cd_drawing_ink_orange
    SketchInk.GREEN -> R.string.cd_drawing_ink_green
    SketchInk.BLUE -> R.string.cd_drawing_ink_blue
    SketchInk.PURPLE -> R.string.cd_drawing_ink_purple
}

@StringRes
private fun widthDescription(width: SketchWidth): Int = when (width) {
    SketchWidth.THIN -> R.string.cd_drawing_width_thin
    SketchWidth.MEDIUM -> R.string.cd_drawing_width_medium
    SketchWidth.BOLD -> R.string.cd_drawing_width_bold
}

private fun widthDotSize(width: SketchWidth) = when (width) {
    SketchWidth.THIN -> 8.dp
    SketchWidth.MEDIUM -> 15.dp
    SketchWidth.BOLD -> 24.dp
}

private const val ZOOM_SURFACE_ALPHA = 0.9f
private const val PLUS_GLYPH = "+"
private const val MINUS_GLYPH = "\u2212"
private const val CHECK_GLYPH = "\u2713"
private const val MAX_TITLE_CHARS = 512
