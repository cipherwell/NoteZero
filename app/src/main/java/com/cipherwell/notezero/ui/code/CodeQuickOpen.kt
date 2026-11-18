package com.cipherwell.notezero.ui.code

import com.cipherwell.notezero.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.theme.NoteZeroSpacing
import com.cipherwell.notezero.ui.theme.NoteZeroType
import java.util.Locale
import androidx.compose.runtime.withFrameNanos

/**
 * One render-ready row of the quick-open file switcher. [path] carries the folder trail and
 * the file name (e.g. `Docs / Main.kt`) so title, path and extension all match the filter.
 *
 * The entry holds decrypted names only in memory, exactly like the file tree rows; it is
 * never logged or persisted.
 */
data class QuickOpenEntry(
    val itemId: String,
    val title: String,
    val path: String,
    val updatedAt: Long,
) {
    /** Lowercase extension without the dot, or an empty string for extension-less titles. */
    val extension: String
        get() = title.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
}

/**
 * Pure quick-open index/filter for code files (docs/CONTRACTS-PHASE14.md §B).
 *
 * The ViewModel feeds the already visibility-filtered in-memory index here: locked/hidden
 * files never reach this list. Order is "last updated first, then title"; filtering is a
 * case-insensitive contains over title, path and extension.
 */
object CodeQuickOpen {

    /**
     * Builds the ordered quick-open entries for [items]/[folders]. Only non-trashed
     * [ItemKind.CODE_FILE] items are listed; notes never leak into the code switcher.
     * A folder cycle is cut off at [CodeTree.MAX_DEPTH].
     */
    fun entries(items: List<Item>, folders: List<Folder>): List<QuickOpenEntry> {
        val foldersById = folders.associateBy { it.id }
        return items
            .filter { it.kind == ItemKind.CODE_FILE && !it.trashed }
            .map { item ->
                val trail = folderTrail(item.folderId, foldersById)
                QuickOpenEntry(
                    itemId = item.id,
                    title = item.title,
                    path = if (trail.isEmpty()) item.title else "$trail / ${item.title}",
                    updatedAt = item.updatedAt,
                )
            }
            .sortedWith(compareByDescending<QuickOpenEntry> { it.updatedAt }.thenBy { it.title.lowercase(Locale.ROOT) })
    }

    /** Case-insensitive filter over title, path and extension; blank query returns everything. */
    fun filter(entries: List<QuickOpenEntry>, query: String): List<QuickOpenEntry> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return entries
        val extensionNeedle = needle.removePrefix(".")
        return entries.filter { entry ->
            entry.title.lowercase(Locale.ROOT).contains(needle) ||
                entry.path.lowercase(Locale.ROOT).contains(needle) ||
                (extensionNeedle.isNotEmpty() && entry.extension == extensionNeedle)
        }
    }

    private fun folderTrail(folderId: String?, foldersById: Map<String, Folder>): String {
        val names = ArrayDeque<String>()
        var current = folderId?.let(foldersById::get)
        var depth = 0
        while (current != null && depth < CodeTree.MAX_DEPTH) {
            names.addFirst(current.name)
            current = current.parentId?.let(foldersById::get)
            depth++
        }
        return names.joinToString(" / ")
    }
}

/**
 * Quick-open dialog: type to narrow the list, tap a row (or press the IME Done action) to
 * open it. The field is focused on entry so hardware keyboards and IMEs are usable at once.
 *
 * Results are rendered newest-first; [CodeTags.QUICK_OPEN_EMPTY] replaces the list when the
 * query has no match. No content is logged.
 */
@Composable
fun QuickOpenDialog(
    entries: List<QuickOpenEntry>,
    onDismiss: () -> Unit,
    onOpen: (QuickOpenEntry) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(entries, query) { CodeQuickOpen.filter(entries, query) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(CodeTags.QUICK_OPEN_DIALOG),
        title = { Text(stringResource(R.string.code_quick_open), style = NoteZeroType.Heading) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = {
                        Text(stringResource(R.string.code_quick_open_hint), style = NoteZeroType.Callout)
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { filtered.firstOrNull()?.let(onOpen) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag(CodeTags.QUICK_OPEN_QUERY),
                )
                Spacer(Modifier.size(NoteZeroSpacing.s))
                when {
                    filtered.isEmpty() -> Text(
                        text = stringResource(R.string.code_quick_open_empty),
                        style = NoteZeroType.Callout,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(vertical = NoteZeroSpacing.m)
                            .testTag(CodeTags.QUICK_OPEN_EMPTY),
                    )

                    else -> {
                        if (query.isBlank()) {
                            Text(
                                text = stringResource(R.string.code_quick_open_recent),
                                style = NoteZeroType.FieldLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag(CodeTags.QUICK_OPEN_RECENT),
                            )
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(NoteZeroSpacing.xxs),
                        ) {
                            items(filtered, key = { it.itemId }) { entry ->
                                QuickOpenResultRow(entry = entry, onClick = { onOpen(entry) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(CodeTags.DIALOG_CANCEL),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = com.cipherwell.notezero.ui.theme.NoteZeroShapes.sheet,
    )
}

@Composable
private fun QuickOpenResultRow(entry: QuickOpenEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NoteZeroSpacing.minTarget)
            .clickable(onClick = onClick)
            .padding(vertical = NoteZeroSpacing.xs)
            .testTag(CodeTags.QUICK_OPEN_RESULT),
    ) {
        Text(
            text = entry.title,
            style = NoteZeroType.Callout,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = entry.path,
            style = NoteZeroType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
