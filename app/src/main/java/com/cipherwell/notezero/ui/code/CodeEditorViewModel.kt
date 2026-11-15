package com.cipherwell.notezero.ui.code

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cipherwell.notezero.code.CodeEditing
import com.cipherwell.notezero.code.CodeLanguage
import com.cipherwell.notezero.code.Languages
import com.cipherwell.notezero.code.LineIndex
import com.cipherwell.notezero.code.TextRange
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.VaultStatus
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

/** Save lifecycle exposed to the code editor chrome. */
enum class CodeSaveStatus {
    IDLE,
    PENDING,
    SAVING,
    SAVED,
    ERROR,
    CONFLICT,
}

/**
 * Draft state, autosave, find/replace and undo history for a single code file.
 *
 * Security: decrypted source and the file name never leave this ViewModel's in-memory
 * state; they are never logged and never copied to the clipboard. Persistence only happens
 * through [NoteRepository.saveItem], which re-encrypts the item in the data layer. The
 * ViewModel is Activity-scoped (it outlives the screen), so it does not rely on being
 * dropped: it observes [NoteRepository.status]/[NoteRepository.verifiedLockedIds] and wipes
 * the source, the file name, undo/redo history and find state as soon as the vault is no
 * longer UNLOCKED or the file loses its session verification (decision #15, audit F1).
 *
 * Autosave: every edit restarts a [debounceMs] (600 ms) timer. Leaving the editor calls
 * [saveOnLeave], which cancels the timer and flushes immediately so no keystroke is lost.
 *
 * Conflicts: the title/body we loaded are remembered. If the stored copy changed
 * underneath us (e.g. a restore), autosave stops and [CodeSaveStatus.CONFLICT] is raised;
 * the user chooses to keep the local draft or reload. Content-only comparison is
 * deliberate, mirroring the notes editor: pin/folder changes bump the stored version but
 * are not a conflicting edit of the code.
 */
class CodeEditorViewModel(
    private val repository: NoteRepository,
    val itemId: String,
    private val preferences: CodeEditorPreferences = InMemoryCodeEditorPreferences(),
    private val debounceMs: Long = AUTOSAVE_DEBOUNCE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 1-based line the editor should open at (workspace search hit); null opens at the top. */
    private val initialLine: Int? = null,
) : ViewModel() {

    private data class Snapshot(val body: String, val cursor: Int, val selectionEnd: Int)

    data class State(
        val loading: Boolean = true,
        val missing: Boolean = false,
        /** File exceeded [MAX_OPEN_BYTES]; the editor refuses to open it. */
        val tooLarge: Boolean = false,
        val fileName: String = "",
        val body: String = "",
        val language: CodeLanguage = CodeLanguage.PLAIN,
        val cursor: Int = 0,
        val selectionEnd: Int = 0,
        val dirty: Boolean = false,
        val saveStatus: CodeSaveStatus = CodeSaveStatus.IDLE,
        val conflict: Boolean = false,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val fontSizeSp: Int = CodeEditorPrefs.DEFAULT_FONT_SIZE_SP,
        val wordWrap: Boolean = true,
        /** False when the file is too large to colour (> [MAX_HIGHLIGHT_BYTES]). */
        val syntaxHighlighting: Boolean = true,
        val findQuery: String = "",
        val replaceQuery: String = "",
        val caseSensitive: Boolean = false,
        val matches: List<TextRange> = emptyList(),
        val currentMatch: Int = -1,
        /** Pair of matching bracket offsets to highlight, when the cursor is on one. */
        val bracketHighlight: TextRange? = null,
        /**
         * Bumped on every cursor jump the UI must reveal (go-to-line, open-at-line). The
         * editor field scrolls to the cursor only when this changes, so normal scrolling and
         * typing never yank the viewport.
         */
        val navigationSeq: Int = 0,
        val lastSavedAt: Long? = null,
        val successfulSaves: Int = 0,
        /**
         * True after the vault locked/re-armed while this (Activity-scoped) ViewModel was
         * alive: source, file name, selection and history were dropped. A new session
         * reloads the file (see [loadDraft]).
         */
        val sessionCleared: Boolean = false,
    ) {
        val isMarkdown: Boolean get() = language == CodeLanguage.MARKDOWN
        val canFind: Boolean get() = findQuery.isNotEmpty()
        val matchLabel: String
            get() = when {
                findQuery.isEmpty() -> ""
                matches.isEmpty() -> "No matches"
                currentMatch in matches.indices -> "${currentMatch + 1}/${matches.size}"
                else -> "${matches.size} matches"
            }
    }

    private val _state = MutableStateFlow(
        State(
            fontSizeSp = preferences.fontSizeSp,
            wordWrap = preferences.wordWrap,
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var undoChars = 0
    private var redoChars = 0
    private val saveMutex = Mutex()
    private var autosaveJob: Job? = null

    /** Title/body as last loaded from or written to the repository. */
    private var baseTitle: String = ""
    private var baseBody: String = ""

    init {
        viewModelScope.launch { loadDraft() }
        viewModelScope.launch {
            // The Activity-scoped ViewModel outlives the screen, so a lock must not wait for
            // destruction: wipe the source/history the moment the session ends or the file is
            // re-masked, then reload when a new session makes it visible again.
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

    /** Loads the decrypted file from the repository index (init and post-session reload). */
    private suspend fun loadDraft() {
        val item = repository.items.value.firstOrNull { it.id == itemId }
            ?: repository.items.first().firstOrNull { it.id == itemId }
        if (item == null) {
            _state.value = _state.value.copy(loading = false, missing = true, sessionCleared = false)
            return
        }
        val bytes = utf8Size(item.body)
        val language = Languages.fromFileName(item.title)
        if (bytes > MAX_OPEN_BYTES) {
            // Refuse to load megabyte files into memory; keep only non-secret metadata. The
            // file name is shown because the workspace already renders it for this session.
            autosaveJob?.cancel()
            undoStack.clear()
            redoStack.clear()
            undoChars = 0
            redoChars = 0
            _state.value = State(
                loading = false,
                tooLarge = true,
                fileName = item.title,
                language = language,
                fontSizeSp = preferences.fontSizeSp,
                wordWrap = preferences.wordWrap,
                syntaxHighlighting = false,
            )
            return
        }
        baseTitle = item.title
        baseBody = item.body
        undoStack.clear()
        redoStack.clear()
        undoChars = 0
        redoChars = 0
        _state.value = withInitialLine(
            loaded = State(
                loading = false,
                fileName = item.title,
                body = item.body,
                language = language,
                fontSizeSp = preferences.fontSizeSp,
                wordWrap = preferences.wordWrap,
                syntaxHighlighting = bytes <= MAX_HIGHLIGHT_BYTES,
                lastSavedAt = item.updatedAt.takeIf { it > 0L },
            ),
            body = item.body,
        )
    }

    /**
     * Drops every decrypted value (source, file name, cursor/selection, find/replace state,
     * undo/redo snapshots, base copy) and cancels the debounce. Only non-secret editor
     * preferences survive; the screen renders a placeholder until a new session reloads.
     */
    private fun clearDecryptedState() {
        if (_state.value.sessionCleared) return
        autosaveJob?.cancel()
        autosaveJob = null
        undoStack.clear()
        redoStack.clear()
        undoChars = 0
        redoChars = 0
        baseTitle = ""
        baseBody = ""
        _state.value = State(
            loading = false,
            sessionCleared = true,
            fontSizeSp = _state.value.fontSizeSp,
            wordWrap = _state.value.wordWrap,
            syntaxHighlighting = false,
        )
    }

    /** Places the caret at the requested 1-based line when opening from a search hit. */
    private fun withInitialLine(loaded: State, body: String): State {
        val target = initialLine ?: return loaded
        if (target < 1) return loaded
        val index = LineIndex.of(body)
        if (target > index.lineCount) return loaded
        val offset = index.startOfLine(target - 1).coerceIn(0, body.length)
        return loaded.copy(cursor = offset, selectionEnd = offset, navigationSeq = 1)
    }

    // ---- Editing -------------------------------------------------------------

    /**
     * Applies a text change from the text field. Detects a single inserted newline and
     * expands it with the language's auto-indent through [CodeEditing.autoIndent].
     */
    fun onTextChanged(text: String, selectionStart: Int, selectionEnd: Int) {
        val current = _state.value
        if (current.loading || current.missing || current.tooLarge || current.sessionCleared) return
        val old = current.body
        val start = selectionStart.coerceIn(0, text.length)
        val end = selectionEnd.coerceIn(start, text.length)
        if (text == old) {
            moveCursor(start, end)
            return
        }

        val insertedNewline = start == end &&
            text.length == old.length + 1 &&
            start > 0 &&
            text[start - 1] == '\n' &&
            text.regionMatches(0, old, 0, start - 1) &&
            text.regionMatches(start, old, start - 1, old.length - (start - 1))

        if (insertedNewline) {
            // The engine's contract: [cursor] is where the line break was inserted; the
            // returned value is the indentation to append after the newline.
            val newlineOffset = start - 1
            val indent = CodeEditing.autoIndent(
                text = text,
                cursor = newlineOffset,
                language = current.language,
                useTabs = false,
                tabWidth = TAB_WIDTH,
            )
            if (indent.isNotEmpty()) {
                val adjusted = buildString(text.length + indent.length) {
                    append(text, 0, start)
                    append(indent)
                    append(text, start, text.length)
                }
                applyBody(adjusted, cursor = start + indent.length, selectionEnd = start + indent.length)
                return
            }
        }

        applyBody(text, cursor = start, selectionEnd = end)
    }

    /** Selection/cursor-only update (no text mutation, no dirty flag). */
    fun onSelectionChanged(selectionStart: Int, selectionEnd: Int) {
        val current = _state.value
        if (current.loading || current.missing || current.tooLarge || current.sessionCleared) return
        moveCursor(
            selectionStart.coerceIn(0, current.body.length),
            selectionEnd.coerceIn(0, current.body.length),
        )
    }

    fun undo() {
        val current = _state.value
        if (current.loading || current.missing || current.tooLarge || current.sessionCleared) return
        val previous = undoStack.removeLastOrNull() ?: return
        undoChars -= previous.body.length
        pushRedo(Snapshot(current.body, current.cursor, current.selectionEnd))
        applyBody(previous.body, previous.cursor, previous.selectionEnd, recordUndo = false)
        updateHistoryFlags()
    }

    fun redo() {
        val current = _state.value
        if (current.loading || current.missing || current.tooLarge || current.sessionCleared) return
        val next = redoStack.removeLastOrNull() ?: return
        redoChars -= next.body.length
        pushUndo(Snapshot(current.body, current.cursor, current.selectionEnd))
        applyBody(next.body, next.cursor, next.selectionEnd, recordUndo = false)
        updateHistoryFlags()
    }

    // ---- View preferences ----------------------------------------------------

    fun increaseFontSize() = setFontSize(_state.value.fontSizeSp + 1)

    fun decreaseFontSize() = setFontSize(_state.value.fontSizeSp - 1)

    private fun setFontSize(sizeSp: Int) {
        val clamped = sizeSp.coerceIn(CodeEditorPrefs.MIN_FONT_SIZE_SP, CodeEditorPrefs.MAX_FONT_SIZE_SP)
        preferences.fontSizeSp = clamped
        _state.value = _state.value.copy(fontSizeSp = clamped)
    }

    fun toggleWordWrap() {
        val wrap = !_state.value.wordWrap
        preferences.wordWrap = wrap
        _state.value = _state.value.copy(wordWrap = wrap)
    }

    // ---- Find / replace ------------------------------------------------------

    fun setFindQuery(query: String) {
        val current = _state.value
        _state.value = current.copy(
            findQuery = query,
            matches = emptyList(),
            currentMatch = -1,
            replaceQuery = current.replaceQuery,
        )
        recomputeMatches()
    }

    fun setReplaceQuery(query: String) {
        _state.value = _state.value.copy(replaceQuery = query)
    }

    fun toggleCaseSensitive() {
        val current = _state.value
        _state.value = current.copy(caseSensitive = !current.caseSensitive)
        recomputeMatches()
    }

    /** Selects the next match (wrapping) and returns its range, or null when none. */
    fun findNext(): TextRange? = selectMatch(forward = true)

    /** Selects the previous match (wrapping) and returns its range, or null when none. */
    fun findPrevious(): TextRange? = selectMatch(forward = false)

    fun replaceCurrent(): Boolean {
        val current = _state.value
        val match = current.matches.getOrNull(current.currentMatch) ?: return false
        if (current.findQuery.isEmpty()) return false
        val replacement = current.replaceQuery
        val updated = buildString {
            append(current.body, 0, match.start)
            append(replacement)
            append(current.body, match.end, current.body.length)
        }
        val cursor = match.start + replacement.length
        applyBody(updated, cursor = cursor, selectionEnd = cursor)
        recomputeMatches(keepIndex = true)
        return true
    }

    fun replaceAll(): Boolean {
        val current = _state.value
        if (current.findQuery.isEmpty()) return false
        val updated = CodeEditing.replaceAll(
            text = current.body,
            query = current.findQuery,
            replacement = current.replaceQuery,
            caseSensitive = current.caseSensitive,
        )
        if (updated == current.body) return false
        val cursor = current.cursor.coerceAtMost(updated.length)
        applyBody(updated, cursor = cursor, selectionEnd = cursor)
        recomputeMatches()
        return true
    }

    private fun selectMatch(forward: Boolean): TextRange? {
        val current = _state.value
        val matches = current.matches
        if (matches.isEmpty()) return null
        val nextIndex = when {
            current.currentMatch !in matches.indices -> if (forward) 0 else matches.lastIndex
            forward -> (current.currentMatch + 1) % matches.size
            else -> (current.currentMatch - 1 + matches.size) % matches.size
        }
        val match = matches[nextIndex]
        _state.value = _state.value.copy(
            currentMatch = nextIndex,
            cursor = match.start,
            selectionEnd = match.end,
            bracketHighlight = null,
        )
        return match
    }

    private fun recomputeMatches(keepIndex: Boolean = false) {
        val current = _state.value
        val matches = if (current.findQuery.isEmpty() || current.tooLarge || current.missing) {
            emptyList()
        } else {
            CodeEditing.findMatches(
                text = current.body,
                query = current.findQuery,
                caseSensitive = current.caseSensitive,
            )
        }
        val index = when {
            matches.isEmpty() -> -1
            keepIndex && current.currentMatch in matches.indices -> current.currentMatch
            else -> 0
        }
        _state.value = _state.value.copy(matches = matches, currentMatch = index)
    }

    // ---- Navigation ----------------------------------------------------------

    /** Jumps to a 1-based line number; returns false when the line is out of range. */
    fun goToLine(line: Int): Boolean {
        val current = _state.value
        if (current.loading || current.missing || current.tooLarge || current.sessionCleared) return false
        if (line < 1) return false
        val zeroBased = line - 1
        val index = LineIndex.of(current.body)
        if (zeroBased > index.lineCount - 1) return false
        val offset = CodeEditing.offsetForLine(current.body, zeroBased)
        if (offset < 0 || offset > current.body.length) return false
        _state.value = _state.value.copy(
            cursor = offset,
            selectionEnd = offset,
            bracketHighlight = bracketPairAt(current.body, offset),
            navigationSeq = _state.value.navigationSeq + 1,
        )
        return true
    }

    // ---- Multi-line indent ---------------------------------------------------

    /** Indents every line the selection touches (one indent unit), keeping the selection. */
    fun indentSelection() = shiftIndent(outdent = false)

    /** Removes one indent unit from every line the selection touches, keeping the selection. */
    fun outdentSelection() = shiftIndent(outdent = true)

    /**
     * Applies [CodeEditing.indentSelection] to the current cursor/selection. With an empty
     * selection the caret's line is indented, which is what Tab/Shift+Tab do. Both the text
     * and the mapped selection come from the pure engine, so undo/autosave work as for typing.
     */
    private fun shiftIndent(outdent: Boolean) {
        val current = _state.value
        if (current.loading || current.missing || current.tooLarge || current.sessionCleared) return
        val selection = TextRange(current.cursor, current.selectionEnd).normalized()
        val result = CodeEditing.indentSelection(
            text = current.body,
            selection = selection,
            outdent = outdent,
            useTabs = false,
            tabWidth = TAB_WIDTH,
        )
        val updated = result.first
        if (updated == current.body) return
        val mapped = result.second.clampedTo(updated.length)
        applyBody(updated, cursor = mapped.start, selectionEnd = mapped.end)
    }

    /**
     * Text the Copy action puts on the clipboard: the selection when there is one, otherwise
     * the whole draft. Empty when nothing is open/loaded. Clipboard handling stays in the UI
     * layer — this ViewModel never touches Android clipboard APIs.
     */
    fun copySelectionOrAll(): String {
        val current = _state.value
        if (current.loading || current.missing || current.tooLarge || current.sessionCleared) return ""
        val body = current.body
        val range = TextRange(current.cursor, current.selectionEnd).normalized().clampedTo(body.length)
        return if (range.isEmpty()) body else body.substring(range.start, range.end)
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
     * With [force] = true a version conflict is overwritten with the local draft.
     */
    suspend fun flush(force: Boolean = false): Boolean = saveMutex.withLock {
        val current = _state.value
        if (current.loading || current.missing || current.tooLarge || current.sessionCleared) return@withLock false
        if (!current.dirty && !force) return@withLock true
        if (repository.status.value != VaultStatus.UNLOCKED) return@withLock false

        val repositoryItem = repository.items.value.firstOrNull { it.id == itemId }
        if (repositoryItem == null) {
            _state.value = current.copy(missing = true, saveStatus = CodeSaveStatus.ERROR)
            return@withLock false
        }
        if (!force && (repositoryItem.title != baseTitle || repositoryItem.body != baseBody)) {
            _state.value = current.copy(conflict = true, saveStatus = CodeSaveStatus.CONFLICT)
            return@withLock false
        }

        _state.value = current.copy(saveStatus = CodeSaveStatus.SAVING)
        return@withLock try {
            val saved = repository.saveItem(
                repositoryItem.copy(title = current.fileName, body = current.body),
            )
            baseTitle = saved.title
            baseBody = saved.body
            val latest = _state.value
            val stillDirty = latest.body != saved.body || latest.fileName != saved.title
            _state.value = latest.copy(
                dirty = stillDirty,
                saveStatus = if (stillDirty) CodeSaveStatus.PENDING else CodeSaveStatus.SAVED,
                conflict = false,
                lastSavedAt = clock(),
                successfulSaves = latest.successfulSaves + 1,
            )
            if (stillDirty) scheduleAutosave()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (locked: IllegalStateException) {
            // Auto-lock can win the race between the UNLOCKED check and the repository
            // mutex; the VMK is gone, so the source must be dropped rather than kept as a
            // "retryable" error (audit findings F1/F10). A generic save failure stays ERROR.
            if (repository.status.value != VaultStatus.UNLOCKED) {
                clearDecryptedState()
                false
            } else {
                _state.value = _state.value.copy(saveStatus = CodeSaveStatus.ERROR, dirty = true)
                false
            }
        } catch (_: Exception) {
            _state.value = _state.value.copy(saveStatus = CodeSaveStatus.ERROR, dirty = true)
            false
        }
    }

    /**
     * Flush used when leaving the editor: cancels the debounce and writes the draft
     * immediately. A clean draft is a no-op so opening a file does not bump its time.
     */
    suspend fun saveOnLeave(): Boolean {
        autosaveJob?.cancel()
        val current = _state.value
        if (current.loading || current.missing || current.tooLarge || current.sessionCleared) return true
        if (!current.dirty) return true
        return flush(force = true)
    }

    /** Conflict resolution: keep the local draft and overwrite the stored copy. */
    fun overwriteWithLocalDraft() {
        autosaveJob?.cancel()
        if (_state.value.sessionCleared) return
        viewModelScope.launch { flush(force = true) }
    }

    /** Conflict resolution: discard local edits and reload the stored copy. */
    fun reloadLatest() {
        autosaveJob?.cancel()
        if (_state.value.sessionCleared) return
        viewModelScope.launch {
            val item = repository.items.value.firstOrNull { it.id == itemId } ?: return@launch
            val bytes = utf8Size(item.body)
            if (bytes > MAX_OPEN_BYTES) {
                _state.value = _state.value.copy(tooLarge = true, loading = false)
                return@launch
            }
            baseTitle = item.title
            baseBody = item.body
            undoStack.clear()
            redoStack.clear()
            undoChars = 0
            redoChars = 0
            _state.value = _state.value.copy(
                loading = false,
                missing = false,
                tooLarge = false,
                fileName = item.title,
                body = item.body,
                language = Languages.fromFileName(item.title),
                dirty = false,
                conflict = false,
                saveStatus = CodeSaveStatus.IDLE,
                canUndo = false,
                canRedo = false,
                syntaxHighlighting = bytes <= MAX_HIGHLIGHT_BYTES,
                lastSavedAt = item.updatedAt.takeIf { it > 0L },
                matches = emptyList(),
                currentMatch = -1,
                bracketHighlight = null,
                cursor = 0,
                selectionEnd = 0,
            )
            recomputeMatches()
        }
    }

    // ---- Internals -----------------------------------------------------------

    private fun applyBody(
        newBody: String,
        cursor: Int,
        selectionEnd: Int,
        recordUndo: Boolean = true,
    ) {
        val current = _state.value
        if (recordUndo) {
            pushUndo(Snapshot(current.body, current.cursor, current.selectionEnd))
            redoStack.clear()
            redoChars = 0
        }
        val safeCursor = cursor.coerceIn(0, newBody.length)
        val safeEnd = selectionEnd.coerceIn(safeCursor, newBody.length)
        _state.value = current.copy(
            body = newBody,
            cursor = safeCursor,
            selectionEnd = safeEnd,
            dirty = true,
            saveStatus = CodeSaveStatus.PENDING,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            syntaxHighlighting = syntaxAllowed(newBody),
            bracketHighlight = bracketPairAt(newBody, safeCursor),
        )
        scheduleAutosave()
        if (current.findQuery.isNotEmpty()) recomputeMatches(keepIndex = true)
    }

    private fun moveCursor(selectionStart: Int, selectionEnd: Int) {
        val current = _state.value
        val start = selectionStart.coerceIn(0, current.body.length)
        val end = selectionEnd.coerceIn(start, current.body.length)
        _state.value = current.copy(
            cursor = start,
            selectionEnd = end,
            bracketHighlight = bracketPairAt(current.body, start),
        )
    }

    private fun bracketPairAt(text: String, offset: Int): TextRange? {
        if (text.isEmpty()) return null
        if (!syntaxAllowed(text)) return null
        val match = CodeEditing.matchingBracket(text, offset) ?: return null
        val bracketStart = if (offset < text.length && text[offset].isBracket()) offset else offset - 1
        if (bracketStart !in text.indices) return null
        val start = minOf(bracketStart, match)
        val end = maxOf(bracketStart, match) + 1
        if (start < 0 || end > text.length || end <= start) return null
        return TextRange(start, end)
    }

    private fun Char.isBracket(): Boolean = this == '(' || this == ')' || this == '[' || this == ']' ||
        this == '{' || this == '}'

    /** Highlighting is only allowed below the size guard (and for languages with a lexer). */
    private fun syntaxAllowed(text: String): Boolean {
        if (text.length > MAX_HIGHLIGHT_BYTES) return false
        return utf8Size(text) <= MAX_HIGHLIGHT_BYTES
    }

    private fun pushUndo(snapshot: Snapshot) {
        undoStack.addLast(snapshot)
        undoChars += snapshot.body.length
        while (undoStack.size > UNDO_LIMIT || undoChars > UNDO_BUDGET_CHARS) {
            val removed = undoStack.removeFirstOrNull() ?: break
            undoChars -= removed.body.length
        }
    }

    private fun pushRedo(snapshot: Snapshot) {
        redoStack.addLast(snapshot)
        redoChars += snapshot.body.length
        while (redoStack.size > UNDO_LIMIT || redoChars > UNDO_BUDGET_CHARS) {
            val removed = redoStack.removeFirstOrNull() ?: break
            redoChars -= removed.body.length
        }
    }

    private fun updateHistoryFlags() {
        _state.value = _state.value.copy(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
        )
    }

    companion object {
        /** Autosave debounce required by the Phase 3 contract. */
        const val AUTOSAVE_DEBOUNCE_MS = 600L

        /** Files above this size are refused by the editor (typing latency guard). */
        const val MAX_OPEN_BYTES = 2 * 1024 * 1024

        /** Files above this size render as plain monospace without syntax colours. */
        const val MAX_HIGHLIGHT_BYTES = 256 * 1024

        /** Indent width used for auto-indent and the tab key (spaces only, no tabs). */
        const val TAB_WIDTH = 4

        private const val UNDO_LIMIT = 50

        /** Total characters kept across undo snapshots — bounds memory on large files. */
        private const val UNDO_BUDGET_CHARS = 4_000_000

        private fun utf8Size(text: String): Int {
            // Cheap path for ASCII-heavy source, exact for the rest.
            var bytes = 0
            var i = 0
            while (i < text.length) {
                val code = text[i]
                bytes += when {
                    code.code < 0x80 -> 1
                    code.code < 0x800 -> 2
                    Character.isHighSurrogate(code) && i + 1 < text.length -> {
                        i++
                        4
                    }

                    else -> 3
                }
                i++
            }
            return bytes
        }
    }
}

/** Creates [CodeEditorViewModel] with the injected repository, item id and preferences. */
class CodeEditorViewModelFactory(
    private val repository: NoteRepository,
    private val itemId: String,
    private val preferences: CodeEditorPreferences,
    private val initialLine: Int? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CodeEditorViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return CodeEditorViewModel(
            repository = repository,
            itemId = itemId,
            preferences = preferences,
            initialLine = initialLine,
        ) as T
    }
}
