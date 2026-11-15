package com.cipherwell.notezero.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * Filled primary action. 52dp tall (>= 48dp WCAG 2.2 target), pill shaped, and the
 * accent/on-accent pair passes WCAG AA at normal text size in both themes.
 *
 * [loading] keeps the label mounted (so the accessible name is stable) and disables
 * re-entry; it announces "Working" through the state description.
 */
@Composable
fun NzPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = LocalNoteZeroColors.current
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = NoteZeroSpacing.controlHeight)
            .semantics { if (loading) stateDescription = "Working" },
        enabled = enabled && !loading,
        shape = NoteZeroShapes.pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accentFill,
            contentColor = colors.onAccentFill,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(
            horizontal = NoteZeroSpacing.xxl,
            vertical = NoteZeroSpacing.m,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.width(NoteZeroSpacing.s))
        }
        Text(text = text, style = NoteZeroType.Button, textAlign = TextAlign.Center)
    }
}

/** Outlined secondary action; accent text on surface passes AA in both themes. */
@Composable
fun NzSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalNoteZeroColors.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = NoteZeroSpacing.controlHeight),
        enabled = enabled,
        shape = NoteZeroShapes.pill,
        border = BorderStroke(1.dp, colors.fieldBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accentText),
        contentPadding = PaddingValues(
            horizontal = NoteZeroSpacing.xxl,
            vertical = NoteZeroSpacing.m,
        ),
    ) {
        Text(text = text, style = NoteZeroType.Button, textAlign = TextAlign.Center)
    }
}

/** Destructive action: error container + on-error text, AA in both themes. */
@Composable
fun NzDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = NoteZeroSpacing.controlHeight),
        enabled = enabled,
        shape = NoteZeroShapes.pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        contentPadding = PaddingValues(
            horizontal = NoteZeroSpacing.xxl,
            vertical = NoteZeroSpacing.m,
        ),
    ) {
        Text(text = text, style = NoteZeroType.Button, textAlign = TextAlign.Center)
    }
}

/** Low-emphasis text action; 48dp minimum target even though it looks like a link. */
@Composable
fun NzTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = NoteZeroSpacing.minTarget),
        enabled = enabled,
    ) {
        Text(text = text, style = NoteZeroType.Callout, color = LocalNoteZeroColors.current.accentText)
    }
}

/**
 * Icon-only button. [contentDescription] is mandatory so screen readers and the
 * instrumentation tests can address it; the hit target is never below 48dp.
 */
@Composable
fun NzIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalNoteZeroColors.current.accentIcon,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.sizeIn(minWidth = NoteZeroSpacing.minTarget, minHeight = NoteZeroSpacing.minTarget),
        enabled = enabled,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
    }
}

/** Standard back affordance used by the settings/recovery routes. */
@Composable
fun NzBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    NzIconButton(
        icon = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "Back",
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * Selectable pill chip (auto-lock minutes, theme mode). Selection is exposed through
 * the `selected` semantics from [selectable] AND a visible check glyph, so state is
 * never communicated by colour alone.
 */
@Composable
fun NzSelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalNoteZeroColors.current
    Surface(
        modifier = modifier
            .heightIn(min = NoteZeroSpacing.minTarget)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = NoteZeroShapes.pill,
        color = if (selected) colors.pillSelected else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) colors.pillSelectedContent else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, if (selected) colors.pillSelectedContent else colors.fieldBorder),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Text(
                    text = "✓",
                    style = NoteZeroType.Callout,
                    modifier = Modifier.padding(start = NoteZeroSpacing.l, end = NoteZeroSpacing.xs),
                )
            }
            Text(
                text = label,
                style = NoteZeroType.Callout,
                modifier = Modifier.padding(
                    start = if (selected) NoteZeroSpacing.xxs else NoteZeroSpacing.l,
                    end = NoteZeroSpacing.l,
                    top = NoteZeroSpacing.s,
                    bottom = NoteZeroSpacing.s,
                ),
            )
        }
    }
}

/** Primary floating action button (56dp, above the pill navigation). */
@Composable
fun NzFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNoteZeroColors.current
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        containerColor = colors.accentFill,
        contentColor = colors.onAccentFill,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}
