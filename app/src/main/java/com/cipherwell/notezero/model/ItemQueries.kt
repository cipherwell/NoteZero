package com.cipherwell.notezero.model

/**
 * Pure-Kotlin read-side helpers over the decrypted item/folder index.
 *
 * These live in `model/` (not in the repository) so the notes list, search screen and
 * editor can share one tested implementation of title extraction, filtering, sorting,
 * tag aggregation, folder paths and trash retention — with no Android dependency and
 * no access to the vault key.
 */
object ItemQueries {

    /** Default trash retention window; items older than this are eligible for purge. */
    const val DEFAULT_TRASH_RETENTION_DAYS: Int = 30

    private const val DEFAULT_TITLE_LENGTH = 120
    private const val DEFAULT_PREVIEW_LENGTH = 160

    private val LIST_MARKER_PREFIX = Regex("""^(?:#{1,6}\s+|>\s?|[-*+]\s+(?:\[[ xX]\]\s+)?|\d{1,9}[.)]\s+)""")

    // ---- title / preview -----------------------------------------------------

    /**
     * Human-readable title: the explicit [Item.title] when non-blank, otherwise the first
     * meaningful body line with block Markdown markers stripped. Never returns Markdown
     * control characters at the start; falls back to an empty string for blank items.
     */
    fun deriveTitle(item: Item, maxLength: Int = DEFAULT_TITLE_LENGTH): String {
        val explicit = item.title.trim()
        if (explicit.isNotEmpty()) return truncate(explicit, maxLength)
        for (line in item.body.lineSequence()) {
            val cleaned = stripBlockMarkers(line).trim()
            if (cleaned.isNotEmpty()) return truncate(cleaned, maxLength)
        }
        return ""
    }

    /**
     * Short list preview of the body: Markdown block markers and inline emphasis markers
     * removed, whitespace collapsed. When the body's first line is the derived title it is
     * skipped so the list row does not show the title twice.
     */
    fun derivePreview(item: Item, maxLength: Int = DEFAULT_PREVIEW_LENGTH): String {
        val title = deriveTitle(item)
        val lines = item.body.lineSequence()
            .map { stripBlockMarkers(it) }
            .map { stripInlineMarkers(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val start = if (lines.isNotEmpty() && lines.first() == title) 1 else 0
        if (start >= lines.size) return ""
        return truncate(lines.drop(start).joinToString(" "), maxLength)
    }

    /** Number of words as a user would count them (whitespace separated). */
    fun wordCount(item: Item): Int = countWords(item.title) + countWords(item.body)

    fun countWords(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    // ---- search / filter -----------------------------------------------------

    /** Case-insensitive substring match over the decrypted title, body, tags and folder id. */
    fun matchesQuery(item: Item, query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase()
        if (item.title.lowercase().contains(needle)) return true
        if (item.body.lowercase().contains(needle)) return true
        if (item.tags.any { it.lowercase().contains(needle) }) return true
        if (item.folderId?.lowercase()?.contains(needle) == true) return true
        return false
    }

    /**
     * Filters the decrypted index for the notes list. All predicates are ANDed.
     * [trashView] selects the trash screen (only trashed items); otherwise trashed items are
     * hidden, which is what the live list, favourites and search show.
     */
    fun filter(
        items: List<Item>,
        query: String = "",
        folderId: String? = null,
        tag: String? = null,
        favoritesOnly: Boolean = false,
        pinnedOnly: Boolean = false,
        trashView: Boolean = false,
    ): List<Item> {
        val needle = query.trim()
        return items.filter { item ->
            if (trashView != item.trashed) return@filter false
            if (pinnedOnly && !item.pinned) return@filter false
            if (favoritesOnly && !item.favorite) return@filter false
            if (folderId != null && item.folderId != folderId) return@filter false
            if (tag != null && item.tags.none { it.equals(tag, ignoreCase = true) }) return@filter false
            matchesQuery(item, needle)
        }
    }

    // ---- sorting -------------------------------------------------------------

    enum class ItemSort { RECENT, CREATED, TITLE }

    /**
     * Deterministic list ordering. Pinned items (and then favourites) float to the top when
     * requested, preserving the chosen base sort as the tie-breaker; ids break final ties.
     */
    fun sort(
        items: List<Item>,
        sort: ItemSort = ItemSort.RECENT,
        pinnedFirst: Boolean = false,
        favoritesFirst: Boolean = false,
    ): List<Item> {
        val comparator = when (sort) {
            ItemSort.RECENT -> compareByDescending<Item> { it.updatedAt }
            ItemSort.CREATED -> compareByDescending<Item> { it.createdAt }
            ItemSort.TITLE -> compareBy<Item> { it.title.lowercase() }
        }
            .thenByDescending { it.updatedAt }
            .thenBy { it.id }

        val ordered = if (pinnedFirst || favoritesFirst) {
            items.sortedWith(
                compareByDescending<Item> { if (pinnedFirst && it.pinned) 1 else 0 }
                    .thenByDescending { if (favoritesFirst && it.favorite) 1 else 0 }
                    .then(comparator),
            )
        } else {
            items.sortedWith(comparator)
        }
        return ordered
    }

    // ---- tags ----------------------------------------------------------------

    data class TagCount(val tag: String, val count: Int)

    /** Every tag on non-trashed items with its item count, count desc then name asc. */
    fun tagCounts(items: List<Item>): List<TagCount> {
        val counts = LinkedHashMap<String, Int>()
        for (item in items) {
            if (item.trashed) continue
            for (tag in item.tags) {
                val normalised = tag.trim()
                if (normalised.isEmpty()) continue
                val existingKey = counts.keys.firstOrNull { it.equals(normalised, ignoreCase = true) }
                val key = existingKey ?: normalised
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts.entries
            .map { TagCount(it.key, it.value) }
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.tag.lowercase() })
    }

    /** Tags of a single item, trimmed, case-insensitively de-duplicated, order preserved. */
    fun normaliseTags(tags: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(tags.size)
        for (tag in tags) {
            val trimmed = tag.trim()
            if (trimmed.isEmpty()) continue
            if (!seen.add(trimmed.lowercase())) continue
            out.add(trimmed)
        }
        return out
    }

    // ---- trash retention -----------------------------------------------------

    /** Epoch millis after which a trashed item may be purged. */
    fun trashExpiry(now: Long, retentionDays: Int = DEFAULT_TRASH_RETENTION_DAYS): Long =
        now + retentionDays.coerceAtLeast(0) * 24L * 60L * 60L * 1000L

    fun isTrashExpired(item: Item, now: Long, retentionDays: Int = DEFAULT_TRASH_RETENTION_DAYS): Boolean {
        if (!item.trashed) return false
        return now - item.updatedAt >= retentionDays.coerceAtLeast(0) * 24L * 60L * 60L * 1000L
    }

    /** Ids of trashed items older than the retention window, for the background purge job. */
    fun expiredTrashIds(
        items: List<Item>,
        now: Long,
        retentionDays: Int = DEFAULT_TRASH_RETENTION_DAYS,
    ): List<String> = items.filter { isTrashExpired(it, now, retentionDays) }.map { it.id }

    // ---- folder tree ---------------------------------------------------------

    /** Folder chain from the root to [folderId]; empty when the folder is unknown; cycle-safe. */
    fun folderPath(folders: List<Folder>, folderId: String?): List<Folder> {
        if (folderId == null) return emptyList()
        val byId = folders.associateBy { it.id }
        val chain = ArrayList<Folder>()
        val visited = HashSet<String>()
        var current = byId[folderId]
        while (current != null && visited.add(current.id)) {
            chain.add(current)
            current = current.parentId?.let(byId::get)
        }
        chain.reverse()
        return chain
    }

    /** "Parent / Child" breadcrumb label for the folder, or "" for the root. */
    fun folderPathLabel(folders: List<Folder>, folderId: String?, separator: String = " / "): String =
        folderPath(folders, folderId).joinToString(separator) { it.name }

    data class FolderNode(val folder: Folder, val children: List<FolderNode>)

    /**
     * Roots of the folder tree in deterministic order (sortOrder, name, id). Cycles and
     * dangling parent ids are tolerated: orphaned folders are treated as roots.
     */
    fun folderTree(folders: List<Folder>): List<FolderNode> {
        val byParent = LinkedHashMap<String?, MutableList<Folder>>()
        val ids = folders.mapTo(HashSet()) { it.id }
        for (folder in folders) {
            val parent = folder.parentId?.takeIf { it in ids && it != folder.id }
            byParent.getOrPut(parent) { ArrayList() }.add(folder)
        }
        fun build(folder: Folder, path: Set<String>): FolderNode {
            val children = byParent[folder.id].orEmpty()
                .filterNot { it.id in path }
                .sortedWith(folderComparator())
                .map { build(it, path + folder.id) }
            return FolderNode(folder, children)
        }
        return byParent[null].orEmpty().sortedWith(folderComparator()).map { build(it, setOf(it.id)) }
    }

    private fun folderComparator(): Comparator<Folder> =
        compareBy<Folder> { it.sortOrder }.thenBy { it.name.lowercase() }.thenBy { it.id }

    // ---- helpers -------------------------------------------------------------

    fun stripBlockMarkers(line: String): String {
        var text = line.trimStart()
        // Strip one layer, possibly repeated for nested list/quote/emphasis prefixes.
        repeat(3) {
            val next = text.replaceFirst(LIST_MARKER_PREFIX, "")
            if (next == text) return@repeat
            text = next.trimStart()
        }
        return text
    }

    private fun stripInlineMarkers(text: String): String =
        text.replace(Regex("""(\*\*|__|\*|_|~~|`+)"""), "").trim()

    private fun truncate(text: String, maxLength: Int): String {
        if (maxLength <= 0) return ""
        if (text.length <= maxLength) return text
        return text.take(maxLength).trimEnd() + "\u2026"
    }
}
