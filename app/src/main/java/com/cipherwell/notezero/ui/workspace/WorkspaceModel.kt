package com.cipherwell.notezero.ui.workspace

import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.ui.locks.LockVisibility
import com.cipherwell.notezero.ui.notes.FolderRowModel

/** The five kinds of record the universal workspace can host. */
internal enum class WorkspaceType { NOTE, CODE, PDF, BOOK, SKETCH }

/** Type filter chips of the workspace top bar. */
internal enum class WorkspaceFilter { ALL, NOTES, CODE, PDFS, BOOKS, DRAWINGS }

/** Sort order of the workspace list; [UPDATED] is the default. */
internal enum class WorkspaceSort { UPDATED, NAME }

/**
 * One visible workspace row: a decrypted [Item] plus the derived display/search metadata.
 *
 * A row is only ever produced for an item that passed the fail-closed visibility gate
 * (not trashed, not locked+unverified, not inside a hidden locked folder). Masked content
 * therefore never reaches this model — there is no "masked row" state in the workspace,
 * unlike the notes list, because the contract excludes those items entirely.
 */
internal data class WorkspaceRow(
    val item: Item,
    val type: WorkspaceType,
    /** Resolved name of the item's folder; null at the vault root (rendered as "No folder"). */
    val folderName: String?,
    val attachmentCount: Int,
    val attachmentBytes: Long,
    /** EPUB author from the encrypted reading state; metadata only, never a title substitute. */
    val author: String?,
    /** Short body excerpt around a search match; null when the query did not hit the body. */
    val snippet: String? = null,
) {
    val id: String get() = item.id
    val title: String get() = item.title
    val locked: Boolean get() = item.locked
    val updatedAt: Long get() = item.updatedAt
    val attachmentNames: List<String> get() = item.attachments.map { it.displayName }
}

/** Content-free storage totals: visible items, visible attachment refs, their summed plaintext size. */
internal data class WorkspaceStorageSummary(
    val items: Int,
    val attachments: Int,
    val attachmentBytes: Long,
)

/**
 * Everything the workspace list needs for one frame. Built by [WorkspaceQuery.build] from the
 * decrypted index only; no disk walk and no extra repository API.
 *
 * [allRows] is the fail-closed visible set before the type filter / search (sorted), so the
 * storage summary and the "there are files but none match" decision are not distorted by the
 * current view. [rows] is what the list renders.
 */
internal data class WorkspaceIndex(
    val rows: List<WorkspaceRow> = emptyList(),
    val allRows: List<WorkspaceRow> = emptyList(),
    val storage: WorkspaceStorageSummary = WorkspaceStorageSummary(0, 0, 0),
    val filter: WorkspaceFilter = WorkspaceFilter.ALL,
    val query: String = "",
    val sort: WorkspaceSort = WorkspaceSort.UPDATED,
) {
    /** True when the vault has no visible record at all (empty-state copy). */
    val isEmpty: Boolean get() = allRows.isEmpty()

    /** True when there are visible records but the filter/search shows none. */
    val hasNoMatches: Boolean get() = allRows.isNotEmpty() && rows.isEmpty()

    /** Ids of the rows currently rendered, in display order. */
    val visibleIds: List<String> get() = rows.map { it.id }
}

/**
 * Pure, Compose-free query logic for the universal workspace: fail-closed visibility, type
 * filtering, case-insensitive search over decrypted metadata (title/body/tags/attachment file
 * names/EPUB author), deterministic sorting, storage totals, selection set algebra and the
 * flattening used by the existing "move to folder" dialog.
 *
 * Nothing here logs, caches or persists content, and every function is a plain JVM function so
 * the rules are unit-testable without Robolectric.
 */
internal object WorkspaceQuery {

    /** Longest body excerpt rendered under a row for a body/code match. */
    const val MAX_SNIPPET_CHARS: Int = 96

    /** Characters of leading context shown before the first match inside a snippet. */
    private const val SNIPPET_LEAD_CHARS: Int = 32

    /** Depth guard mirrors the notes/code folder flatteners (cycle-safe). */
    private const val MAX_FOLDER_DEPTH: Int = 32

    private val NEWEST_FIRST: Comparator<WorkspaceRow> =
        compareByDescending<WorkspaceRow> { it.updatedAt }
            .thenBy { it.title.lowercase() }
            .thenBy { it.id }

    private val BY_NAME: Comparator<WorkspaceRow> =
        compareBy<WorkspaceRow> { it.title.lowercase() }
            .thenByDescending { it.updatedAt }
            .thenBy { it.id }

    private val WHITESPACE = Regex("\\s+")

    /** Maps an [ItemKind] onto the workspace type surface. */
    fun typeOf(kind: ItemKind): WorkspaceType = when (kind) {
        ItemKind.NOTE -> WorkspaceType.NOTE
        ItemKind.CODE_FILE -> WorkspaceType.CODE
        ItemKind.PDF_FILE -> WorkspaceType.PDF
        ItemKind.BOOK_FILE -> WorkspaceType.BOOK
        ItemKind.SKETCH_FILE -> WorkspaceType.SKETCH
    }

    /** True when [type] passes [filter]. */
    fun matchesFilter(type: WorkspaceType, filter: WorkspaceFilter): Boolean = when (filter) {
        WorkspaceFilter.ALL -> true
        WorkspaceFilter.NOTES -> type == WorkspaceType.NOTE
        WorkspaceFilter.CODE -> type == WorkspaceType.CODE
        WorkspaceFilter.PDFS -> type == WorkspaceType.PDF
        WorkspaceFilter.BOOKS -> type == WorkspaceType.BOOK
        WorkspaceFilter.DRAWINGS -> type == WorkspaceType.SKETCH
    }

    /**
     * Ids of every folder that must stay invisible: locked+unverified folders and every
     * descendant, fail-closed via [LockVisibility.hiddenFolderIds].
     */
    fun hiddenFolderIds(folders: List<Folder>, verifiedLockedIds: Set<String>): Set<String> =
        LockVisibility.hiddenFolderIds(folders, verifiedLockedIds)

    /**
     * Resolves display names for folders whose content the session may show. Hidden folder
     * names never enter this map, so a row can never leak a locked folder path even if an
     * item unexpectedly references one (the item itself is excluded before lookup).
     */
    fun visibleFolderNames(
        folders: List<Folder>,
        verifiedLockedIds: Set<String>,
    ): Map<String, String> {
        val hidden = hiddenFolderIds(folders, verifiedLockedIds)
        return folders.asSequence()
            .filterNot { it.id in hidden }
            .associate { it.id to it.name }
    }

    /**
     * Builds the fail-closed base rows: every non-trashed item that is not masked and not
     * inside a hidden locked folder. This is the ONLY entry point that creates rows, so the
     * gate can never be forgotten by the screen.
     */
    fun visibleRows(
        items: List<Item>,
        folders: List<Folder>,
        verifiedLockedIds: Set<String>,
    ): List<WorkspaceRow> {
        val hidden = hiddenFolderIds(folders, verifiedLockedIds)
        val folderNames = visibleFolderNames(folders, verifiedLockedIds)
        val rows = ArrayList<WorkspaceRow>(items.size)
        for (item in items) {
            if (item.trashed) continue
            val folderId = item.folderId
            if (folderId != null && folderId in hidden) continue
            if (LockVisibility.isItemMasked(item, verifiedLockedIds)) continue
            rows += rowOf(item, folderNames[folderId])
        }
        return rows
    }

    private fun rowOf(item: Item, folderName: String?): WorkspaceRow {
        var attachmentBytes = 0L
        for (attachment in item.attachments) {
            attachmentBytes += attachment.sizeBytes.coerceAtLeast(0L)
        }
        return WorkspaceRow(
            item = item,
            type = typeOf(item.kind),
            folderName = folderName,
            attachmentCount = item.attachments.size,
            attachmentBytes = attachmentBytes,
            author = item.readingState?.author?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** Sums the visible attachment refs; no disk walk, no repository call. */
    fun storageSummary(rows: List<WorkspaceRow>): WorkspaceStorageSummary {
        var attachments = 0
        var bytes = 0L
        for (row in rows) {
            attachments += row.attachmentCount
            bytes += row.attachmentBytes
        }
        return WorkspaceStorageSummary(
            items = rows.size,
            attachments = attachments,
            attachmentBytes = bytes,
        )
    }

    /**
     * Case-insensitive substring match over decrypted metadata: title, body, tags, the
     * encrypted attachments' display names (the "file name") and the EPUB author. The empty
     * query matches everything. Only rows that already passed the visibility gate are passed
     * here, so masked content can never contribute a match.
     */
    fun matchesQuery(row: WorkspaceRow, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        val item = row.item
        if (item.title.contains(needle, ignoreCase = true)) return true
        if (item.body.contains(needle, ignoreCase = true)) return true
        if (item.tags.any { it.contains(needle, ignoreCase = true) }) return true
        if (item.attachments.any { it.displayName.contains(needle, ignoreCase = true) }) return true
        if (row.author?.contains(needle, ignoreCase = true) == true) return true
        return false
    }

    /**
     * Short single-line excerpt around the first case-insensitive occurrence of [query] in
     * [text], bounded to roughly [maxLength]. Returns null for a blank query or no hit, so a
     * title-only match renders no body snippet. Never called for masked content.
     */
    fun searchSnippet(
        text: String,
        query: String,
        maxLength: Int = MAX_SNIPPET_CHARS,
    ): String? {
        val needle = query.trim()
        if (needle.isEmpty() || text.isEmpty()) return null
        val collapsed = text.replace(WHITESPACE, " ").trim()
        if (collapsed.isEmpty()) return null
        val index = collapsed.indexOf(needle, ignoreCase = true)
        if (index < 0) return null
        val limit = maxLength.coerceAtLeast(needle.length + 2)
        var start = (index - SNIPPET_LEAD_CHARS).coerceAtLeast(0)
        var end = (start + limit).coerceAtMost(collapsed.length)
        if (end < collapsed.length) {
            // Prefer a word boundary so the excerpt does not cut a word in the middle.
            val space = collapsed.lastIndexOf(' ', end)
            if (space > index + needle.length) end = space
        }
        if (start > 0) {
            // Do not start mid-word either.
            val nextSpace = collapsed.indexOf(' ', start)
            if (nextSpace in 0 until index) start = nextSpace + 1
        }
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < collapsed.length) "…" else ""
        val excerpt = collapsed.substring(start, end).trim()
        if (excerpt.isEmpty()) return null
        return prefix + excerpt + suffix
    }

    /** Applies the search, attaching a body/code snippet to rows matched through the body. */
    fun search(rows: List<WorkspaceRow>, query: String): List<WorkspaceRow> {
        val needle = query.trim()
        if (needle.isEmpty()) return rows
        return rows.mapNotNull { row ->
            if (!matchesQuery(row, needle)) return@mapNotNull null
            val snippet = searchSnippet(row.item.body, needle)
            if (snippet == null) row else row.copy(snippet = snippet)
        }
    }

    /** Applies the type filter chip. [WorkspaceFilter.ALL] keeps every row. */
    fun filter(rows: List<WorkspaceRow>, filter: WorkspaceFilter): List<WorkspaceRow> =
        if (filter == WorkspaceFilter.ALL) {
            rows
        } else {
            rows.filter { matchesFilter(it.type, filter) }
        }

    /** Deterministic ordering: last-updated desc (default) or title A-Z. */
    fun sort(rows: List<WorkspaceRow>, sort: WorkspaceSort): List<WorkspaceRow> =
        when (sort) {
            WorkspaceSort.UPDATED -> rows.sortedWith(NEWEST_FIRST)
            WorkspaceSort.NAME -> rows.sortedWith(BY_NAME)
        }

    /**
     * Full frame build: visible rows first (fail-closed), then sort, type filter and search.
     * The storage summary is computed from the whole visible set, so it does not change while
     * the user filters or searches.
     */
    fun build(
        items: List<Item>,
        folders: List<Folder>,
        verifiedLockedIds: Set<String>,
        filter: WorkspaceFilter,
        query: String,
        sort: WorkspaceSort,
    ): WorkspaceIndex {
        val visible = visibleRows(items, folders, verifiedLockedIds)
        val sorted = sort(visible, sort)
        val filtered = filter(sorted, filter)
        val searched = search(filtered, query)
        return WorkspaceIndex(
            rows = searched,
            allRows = sorted,
            storage = storageSummary(visible),
            filter = filter,
            query = query,
            sort = sort,
        )
    }

    // ---- Selection set algebra (pure; the screen holds the state) ------------

    /** True when [id] is currently selected. */
    fun isSelected(selection: Set<String>, id: String): Boolean = id in selection

    /** Adds [id] to (or removes it from) the selection. */
    fun toggle(selection: Set<String>, id: String): Set<String> =
        if (id in selection) selection - id else selection + id

    /** Selects exactly the rows the user can currently see (select-all). */
    fun selectAll(rows: List<WorkspaceRow>): Set<String> =
        rows.mapTo(LinkedHashSet()) { it.id }

    /**
     * Effective selection for a bulk action: only currently visible row ids survive. Stale ids
     * (the item was trashed/locked elsewhere) are dropped so a bulk call can never touch
     * something the user cannot see.
     */
    fun selectedVisibleIds(
        selection: Set<String>,
        visibleRows: List<WorkspaceRow>,
    ): List<String> {
        if (selection.isEmpty()) return emptyList()
        val visible = visibleRows.mapTo(mutableSetOf()) { it.id }
        return selection.filter { it in visible }
    }

    /** Drops selected ids that are no longer visible after a repository refresh. */
    fun retainVisible(selection: Set<String>, visibleRows: List<WorkspaceRow>): Set<String> {
        if (selection.isEmpty()) return selection
        val visible = visibleRows.mapTo(mutableSetOf()) { it.id }
        return selection.intersect(visible)
    }

    // ---- Move-to-folder choices --------------------------------------------

    /**
     * Pre-order flattening of the folder tree for the existing
     * [com.cipherwell.notezero.ui.notes.components.MoveToFolderDialog].
     *
     * Hidden locked+unverified folders (and their descendants) are excluded entirely: their
     * names are content and must never be offered as a move target. Orphans are surfaced at
     * the root and cycles cannot loop forever.
     */
    fun folderChoices(
        folders: List<Folder>,
        verifiedLockedIds: Set<String>,
        items: List<Item> = emptyList(),
    ): List<FolderRowModel> {
        val hidden = hiddenFolderIds(folders, verifiedLockedIds)
        val visible = folders.filterNot { it.id in hidden }
        val byId = visible.associateBy { it.id }
        val childrenByParent = visible.groupBy { it.parentId }
        val itemCounts = HashMap<String, Int>(visible.size)
        for (item in items) {
            if (item.trashed) continue
            val folderId = item.folderId ?: continue
            if (folderId in hidden) continue
            itemCounts[folderId] = (itemCounts[folderId] ?: 0) + 1
        }
        val result = ArrayList<FolderRowModel>(visible.size)
        val visited = HashSet<String>(visible.size)

        fun visit(parentId: String?, depth: Int) {
            if (depth > MAX_FOLDER_DEPTH) return
            val children = childrenByParent[parentId].orEmpty()
                .sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }, { it.id }))
            for (folder in children) {
                if (!visited.add(folder.id)) continue
                result += FolderRowModel(
                    folder = folder,
                    depth = depth,
                    itemCount = itemCounts[folder.id] ?: 0,
                    childFolderCount = childrenByParent[folder.id].orEmpty().size,
                    masked = false,
                )
                visit(folder.id, depth + 1)
            }
        }

        visit(null, 0)
        // Orphans (parent outside the visible tree) are shown at the root.
        for (orphan in visible.filter { it.parentId != null && byId[it.parentId] == null }
            .sortedBy { it.name.lowercase() }) {
            if (!visited.add(orphan.id)) continue
            result += FolderRowModel(
                folder = orphan,
                depth = 0,
                itemCount = itemCounts[orphan.id] ?: 0,
                childFolderCount = childrenByParent[orphan.id].orEmpty().size,
                masked = false,
            )
            visit(orphan.id, 1)
        }
        // Defensive: anything unreachable (cycles) still appears at the root exactly once.
        for (remaining in visible.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))) {
            if (!visited.add(remaining.id)) continue
            result += FolderRowModel(
                folder = remaining,
                depth = 0,
                itemCount = itemCounts[remaining.id] ?: 0,
                childFolderCount = childrenByParent[remaining.id].orEmpty().size,
                masked = false,
            )
            visit(remaining.id, 1)
        }
        return result
    }
}
