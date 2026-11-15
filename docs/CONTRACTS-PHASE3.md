# NoteZero — Phase 3 (increment 2) contracts

Scope of this increment: **close the Phase-2 gap (SAF encrypted backup UI)** and
**start Phase 3 (code editor)**. Decisions already frozen in `docs/DECISIONS.md`
(custom Compose editor, pure-Kotlin lexers, single type-tagged item model).

Read `docs/CONTRACTS.md` rules first; they still apply (no new deps, no network, no plaintext
secrets in logs, stay in scope, no git, no build-file edits, pure-JVM logic where possible).

---

## A. Backup UI + external-activity lock (owner: backup-owner)

### A.1 Repository API additions (`data/NoteRepository.kt` + `EncryptedNoteRepository`)

```kotlin
/** Call immediately before launching a system activity (SAF picker). */
fun beginExternalActivity()

/** Call when that activity's result arrives (success, cancel or error). */
fun endExternalActivity()
```

Required semantics:
- While an external activity is active, `onAppBackgrounded()` MUST NOT lock the vault
  (that ON_STOP is caused by the picker, not by the user leaving). Record the pending
  background state instead.
- `onAppForegrounded()` on return clears the pending state.
- `endExternalActivity()` with no foreground yet (process came back weirdly) locks immediately.
- Idle auto-lock keeps working normally; a vault that stays backgrounded past the timeout
  still locks.
- No plaintext is written by any of this. Thread-safe; no leaks.

Tests (Robolectric/JVM, real repository where practical):
- background during external activity → still UNLOCKED, items available;
- end while foreground → stays UNLOCKED;
- end while backgrounded → LOCKED;
- idle timeout still locks while external activity is active (no permanent suppression);
- existing background-lock tests still pass.

### A.2 Backup flow controller (testable, no Android SAF types)

`app/src/main/java/com/cipherwell/notezero/ui/backup/BackupFlowController.kt`

The UI only obtains streams from SAF; the controller must be unit-testable with injected
stream providers (fake in tests, real `ContentResolver` adapter in the app):

```kotlin
enum class BackupMode { EXPORT_VAULT_KEY, EXPORT_PASSWORD, IMPORT }

sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object PickingLocation : BackupUiState
    data class Running(val mode: BackupMode) : BackupUiState
    data class Success(val mode: BackupMode, val report: BackupImportReport?) : BackupUiState
    data object NeedsCredential : BackupUiState      // import from another vault
    data class Error(val kind: BackupErrorKind) : BackupUiState
}
enum class BackupErrorKind { INVALID_FILE, WRONG_CREDENTIAL, IO, LOCKED, EMPTY_VAULT }

class BackupFlowController(repository, openOut: (uri) -> OutputStream?, openIn: (uri) -> InputStream?, clock)
```

Behaviour:
- Export: `repository.beginExternalActivity()` before launching SAF, `endExternalActivity()`
  when the result arrives, then `repository.exportBackup(stream, passwordOrNull)`.
  Vault-key mode passes `password = null`; optional separate-password mode passes the
  user password and the UI must offer a confirm field.
- Import: try `repository.importBackup(stream, null)`; if it returns null (cross-vault
  envelope needs credentials) → `NeedsCredential`, accept either the **source master
  password** or the **source 12-word phrase** (joined with single spaces as `CharArray`),
  retry; cap attempts at 3 then `Error(WRONG_CREDENTIAL)`.
- If the repository is LOCKED when the picker result arrives (timeout/background), keep the
  pending mode + uri and resume after the user unlocks — the controller must expose
  `onVaultUnlocked()` to continue; never drop the user's chosen file silently.
- Errors are non-secret enums only; never log content, paths or credentials.
- Result/state exposed as `StateFlow<BackupUiState>`; `reset()` returns to Idle.

### A.3 Settings UI

`SettingsScreen.kt`: add a "Data & backup" section (tag `settings_backup_section`):
- `settings_backup_export` — "Export encrypted backup" → SAF `CreateDocument` (MIME
  `application/octet-stream`, suggested name `notezero-backup-YYYYMMDD-HHmm.nzbk`).
- Optional separate-password checkbox/flow (`settings_backup_password`,
  `settings_backup_password_confirm`) before export.
- `settings_backup_import` — SAF `OpenDocument` → shows report counts (imported/duplicated/
  skipped/folders) or the credential prompt (`settings_backup_credential_field`,
  `settings_backup_confirm`, `settings_backup_cancel`).
- `settings_backup_status` for progress/success/error text (non-secret).
- The section must also state that backups are encrypted and that nothing leaves the device
  except the file the user picks.

### A.4 Docs to update

- `docs/BACKUP-FORMAT.md`: UI flow + resume-after-unlock rule.
- `docs/ARCHITECTURE.md`: replace the SAF caveat with the external-activity mechanism.
- `docs/DECISIONS.md`: add decision #13 — external-activity lock deferral (picker returns
  to the same unlocked vault; idle timeout still applies; no permanent suppression).
- `docs/TESTING.md`: add the backup-flow test rows.

Do NOT edit `docs/CONTRACTS-PHASE3.md`, `ui/AppRoot.kt`, `ui/notes/**`, `code/**`, `ui/code/**`.

---

## B. Code editor engine (owner: code-engine)

New package `com.cipherwell.notezero.code` — **pure Kotlin, no android.* / Compose imports**,
fully unit-testable on the JVM. Files: `Language.kt`, `SyntaxToken.kt`, `CodeLexers.kt`,
`LineIndex.kt`, `CodeEditing.kt` (+ per-language lexers if you prefer).

```kotlin
enum class CodeLanguage { KOTLIN, JAVA, JAVASCRIPT, TYPESCRIPT, PYTHON, JSON, XML, MARKDOWN, SHELL, PLAIN }

object Languages {
    fun fromFileName(fileName: String): CodeLanguage        // extension based, case-insensitive
    val editorLanguages: List<CodeLanguage>                 // all except PLAIN
    fun defaultFileName(language: CodeLanguage): String     // e.g. "untitled.kt"
}

enum class TokenType {
    KEYWORD, TYPE, STRING, CHAR, NUMBER, COMMENT, ANNOTATION, OPERATOR,
    PUNCTUATION, IDENTIFIER, PROPERTY, TAG, ATTRIBUTE, HEADING, LINK, CODE_SPAN, ERROR,
}

data class SyntaxToken(val start: Int, val end: Int, val type: TokenType) // [start,end) offsets

interface CodeLexer {
    val language: CodeLanguage
    /** Non-overlapping, ascending offsets; never throws, bounded work. */
    fun tokenize(text: String): List<SyntaxToken>
}

object CodeLexers { fun forLanguage(language: CodeLanguage): CodeLexer }
```

Requirements:
- Languages: Kotlin, Java, JS, TS, Python, JSON, XML, Shell, Markdown (PLAIN = no tokens).
- Single-pass char scanners; no catastrophic regex; handle unterminated strings/comments,
  escaped quotes, triple-quoted strings, raw strings, template `${...}` (Kotlin/JS), Python
  docstrings/triple quotes, XML tags/attributes/comments/CDATA, shell `$VAR`/`${...}`/heredoc
  best-effort, Markdown headings/fences/inline code/links.
- Token budget: cap at 200_000 tokens (return what fits, no crash).
- Performance target: tokenize 200 KB of source in < 250 ms on the test machine (assert with a
  generous bound so CI is stable), and never allocate per-character lists.

Editing helpers (pure, immutable API):

```kotlin
class LineIndex private constructor(...) {
    val lineCount: Int
    fun lineOf(offset: Int): Int
    fun startOfLine(line: Int): Int
    fun endOfLine(text: String, line: Int): Int
    companion object { fun of(text: String): LineIndex }
}

data class TextRange(val start: Int, val end: Int)   // clamp helpers as needed

object CodeEditing {
    fun indentUnit(language: CodeLanguage, useTabs: Boolean, tabWidth: Int): String
    /** Text to insert at a newline given the line being split (auto-indent). */
    fun autoIndent(text: String, cursor: Int, language: CodeLanguage, useTabs: Boolean, tabWidth: Int): String
    fun indentSelection(text: String, selection: TextRange, outdent: Boolean, useTabs: Boolean, tabWidth: Int): Pair<String, TextRange>
    fun matchingBracket(text: String, offset: Int): Int?
    fun findMatches(text: String, query: String, caseSensitive: Boolean, limit: Int = 10_000): List<TextRange>
    fun replaceAll(text: String, query: String, replacement: String, caseSensitive: Boolean): String
    fun offsetForLine(text: String, line: Int): Int
}
```

Tests: per-language token assertions (keywords, strings, comments, numbers, XML tag/attr,
Markdown heading/fence/link), unterminated constructs, empty/random fuzz (no throw, monotonic
offsets), LineIndex edge cases (empty text, trailing newline, CRLF), editing ops (auto-indent
after `{`/`:`/`<tag>` context, outdent, bracket matching nested/quotes, find/replace case
modes, go-to-line clamp), 200 KB performance assertion.

Docs: `docs/EDITOR-SPEC.md` (languages, token mapping, editing behaviour, performance limits).

Do NOT edit UI packages, `data/`, build files or docs owned by others.

---

## C. Code workspace UI (owner: code-ui)

New package `com.cipherwell.notezero.ui.code` (+ tests under `test/.../ui/code/`).

Entry point (Lead wires it, exact symbol mandatory):

```kotlin
@Composable
fun CodeWorkspaceScreen(repository: NoteRepository, onBack: () -> Unit)
```

Content:
- **File tree**: folders + items with `ItemKind.CODE_FILE` from `repository.folders` /
  `repository.items`; nested tree with expand/collapse; create file (name dialog, language
  from extension, default name), create folder, rename, move, delete; opening a file shows
  the editor. Scrollable, 48dp targets, empty state.
- **Code editor** (`CodeEditorScreen.kt`): multiline `BasicTextField`/`TextField` with
  - line-number gutter synced to scroll, current-line highlight,
  - syntax colours from `CodeLexers` tokens + `TokenType` → theme colours (tokens live in
    `ui/theme`; define a `CodeSyntaxColors` palette there? NO — theme package is Lead-owned;
    put a `CodeSyntaxPalette` object inside `ui/code/` derived from `MaterialTheme` colours
    and `NoteZeroPalette` accent; no raw hex outside `ui/theme`),
  - auto-indent on Enter via `CodeEditing.autoIndent`, bracket-match highlight,
  - find/replace bar (next/prev, replace, replace all, case toggle, match count),
  - go-to-line dialog, undo/redo (bounded in-memory history), font-size +/- (12–24sp,
    non-secret prefs via `SharedPreferences` in `ui/code/CodeEditorPrefs.kt`), wrap toggle,
    monospace always,
  - autosave debounce 600 ms via `repository.saveItem`, explicit save state
    (`dirty/saved/error`), conflict handling using `Item.version` like the notes editor,
  - file size guard: > 2 MiB refuses to open with a message; > 256 KiB renders without
    syntax colours (plain monospace) to protect typing latency.
- **Markdown files** (`ItemKind.CODE_FILE` with .md/.markdown extension): offer the existing
  `ui.editor.MarkdownPreview` preview toggle (reuse, do not duplicate).
- Tags for the E2E test (stable): `code_workspace`, `code_back`, `code_file_tree`,
  `code_new_file`, `code_new_folder`, `code_file_row`, `code_folder_row`, `code_editor`,
  `code_line_numbers`, `code_find_toggle`, `code_find_query`, `code_find_next`,
  `code_find_prev`, `code_replace_field`, `code_replace_all`, `code_goto_line`,
  `code_font_increase`, `code_font_decrease`, `code_wrap_toggle`, `code_save_state`,
  `code_dialog_field`, `code_dialog_confirm`, `code_dialog_cancel`, `code_preview_toggle`.
  Every icon-only button needs a `contentDescription`.
- No logging of file content/names; no new dependencies; no edits outside `ui/code/**` +
  its tests (Lead wires `AppRoot`/notes entry).

Tests: ViewModel with `FakeNoteRepository` (create/open/save/rename/move/delete, debounce,
dirty/conflict, size guards, markdown detection), pure tree-building tests, Robolectric
Compose tests for tree/editor basics/find bar/tags. Report exact counts.

---

## D. Lead (integration)

- `ui/AppRoot.kt`: add `ROUTE_CODE`; pass `onOpenCode` to notes; render
  `CodeWorkspaceScreen(repository, onBack = { unlockedRoute = ROUTE_NOTES })`.
- `ui/notes/NotesListScreen.kt`: add `onOpenCode: () -> Unit = {}` parameter + top-bar Code
  action (`testTag("nav_code")`, contentDescription "Code workspace").
- Extend `VaultFlowE2ETest`: open code workspace via `nav_code` → create file → type code →
  verify persisted as `ItemKind.CODE_FILE` and encrypted at rest (extend the plaintext scan) →
  screenshots of tree + highlighted editor.
- Full unit suite + device suite, docs/CHANGELOG/VERIFICATION, commit, push, CI.
