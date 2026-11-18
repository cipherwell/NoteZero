package com.cipherwell.notezero.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.data.AttachmentImport
import com.cipherwell.notezero.model.BlankPdf
import com.cipherwell.notezero.model.Folder
import com.cipherwell.notezero.model.Item
import com.cipherwell.notezero.model.ItemKind
import com.cipherwell.notezero.model.NoteExport
import com.cipherwell.notezero.ui.locks.LockVisibility
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.io.ByteArrayInputStream
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
    private val _createError = MutableStateFlow(false)

    /** True when the last create action failed (rare I/O/quota failure); non-secret copy. */
    val createError: StateFlow<Boolean> = _createError.asStateFlow()

    val uiState: StateFlow<NotesUiState> = combine(
        repository.items,
        repository.folders,
        repository.status,
        repository.verifiedLockedIds,
        controls,
    ) { items, folders, status, verifiedLockedIds, currentControls ->
        NotesIndex.build(
            items = items,
            folders = folders,
            controls = currentControls,
            isUnlocked = status == VaultStatus.UNLOCKED,
            verifiedLockedIds = verifiedLockedIds,
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

    private val _sessionStale = MutableStateFlow(false)

    /**
     * True when a mutation lost the auto-lock race: the `isUnlocked()` pre-check passed but
     * the repository had already wiped the VMK, so it threw `IllegalStateException` instead
     * of mutating. Non-secret refresh state (no ids, no content) — the screen can show a
     * "session ended" notice and the vault's LOCKED routing takes over. Reset on the next
     * unlocked session. Audit finding F10.
     */
    val sessionStale: StateFlow<Boolean> = _sessionStale.asStateFlow()

    private val _exportStatus = MutableStateFlow(NoteExportStatus())
    private var exportSequence = 0L

    /**
     * Non-secret status of the last note-export attempt (written / cancelled / failed /
     * locked / unavailable). Shown by the notes screen; never carries content, a title or
     * a file name. The [NoteExportStatus.sequence] lets the same message repeat.
     */
    val exportStatus: StateFlow<NoteExportStatus> = _exportStatus.asStateFlow()

    init {
        viewModelScope.launch {
            repository.status.collect { status ->
                if (status == VaultStatus.UNLOCKED) _sessionStale.value = false
            }
        }
    }

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

    /**
     * Creates a code file titled [name] (the format dialog already resolved the extension) and
     * returns it so the caller can open the code editor. Null when locked or on failure.
     */
    suspend fun createCodeFile(
        name: String,
        folderId: String? = null,
        body: String = "",
    ): Item? {
        if (!isUnlocked()) return null
        val title = name.trim()
        if (title.isEmpty()) return null
        return try {
            val created = repository.createItem(ItemKind.CODE_FILE, folderId)
            repository.saveItem(created.copy(title = title, body = body))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            _createError.value = true
            null
        }
    }

    /**
     * Creates a PDF item: a blank, valid one-page PDF stored as an encrypted
     * `application/pdf` attachment through the normal attachment pipeline. The item is trashed
     * again if the attachment import fails, so a failed create can never leave an empty PDF
     * row (or an orphan blob) behind. Null when locked or on failure.
     */
    suspend fun createPdf(
        folderId: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): Item? {
        if (!isUnlocked()) return null
        val name = BlankPdf.fileName(nowMillis)
        var created: Item? = null
        return try {
            // Title first, attachment second: the import adds its ref to the stored item.
            val titled = repository.saveItem(repository.createItem(ItemKind.PDF_FILE, folderId).copy(title = name))
            created = titled
            val imported = repository.importAttachment(
                noteId = titled.id,
                displayName = name,
                mimeType = BlankPdf.MIME_TYPE,
                source = ByteArrayInputStream(BlankPdf.create()),
            )
            if (imported !is AttachmentImport.Success) {
                repository.trashItem(titled.id)
                _createError.value = true
                null
            } else {
                // Return the stored item (with the new attachment ref), not the pre-import copy.
                repository.items.value.firstOrNull { it.id == titled.id } ?: titled
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            created?.let { runCatching { repository.trashItem(it.id) } }
            _createError.value = true
            null
        }
    }

    /** Dismisses the create-failure banner/dialog. */
    fun dismissCreateError() {
        _createError.value = false
    }

    fun togglePinned(item: Item) = mutate { repository.setPinned(item.id, !item.pinned) }

    fun toggleFavorite(item: Item) = mutate { repository.setFavorite(item.id, !item.favorite) }

    fun moveToTrash(item: Item) = mutate { repository.trashItem(item.id) }

    fun restoreFromTrash(item: Item) = mutate { repository.restoreItem(item.id) }

    fun deleteForever(item: Item) = mutate { repository.deleteItemForever(item.id) }

    fun emptyTrash() = mutate { repository.emptyTrash() }

    fun moveItem(item: Item, folderId: String?) = mutate { repository.moveItem(item.id, folderId) }

    fun renameItem(item: Item, newTitle: String) {
        // A masked row never reaches this (its menu has no rename), but guard anyway:
        // saving a masked copy would overwrite the real title with "Locked note".
        if (!isRevealed(item)) return
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

    // ---- Locked notes & folders (visibility gate; decision #15) --------------

    /** True when this session has re-authenticated for [item] (or it is not locked). */
    fun isRevealed(item: Item): Boolean =
        !item.locked || item.id in repository.verifiedLockedIds.value

    /**
     * Persists the per-note lock flag. Locking drops any verification for the note in the
     * repository, so the row re-masks immediately; removing a lock while unverified must
     * be re-authenticated by the caller first (the repository deliberately does not gate
     * this, see decision #15).
     */
    fun setItemLocked(item: Item, locked: Boolean) = mutate {
        repository.setItemLocked(item.id, locked)
    }

    /** Persists the per-folder lock flag; descendants follow the folder's visibility. */
    fun setFolderLocked(folder: Folder, locked: Boolean) = mutate {
        repository.setFolderLocked(folder.id, locked)
    }

    /** Re-arms every locked note/folder ("Re-arm locked notes now" / settings action). */
    fun rearmLocks() = mutate { repository.clearLockVerifications() }

    // ---- Note export (Markdown / HTML, unencrypted copy) ---------------------

    /**
     * Converts the *current* repository copy of [item] to [format] and hands it to the
     * SAF [target]. The warning dialog is the caller's gate: this method only runs after
     * the user explicitly confirmed the unencrypted-copy warning.
     *
     * Re-checks the session here (not just in the UI): a masked locked note or a locked
     * vault exports nothing and reports a non-secret status. Conversion happens in memory;
     * the content is not stored on [exportStatus] and never logged.
     */
    fun exportNote(item: Item, format: NoteExportFormat, target: NoteExportTarget) {
        if (!isUnlocked()) {
            publishExportOutcome(NoteExportOutcome.LOCKED)
            return
        }
        val current = repository.items.value.firstOrNull { it.id == item.id }
        if (current == null) {
            publishExportOutcome(NoteExportOutcome.UNAVAILABLE)
            return
        }
        if (LockVisibility.isItemMasked(current, repository.verifiedLockedIds.value)) {
            // A masked note's text must never reach the converter, even if a stale row
            // still holds the real title/body in memory.
            publishExportOutcome(NoteExportOutcome.LOCKED)
            return
        }
        val content = when (format) {
            NoteExportFormat.MARKDOWN -> NoteExport.toMarkdown(current.title, current.body)
            NoteExportFormat.HTML -> NoteExport.toHtml(current.title, current.body)
            // The PDF renderer consumes Markdown (title as a level-1 heading) and styles it.
            NoteExportFormat.PDF -> NoteExport.toMarkdown(current.title, current.body)
        }
        val request = NoteExportRequest(
            itemId = current.id,
            format = format,
            suggestedName = sanitizeNoteExportName(current.title, format.extension),
            content = content,
        )
        target.requestExport(request) { result ->
            publishExportOutcome(result.toOutcome())
        }
    }

    /** Dismisses the current non-secret export status (no content involved). */
    fun dismissExportStatus() {
        _exportStatus.value = NoteExportStatus()
    }

    private fun publishExportOutcome(outcome: NoteExportOutcome) {
        exportSequence += 1
        _exportStatus.value = NoteExportStatus(outcome = outcome, sequence = exportSequence)
    }

    // ---- Internals -----------------------------------------------------------

    private fun isUnlocked(): Boolean = repository.status.value == VaultStatus.UNLOCKED

    /**
     * Runs [block] only while unlocked. The vault can auto-lock between a click and the
     * coroutine dispatch, hence the guard inside the coroutine as well. That guard is still
     * outside the repository mutex, so the lock can win again before the call: the resulting
     * `IllegalStateException("Vault is locked")` is converted into [sessionStale] instead of
     * escaping an uncaught coroutine exception and crashing the app (audit F10).
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
