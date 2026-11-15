package com.cipherwell.notezero.ui.notes

import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.ui.locks.LockVisibility

/** Top-level destination inside the notes surface. Settings is rendered by ui-shell. */
enum class NotesSection {
    NOTES,
    FOLDERS,
    SEARCH,
    TRASH,
}

/**
 * User-controlled list state: filter + folder navigation. Kept separate from the
 * repository flows so the screen can restore it across configuration changes.
 */
data class NotesControls(
    val section: NotesSection = NotesSection.NOTES,
    val query: String = "",
    val activeTag: String? = null,
    val favoritesOnly: Boolean = false,
    val currentFolderId: String? = null,
)

/** A decrypted note plus its resolved folder path, ready to render. */
data class NoteRowModel(
    val item: Item,
    /** Null for masked rows: a locked note must not leak its folder path. */
    val folderPath: String?,
    /**
     * True when the note is locked and this session has not re-authenticated for it. The
     * [item] is then a content-free mask (see [LockVisibility.maskItem]) and the row must
     * not be opened in the editor.
     */
    val masked: Boolean = false,
)

/** A folder as rendered in the Folders tab. */
data class FolderRowModel(
    val folder: Folder,
    val depth: Int,
    val itemCount: Int,
    val childFolderCount: Int,
    /** True when the folder is locked+unverified: name/counts are masked, children hidden. */
    val masked: Boolean = false,
)

/** Everything the notes list needs for one frame. Immutable, built by [NotesIndex]. */
data class NotesUiState(
    val section: NotesSection = NotesSection.NOTES,
    val query: String = "",
    val activeTag: String? = null,
    val favoritesOnly: Boolean = false,
    val currentFolderId: String? = null,
    val pinned: List<NoteRowModel> = emptyList(),
    val favorites: List<NoteRowModel> = emptyList(),
    val recent: List<NoteRowModel> = emptyList(),
    val searchResults: List<NoteRowModel> = emptyList(),
    val trash: List<NoteRowModel> = emptyList(),
    val tags: List<String> = emptyList(),
    val folders: List<FolderRowModel> = emptyList(),
    val folderItems: List<NoteRowModel> = emptyList(),
    val breadcrumb: List<Folder> = emptyList(),
    /** Whole folder tree, flattened, for "move to folder" pickers. */
    val allFolders: List<FolderRowModel> = emptyList(),
    val isUnlocked: Boolean = false,
    val totalNotes: Int = 0,
    val trashCount: Int = 0,
) {
    /** True when the Notes tab has no note to show at all (ignoring tag/tab state). */
    val hasNoNotes: Boolean get() = pinned.isEmpty() && favorites.isEmpty() && recent.isEmpty()

    /** True when the current search produced nothing. */
    val hasNoSearchResults: Boolean get() = searchResults.isEmpty()
}

/**
 * Pure list-building logic: filtering, search, pin/favourite ordering and folder
 * flattening over the decrypted in-memory index. No Android/Compose imports so it is
 * fully unit-testable.
 */
object NotesIndex {

    private const val MAX_FOLDER_DEPTH = 32

    private val NEWEST_FIRST: Comparator<Item> =
        compareByDescending<Item> { it.updatedAt }.thenByDescending { it.createdAt }

    fun build(
        items: List<Item>,
        folders: List<Folder>,
        controls: NotesControls,
        isUnlocked: Boolean,
        verifiedLockedIds: Set<String> = emptySet(),
    ): NotesUiState {
        if (!isUnlocked) {
            return NotesUiState(
                section = controls.section,
                query = controls.query,
                activeTag = controls.activeTag,
                favoritesOnly = controls.favoritesOnly,
                currentFolderId = controls.currentFolderId,
                isUnlocked = false,
            )
        }

        // Locked folders hide themselves and every descendant; locked notes are masked
        // in place. Both decisions come from the repository's memory-only session set.
        val hiddenFolderIds = LockVisibility.hiddenFolderIds(folders, verifiedLockedIds)
        val maskedFolderIds = folders
            .filter { LockVisibility.isFolderMasked(it, verifiedLockedIds) }
            .mapTo(mutableSetOf()) { it.id }
        // A locked folder itself stays visible as a mask row (it is the re-auth entry
        // point); only its descendants are hidden. A hidden non-root is never listed.
        val visibleFolders = folders.filterNot {
            it.id in hiddenFolderIds && it.id !in maskedFolderIds
        }
        val folderById = visibleFolders.associateBy { it.id }
        val contentItems = items.filterNot { item ->
            item.folderId?.let { it in hiddenFolderIds } == true
        }
        val live = contentItems.filterNot { it.trashed }
        val trashed = contentItems.filter { it.trashed }.sortedWith(NEWEST_FIRST)
        val tagKey = controls.activeTag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        // Rows are built from masked copies only: no preview/body/tag/path of a locked,
        // unverified note ever reaches the UI state. Content-derived work (search, tags)
        // uses [revealedLive], which excludes masked notes entirely.
        val displayedLive = live.map { LockVisibility.maskItem(it, verifiedLockedIds) }
        val revealedLive = live.filterNot { LockVisibility.isItemMasked(it, verifiedLockedIds) }

        val tagFilteredDisplayed = if (tagKey == null) {
            displayedLive
        } else {
            // Masked rows carry no tags, so their real tags can never match a filter.
            displayedLive.filter { item -> item.tags.any { it.trim().lowercase() == tagKey } }
        }
        val visible = if (controls.favoritesOnly) {
            tagFilteredDisplayed.filter { it.favorite }
        } else {
            tagFilteredDisplayed
        }

        val pinned = rows(visible.filter { it.pinned }.sortedWith(NEWEST_FIRST), folderById, verifiedLockedIds)
        val favorites = rows(
            visible.filter { it.favorite && !it.pinned }.sortedWith(NEWEST_FIRST),
            folderById,
            verifiedLockedIds,
        )
        val recent = rows(
            visible.filter { !it.pinned && !it.favorite }.sortedWith(NEWEST_FIRST),
            folderById,
            verifiedLockedIds,
        )

        val query = controls.query.trim()
        val searchPool = if (tagKey == null) {
            revealedLive
        } else {
            revealedLive.filter { item -> item.tags.any { it.trim().lowercase() == tagKey } }
        }.let { if (controls.favoritesOnly) it.filter { item -> item.favorite } else it }
        val searchResults = if (query.isEmpty()) {
            emptyList()
        } else {
            // Masked notes are not searchable at all (their placeholder title is not
            // indexed either): search must never confirm what a locked note contains.
            rows(
                searchPool
                    .filter { item -> matchesQuery(item, folderPathLabel(folderById, item.folderId), query) }
                    .sortedWith(NEWEST_FIRST),
                folderById,
                verifiedLockedIds,
            )
        }

        // Re-masking while inside a locked folder falls back to the root listing.
        val currentFolderId = controls.currentFolderId?.takeUnless { it in hiddenFolderIds }
        val childFolders = visibleFolders
            .filter { it.parentId == currentFolderId }
            .sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
            .map { folder ->
                val masked = LockVisibility.isFolderMasked(folder, verifiedLockedIds)
                FolderRowModel(
                    folder = LockVisibility.maskFolder(folder, verifiedLockedIds),
                    depth = 0,
                    itemCount = if (masked) 0 else live.count { it.folderId == folder.id },
                    childFolderCount = if (masked) 0 else visibleFolders.count { it.parentId == folder.id },
                    masked = masked,
                )
            }

        val folderItems = rows(
            live.filter { it.folderId == currentFolderId }
                .sortedWith(compareByDescending<Item> { it.pinned }.then(NEWEST_FIRST)),
            folderById,
            verifiedLockedIds,
        )

        return NotesUiState(
            section = controls.section,
            query = controls.query,
            activeTag = controls.activeTag,
            favoritesOnly = controls.favoritesOnly,
            currentFolderId = currentFolderId,
            pinned = pinned,
            favorites = favorites,
            recent = recent,
            searchResults = searchResults,
            trash = rows(trashed, folderById, verifiedLockedIds),
            tags = collectTags(revealedLive),
            folders = childFolders,
            folderItems = folderItems,
            breadcrumb = folderPath(visibleFolders, currentFolderId),
            // Locked+unverified folders are not valid move targets (and their names are
            // masked), so the picker only lists revealed folders.
            allFolders = flattenFolders(visibleFolders.filterNot { it.id in maskedFolderIds }),
            isUnlocked = true,
            totalNotes = live.size,
            trashCount = trashed.size,
        )
    }

    /** Case-insensitive substring search over title, tags, folder path and body. */
    fun matchesQuery(item: Item, folderPath: String?, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return item.title.contains(needle, ignoreCase = true) ||
            item.body.contains(needle, ignoreCase = true) ||
            item.tags.any { it.contains(needle, ignoreCase = true) } ||
            folderPath?.contains(needle, ignoreCase = true) == true
    }

    /** Distinct tags across the given items, case-insensitively deduplicated and sorted. */
    fun collectTags(items: List<Item>): List<String> {
        val byKey = LinkedHashMap<String, String>()
        items.forEach { item ->
            item.tags.forEach { raw ->
                val tag = raw.trim()
                if (tag.isNotEmpty()) byKey.putIfAbsent(tag.lowercase(), tag)
            }
        }
        return byKey.values.sortedBy { it.lowercase() }
    }

    /** Root -> leaf chain of folders for [folderId]; empty when the folder does not exist. */
    fun folderPath(folders: List<Folder>, folderId: String?): List<Folder> {
        if (folderId == null) return emptyList()
        val byId = folders.associateBy { it.id }
        val path = ArrayDeque<Folder>()
        var current = byId[folderId]
        var guard = 0
        while (current != null && guard <= MAX_FOLDER_DEPTH) {
            path.addFirst(current)
            current = current.parentId?.let { byId[it] }
            guard++
        }
        return path.toList()
    }

    /** "Work / Projects" label for a folder id, or null for the root. */
    fun folderPathLabel(folders: List<Folder>, folderId: String?): String? {
        if (folderId == null) return null
        return folderPathLabel(folders.associateBy { it.id }, folderId)
    }

    private fun folderPathLabel(folderById: Map<String, Folder>, folderId: String?): String? {
        if (folderId == null) return null
        val names = ArrayDeque<String>()
        var current = folderById[folderId]
        var guard = 0
        while (current != null && guard <= MAX_FOLDER_DEPTH) {
            names.addFirst(current.name)
            current = current.parentId?.let { folderById[it] }
            guard++
        }
        if (names.isEmpty()) return null
        return names.joinToString(" / ")
    }

    /**
     * Pre-order flattening of the folder tree for "move to folder" pickers. Orphaned
     * folders (parent id no longer present) are surfaced at the root instead of hidden.
     */
    fun flattenFolders(folders: List<Folder>): List<FolderRowModel> {
        val byParent = folders.groupBy { it.parentId }
        val byId = folders.associateBy { it.id }
        val result = mutableListOf<FolderRowModel>()
        val visited = mutableSetOf<String>()

        fun visit(parentId: String?, depth: Int) {
            if (depth > MAX_FOLDER_DEPTH) return
            byParent[parentId].orEmpty()
                .sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
                .forEach { folder ->
                    if (!visited.add(folder.id)) return@forEach
                    result += FolderRowModel(folder, depth, itemCount = 0, childFolderCount = 0)
                    visit(folder.id, depth + 1)
                }
        }

        visit(null, 0)
        folders.filter { it.parentId != null && byId[it.parentId] == null }
            .sortedBy { it.name.lowercase() }
            .forEach { orphan ->
                if (visited.add(orphan.id)) {
                    result += FolderRowModel(orphan, 0, itemCount = 0, childFolderCount = 0)
                    visit(orphan.id, 1)
                }
            }
        return result
    }

    private fun rows(
        items: List<Item>,
        folderById: Map<String, Folder>,
        verifiedLockedIds: Set<String>,
    ): List<NoteRowModel> = items.map { item ->
        val masked = LockVisibility.isItemMasked(item, verifiedLockedIds)
        NoteRowModel(
            item = if (masked) LockVisibility.maskItem(item, verifiedLockedIds) else item,
            folderPath = if (masked) null else folderPathLabel(folderById, item.folderId),
            masked = masked,
        )
    }
}
