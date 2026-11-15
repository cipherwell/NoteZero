package com.cipherwell.notezero.model

/**
 * The kind of item stored in the vault. A single item model carries both notes and
 * code files (decision 2026-11-15): the storage envelope and folder tree are shared,
 * and the UI decides how to render/edit based on [ItemKind].
 */
enum class ItemKind {
    /** Markdown note rendered with the block preview. */
    NOTE,

    /** Plain source file opened in the code editor. */
    CODE_FILE,
}

/**
 * A decrypted vault item. This type must never be written to disk in plaintext:
 * persistence happens through the encrypted item envelope (see data/ItemCodec).
 *
 * [id] is a random UUID generated at creation and never derived from content.
 * [createdAt]/[updatedAt] are epoch millis.
 */
data class Item(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val folderId: String? = null,
    val pinned: Boolean = false,
    val favorite: Boolean = false,
    val trashed: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Incremented on every successful save; used for autosave conflict detection. */
    val version: Long = 0L,
) {
    val isBlank: Boolean get() = title.isBlank() && body.isBlank()
}

/** A decrypted folder in the single hierarchical tree shared by notes and code files. */
data class Folder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
)

/** User-selectable theme mode; persisted in vault preferences (non-secret). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }
