package com.cipherwell.notezero.ui.code

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.code.CodeSearchMatch
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType

/**
 * Workspace search bar: query field, case-sensitivity toggle, close action and a non-secret
 * result count. The query and the results are decrypted content that stays in memory; nothing
 * here logs or persists them.
 */
@Composable
internal fun CodeSearchBar(
    query: String,
    caseSensitive: Boolean,
    resultCount: Int,
    onQueryChange: (String) -> Unit,
    onToggleCase: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(
                start = NoteZeroSpacing.s,
                end = NoteZeroSpacing.xs,
                top = NoteZeroSpacing.xxs,
                bottom = NoteZeroSpacing.xxs,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = NoteZeroType.Mono,
                    placeholder = { Text("Search code files", style = NoteZeroType.Caption) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(CodeTags.SEARCH_QUERY),
                )
                FilterChip(
                    selected = caseSensitive,
                    onClick = onToggleCase,
                    modifier = Modifier
                        .heightIn(min = NoteZeroSpacing.minTarget)
                        .testTag(CodeTags.SEARCH_CASE),
                    label = { Text("Aa", style = NoteZeroType.Caption) },
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.heightIn(min = NoteZeroSpacing.minTarget),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close search")
                }
            }
            Text(
                text = searchStatusLabel(query, resultCount),
                style = NoteZeroType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(CodeTags.SEARCH_STATUS),
            )
        }
    }
}

/**
 * Result list for the active query: one row per [CodeSearchMatch], tap opens the file at the
 * hit's line. Empty results render a friendly message inside the same container so the UI
 * never jumps between two layouts.
 */
@Composable
internal fun CodeSearchResults(
    results: List<CodeSearchMatch>,
    onOpen: (CodeSearchMatch) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (results.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .testTag(CodeTags.SEARCH_RESULTS),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No matches in code files.",
                style = NoteZeroType.Callout,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(CodeTags.SEARCH_RESULTS),
        contentPadding = PaddingValues(
            start = NoteZeroSpacing.s,
            end = NoteZeroSpacing.s,
            top = NoteZeroSpacing.s,
            bottom = NoteZeroSpacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(results, key = { index, _ -> "search-$index" }) { _, match ->
            CodeSearchResultRow(match = match, onOpen = { onOpen(match) })
        }
    }
}

/**
 * One hit: file name, 1-based line/column and a bounded one-line snippet. Derived text only —
 * test tags are structural so E2E selectors never depend on content.
 */
@Composable
private fun CodeSearchResultRow(
    match: CodeSearchMatch,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NoteZeroSpacing.minTarget)
            .clickable(onClickLabel = "Open at line ${match.line}", onClick = onOpen)
            .padding(horizontal = NoteZeroSpacing.s, vertical = NoteZeroSpacing.xs)
            .testTag(CodeTags.SEARCH_RESULT_ROW),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = match.fileName,
                    style = NoteZeroType.Mono,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = "  ${match.line}:${match.column}",
                    style = NoteZeroType.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = match.snippet,
                style = NoteZeroType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Non-secret count label; never contains the query itself. */
internal fun searchStatusLabel(query: String, resultCount: Int): String = when {
    query.isBlank() -> "Type to search every code file"
    resultCount == 0 -> "No matches"
    resultCount == 1 -> "1 match"
    else -> "$resultCount matches"
}
