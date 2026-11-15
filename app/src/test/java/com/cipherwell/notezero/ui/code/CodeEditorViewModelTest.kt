package com.cipherwell.notezero.ui.code

import com.cipherwell.notezero.code.CodeLanguage
import com.cipherwell.notezero.data.VaultStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ViewModel tests for the code editor: load/language detection, 600 ms autosave debounce,
 * save-on-leave, dirty/conflict handling, size guards, find/replace, go-to-line,
 * bounded undo/redo and persisted font-size/wrap preferences.
 *
 * Coroutine time is virtual (StandardTestDispatcher + runTest): no sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodeEditorViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: FakeCodeRepository
    private lateinit var preferences: InMemoryCodeEditorPreferences

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        repository = FakeCodeRepository()
        preferences = InMemoryCodeEditorPreferences()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seed(
        body: String = "",
        title: String = "Main.kt",
        id: String = "f1",
    ): com.cipherwell.notezero.model.Item = repository.seedCodeFile(id = id, title = title, body = body)

    private fun viewModel(itemId: String = "f1") =
        CodeEditorViewModel(repository, itemId, preferences)

    @Test
    fun `loads the decrypted file into the draft`() = runTest(dispatcher) {
        seed(body = "fun main() = println(\"hi\")")
        val model = viewModel()
        advanceUntilIdle()

        val state = model.state.value
        assertFalse(state.loading)
        assertFalse(state.missing)
        assertFalse(state.tooLarge)
        assertEquals("Main.kt", state.fileName)
        assertEquals("fun main() = println(\"hi\")", state.body)
        assertEquals(CodeLanguage.KOTLIN, state.language)
        assertFalse(state.dirty)
        assertEquals(CodeSaveStatus.IDLE, state.saveStatus)
        assertEquals("Saved", saveStateLabel(state))
        assertTrue(state.syntaxHighlighting)
    }

    @Test
    fun `missing file is reported without crashing`() = runTest(dispatcher) {
        val model = viewModel("does-not-exist")
        advanceUntilIdle()

        assertTrue(model.state.value.missing)
        assertFalse(model.flush())
        assertTrue(model.saveOnLeave())
    }

    @Test
    fun `autosave persists only after the 600ms debounce`() = runTest(dispatcher) {
        seed()
        val model = viewModel()
        advanceUntilIdle()

        model.onTextChanged("fun main() {}", 0, 13)
        assertTrue(model.state.value.dirty)
        assertEquals(CodeSaveStatus.PENDING, model.state.value.saveStatus)
        assertEquals("Unsaved changes", saveStateLabel(model.state.value))

        advanceTimeBy(CodeEditorViewModel.AUTOSAVE_DEBOUNCE_MS - 1)
        assertEquals(0, repository.saveCount)

        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals(1, repository.saveCount)
        assertEquals("fun main() {}", repository.itemById("f1")!!.body)
        val state = model.state.value
        assertFalse(state.dirty)
        assertEquals(CodeSaveStatus.SAVED, state.saveStatus)
        assertEquals(1, state.successfulSaves)
        assertEquals("Saved", saveStateLabel(state))
    }

    @Test
    fun `rapid edits are coalesced into one debounced save`() = runTest(dispatcher) {
        seed()
        val model = viewModel()
        advanceUntilIdle()

        model.onTextChanged("f", 0, 1)
        advanceTimeBy(300)
        model.onTextChanged("fu", 0, 2)
        advanceTimeBy(CodeEditorViewModel.AUTOSAVE_DEBOUNCE_MS - 1)
        assertEquals(0, repository.saveCount)

        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals(1, repository.saveCount)
        assertEquals("fu", repository.itemById("f1")!!.body)
    }

    @Test
    fun `clean draft does not touch the repository`() = runTest(dispatcher) {
        seed(body = "val x = 1")
        val model = viewModel()
        advanceUntilIdle()

        assertTrue(model.flush())
        assertTrue(model.saveOnLeave())
        advanceUntilIdle()
        assertEquals(0, repository.saveCount)
    }

    @Test
    fun `save on leave flushes immediately without waiting for the debounce`() = runTest(dispatcher) {
        seed()
        val model = viewModel()
        advanceUntilIdle()

        model.onTextChanged("hello", 0, 5)
        assertEquals(0, repository.saveCount)

        assertTrue(model.saveOnLeave())
        assertEquals(1, repository.saveCount)
        assertEquals("hello", repository.itemById("f1")!!.body)
        assertFalse(model.state.value.dirty)
        advanceUntilIdle()
        assertEquals(1, repository.saveCount)
    }

    @Test
    fun `external change raises a conflict and blocks autosave`() = runTest(dispatcher) {
        val item = seed(body = "old body")
        val model = viewModel()
        advanceUntilIdle()

        repository.replaceStored(item.copy(body = "changed elsewhere", version = 7L))
        model.onTextChanged("my local edit", 0, 13)
        advanceTimeBy(CodeEditorViewModel.AUTOSAVE_DEBOUNCE_MS + 10)
        advanceUntilIdle()

        val conflicted = model.state.value
        assertEquals(0, repository.saveCount)
        assertTrue(conflicted.conflict)
        assertEquals(CodeSaveStatus.CONFLICT, conflicted.saveStatus)
        assertEquals("Conflict — unsaved edits", saveStateLabel(conflicted))
        assertEquals("changed elsewhere", repository.itemById("f1")!!.body)
    }

    @Test
    fun `keeping the local draft overwrites the external change`() = runTest(dispatcher) {
        val item = seed(body = "old body")
        val model = viewModel()
        advanceUntilIdle()
        repository.replaceStored(item.copy(body = "changed elsewhere", version = 7L))
        model.onTextChanged("my local edit", 0, 13)
        advanceTimeBy(CodeEditorViewModel.AUTOSAVE_DEBOUNCE_MS + 10)
        advanceUntilIdle()
        assertTrue(model.state.value.conflict)

        model.overwriteWithLocalDraft()
        advanceUntilIdle()

        assertEquals(1, repository.saveCount)
        assertEquals("my local edit", repository.itemById("f1")!!.body)
        assertFalse(model.state.value.conflict)
        assertEquals(CodeSaveStatus.SAVED, model.state.value.saveStatus)
    }

    @Test
    fun `reloading latest discards the local draft`() = runTest(dispatcher) {
        val item = seed(body = "old body")
        val model = viewModel()
        advanceUntilIdle()
        repository.replaceStored(item.copy(body = "changed elsewhere", version = 7L))
        model.onTextChanged("my local edit", 0, 13)
        advanceTimeBy(CodeEditorViewModel.AUTOSAVE_DEBOUNCE_MS + 10)
        advanceUntilIdle()
        assertTrue(model.state.value.conflict)

        model.reloadLatest()
        advanceUntilIdle()

        val state = model.state.value
        assertEquals("changed elsewhere", state.body)
        assertFalse(state.dirty)
        assertFalse(state.conflict)
        assertEquals(CodeSaveStatus.IDLE, state.saveStatus)
    }

    @Test
    fun `save failure keeps the draft dirty and a later edit retries`() = runTest(dispatcher) {
        seed()
        val model = viewModel()
        advanceUntilIdle()

        repository.failNextSave = true
        model.onTextChanged("x", 0, 1)
        advanceTimeBy(CodeEditorViewModel.AUTOSAVE_DEBOUNCE_MS + 10)
        advanceUntilIdle()

        assertEquals(CodeSaveStatus.ERROR, model.state.value.saveStatus)
        assertTrue(model.state.value.dirty)
        assertEquals("Save failed", saveStateLabel(model.state.value))

        model.onTextChanged("xy", 0, 2)
        advanceTimeBy(CodeEditorViewModel.AUTOSAVE_DEBOUNCE_MS + 10)
        advanceUntilIdle()
        assertEquals(1, repository.saveCount)
        assertEquals("xy", repository.itemById("f1")!!.body)
        assertEquals(CodeSaveStatus.SAVED, model.state.value.saveStatus)
    }

    @Test
    fun `file over 2 MiB is refused and left untouched`() = runTest(dispatcher) {
        val huge = "a".repeat(CodeEditorViewModel.MAX_OPEN_BYTES + 1)
        seed(body = huge)
        val model = viewModel()
        advanceUntilIdle()

        val state = model.state.value
        assertFalse(state.loading)
        assertTrue(state.tooLarge)
        assertEquals("", state.body)
        assertEquals("Main.kt", state.fileName)
        assertFalse(model.flush(force = true))
        assertTrue(model.saveOnLeave())
        advanceUntilIdle()
        assertEquals(0, repository.saveCount)
        assertEquals(huge, repository.itemById("f1")!!.body)
    }

    @Test
    fun `file over 256 KiB opens without syntax highlighting`() = runTest(dispatcher) {
        val large = "a".repeat(CodeEditorViewModel.MAX_HIGHLIGHT_BYTES + 1)
        seed(body = large)
        val model = viewModel()
        advanceUntilIdle()

        val state = model.state.value
        assertFalse(state.tooLarge)
        assertEquals(large, state.body)
        assertFalse(state.syntaxHighlighting)

        val boundary = "a".repeat(CodeEditorViewModel.MAX_HIGHLIGHT_BYTES)
        repository.setItems(listOf(repository.itemById("f1")!!.copy(body = boundary)))
        val second = viewModel()
        advanceUntilIdle()
        assertTrue(second.state.value.syntaxHighlighting)
    }

    @Test
    fun `language is detected from the file extension only`() = runTest(dispatcher) {
        repository.seedCodeFile(id = "kt", title = "Main.kt")
        repository.seedCodeFile(id = "js", title = "index.js")
        repository.seedCodeFile(id = "md", title = "README.md")
        repository.seedCodeFile(id = "txt", title = "NOTES")

        val kotlin = viewModel("kt")
        val javascript = viewModel("js")
        val markdown = viewModel("md")
        val plain = viewModel("txt")
        advanceUntilIdle()

        assertEquals(CodeLanguage.KOTLIN, kotlin.state.value.language)
        assertEquals(CodeLanguage.JAVASCRIPT, javascript.state.value.language)
        assertEquals(CodeLanguage.MARKDOWN, markdown.state.value.language)
        assertTrue(markdown.state.value.isMarkdown)
        assertEquals(CodeLanguage.PLAIN, plain.state.value.language)
        assertFalse(plain.state.value.isMarkdown)
    }

    @Test
    fun `font size is clamped between 12 and 24 and persisted`() = runTest(dispatcher) {
        seed()
        val model = viewModel()
        advanceUntilIdle()
        assertEquals(CodeEditorPrefs.DEFAULT_FONT_SIZE_SP, model.state.value.fontSizeSp)

        repeat(20) { model.increaseFontSize() }
        assertEquals(CodeEditorPrefs.MAX_FONT_SIZE_SP, model.state.value.fontSizeSp)
        assertEquals(CodeEditorPrefs.MAX_FONT_SIZE_SP, preferences.fontSizeSp)

        repeat(40) { model.decreaseFontSize() }
        assertEquals(CodeEditorPrefs.MIN_FONT_SIZE_SP, model.state.value.fontSizeSp)
        assertEquals(CodeEditorPrefs.MIN_FONT_SIZE_SP, preferences.fontSizeSp)
    }

    @Test
    fun `word wrap toggle is persisted`() = runTest(dispatcher) {
        seed()
        val model = viewModel()
        advanceUntilIdle()
        assertTrue(model.state.value.wordWrap)

        model.toggleWordWrap()
        assertFalse(model.state.value.wordWrap)
        assertFalse(preferences.wordWrap)

        model.toggleWordWrap()
        assertTrue(preferences.wordWrap)
    }

    @Test
    fun `find reports matches and cycles next and previous`() = runTest(dispatcher) {
        seed(body = "main main MAIN")
        val model = viewModel()
        advanceUntilIdle()

        model.setFindQuery("main")
        // Case-insensitive by default: "main", "main", "MAIN".
        assertEquals(3, model.state.value.matches.size)
        assertEquals("1/3", model.state.value.matchLabel)
        assertEquals(listOf(0, 5, 10), model.state.value.matches.map { it.start })

        model.findNext()
        assertEquals(1, model.state.value.currentMatch)
        assertEquals(5, model.state.value.cursor)
        assertEquals("2/3", model.state.value.matchLabel)
        model.findNext()
        assertEquals(2, model.state.value.currentMatch)
        assertEquals(10, model.state.value.cursor)
        model.findNext()
        assertEquals(0, model.state.value.currentMatch)
        model.findPrevious()
        assertEquals(2, model.state.value.currentMatch)

        model.toggleCaseSensitive()
        assertEquals(2, model.state.value.matches.size)
        model.setFindQuery("zzz")
        assertEquals(0, model.state.value.matches.size)
        assertEquals("No matches", model.state.value.matchLabel)
        assertNull(model.findNext())
    }

    @Test
    fun `replace current and replace all rewrite the draft`() = runTest(dispatcher) {
        seed(body = "foo foo FOO")
        val model = viewModel()
        advanceUntilIdle()

        model.setFindQuery("foo")
        model.setReplaceQuery("bar")
        assertTrue(model.replaceCurrent())
        assertEquals("bar foo FOO", model.state.value.body)
        assertEquals(2, model.state.value.matches.size)

        assertTrue(model.replaceAll())
        assertEquals("bar bar bar", model.state.value.body)
        assertEquals(0, model.state.value.matches.size)
        assertFalse(model.replaceAll())
    }

    @Test
    fun `go to line moves the cursor and rejects out of range lines`() = runTest(dispatcher) {
        seed(body = "line one\nline two\nline three")
        val model = viewModel()
        advanceUntilIdle()

        assertTrue(model.goToLine(2))
        assertEquals("line one\n".length, model.state.value.cursor)

        assertTrue(model.goToLine(3))
        assertEquals("line one\nline two\n".length, model.state.value.cursor)

        assertFalse(model.goToLine(0))
        assertFalse(model.goToLine(99))
        assertEquals("line one\nline two\n".length, model.state.value.cursor)
    }

    @Test
    fun `undo and redo walk the bounded history`() = runTest(dispatcher) {
        seed()
        val model = viewModel()
        advanceUntilIdle()

        model.onTextChanged("a", 0, 1)
        model.onTextChanged("ab", 0, 2)
        assertTrue(model.state.value.canUndo)

        model.undo()
        assertEquals("a", model.state.value.body)
        model.undo()
        assertEquals("", model.state.value.body)
        assertFalse(model.state.value.canUndo)

        model.redo()
        assertEquals("a", model.state.value.body)
        model.redo()
        assertEquals("ab", model.state.value.body)
        assertFalse(model.state.value.canRedo)
    }

    @Test
    fun `undo history is bounded`() = runTest(dispatcher) {
        seed()
        val model = viewModel()
        advanceUntilIdle()

        repeat(80) { index -> model.onTextChanged("x".repeat(index + 1), 0, index + 1) }
        var undoSteps = 0
        while (model.state.value.canUndo && undoSteps < 100) {
            model.undo()
            undoSteps++
        }
        assertTrue("undo history must be bounded", undoSteps <= 50)
        assertTrue(undoSteps > 0)
        assertFalse(model.state.value.canUndo)
    }

    @Test
    fun `enter auto-indents after an opening brace`() = runTest(dispatcher) {
        val body = "fun main() {"
        seed(body = body)
        val model = viewModel()
        advanceUntilIdle()

        val withNewline = "$body\n"
        model.onTextChanged(withNewline, withNewline.length, withNewline.length)

        assertEquals("$body\n    ", model.state.value.body)
        assertEquals("$body\n    ".length, model.state.value.cursor)
    }

    @Test
    fun `enter on a plain line only adds the newline`() = runTest(dispatcher) {
        val body = "val x = 1"
        seed(body = body)
        val model = viewModel()
        advanceUntilIdle()

        val withNewline = "$body\n"
        model.onTextChanged(withNewline, withNewline.length, withNewline.length)

        assertEquals(withNewline, model.state.value.body)
        assertEquals(withNewline.length, model.state.value.cursor)
    }

    @Test
    fun `bracket highlight appears when the cursor sits on a bracket`() = runTest(dispatcher) {
        seed(body = "fun main() {}")
        val model = viewModel()
        advanceUntilIdle()

        model.onSelectionChanged(8, 8)
        val highlight = model.state.value.bracketHighlight
        assertNotNull(highlight)
        assertEquals(8, highlight!!.start)
        assertEquals(10, highlight.end)

        model.onSelectionChanged(3, 3)
        assertNull(model.state.value.bracketHighlight)
    }

    @Test
    fun `save status labels match the visible contract`() {
        val loading = CodeEditorViewModel.State(loading = true)
        assertEquals("Loading…", saveStateLabel(loading))
        assertEquals("Saved", saveStateLabel(CodeEditorViewModel.State(loading = false)))
        assertEquals(
            "Unsaved changes",
            saveStateLabel(CodeEditorViewModel.State(loading = false, dirty = true)),
        )
        assertEquals(
            "Saving…",
            saveStateLabel(
                CodeEditorViewModel.State(loading = false, saveStatus = CodeSaveStatus.SAVING),
            ),
        )
        assertEquals(
            "Save failed",
            saveStateLabel(
                CodeEditorViewModel.State(loading = false, saveStatus = CodeSaveStatus.ERROR),
            ),
        )
        assertEquals(
            "Conflict — unsaved edits",
            saveStateLabel(CodeEditorViewModel.State(loading = false, conflict = true)),
        )
    }

    @Test
    fun `locking the vault stops writes`() = runTest(dispatcher) {
        seed()
        val model = viewModel()
        advanceUntilIdle()

        repository.setStatus(VaultStatus.LOCKED)
        model.onTextChanged("should not save", 0, 15)
        advanceTimeBy(CodeEditorViewModel.AUTOSAVE_DEBOUNCE_MS + 10)
        advanceUntilIdle()

        assertEquals(0, repository.saveCount)
        assertTrue(model.state.value.dirty)
    }

    // ---- Open-at-line, indent and copy payloads -------------------------------

    @Test
    fun `initialLine opens the draft with the caret on that one-based line`() = runTest(dispatcher) {
        seed(body = "l1\nl2\nl3")
        val model = CodeEditorViewModel(repository, "f1", preferences, initialLine = 2)
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(3, state.cursor)
        assertEquals(3, state.selectionEnd)
        assertEquals(1, state.navigationSeq)
        assertFalse(state.dirty)
    }

    @Test
    fun `initialLine out of range is ignored`() = runTest(dispatcher) {
        seed(body = "l1\nl2")
        val beyond = CodeEditorViewModel(repository, "f1", preferences, initialLine = 99)
        advanceUntilIdle()
        assertEquals(0, beyond.state.value.cursor)
        assertEquals(0, beyond.state.value.navigationSeq)

        val zero = CodeEditorViewModel(repository, "f1", preferences, initialLine = 0)
        advanceUntilIdle()
        assertEquals(0, zero.state.value.cursor)
        assertEquals(0, zero.state.value.navigationSeq)
    }

    @Test
    fun `goToLine bumps the navigation counter so the field can reveal the caret`() = runTest(dispatcher) {
        seed(body = "one\ntwo\nthree")
        val model = viewModel()
        advanceUntilIdle()
        assertEquals(0, model.state.value.navigationSeq)

        assertTrue(model.goToLine(3))
        assertEquals(8, model.state.value.cursor)
        assertEquals(1, model.state.value.navigationSeq)

        assertFalse(model.goToLine(99))
        assertEquals(1, model.state.value.navigationSeq)
    }

    @Test
    fun `indentSelection indents every touched line and maps the selection`() = runTest(dispatcher) {
        seed(body = "a\nb\nc")
        val model = viewModel()
        advanceUntilIdle()
        model.onSelectionChanged(0, 3)

        model.indentSelection()

        val state = model.state.value
        assertEquals("    a\n    b\nc", state.body)
        assertEquals(4, state.cursor)
        assertEquals(11, state.selectionEnd)
        assertTrue(state.dirty)
        assertTrue(state.canUndo)
    }

    @Test
    fun `outdentSelection removes one unit from every touched line`() = runTest(dispatcher) {
        seed(body = "    a\n    b\nc")
        val model = viewModel()
        advanceUntilIdle()
        model.onSelectionChanged(4, 11)

        model.outdentSelection()

        val state = model.state.value
        assertEquals("a\nb\nc", state.body)
        assertEquals(0, state.cursor)
        assertEquals(3, state.selectionEnd)
    }

    @Test
    fun `indent with an empty selection indents the caret line like Tab`() = runTest(dispatcher) {
        seed(body = "x\ny")
        val model = viewModel()
        advanceUntilIdle()
        model.onSelectionChanged(2, 2)

        model.indentSelection()

        val state = model.state.value
        assertEquals("x\n    y", state.body)
        assertEquals(6, state.cursor)
        assertEquals(6, state.selectionEnd)
    }

    @Test
    fun `outdent on an unindented line is a no-op`() = runTest(dispatcher) {
        seed(body = "abc")
        val model = viewModel()
        advanceUntilIdle()
        model.onSelectionChanged(0, 0)

        model.outdentSelection()

        val state = model.state.value
        assertEquals("abc", state.body)
        assertFalse(state.dirty)
        assertFalse(state.canUndo)
        assertEquals(0, repository.saveCount)
    }

    @Test
    fun `indent autosaves and undo restores the unindented draft`() = runTest(dispatcher) {
        seed(body = "a")
        val model = viewModel()
        advanceUntilIdle()
        model.onSelectionChanged(0, 1)

        model.indentSelection()
        assertEquals("    a", model.state.value.body)
        advanceTimeBy(CodeEditorViewModel.AUTOSAVE_DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals("    a", repository.itemById("f1")!!.body)

        model.undo()
        assertEquals("a", model.state.value.body)
    }

    @Test
    fun `copySelectionOrAll prefers the selection and falls back to the whole draft`() = runTest(dispatcher) {
        seed(body = "abcd")
        val model = viewModel()
        advanceUntilIdle()

        model.onSelectionChanged(1, 3)
        assertEquals("bc", model.copySelectionOrAll())

        model.onSelectionChanged(2, 2)
        assertEquals("abcd", model.copySelectionOrAll())

        // Caret at the very end (empty selection) still copies the whole draft.
        model.onSelectionChanged(4, 4)
        assertEquals("abcd", model.copySelectionOrAll())

        val missing = viewModel("missing")
        advanceUntilIdle()
        assertEquals("", missing.copySelectionOrAll())
    }
}
