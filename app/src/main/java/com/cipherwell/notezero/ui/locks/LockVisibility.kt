package com.cipherwell.notezero.ui.locks

import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item

/**
 * Pure masking helpers for the locked notes/folders visibility gate (decision #15).
 *
 * Locking is **not** a second encryption layer: the note/folder content is already sealed
 * under the vault VMK with a per-record AAD, so the repository still exposes the decrypted
 * record. This object is the single place that decides whether the UI may show that content
 * for the current session. Every list row, search match, tag chip and count is derived
 * through these helpers, so a locked record that is absent from
 * [com.cipherwell.notezero.data.NoteRepository.verifiedLockedIds] can never leak its title,
 * body, tags, folder path or attachments.
 *
 * No Android/Compose imports: unit-testable as plain JVM code.
 */
object LockVisibility {

    /** Placeholder title rendered for a locked, unverified note. */
    const val MASKED_NOTE_TITLE: String = "Locked note"

    /** Placeholder name rendered for a locked, unverified folder. */
    const val MASKED_FOLDER_NAME: String = "Locked folder"

    /** True when the note must be rendered as a mask in this session. */
    fun isItemMasked(item: Item, verifiedLockedIds: Set<String>): Boolean =
        item.locked && item.id !in verifiedLockedIds

    /** True when the folder (and therefore its descendants) must be hidden. */
    fun isFolderMasked(folder: Folder, verifiedLockedIds: Set<String>): Boolean =
        folder.locked && folder.id !in verifiedLockedIds

    /**
     * Ids of every folder that is hidden for this session: each locked+unverified folder
     * plus all of its descendants (locked or not). Cycle-safe via the visited set.
     */
    fun hiddenFolderIds(folders: List<Folder>, verifiedLockedIds: Set<String>): Set<String> {
        val roots = folders.filter { isFolderMasked(it, verifiedLockedIds) }
        if (roots.isEmpty()) return emptySet()
        val childrenByParent = folders.groupBy { it.parentId }
        val hidden = roots.mapTo(mutableSetOf()) { it.id }
        val queue = ArrayDeque(hidden)
        while (queue.isNotEmpty()) {
            val parentId = queue.removeFirst()
            childrenByParent[parentId].orEmpty().forEach { child ->
                if (hidden.add(child.id)) queue.addLast(child.id)
            }
        }
        return hidden
    }

    /**
     * Content-free copy of [item] for rendering: mask title, no body, no tags, no folder
     * path and no attachment names. Pinned/favourite flags are kept so the masked row stays
     * in the section the user expects (and remains tappable for re-auth).
     *
     * Never persist this copy: [com.cipherwell.notezero.data.NoteRepository.saveItem]
     * preserves the stored lock flag but would otherwise overwrite the real content.
     */
    fun maskItem(item: Item, verifiedLockedIds: Set<String>): Item =
        if (isItemMasked(item, verifiedLockedIds)) {
            item.copy(
                title = MASKED_NOTE_TITLE,
                body = "",
                tags = emptyList(),
                folderId = null,
                attachments = emptyList(),
            )
        } else {
            item
        }

    /** Content-free copy of [folder] for rendering (the name is content too). */
    fun maskFolder(folder: Folder, verifiedLockedIds: Set<String>): Folder =
        if (isFolderMasked(folder, verifiedLockedIds)) {
            folder.copy(name = MASKED_FOLDER_NAME)
        } else {
            folder
        }
}
