package com.cipherwell.notezero.ui.code

import com.cipherwell.notezero.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.ui.theme.NoteZeroShapes
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/** One open file in the workspace tab strip. */
data class CodeTab(
    val itemId: String,
    /** Item title; blank falls back to the `code_tab_untitled` label when rendered. */
    val title: String,
)

/**
 * Multi-file tab model for the code workspace (docs/CONTRACTS-PHASE14.md §A).
 *
 * Pure list transitions so open/close/limit rules are plain JVM unit tests. The workspace
 * owns the state; nothing here touches the repository and nothing is logged.
 */
object CodeTabs {

    /** Maximum number of files the workspace keeps open at once. */
    const val MAX_OPEN_TABS = 8

    /** Result of opening [itemId]: the new tab list, the active tab and whether the cap hit. */
    data class Opened(
        val openFileIds: List<String>,
        val activeFileId: String?,
        val limitReached: Boolean = false,
    )

    /**
     * Opens [itemId] as the active tab. An already-open file is only re-activated (never
     * duplicated, so its existing editor state is kept). At [max] tabs a new file is
     * refused and [Opened.limitReached] is raised; the current tabs and active file stay.
     */
    fun open(
        openFileIds: List<String>,
        activeFileId: String?,
        itemId: String,
        max: Int = MAX_OPEN_TABS,
    ): Opened {
        if (itemId in openFileIds) return Opened(openFileIds, itemId)
        if (openFileIds.size >= max) {
            return Opened(openFileIds, activeFileId, limitReached = true)
        }
        return Opened(openFileIds + itemId, itemId)
    }

    /** Result of closing a tab: the remaining tabs and the tab that becomes active. */
    data class Closed(
        val openFileIds: List<String>,
        val activeFileId: String?,
    )

    /**
     * Closes [itemId]. Closing the active tab activates its left neighbour, or the next
     * tab to the right when it was the first one; closing the last active tab returns to
     * the file tree (`activeFileId == null`). Closing an inactive tab keeps the active one.
     */
    fun close(
        openFileIds: List<String>,
        activeFileId: String?,
        itemId: String,
    ): Closed {
        val index = openFileIds.indexOf(itemId)
        if (index < 0) return Closed(openFileIds, activeFileId)

        val remaining = openFileIds.filterIndexed { position, _ -> position != index }
        val nextActive = when {
            activeFileId != itemId -> activeFileId
            remaining.isEmpty() -> null
            index > 0 -> remaining[index - 1]
            else -> remaining.firstOrNull()
        }
        return Closed(remaining, nextActive)
    }
}

/**
 * Horizontally scrollable open-file strip shown above the editor while editing.
 *
 * Each tab is a 48dp target with selected semantics; its close button carries the
 * `cd_code_close_tab` content description (title included). [CodeTags.TAB]/[CodeTags.TAB_CLOSE]
 * are the stable E2E tags; titles are content, never logged.
 */
@Composable
fun CodeTabsStrip(
    tabs: List<CodeTab>,
    activeFileId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val untitled = stringResource(R.string.code_tab_untitled)
    val tabsLabel = stringResource(R.string.cd_code_tabs)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = tabsLabel }
            .testTag(CodeTags.TABS)
            .padding(horizontal = NoteZeroSpacing.xs, vertical = NoteZeroSpacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val title = tab.title.trim().ifEmpty { untitled }
            val selected = tab.itemId == activeFileId
            Surface(
                shape = NoteZeroShapes.pill,
                color = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The selectable target is the title area only; the close button stays a
                    // separate 48dp target so tapping the label can never close the tab.
                    Row(
                        modifier = Modifier
                            .heightIn(min = NoteZeroSpacing.minTarget)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { onSelect(tab.itemId) },
                            )
                            .semantics { contentDescription = title }
                            .testTag(CodeTags.TAB),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            style = NoteZeroType.Caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 160.dp)
                                .padding(start = NoteZeroSpacing.s, end = NoteZeroSpacing.xs),
                        )
                    }
                    IconButton(
                        onClick = { onClose(tab.itemId) },
                        modifier = Modifier.testTag(CodeTags.TAB_CLOSE),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cd_code_close_tab, title),
                        )
                    }
                }
            }
        }
    }
}
