# Code workspace (IDE)

The encrypted code workspace renders the vault's `ItemKind.CODE_FILE` items as a folder
tree and edits one file at a time in a syntax-highlighted editor. This document covers the
UI layer under `app/src/main/java/com/cipherwell/notezero/ui/code/`; storage, crypto and the
item payload live in the data layer and are unchanged by the IDE features below.

Contract history: base workspace `docs/CONTRACTS-PHASE3.md`, editor polish
`docs/CONTRACTS-PHASE4.md`, IDE upgrades `docs/CONTRACTS-PHASE14.md`.

## Layout

| File | Responsibility |
| --- | --- |
| `CodeWorkspaceScreen.kt` | Tree shell, toolbar actions, tabs host, create/rename/move/delete dialogs |
| `CodeWorkspaceViewModel.kt` | Folder tree, workspace search, quick-open index, file mutations |
| `CodeEditorScreen.kt` | Editor chrome, find/replace, go-to-line, Markdown preview/split, save-on-leave |
| `CodeEditorViewModel.kt` | Draft, bounded undo/redo, autosave, conflict state (keyed `code_editor_<itemId>`) |
| `CodeEditorField.kt` | Text field, line-number gutter, syntax colours, bracket/current-line highlight |
| `CodeTabs.kt` | Tab model (`CodeTabs` open/close/cap) and the tab strip composable |
| `CodeQuickOpen.kt` | Quick-open index/filter (`CodeQuickOpen`) and the quick-open dialog |
| `CodeTemplates.kt` | `NewCodeFileRequest`, `CodeTemplate`, built-in starter snippets |
| `CodeDialogs.kt` | Shared dialogs; `NewCodeFileDialog` (formats + templates) |
| `CodeTree.kt` / `CodeTreeRows.kt` | Pure tree building and tree row composables |
| `CodeSearchBar.kt` | Workspace-wide search chrome/results |
| `CodeEditorPrefs.kt` | Font-size / wrap preferences |
| `CodeExport.kt` | Explicit decrypted-copy export (SAF) |
| `CodeTags.kt` | Stable E2E/Robolectric test tags |

Security: decrypted file names and source live only in memory (workspace ViewModel,
editor ViewModel, quick-open entries, tab titles). Nothing is logged, nothing is written
outside the encrypted repository. Locked + unverified code files and every descendant of a
locked folder are filtered before the tree, search, quick-open and tabs see them; the
editor keeps a second gate before its ViewModel loads source.

## IDE upgrades (increment 15, `docs/CONTRACTS-PHASE14.md`)

### Multi-file tabs (§A)

- `CodeWorkspaceScreen` keeps `openFileIds: List<String>` (rememberSaveable via an explicit
  list saver) and `activeFileId: String?`; the cap is `CodeTabs.MAX_OPEN_TABS = 8`.
- Opening from the tree or quick open appends a tab (or activates the existing one, never a
  duplicate) and bumps the open token so a pending search-hit line is re-applied by the
  editor (`initialLine`/`openToken`).
- While editing, `CodeTabsStrip` renders above the editor: horizontally scrollable tabs
  (`code_tabs`, `code_tab`), selected semantics for the active file, title = item title with
  the `code_tab_untitled` fallback, and a separate 48dp close target (`code_tab_close`,
  contentDescription `cd_code_close_tab`).
- Editor state is preserved per tab two ways: the editor ViewModel is keyed
  `code_editor_<itemId>` and lives in the screen's ViewModel store (never cleared on a tab
  switch), and `rememberSaveableStateHolder().SaveableStateProvider(itemId)` keeps the
  per-tab composable state (find bar, preview mode, scroll/focus chrome).
- Closing the active tab activates the left neighbour, else the right one, else returns to
  the tree. Back from the editor returns to the tree **without closing tabs**; re-opening a
  tabbed file activates its existing tab.
- Opening a ninth file shows the inline non-secret `code_tab_limit` notice
  (`code_tab_limit_notice` tag) and does not open it.

### Quick open (§B)

- Toolbar action `code_quick_open` (contentDescription `cd_code_quick_open`) in the tree
  shell opens `quick_open_dialog`.
- `quick_open_query` (hint `code_quick_open_hint`, IME Done opens the first match) filters
  `quick_open_result` rows case-insensitively over title, folder path and extension.
- Only visible code files are listed: locked/hidden files never reach the index, notes and
  trashed files are excluded, and ordering is last-updated first then title
  (`code_quick_open_recent` labels the unfiltered list). `code_quick_open_empty` replaces the
  list when nothing matches; tapping a row opens the file in a tab.

### New-file templates (§C)

- `NewCodeFileDialog` keeps its name field and format chips (`code_format_<ext>`) and adds a
  template row (`code_template_label`): Blank (`code_template_blank`, default) and Kotlin,
  Python, Markdown, HTML and JSON chips. A template swaps the suggested name, the extension
  and the starter body; choosing a format that disagrees with the template falls back to the
  blank body so a Kotlin snippet can never be stored behind a `.py` name.
- Confirm reports `NewCodeFileRequest(name, body)`. The dialog also retains the legacy
  `onConfirm(name)` callback for the notes-shell call site until it migrates to
  `onConfirmRequest`; the workspace uses the request form.
- `CodeWorkspaceViewModel.createFile(rawName, folderId, body = "")` saves the body through the
  normal encrypted `saveItem` path and the created file opens in a tab.

### Split Markdown preview (§D)

On wide windows (`>= 600dp`; overridable through the injectable `wideLayout` parameter for
tests) the Markdown toolbar shows an extra `Split` chip (`code_split_preview`) once preview
is on. Split renders the editor and `MarkdownPreview` side by side; compact layouts keep the
existing single-pane preview. Existing `code_preview_toggle`/`code_markdown_preview` tags are
unchanged, and the preview pane carries contentDescription `cd_code_preview_pane`.

## Stable tags

All tags live in `CodeTags` and must never be renamed/reused (E2E suite + Robolectric tests
address them). IDE additions: `code_tabs`, `code_tab`, `code_tab_close`,
`code_tab_limit_notice`, `code_quick_open`, `quick_open_dialog`, `quick_open_query`,
`quick_open_result`, `code_quick_open_empty`, `code_quick_open_recent`, the
`code_template_*` family + `code_template_label`, and `code_split_preview`.

## Tests

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./gradlew :app:testDebugUnitTest --tests "com.cipherwell.notezero.ui.code.*" --console=plain
```

JVM helpers: `CodeTabsTest` (open/activate/close/cap), `CodeQuickOpenTest` (index/filter/
ordering), `CodeTemplatesTest` (unique ids, known extensions, blank default). Dialog tests
use `@Config(sdk = [34])` and no display qualifiers (Robolectric OOM rule); UI tests use
`createComposeRule` with `FakeCodeRepository` (no crypto/Room). The split preview is tested
through the injectable `wideLayout` flag rather than a display qualifier.
