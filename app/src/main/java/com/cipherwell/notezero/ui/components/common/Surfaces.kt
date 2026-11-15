package com.cipherwell.notezero.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/** Squircle card container used for grouped settings rows and info panels. */
@Composable
fun NzCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = NoteZeroShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(NoteZeroSpacing.l), content = content)
    }
}

/** Section heading inside settings/long forms (announced as a heading). */
@Composable
fun NzSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = NoteZeroType.Heading,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier
            .padding(top = NoteZeroSpacing.xl, bottom = NoteZeroSpacing.s)
            .semantics { heading() },
    )
}

/**
 * One UI-style large title used at the top of the setup/unlock/recovery screens.
 * Kept as plain text (not a collapsing toolbar) because those screens are short.
 */
@Composable
fun NzLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = NoteZeroType.LargeTitle,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = NoteZeroType.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = NoteZeroSpacing.s),
            )
        }
    }
}

/** Centred empty/zero state with an optional single action. */
@Composable
fun NzEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(NoteZeroSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = NoteZeroType.Heading,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(NoteZeroSpacing.s))
        Text(
            text = message,
            style = NoteZeroType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(NoteZeroSpacing.l))
            NzSecondaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

enum class NzBannerTone { INFO, WARNING, DANGER, SUCCESS }

/**
 * Inline status banner. The message text always carries the meaning, so the tone colour
 * is decorative and never the only signal (WCAG 1.4.1). Announced politely.
 */
@Composable
fun NzInfoBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: NzBannerTone = NzBannerTone.INFO,
) {
    val colors = LocalNoteZeroColors.current
    val (container: Color, content: Color, glyph: String) = when (tone) {
        NzBannerTone.INFO -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
            "i",
        )
        NzBannerTone.WARNING -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            colors.warningText,
            "!",
        )
        NzBannerTone.DANGER -> Triple(colors.dangerContainer, colors.onDangerContainer, "!")
        NzBannerTone.SUCCESS -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            colors.successText,
            "✓",
        )
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = NoteZeroShapes.row,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = NoteZeroSpacing.minTarget)
                .padding(horizontal = NoteZeroSpacing.l, vertical = NoteZeroSpacing.m),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = glyph,
                style = NoteZeroType.Callout,
                color = content,
                modifier = Modifier
                    .size(NoteZeroSpacing.xl)
                    .clearAndSetSemantics { },
            )
            Spacer(Modifier.width(NoteZeroSpacing.s))
            Text(
                text = text,
                style = NoteZeroType.Callout,
                color = content,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
