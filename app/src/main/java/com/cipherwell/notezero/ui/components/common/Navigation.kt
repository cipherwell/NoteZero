package com.cipherwell.notezero.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.ui.theme.LocalNoteZeroColors
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/** One destination of the floating pill navigation. */
data class NzNavItem(
    val key: String,
    val label: String,
    val contentDescription: String = label,
)

/**
 * One UI-style floating pill navigation.
 *
 * Each item is a >= 48dp target with the Tab role, a selected state exposed through
 * semantics (not colour alone) and an explicit content description. The caller owns
 * the selected key so it can persist navigation state.
 */
@Composable
fun NoteZeroPillNav(
    items: List<NzNavItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(items.size in 2..5) { "pill nav supports 2..5 destinations" }
    val colors = LocalNoteZeroColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = NoteZeroShapes.pill,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = NoteZeroSpacing.pillNavHeight)
                .padding(NoteZeroSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = item.key == selectedKey
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .clip(NoteZeroShapes.pill)
                        .background(if (selected) colors.pillSelected else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(item.key) })
                        .semantics { contentDescription = item.contentDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.label,
                        style = NoteZeroType.Caption,
                        color = if (selected) colors.pillSelectedContent else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = NoteZeroSpacing.xs),
                    )
                }
            }
        }
    }
}

/** Pill-shaped secondary surface used for search fields and chips rows. */
@Composable
fun NzPillSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}
