# NoteZero — Phase-14 (increment 15) contract: code-workspace IDE upgrades

Frozen for increment 15. UI/tests address these names only.

## Scope

Make the code workspace feel like an IDE without adding dependencies or changing storage:
**multi-file tabs** (state-preserving), a **quick-open** file switcher, and **new-file templates**.
Optional stretch: a **split** Markdown preview beside the editor on wide layouts (the existing
single-pane preview toggle already exists).

No data-model, payload, permission or dependency changes; everything is in-memory UI state +
existing repository APIs.

## A. Multi-file tabs (`ui/code/**`)

- `CodeWorkspaceScreen` keeps `openFileIds: List<String>` (rememberSaveable, max
  `MAX_OPEN_TABS = 8`) and `activeFileId: String?`; opening a file from the tree or quick-open
  activates it and appends it if new. Locked+unverified files are never opened/tabbed (existing
  workspace filter + editor gate remain).
- While editing, a horizontally scrollable tab strip (`code_tabs`) sits above the editor:
  rows tag `code_tab` (one per open file), active tab selected semantics, title = item title
  (fallback `code_tab_untitled`), close button tag `code_tab_close` with contentDescription
  `cd_code_close_tab`.
- Switching tabs must **preserve each file's editor state** (draft, undo history, find/font
  preferences per tab) — `CodeEditorScreen` already keys its ViewModel with
  `key = "code_editor_$itemId"`, so the workspace must keep those editors' ViewModel entries alive
  while the workspace is composed (do not clear the ViewModel store between tabs). The content
  text field tag (`code_editor_field`) and existing tags/behavior stay unchanged.
- Close tab: removes it; if it was active, activate the neighbour to the left (or right, else
  return to the tree). Back from the editor returns to the file tree **without closing tabs**;
  re-opening an already-tabbed file activates its tab. Opening more than the cap shows the
  non-secret `code_tab_limit` (snackbar/inline notice) and does not open.
- Tab click semantics must be 48dp targets and content descriptions; large-font safe; no
  content logging.

## B. Quick open (`ui/code/**`)

- Toolbar action tag `code_quick_open` (contentDescription `cd_code_quick_open`) in the file tree
  shell opens dialog tag `quick_open_dialog`: search field `quick_open_query` (hint
  `code_quick_open_hint`), results tag `quick_open_result` (code files only, filtered by
  case-insensitive title/path/extension; excluded locked/hidden folders; sorted by last updated,
  then title), empty state `code_quick_open_empty`. Tap/Enter opens the file (adds/activates a
  tab). Dialog uses `@Config(sdk=[34])`-friendly Compose (tests avoid display qualifiers).
- Optional recent label `code_quick_open_recent` above results; no separate recent store.

## C. New-file templates (`ui/code/**`, `NotesListScreen` wiring by Lead)

- `NewCodeFileDialog` gains a template chip row (tags `code_template_blank`, `code_template_kotlin`,
  `code_template_python`, `code_template_markdown`, `code_template_html`, `code_template_json`;
  label `code_template_label`) that swaps the extension/name suggestion and the file body.
- The dialog returns a `NewCodeFileRequest(name: String, body: String)` (or equivalent
  immutable type) instead of a bare name; default template = blank. Templates are local static
  snippets (no user data): Kotlin class, Python script, Markdown note, HTML page, JSON config.
- Workspace create flow writes that body through the existing repository create/save path (extend
  `CodeWorkspaceViewModel.createFile(name, parentId, body)` or equivalent); created file opens in a
  new tab. Lead will update the notes-shell create path (`NotesListScreen`/`NotesViewModel`) to
  pass the body; expose the needed ViewModel/API publicly from your work and tell the Lead what to
  wire.
- Tags/strings pre-added; no content logging.

## D. Optional split preview (only if low-risk)

For Markdown files, on wide layouts the existing `previewMode` toggle may show `Split` (string
`code_split_preview`) and render the editor and `MarkdownPreview(document)` side by side; on
compact widths keep the existing single-pane preview. Must not change existing preview tags.
Skip if it risks the other deliverables; report if skipped.

## E. Tests (`ui/code` package; Robolectric + JVM helpers)

- Tabs: tree-open creates a tab; opening second file → two tabs; switching tabs preserves the
  first file's draft/undo (keyed ViewModel; verify text persists via `code_editor_field`);
  closing the active tab activates the neighbour; Back to tree keeps tabs; reopening a tabbed
  file activates its existing tab; max-tab notice.
- Quick open: filtering, empty state, locked file excluded, opening adds/activates a tab.
- Templates: each chip changes the suggested name/extension and the created file's body
  (workspace VM/repository assertion); blank default; dialog cancel creates nothing.
- Existing code workspace/editor tests + E2E tags stay green (especially `nav_code`,
  `code_workspace`, `code_editor_field`, tree/search/export tags).
- Keep dumps/logging/TODO out; no new dependencies/permissions.

## F. Lead integration

- `NotesListScreen`/`NotesViewModel` code-file creation updated for the new
  `NewCodeFileRequest` body; strings/docs/repro/verification/device QA.
