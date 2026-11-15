package com.cipherwell.notezero.ui.code

import com.cipherwell.notezero.code.CodeLanguage
import com.cipherwell.notezero.code.Languages
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind

/**
 * A single row of the flattened, render-ready code file tree.
 *
 * The UI renders [CodeTree.build]'s output as a flat list; nothing in this file touches
 * Android or Compose, so the tree logic is fully unit-testable on the JVM.
 */
sealed interface CodeTreeNode {
    val key: String
    val depth: Int

    data class FolderNode(
        val folder: Folder,
        override val depth: Int,
        val expanded: Boolean,
        /** Direct code files in this folder (used for the accessibility/count hint). */
        val fileCount: Int,
    ) : CodeTreeNode {
        override val key: String get() = "folder:${folder.id}"
    }

    data class FileNode(
        val item: Item,
        override val depth: Int,
    ) : CodeTreeNode {
        override val key: String get() = "file:${item.id}"
    }
}

/** A folder as offered by the "move file" picker. */
data class FolderChoice(
    val folder: Folder,
    val depth: Int,
)

/**
 * Pure tree building over the decrypted in-memory index: folders nest, only
 * [ItemKind.CODE_FILE] items are shown, trashed items are hidden. Expanded state is
 * supplied by the caller (ViewModel) so the whole result is a deterministic function.
 */
object CodeTree {

    /** Guards against pathological/cyclic folder graphs (mirrors the notes index). */
    const val MAX_DEPTH = 32

    /** File names longer than this are rejected by the dialogs; the vault title is text. */
    const val MAX_NAME_LENGTH = 120

    fun codeFiles(items: List<Item>): List<Item> =
        items.filter { it.kind == ItemKind.CODE_FILE && !it.trashed }

    fun build(
        items: List<Item>,
        folders: List<Folder>,
        expandedFolderIds: Set<String>,
        isUnlocked: Boolean,
    ): List<CodeTreeNode> {
        if (!isUnlocked) return emptyList()

        val files = codeFiles(items)
        val childrenByParent: Map<String?, List<Folder>> = folders.groupBy { it.parentId }
        val foldersById = folders.associateBy { it.id }
        val filesByFolder: Map<String?, List<Item>> = files.groupBy { it.folderId }
        val rows = mutableListOf<CodeTreeNode>()
        val visited = mutableSetOf<String>()

        fun folderComparator(): Comparator<Folder> =
            compareBy<Folder> { it.sortOrder }.thenBy { it.name.lowercase() }.thenBy { it.id }

        fun fileComparator(): Comparator<Item> =
            compareBy<Item> { it.title.lowercase() }.thenBy { it.id }

        fun visitFolder(folder: Folder, depth: Int) {
            if (!visited.add(folder.id)) return
            rows += CodeTreeNode.FolderNode(
                folder = folder,
                depth = depth,
                expanded = folder.id in expandedFolderIds,
                fileCount = filesByFolder[folder.id].orEmpty().size,
            )
            if (folder.id !in expandedFolderIds) return
            childrenByParent[folder.id].orEmpty()
                .sortedWith(folderComparator())
                .forEach { child -> visitFolder(child, depth + 1) }
            filesByFolder[folder.id].orEmpty()
                .sortedWith(fileComparator())
                .forEach { file -> rows += CodeTreeNode.FileNode(file, depth + 1) }
        }

        // Root folders, then root files.
        childrenByParent[null].orEmpty()
            .sortedWith(folderComparator())
            .forEach { folder -> visitFolder(folder, 0) }

        // Orphaned folders (parent id no longer present) surface at the root rather than
        // disappearing with their files.
        folders.filter { it.parentId != null && foldersById[it.parentId] == null }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
            .forEach { orphan ->
                if (orphan.id !in visited) visitFolder(orphan, 0)
            }

        // Folders caught in a parent cycle are unreachable from any root: surface each
        // such folder once so its files can never be silently hidden. Folders that are
        // merely collapsed are reachable, so they stay hidden until expanded.
        folders.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
            .forEach { remaining ->
                if (remaining.id !in visited && !reachesRoot(remaining, foldersById)) {
                    visitFolder(remaining, 0)
                }
            }

        filesByFolder[null].orEmpty()
            .sortedWith(fileComparator())
            .forEach { file -> rows += CodeTreeNode.FileNode(file, 0) }

        return rows
    }

    /** True when following parent links from [folder] reaches a real root (no cycle). */
    private fun reachesRoot(folder: Folder, foldersById: Map<String, Folder>): Boolean {
        var current: Folder = folder
        var guard = 0
        val seen = mutableSetOf<String>()
        while (guard <= MAX_DEPTH) {
            if (!seen.add(current.id)) return false
            val parent = current.parentId?.let { foldersById[it] } ?: return true
            current = parent
            guard++
        }
        return false
    }

    /**
     * Pre-order flattening of the folder tree for "move to folder" pickers. Orphans are
     * surfaced at the root; cycles cannot loop forever.
     */
    fun flattenFolders(folders: List<Folder>): List<FolderChoice> {
        val childrenByParent = folders.groupBy { it.parentId }
        val byId = folders.associateBy { it.id }
        val result = mutableListOf<FolderChoice>()
        val visited = mutableSetOf<String>()

        fun visit(parentId: String?, depth: Int) {
            if (depth > MAX_DEPTH) return
            childrenByParent[parentId].orEmpty()
                .sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }, { it.id }))
                .forEach { folder ->
                    if (!visited.add(folder.id)) return@forEach
                    result += FolderChoice(folder, depth)
                    visit(folder.id, depth + 1)
                }
        }

        visit(null, 0)
        folders.filter { it.parentId != null && byId[it.parentId] == null }
            .sortedBy { it.name.lowercase() }
            .forEach { orphan ->
                if (visited.add(orphan.id)) {
                    result += FolderChoice(orphan, 0)
                    visit(orphan.id, 1)
                }
            }
        folders.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
            .forEach { remaining ->
                if (remaining.id !in visited) {
                    visited.add(remaining.id)
                    result += FolderChoice(remaining, 0)
                    visit(remaining.id, 1)
                }
            }
        return result
    }

    // ---- Naming --------------------------------------------------------------

    /** Default name offered when the create dialog is confirmed empty. */
    fun defaultNewFileName(): String = Languages.defaultFileName(CodeLanguage.KOTLIN)

    /** Default file name for a title that already carries a known extension. */
    fun suggestedFileName(seed: String?): String {
        val trimmed = seed?.trim().orEmpty()
        return trimmed.ifEmpty { defaultNewFileName() }
    }

    /** Language for a file title; extension based (case-insensitive) via the engine. */
    fun languageFor(fileName: String): CodeLanguage = Languages.fromFileName(fileName)

    /**
     * Validates a user-entered file/folder name. Returns null when valid, otherwise a
     * human-readable reason. Empty input is *not* an error for new files: the dialog
     * substitutes [defaultNewFileName] first.
     */
    fun validateName(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty()) return "Name cannot be empty"
        if (name.length > MAX_NAME_LENGTH) return "Name is too long"
        if (name.any { it == '/' || it == '\\' }) return "Name cannot contain slashes"
        if (name == "." || name == "..") return "Name is reserved"
        if (name.any { it.isISOControl() }) return "Name contains invalid characters"
        return null
    }

    /** Normalises a new-file name: blank input falls back to the default file name. */
    fun normalizeNewFileName(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.isEmpty()) defaultNewFileName() else trimmed
    }
}
