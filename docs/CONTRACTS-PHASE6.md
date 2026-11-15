# NoteZero — Phase 4 completion (increment 6) contracts

Scope: finish the remaining SPEC "Attachments & iOS-like extras" pieces that do not need new
permissions: **drawing canvas**, **export note as Markdown/HTML**, **tables insert/edit
helper**, **quick-note launcher shortcut**. Camera scan, home-screen widgets, PDF export and
encrypted version history stay for a later increment. Same rules as `docs/CONTRACTS.md`.

Baseline: Phase 5 hardening complete at commit `a8b97b3`: 764 unit tests / 10 device tests
green, signed APK+AAB reproducible, all UI strings in resources (`stringResource` ONLY —
no new inline user-visible English in Composables; symbols/glyphs may stay inline).

---

## A. Drawing canvas attachment (owner: draw-canvas)

Write scope: `ui/attachments/**`, `app/src/test/java/com/cipherwell/notezero/ui/attachments/**`,
`app/src/main/res/values/strings.xml` (+plurals), `docs/ATTACHMENTS-UI.md`. Nothing else.

Deliver:
1. `DrawingCanvasScreen`/dialog (Compose Canvas) opened from the existing attachment add menu
   as a third option `attachment_add_drawing` (string resource). Features: freehand strokes
   (finger/stylus), colour palette (sensible 6–8 colours, no raw hex outside `ui/theme` — derive
   from `MaterialTheme`/`NoteZeroPalette` or add named tokens to `ui/code`-style local palette),
   3 stroke widths, undo (bounded stack), clear, cancel, save.
2. Save path: render strokes to a `Bitmap` (white background, bounded resolution e.g. max
   2048 px), encode PNG in memory, then import through the SAME encrypted attachment pipeline
   (`repository.importAttachment(noteId, "drawing-<yyyyMMdd-HHmmss>.png", "image/png", source)`).
   No plaintext file/cache on disk; bytes wiped/GC'd after import; PNG import must respect the
   10 MiB cap. If you need a bytes-based import path, add it inside `ui/attachments/` (e.g.
   `AndroidAttachmentSource.importBytes(...)` / controller method) — do NOT change the data
   layer; `ByteArrayInputStream` through the existing method is preferred.
3. UX: drawing appears in the strip as an image thumbnail like other images; delete/view/export
   reuse existing attachment actions. 48dp targets, content descriptions, strings in resources,
   no logging of names/content, no new dependencies/permissions.
4. Tests: pure stroke model (add/undo/clear, bounds, PNG render produces non-empty bytes with
   expected size limits) if you extract one; controller import-bytes path with FakeAttachmentRepository;
   Robolectric Compose test: add menu shows the drawing option, canvas opens, save path invokes
   import exactly once with `image/png`, cancel imports nothing. No sleeps.
5. `docs/ATTACHMENTS-UI.md`: add the drawing flow and its security notes.

Build: JDK17, `:app:compileDebugKotlin`, `:app:testDebugUnitTest --tests "com.cipherwell.notezero.ui.attachments.*"`; no clean;
another subagent builds concurrently (retry once on transient KSP FileNotFoundException).

---

## B. Note export (Markdown/HTML) + tables helper (owner: note-extras)

Write scope: `ui/notes/**`, `ui/editor/**`, `model/NoteExport.kt` (new, pure Kotlin) or
`model/` additions, `app/src/test/java/com/cipherwell/notezero/{ui/notes,ui/editor,model}/**`,
`app/src/main/res/values/strings.xml` (+plurals), `docs/DECISIONS.md` if a decision is needed.
Nothing else (an E2E tag contract is given below — coordinate with Lead via send_message).

Deliver:
1. **Export note** (Markdown and HTML) through SAF `CreateDocument` only, behind an explicit
   "writes an UNENCRYPTED copy outside the vault" warning dialog (same pattern as code export).
   Entry point in the note row menu (`note_export_action`) and/or editor overflow; choose one,
   wire the SAF launcher, suggested filename from the title (sanitised), MIME `text/markdown`
   / `text/html`, write title + body. Failure is a non-secret error state. No clipboard, no
   share providers, no file writes except the user-chosen SAF target.
   Pure converter `model/NoteExport.kt`: `toMarkdown(title, body)` (title heading + body) and
   `toHtml(title, body)` (headings, paragraphs, bullets/numbered, checklists as disabled
   checkboxes, fenced code escaped, quotes, rules, tables; inline bold/italic/code/links;
   CRITICAL: HTML-escape all text content and link URLs, no script injection). Unit tests.
2. **Tables helper** in the markdown editor: toolbar "Insert table" action
   (`editor_insert_table`) opens a small dialog (rows/cols 1–20, with header) and inserts a
   markdown table at the cursor; plus pure helpers to add/remove a row/column for the table the
   cursor is in (`MarkdownTableEdit` in `model/` or `ui/editor/` pure file) with unit tests.
   Keep preview rendering unchanged (tables already render).
3. Strings in resources only; keep existing tags/tests green; no logging of note content;
   no new dependencies; do not change visible copy of existing screens.
4. Tests: NoteExport markdown/HTML incl. escaping/injection cases; table insert/row-col edit
   helpers; ViewModel tests for export error/success with a fake export target; Robolectric UI
   test for insert-table dialog and export warning gate. No sleeps.
5. Tags for the Lead's E2E: `editor_insert_table`, `table_rows_field`, `table_cols_field`,
   `table_confirm`, `table_cancel`, `note_export_action`, `note_export_confirm`,
   `note_export_cancel`, `note_export_status`.

Build: JDK17, `:app:compileDebugKotlin`, `:app:testDebugUnitTest --tests "com.cipherwell.notezero.ui.notes.*" --tests "com.cipherwell.notezero.ui.editor.*" --tests "com.cipherwell.notezero.model.*"`;
no clean; another subagent builds concurrently (retry once on transient KSP FileNotFoundException).

---

## C. Lead: quick-note launcher shortcut + E2E + integration

- Static launcher shortcut "New note" (`res/xml/shortcuts.xml`, action
  `com.cipherwell.notezero.SHORTCUT_NEW_NOTE`, manifest meta-data already wired) → `MainActivity`
  receives the intent, `AppRoot` routes to notes with a new-note request, `NotesListScreen`
  creates + opens a note (via a new optional `onStartNewNote`/request flag; default behaviour
  unchanged so existing tests pass). No widget yet.
- E2E additions: insert a markdown table through `editor_insert_table` → verify persisted body
  contains `|`; open the drawing canvas from the attachment menu (tag + screenshot), cancel;
  screenshot the export warning dialog if reachable without the SAF picker (warning before
  picker). Keep the existing locked/note/code flows green.
- Integration: full unit suite (764+ baseline), device suite, screenshots, docs
  (README/CHANGELOG/TESTING/VERIFICATION/DECISIONS), reproducibility re-run for the new
  commit, signed artifacts refreshed, commit/push/CI, memory.
