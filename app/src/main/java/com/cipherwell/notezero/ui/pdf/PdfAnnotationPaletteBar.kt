package com.cipherwell.notezero.ui.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.R
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors

/**
 * Annotate-mode tool palette: one horizontally scrollable row of tool chips + undo/redo, one row
 * of colours and stroke widths, then the hint. Shown only while annotate mode is active.
 *
 * Every control carries a stable tag (`pdf_tool_*`, `pdf_color_0..5`, `pdf_width_0..2`) and is at
 * least 48dp tall; the rows scroll horizontally so large-font settings and narrow screens keep
 * every control reachable instead of squeezing the labels.
 */
@Composable
internal fun PdfAnnotationPaletteBar(
    tool: PdfAnnotationTool,
    colorIndex: Int,
    widthIndex: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    onToolSelected: (PdfAnnotationTool) -> Unit,
    onColorSelected: (Int) -> Unit,
    onWidthSelected: (Int) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("pdf_annotate_palette"),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = NoteZeroShapes.sheet,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(
                start = NoteZeroSpacing.m,
                end = NoteZeroSpacing.m,
                top = NoteZeroSpacing.s,
                bottom = NoteZeroSpacing.s,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolChip(
                    label = stringResource(R.string.pdf_tool_ink),
                    tag = "pdf_tool_ink",
                    selected = tool == PdfAnnotationTool.INK,
                    onClick = { onToolSelected(PdfAnnotationTool.INK) },
                )
                ToolChip(
                    label = stringResource(R.string.pdf_tool_highlighter),
                    tag = "pdf_tool_highlighter",
                    selected = tool == PdfAnnotationTool.HIGHLIGHTER,
                    onClick = { onToolSelected(PdfAnnotationTool.HIGHLIGHTER) },
                )
                ToolChip(
                    label = stringResource(R.string.pdf_tool_rect),
                    tag = "pdf_tool_rect",
                    selected = tool == PdfAnnotationTool.RECT,
                    onClick = { onToolSelected(PdfAnnotationTool.RECT) },
                )
                ToolChip(
                    label = stringResource(R.string.pdf_tool_eraser),
                    tag = "pdf_tool_eraser",
                    selected = tool == PdfAnnotationTool.ERASER,
                    onClick = { onToolSelected(PdfAnnotationTool.ERASER) },
                )
                ToolChip(
                    label = stringResource(R.string.pdf_tool_note),
                    tag = "pdf_tool_note",
                    selected = tool == PdfAnnotationTool.NOTE,
                    onClick = { onToolSelected(PdfAnnotationTool.NOTE) },
                )
                TextButton(
                    onClick = onUndo,
                    enabled = canUndo,
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("pdf_tool_undo"),
                ) {
                    Text(stringResource(R.string.pdf_tool_undo), style = NoteZeroType.Button)
                }
                TextButton(
                    onClick = onRedo,
                    enabled = canRedo,
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag("pdf_tool_redo"),
                ) {
                    Text(stringResource(R.string.pdf_tool_redo), style = NoteZeroType.Button)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val colourLabel = stringResource(R.string.pdf_tool_colour)
                for (index in 0 until PdfAnnotationPalette.PALETTE_SIZE) {
                    ColourSwatch(
                        index = index,
                        colour = PdfAnnotationPalette.color(index),
                        contentDescription = "$colourLabel ${index + 1}",
                        selected = colorIndex == index,
                        onClick = { onColorSelected(index) },
                    )
                }
                val widthLabel = stringResource(R.string.pdf_tool_width)
                for (index in 0 until PdfAnnotationPalette.WIDTH_LEVELS) {
                    WidthOption(
                        index = index,
                        contentDescription = "$widthLabel ${index + 1}",
                        colour = PdfAnnotationPalette.color(colorIndex),
                        selected = widthIndex == index,
                        onClick = { onWidthSelected(index) },
                    )
                }
            }

            Text(
                text = stringResource(R.string.pdf_annotate_hint),
                style = NoteZeroType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = NoteZeroSpacing.xxs, start = NoteZeroSpacing.s)
                    .testTag("pdf_annotate_hint"),
            )
        }
    }
}

@Composable
private fun ToolChip(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = NoteZeroType.Callout) },
        modifier = Modifier
            .heightIn(min = NoteZeroSpacing.minTarget)
            .testTag(tag),
    )
}

@Composable
private fun ColourSwatch(
    index: Int,
    colour: Color,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colours = LocalNoteZeroColors.current
    Box(
        modifier = Modifier
            .size(NoteZeroSpacing.minTarget)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { this.contentDescription = contentDescription }
            .testTag("pdf_color_$index"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colour)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) colours.accentIcon else colours.fieldBorder,
                    shape = CircleShape,
                ),
        )
    }
}

@Composable
private fun WidthOption(
    index: Int,
    contentDescription: String,
    colour: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colours = LocalNoteZeroColors.current
    Box(
        modifier = Modifier
            .size(NoteZeroSpacing.minTarget)
            .clip(RoundedCornerShape(NoteZeroSpacing.s))
            .background(
                if (selected) colours.pillSelected else Color.Transparent,
            )
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { this.contentDescription = contentDescription }
            .testTag("pdf_width_$index"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(30.dp)
                .heightIn(min = (2 + index * 3).dp, max = (2 + index * 3).dp)
                .clip(CircleShape)
                .background(if (selected) colours.pillSelectedContent else colour),
        )
    }
}
