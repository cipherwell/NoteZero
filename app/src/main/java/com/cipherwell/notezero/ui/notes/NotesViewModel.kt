package com.cipherwell.notezero.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder for the notes list: filtering, search, folder navigation and mutations.
 *
 * Everything it exposes is derived from the decrypted in-memory index of the
 * [NoteRepository] (empty while the vault is locked) plus [NotesControls]. Mutations
 * are dropped while the vault is not UNLOCKED so a stale screen can never touch
 * encrypted storage after auto-lock.
 */
class NotesViewModel(
    private val repository: NoteRepository,
) : ViewModel() {

    private val controls = MutableStateFlow(NotesControls())

    val uiState: StateFlow<NotesUiState> = combine(
        repository.items,
        repository.folders,
        repository.status,
        controls,
    ) { items, folders, status, currentControls ->
        NotesIndex.build(
            items = items,
            folders = folders,
            controls = currentControls,
            isUnlocked = status == VaultStatus.UNLOCKED,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = NotesIndex.build(
            items = emptyList(),
            folders = emptyList(),
            controls = NotesControls(),
            isUnlocked = false,
        ),
    )

    /** Latest controls value (not thread-safe for external mutation; read-only helper). */
    val currentControls: NotesControls get() = controls.value

    // ---- Filter / navigation -------------------------------------------------

    fun setSection(section: NotesSection) {
        controls.update { current ->
            current.copy(
                section = section,
                query = if (section == NotesSection.SEARCH) current.query else "",
                activeTag = null,
            )
        }
    }

    fun setQuery(query: String) {
        controls.update { it.copy(query = query) }
    }

    fun clearQuery() {
        controls.update { it.copy(query = "") }
    }

    fun setTag(tag: String?) {
        controls.update { it.copy(activeTag = tag?.trim()?.takeIf { trimmed -> trimmed.isNotEmpty() }) }
    }

    fun toggleTag(tag: String) {
        controls.update { current ->
            val same = current.activeTag?.equals(tag, ignoreCase = true) == true
            current.copy(activeTag = if (same) null else tag)
        }
    }

    fun toggleFavoritesOnly() {
        controls.update { it.copy(favoritesOnly = !it.favoritesOnly) }
    }

    fun openFolder(folderId: String?) {
        controls.update { it.copy(section = NotesSection.FOLDERS, currentFolderId = folderId) }
    }

    fun closeFolder() {
        controls.update { current ->
            val parentId = current.currentFolderId?.let { id ->
                repository.folders.value.firstOrNull { it.id == id }?.parentId
            }
            current.copy(currentFolderId = parentId)
        }
    }

    fun showTrash() {
        controls.update { it.copy(section = NotesSection.TRASH, query = "") }
    }

    fun leaveTrash() {
        controls.update { it.copy(section = NotesSection.NOTES) }
    }

    fun showSearch() {
        controls.update { it.copy(section = NotesSection.SEARCH) }
    }

    // ---- Item mutations ------------------------------------------------------

    /** Creates a note and returns it so the caller can open the editor. Null when locked. */
    suspend fun createNote(folderId: String? = null): Item? {
        if (!isUnlocked()) return null
        return repository.createItem(ItemKind.NOTE, folderId)
    }

    fun togglePinned(item: Item) = mutate { repository.setPinned(item.id, !item.pinned) }

    fun toggleFavorite(item: Item) = mutate { repository.setFavorite(item.id, !item.favorite) }

    fun moveToTrash(item: Item) = mutate { repository.trashItem(item.id) }

    fun restoreFromTrash(item: Item) = mutate { repository.restoreItem(item.id) }

    fun deleteForever(item: Item) = mutate { repository.deleteItemForever(item.id) }

    fun emptyTrash() = mutate { repository.emptyTrash() }

    fun moveItem(item: Item, folderId: String?) = mutate { repository.moveItem(item.id, folderId) }

    fun renameItem(item: Item, newTitle: String) {
        val title = newTitle.trim()
        if (title.isEmpty() || title == item.title) return
        mutate { repository.saveItem(item.copy(title = title)) }
    }

    // ---- Folder mutations ----------------------------------------------------

    fun createFolder(name: String, parentId: String? = null) = mutate {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) repository.createFolder(trimmed, parentId)
    }

    fun renameFolder(folder: Folder, newName: String) {
        val name = newName.trim()
        if (name.isEmpty() || name == folder.name) return
        mutate { repository.renameFolder(folder.id, name) }
    }

    fun deleteFolder(folder: Folder) = mutate { repository.deleteFolder(folder.id) }

    // ---- Internals -----------------------------------------------------------

    private fun isUnlocked(): Boolean = repository.status.value == VaultStatus.UNLOCKED

    /**
     * Runs [block] only while unlocked. The vault can auto-lock between a click and the
     * coroutine dispatch, hence the guard inside the coroutine as well.
     */
    private fun mutate(block: suspend () -> Unit) {
        if (!isUnlocked()) return
        viewModelScope.launch {
            if (isUnlocked()) block()
        }
    }
}

/** Creates [NotesViewModel] without relying on Android's default ViewModel factory. */
class NotesViewModelFactory(
    private val repository: NoteRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(NotesViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return NotesViewModel(repository) as T
    }
}
