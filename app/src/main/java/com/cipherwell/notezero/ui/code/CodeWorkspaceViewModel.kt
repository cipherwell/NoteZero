package com.cipherwell.notezero.ui.code

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cipherwell.notezero.code.CodeSearch
import com.cipherwell.notezero.code.CodeSearchMatch
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Render-ready state of the code workspace file tree and its search surface. */
data class CodeWorkspaceUiState(
    val isUnlocked: Boolean = false,
    val rows: List<CodeTreeNode> = emptyList(),
    val moveTargets: List<FolderChoice> = emptyList(),
    val fileCount: Int = 0,
    val folderCount: Int = 0,
    val searchVisible: Boolean = false,
    val searchQuery: String = "",
    val searchCaseSensitive: Boolean = false,
    val searchResults: List<CodeSearchMatch> = emptyList(),
    /** Code files/folders hidden because they are locked and not verified this session. */
    val lockedHiddenCount: Int = 0,
) {
    val isEmpty: Boolean get() = fileCount == 0 && folderCount == 0

    /**
     * True when the search bar is open *and* a non-blank query is being searched; the screen
     * then shows the result list instead of the tree.
     */
    val searchActive: Boolean get() = searchVisible && searchQuery.isNotBlank()
}

/**
 * State holder for the code workspace file tree.
 *
 * It reads the decrypted in-memory index of [NoteRepository] (empty while the vault is
 * locked) and owns only non-secret view state (expanded folders). File names and source
 * never leave this ViewModel's in-memory state and are never logged.
 *
 * [ItemKind.CODE_FILE] items share the single vault folder tree with notes, so this
 * screen never creates or renames folders that notes could not also use.
 */
class CodeWorkspaceViewModel(
    private val repository: NoteRepository,
) : ViewModel() {

    private val expandedFolderIds = MutableStateFlow<Set<String>>(emptySet())

    /** Non-secret search chrome: whether the bar is open, its query and the case toggle. */
    private data class SearchState(
        val visible: Boolean = false,
        val query: String = "",
        val caseSensitive: Boolean = false,
    )

    private val searchState = MutableStateFlow(SearchState())

    /**
     * Locked content never reaches the code workspace: locked+unverified code files and every
     * descendant of a locked+unverified folder are removed *before* the tree, counts and
     * workspace search are computed. This mirrors the notes-list masking (the data layer keeps
     * decrypted items in memory; the UI is responsible for hiding them).
     */
    private data class VisibleContent(
        val items: List<Item> = emptyList(),
        val folders: List<Folder> = emptyList(),
        val hiddenCount: Int = 0,
        val isUnlocked: Boolean = false,
    )

    private val visibleContent: Flow<VisibleContent> = combine(
        repository.items,
        repository.folders,
        repository.status,
        repository.verifiedLockedIds,
    ) { items, folders, status, verified ->
        if (status != VaultStatus.UNLOCKED) {
            VisibleContent()
        } else {
            val hiddenFolders = mutableSetOf<String>()
            var changed = true
            while (changed) {
                changed = false
                folders.forEach { folder ->
                    val hidden = (folder.locked && folder.id !in verified) ||
                        (folder.parentId != null && folder.parentId in hiddenFolders)
                    if (hidden && hiddenFolders.add(folder.id)) changed = true
                }
            }
            val hiddenFileCount = items.count { item ->
                item.kind == ItemKind.CODE_FILE &&
                    ((item.locked && item.id !in verified) || item.folderId in hiddenFolders)
            }
            VisibleContent(
                items = items.filterNot { item ->
                    (item.locked && item.id !in verified) || item.folderId in hiddenFolders
                },
                folders = folders.filterNot { it.id in hiddenFolders },
                hiddenCount = hiddenFileCount + hiddenFolders.size,
                isUnlocked = true,
            )
        }
    }

    val uiState: StateFlow<CodeWorkspaceUiState> = combine(
        visibleContent,
        expandedFolderIds,
        searchState,
    ) { content, expanded, search ->
        if (!content.isUnlocked) {
            CodeWorkspaceUiState(
                isUnlocked = false,
                searchVisible = search.visible,
                searchQuery = search.query,
                searchCaseSensitive = search.caseSensitive,
            )
        } else {
            CodeWorkspaceUiState(
                isUnlocked = true,
                rows = CodeTree.build(content.items, content.folders, expanded, isUnlocked = true),
                moveTargets = CodeTree.flattenFolders(content.folders),
                fileCount = CodeTree.codeFiles(content.items).size,
                folderCount = content.folders.size,
                searchVisible = search.visible,
                searchQuery = search.query,
                searchCaseSensitive = search.caseSensitive,
                searchResults = if (search.query.isBlank()) {
                    emptyList()
                } else {
                    CodeSearch.search(content.items, search.query, search.caseSensitive)
                },
                lockedHiddenCount = content.hiddenCount,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CodeWorkspaceUiState(),
    )

    // ---- Workspace search ----------------------------------------------------

    /** Opens the search bar, or closes it and resets the query/case toggle when already open. */
    fun toggleSearch() {
        searchState.update { current ->
            if (current.visible) SearchState() else current.copy(visible = true)
        }
    }

    fun setSearchQuery(query: String) {
        searchState.update { current -> current.copy(query = query) }
    }

    fun toggleSearchCaseSensitive() {
        searchState.update { current -> current.copy(caseSensitive = !current.caseSensitive) }
    }

    /** Hides the search bar and discards the query (tag results never outlive the bar). */
    fun closeSearch() {
        searchState.value = SearchState()
    }

    // ---- Tree navigation -----------------------------------------------------

    fun toggleFolder(folderId: String) {
        expandedFolderIds.update { current ->
            if (folderId in current) current - folderId else current + folderId
        }
    }

    fun expandFolder(folderId: String) {
        expandedFolderIds.update { current -> if (folderId in current) current else current + folderId }
    }

    fun collapseFolder(folderId: String) {
        expandedFolderIds.update { current -> current - folderId }
    }

    // ---- File mutations ------------------------------------------------------

    /**
     * Creates a [ItemKind.CODE_FILE] item titled [rawName] inside [folderId] (root when
     * null). Blank names fall back to the engine's default file name. Returns the saved
     * item — the caller opens the editor — or null when the vault locked or the name is
     * invalid.
     */
    suspend fun createFile(rawName: String, folderId: String?): Item? {
        if (!isUnlocked()) return null
        val name = CodeTree.normalizeNewFileName(rawName)
        if (CodeTree.validateName(name) != null) return null
        return try {
            val created = repository.createItem(ItemKind.CODE_FILE, folderId)
            val saved = repository.saveItem(created.copy(title = name))
            folderId?.let(::expandFolder)
            saved
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The data layer already reports failures; never surface decrypted names.
            null
        }
    }

    fun renameFile(item: Item, rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty() || name == item.title) return
        if (CodeTree.validateName(name) != null) return
        mutate { repository.saveItem(item.copy(title = name)) }
    }

    fun moveFile(item: Item, folderId: String?) {
        if (folderId == item.folderId) return
        mutate {
            repository.moveItem(item.id, folderId)
            folderId?.let(::expandFolder)
        }
    }

    /** Moves a code file to the trash (recoverable; never a hard delete from the tree). */
    fun trashFile(item: Item) = mutate { repository.trashItem(item.id) }

    // ---- Folder mutations ----------------------------------------------------

    fun createFolder(rawName: String, parentId: String?): Boolean {
        val name = rawName.trim()
        if (name.isEmpty() || CodeTree.validateName(name) != null) return false
        if (!isUnlocked()) return false
        viewModelScope.launch {
            if (!isUnlocked()) return@launch
            try {
                repository.createFolder(name, parentId)
                parentId?.let(::expandFolder)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Ignored: no content or names are logged.
            }
        }
        return true
    }

    fun renameFolder(folder: Folder, rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty() || name == folder.name) return
        if (CodeTree.validateName(name) != null) return
        mutate { repository.renameFolder(folder.id, name) }
    }

    /**
     * Deletes a folder. Per the repository contract its children move to the folder's
     * parent — files are never deleted implicitly.
     */
    fun deleteFolder(folder: Folder) = mutate { repository.deleteFolder(folder.id) }

    // ---- Internals -----------------------------------------------------------

    private fun isUnlocked(): Boolean = repository.status.value == VaultStatus.UNLOCKED

    private val _sessionStale = MutableStateFlow(false)

    /**
     * True when a file/folder mutation lost the auto-lock race: the pre-check passed but the
     * repository had already wiped the VMK, so it threw `IllegalStateException` instead of
     * mutating. Non-secret refresh state (no names, no content); reset on the next unlocked
     * session. Audit finding F10.
     */
    val sessionStale: StateFlow<Boolean> = _sessionStale.asStateFlow()

    init {
        viewModelScope.launch {
            repository.status.collect { status ->
                if (status == VaultStatus.UNLOCKED) _sessionStale.value = false
            }
        }
    }

    /**
     * Runs [block] only while unlocked; a lock that wins between the check and the repository
     * mutex is reported as [sessionStale] instead of crashing the app (audit F10).
     */
    private fun mutate(block: suspend () -> Unit) {
        if (!isUnlocked()) return
        viewModelScope.launch {
            if (!isUnlocked()) return@launch
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalStateException) {
                _sessionStale.value = true
            }
        }
    }
}

/** Creates [CodeWorkspaceViewModel] with the injected repository (no default factory). */
class CodeWorkspaceViewModelFactory(
    private val repository: NoteRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CodeWorkspaceViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return CodeWorkspaceViewModel(repository) as T
    }
}
