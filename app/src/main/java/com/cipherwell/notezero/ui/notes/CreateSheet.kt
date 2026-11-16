package com.cipherwell.notezero.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.R
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * Bottom sheet behind the Notes "+" button: choose what to create.
 *
 * Rendered as an **in-window overlay** (not a `Dialog`): on edge-to-edge Android 15+ devices a
 * full-screen dialog window can be laid out taller than the visible display, which pushed the
 * last option under the gesture bar in device QA. An overlay inside the app window inherits the
 * real window bounds and insets, so the sheet always sits above the navigation bar; the content
 * is also scrollable and capped, so a large font scale can never hide the PDF option.
 *
 * Dismissal: tapping the scrim or the system back (the shell's BackHandler path is unchanged;
 * the scrim is a full-size clickable target). The glyph is decorative and hidden from
 * accessibility because the row text already carries the meaning.
 */
@Composable
fun CreateSheet(
    onDismiss: () -> Unit,
    onCreateNote: () -> Unit,
    onCreateDrawing: () -> Unit,
    onCreateCode: () -> Unit,
    onCreatePdf: () -> Unit,
) {
    BackHandler { onDismiss() }
    val colors = LocalNoteZeroColors.current
    val interaction = remember { MutableInteractionSource() }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("create_overlay"),
    ) {
        // Scrim: dismisses the sheet, opaque token scrim so no list content flashes through.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onDismiss,
                )
                .testTag("create_scrim"),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(NoteZeroSpacing.l)
                .heightIn(max = maxHeight * 0.92f)
                .testTag("create_sheet"),
            shape = NoteZeroShapes.sheet,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = NoteZeroSpacing.l),
            ) {
                Text(
                    text = stringResource(R.string.create_sheet_title),
                    style = NoteZeroType.Heading,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = NoteZeroSpacing.l),
                )
                Spacer(Modifier.size(NoteZeroSpacing.s))
                CreateOption(
                    glyph = "≡",
                    title = stringResource(R.string.create_note),
                    subtitle = stringResource(R.string.create_note_subtitle),
                    tag = "create_note",
                    onClick = onCreateNote,
                )
                CreateOption(
                    glyph = "✎",
                    title = stringResource(R.string.create_drawing),
                    subtitle = stringResource(R.string.create_drawing_subtitle),
                    tag = "create_drawing",
                    onClick = onCreateDrawing,
                )
                CreateOption(
                    glyph = "</>",
                    title = stringResource(R.string.create_code),
                    subtitle = stringResource(R.string.create_code_subtitle),
                    tag = "create_code",
                    onClick = onCreateCode,
                )
                CreateOption(
                    glyph = "PDF",
                    title = stringResource(R.string.create_pdf),
                    subtitle = stringResource(R.string.create_pdf_subtitle),
                    tag = "create_pdf",
                    onClick = onCreatePdf,
                )
            }
        }
    }
}

@Composable
private fun CreateOption(
    glyph: String,
    title: String,
    subtitle: String,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NoteZeroSpacing.minTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.s)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier
                .size(40.dp)
                .clip(NoteZeroShapes.pill)
                .clearAndSetSemantics { },
            shape = NoteZeroShapes.pill,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = glyph,
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(NoteZeroSpacing.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = NoteZeroType.Callout,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = NoteZeroType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
