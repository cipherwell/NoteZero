package com.cipherwell.notezero.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.ItemKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Autosave lifecycle exposed to the editor chrome. */
enum class SaveStatus {
    /** Nothing has been changed yet. */
    IDLE,

    /** Local edits exist and the debounce timer is running. */
    PENDING,

    /** A save is in flight. */
    SAVING,

    /** The latest draft is persisted. */
    SAVED,

    /** The last save attempt failed; the draft is still in memory (dirty). */
    ERROR,

    /** The note changed underneath us; the user must choose how to resolve it. */
    CONFLICT,
}

/**
 * Draft state + autosave for a single note.
 *
 * Security: the decrypted title/body never leave this ViewModel's in-memory state; it is
 * never logged. Persistence only happens through [NoteRepository.saveItem], which
 * re-encrypts on the data layer. Because the Activity-scoped ViewModel outlives the screen,
 * it cannot rely on being dropped: it watches [NoteRepository.status] and
 * [NoteRepository.verifiedLockedIds] and wipes the draft, undo/redo history, conflict and
 * find state the moment the vault is no longer UNLOCKED or the note loses its session
 * verification (decision #15). A new session reloads the draft from the repository index,
 * so nothing decrypted survives a lock (audit finding F1).
 *
 * Autosave: every edit restarts a [debounceMs] timer (~800 ms) that persists the draft.
 * Leaving the screen calls [saveOnLeave], which flushes immediately (force = true) so no
 * edit is lost when navigating back.
 *
 * Version conflict: we remember the title/body we loaded. If the repository copy changed
 * underneath us (someone else wrote the note — another surface of the app, a restore,
 * etc.), autosave stops and sets [SaveStatus.CONFLICT]. The user can overwrite with the
 * local draft or reload the repository version. Content-only comparison is deliberate:
 * pin/favourite/tag/folder changes bump the stored version but must not look like a
 * conflicting body edit.
 */
class EditorViewModel(
    private val repository: NoteRepository,
    val itemId: String,
    private val debounceMs: Long = AUTOSAVE_DEBOUNCE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    data class Snapshot(val title: String, val body: String)

    data class EditorState(
        val loading: Boolean = true,
        val missing: Boolean = false,
        val title: String = "",
        val body: String = "",
        val kind: ItemKind = ItemKind.NOTE,
        val dirty: Boolean = false,
        val saveStatus: SaveStatus = SaveStatus.IDLE,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val conflict: Boolean = false,
        val lastSavedAt: Long? = null,
        val successfulSaves: Int = 0,
        /**
         * True after the vault locked/re-armed while this (Activity-scoped) ViewModel was
         * alive: every decrypted field above has been dropped and the screen must not render
         * a draft. A new session reloads the note (see [loadDraft]).
         */
        val sessionCleared: Boolean = false,
    ) {
        val wordCount: Int get() = TextMetrics.wordCount(body)
        val characterCount: Int get() = TextMetrics.characterCount(body)
    }

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private val saveMutex = Mutex()
    private var autosaveJob: Job? = null

    /** Title/body as last loaded from or written to the repository. */
    private var baseTitle: String = ""
    private var baseBody: String = ""

    init {
        viewModelScope.launch { loadDraft() }
        viewModelScope.launch {
            // The ViewModel lives in the Activity's ViewModelStore, so "dropped with the
            // ViewModel" would only happen on Activity destruction. Watch the session instead:
            // a lock, a re-arm of the lock flag or any status change away from UNLOCKED wipes
            // the decrypted draft immediately; a later unlock reloads it from the index.
            combine(repository.status, repository.verifiedLockedIds) { status, verified ->
                status to verified
            }.collect { (status, verified) ->
                val item = repository.items.value.firstOrNull { it.id == itemId }
                val hidden = item?.locked == true && itemId !in verified
                if (status != VaultStatus.UNLOCKED || hidden) {
                    clearDecryptedState()
                } else if (_state.value.sessionCleared) {
                    loadDraft()
                }
            }
        }
    }

    /** Loads the decrypted note from the repository index (init and post-session reload). */
    private suspend fun loadDraft() {
        val item = repository.items.value.firstOrNull { it.id == itemId }
            ?: repository.items.first().firstOrNull { it.id == itemId }
        if (item == null) {
            _state.value = _state.value.copy(
                loading = false,
                missing = true,
                sessionCleared = false,
            )
            return
        }
        baseTitle = item.title
        baseBody = item.body
        undoStack.clear()
        redoStack.clear()
        autosaveJob?.cancel()
        _state.value = EditorState(
            loading = false,
            title = item.title,
            body = item.body,
            kind = item.kind,
            lastSavedAt = item.updatedAt.takeIf { it > 0L },
        )
    }

    /**
     * Drops every decrypted value (title/body, undo/redo snapshots, conflict flag, base copy)
     * and cancels the debounce. The screen renders a non-secret "session ended" placeholder
     * until a new session reloads the note. Nothing here is persisted.
     */
    private fun clearDecryptedState() {
        if (_state.value.sessionCleared) return
        autosaveJob?.cancel()
        autosaveJob = null
        undoStack.clear()
        redoStack.clear()
        baseTitle = ""
        baseBody = ""
        _state.value = EditorState(
            loading = false,
            kind = _state.value.kind,
            sessionCleared = true,
        )
    }

    // ---- Editing -------------------------------------------------------------

    fun updateTitle(value: String) {
        val current = _state.value
        mutate(title = value, body = current.body)
    }

    fun updateBody(value: String) {
        val current = _state.value
        mutate(title = current.title, body = value)
    }

    private fun mutate(title: String, body: String) {
        val current = _state.value
        if (current.loading || current.missing || current.sessionCleared) return
        if (title == current.title && body == current.body) return
        pushUndo(Snapshot(current.title, current.body))
        redoStack.clear()
        _state.value = current.copy(
            title = title,
            body = body,
            dirty = true,
            saveStatus = SaveStatus.PENDING,
            canUndo = undoStack.isNotEmpty(),
            canRedo = false,
        )
        scheduleAutosave()
    }

    fun undo() {
        val current = _state.value
        if (current.loading || current.missing || current.sessionCleared) return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(Snapshot(current.title, current.body))
        _state.value = current.copy(
            title = previous.title,
            body = previous.body,
            dirty = true,
            saveStatus = SaveStatus.PENDING,
            canUndo = undoStack.isNotEmpty(),
            canRedo = true,
        )
        scheduleAutosave()
    }

    fun redo() {
        val current = _state.value
        if (current.loading || current.missing || current.sessionCleared) return
        val next = redoStack.removeLastOrNull() ?: return
        pushUndo(Snapshot(current.title, current.body))
        _state.value = current.copy(
            title = next.title,
            body = next.body,
            dirty = true,
            saveStatus = SaveStatus.PENDING,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
        )
        scheduleAutosave()
    }

    /** Flips the [index]-th checklist item in the Markdown source (preview tap). */
    fun toggleChecklistItem(index: Int) {
        val current = _state.value
        if (current.loading || current.missing || current.sessionCleared) return
        val updated = ChecklistMarkdown.toggle(current.body, index)
        if (updated != current.body) updateBody(updated)
    }

    // ---- Saving --------------------------------------------------------------

    private fun scheduleAutosave() {
        if (_state.value.conflict) return
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(debounceMs)
            flush(force = false)
        }
    }

    /**
     * Persists the current draft. Returns true when the draft is on disk afterwards.
     * When [force] is true, a version conflict is overwritten with the local draft.
     */
    suspend fun flush(force: Boolean = false): Boolean = saveMutex.withLock {
        val current = _state.value
        if (current.loading || current.missing || current.sessionCleared) return@withLock false
        if (!current.dirty && !force) return@withLock true
        if (repository.status.value != VaultStatus.UNLOCKED) return@withLock false

        val repositoryItem = repository.items.value.firstOrNull { it.id == itemId }
        if (repositoryItem == null) {
            _state.value = current.copy(missing = true, saveStatus = SaveStatus.ERROR)
            return@withLock false
        }

        if (!force && (repositoryItem.title != baseTitle || repositoryItem.body != baseBody)) {
            _state.value = current.copy(conflict = true, saveStatus = SaveStatus.CONFLICT)
            return@withLock false
        }

        _state.value = current.copy(saveStatus = SaveStatus.SAVING)
        return@withLock try {
            val saved = repository.saveItem(
                repositoryItem.copy(title = current.title, body = current.body),
            )
            baseTitle = saved.title
            baseBody = saved.body
            val latest = _state.value
            val stillDirty = latest.title != saved.title || latest.body != saved.body
            _state.value = latest.copy(
                dirty = stillDirty,
                saveStatus = if (stillDirty) SaveStatus.PENDING else SaveStatus.SAVED,
                conflict = false,
                lastSavedAt = clock(),
                successfulSaves = latest.successfulSaves + 1,
            )
            if (stillDirty) scheduleAutosave()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (locked: IllegalStateException) {
            // Auto-lock can win the race between the UNLOCKED check and the repository mutex
            // (the VMK is wiped under it). That is not a retryable save error: drop the draft
            // instead of leaving plaintext on screen or crashing (audit findings F1/F10).
            if (repository.status.value != VaultStatus.UNLOCKED) {
                clearDecryptedState()
                false
            } else {
                _state.value = _state.value.copy(saveStatus = SaveStatus.ERROR, dirty = true)
                false
            }
        } catch (error: Exception) {
            _state.value = _state.value.copy(saveStatus = SaveStatus.ERROR, dirty = true)
            false
        }
    }

    /**
     * Flush used when leaving the editor: cancels the debounce and writes the draft
     * immediately. A clean draft is a no-op so merely opening and closing a note does
     * not bump its edited time.
     */
    suspend fun saveOnLeave(): Boolean {
        autosaveJob?.cancel()
        val current = _state.value
        if (current.sessionCleared) return false
        if (!current.dirty) return true
        return flush(force = true)
    }

    /** Conflict resolution: keep the local draft and overwrite the repository copy. */
    fun overwriteWithLocalDraft() {
        autosaveJob?.cancel()
        if (_state.value.sessionCleared) return
        viewModelScope.launch { flush(force = true) }
    }

    /** Conflict resolution: discard local edits and take the repository copy. */
    fun reloadLatest() {
        autosaveJob?.cancel()
        if (_state.value.sessionCleared) return
        viewModelScope.launch {
            val item = repository.items.value.firstOrNull { it.id == itemId } ?: return@launch
            baseTitle = item.title
            baseBody = item.body
            undoStack.clear()
            redoStack.clear()
            _state.value = _state.value.copy(
                title = item.title,
                body = item.body,
                dirty = false,
                conflict = false,
                saveStatus = SaveStatus.IDLE,
                canUndo = false,
                canRedo = false,
                lastSavedAt = item.updatedAt.takeIf { it > 0L },
            )
        }
    }

    private fun pushUndo(snapshot: Snapshot) {
        undoStack.addLast(snapshot)
        while (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
    }

    companion object {
        /** Autosave debounce required by the product spec. */
        const val AUTOSAVE_DEBOUNCE_MS = 800L

        private const val UNDO_LIMIT = 100
    }
}

/** Creates [EditorViewModel] with an injected repository and item id. */
class EditorViewModelFactory(
    private val repository: NoteRepository,
    private val itemId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(EditorViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return EditorViewModel(repository, itemId) as T
    }
}
