# Changelog

All notable changes to NoteZero are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow semantic versioning.

## [Unreleased] — full-document lazy PDF viewer (2026-11-20, increment 18)

### Added
- **`PdfPageSession` / `PdfPageSessionFactory`** with `AndroidPdfPageSessionFactory`: one open
  `PdfRenderer` per viewer, decrypted bytes written to an app-private cache file that is
  **unlinked immediately after opening**, per-page renders on IO serialised by a `Mutex` (white
  ARGB, ≤1600 px long edge, never upscaled), fail-closed null on invalid/corrupt/OOM, idempotent
  close; `MAX_VIEW_PAGES = 2000` guard.
- **Viewer refactor**: `LazyColumn` spans the real `pageCount`; each `PdfPageItem` renders lazily
  while composed with A4 `pdf_page_loading` placeholders / `pdf_page_render_failed`, recycles page
  bitmaps on item disposal; full-range reading progress, jumps, bookmarks and annotations; the
  first-8-pages cap and `pdf_pages_capped` notice are gone (legacy `PdfPageRenderer`/`PdfPages` and
  `PdfFlattenedExporter` remain standalone utilities).
- **Pages overview** now covers all thumbnail-rendered pages up to 100 with current-page emphasis;
  numeric fallback remains.
- **Annotated export** moved to the all-pages `pageToolsExporter` with an identity plan (≤100
  pages; above that `pdf_annotated_export_too_many` and nothing written), keeping the explicit
  SAF warning/lock/re-read rules.
- Tests: +23 unit/Robolectric lazy-viewer/session tests and +6 instrumented real-`PdfRenderer`
  session tests; full suite **1490 / 0 failures / 120 classes**, **38 instrumented tests**.
  Docs `docs/CONTRACTS-PHASE17.md`, `docs/PDF-LAZY-VIEWER.md`, VERIFICATION §23, DECISIONS #29,
  THREAT-MODEL §3.9.9; screenshots 52–54.

### Verified (measured)
- 12-page PDF: scroll 1→12, jump to page 10, annotate page 10, export 12-page flattened annotated
  copy (ink verified on exported page 10 via `pdftoppm`), overview all 12, no session temp file
  left; 0 crashes.
- Offline guard OK; token lint clean; no new permissions/dependencies; reproducible hashes in
  VERIFICATION §23.

## [Unreleased] — PDF visual page navigation: in-memory thumbnails (2026-11-20, increment 17)

### Added
- `PdfThumbnails` (bounded caller-owned bitmap set with idempotent recycle) and
  `PdfThumbnailRenderer` / `AndroidPdfThumbnailRenderer` (≤100 pages, ≤144 px long edge, white
  ARGB backgrounds, one ephemeral app-private cache file deleted in `finally`, fail-closed empty
  on corrupt/OOM, cancellation rethrown, no logging).
- Lazy, once-per-viewer-session thumbnail loading in `PdfViewerScreen`; re-reads decrypted
  attachment bytes only when a thumbnail surface opens; recycles on replace/dispose; no PDF bytes
  held in state.
- **Pages overview**: the page-jump dialog now shows a 3-column thumbnail grid with current-page
  emphasis and page numbers (tap to jump/close), retaining the existing numeric field/error/Go/
  Cancel fallback, plus loading/unavailable states.
- **Bookmark rows** show a 48×64 dp page thumbnail when available.
- **Page-tools rows** show 56×72 dp previews (source page rotated by its quarter-turn, labelled
  blank placeholder for inserted blanks); opening page tools triggers the lazy load.
- Tests: +16 unit/Robolectric thumbnail/viewer tests and +5 instrumented real-`PdfRenderer`
  thumbnail tests; full suite **1467 / 0 failures / 118 classes**, **32 instrumented tests**.
  Docs `docs/CONTRACTS-PHASE16.md`, `docs/PDF-THUMBNAILS.md`, VERIFICATION §22, DECISIONS #28,
  THREAT-MODEL §3.9.8; screenshots 49–51.

### Notes
- The thumbnail grid intentionally shows the pages the viewer has rasterised (existing 8-page
  render cap); the numeric field retains the document's full range, and page tools operate on all
  pages up to 100. A full-document lazy-render viewer is a separate queued improvement.

### Verified (measured)
- Emulator QA on a 12-page PDF: overview thumbnails/current-page emphasis, thumbnail jump 1→5,
  bookmark thumbnail, page-tools previews incl. rotated page; 0 crashes.
- Offline guard OK; token lint clean; no new permissions/dependencies; reproducible hashes in
  VERIFICATION §22.

## [Unreleased] — PDF page tools: rotate/delete/reorder/insert blank (2026-11-20, increment 16)

### Added

**PDF page tools**
- New `PdfPagePlan` pure model (A4 output plan: source page/blank + clockwise quarter-turns,
  rotate left/right, move up/down, delete-with-last-page-refusal, insert blank, reset; 100-page
  cap; hostile-position safe; no duplicates/out-of-range pages).
- `PdfPageToolsExporter` seam + `AndroidPdfPageToolsExporter`: renders planned pages on demand with
  `PdfRenderer` (≤1600 px long edge, ~48 MiB total pixel budget), draws each scaled-to-fit/centred
  on a white A4 `PdfDocument` page with the quarter-turn applied, and flattens that page's
  annotations through the shared `PdfPageDrawing` helper (annotations rotate/move with the page;
  deleted pages' annotations are dropped). One ephemeral app-private cache file, deleted in
  `finally`; returns null on any failure; no logging.
- `PdfPageCopySaver` seam + `DefaultPdfPageCopySaver`: creates a new encrypted `PDF_FILE` item in
  the source folder (title `… (pages).pdf`), imports the flattened bytes through
  `repository.importAttachment`, trashes the half-created row on failure; original untouched.
- `PdfPageToolsScreen` full-screen overlay opened from the PDF viewer top bar: warning/hint,
  scrollable page rows with 48dp rotate/move/delete controls, insert blank/reset/cancel/save,
  last-page and too-many-pages notices, saving/locked/failure states; Back closes only the overlay.
- Viewer save flow re-reads the attachment bytes at save time, re-checks unlocked/item-alive before
  writing, and shows a saved-copy notice with the new title.
- **Tests**: +54 PDF tests (plan 23, screen 20, saver 8, viewer 3) and 4 instrumented real-
  `PdfRenderer` export tests; full suite 1451 / 0 failures / 116 classes; 27 instrumented tests
  green. Docs `docs/CONTRACTS-PHASE15.md`, `docs/PDF-PAGE-TOOLS.md`, VERIFICATION §21, DECISIONS
  #27, THREAT-MODEL §3.9.7; screenshots 46–48.

### Fixed
- Refactored the flattened annotated exporter onto the shared `PdfPageDrawing` helper so the two
  exports cannot drift (public annotated-export behaviour unchanged).
- Instrumented page-tools fixture ink moved off the sampled page centre (test-only; previously the
  red page-centre assertion read the annotation stroke instead of the page colour).

### Verified (measured)
- 1451 unit tests / 0 failures / 116 classes; 27 instrumented tests OK.
- Device QA as above (rotate/move/insert/delete/save/reopen); original PDF retained 3 pages; new
  copy 3 pages in expected order with rotated page and white blank; saved notice shown; 0 crashes.
- Offline guard OK; token lint clean; no new permissions/dependencies; no logging/TODO leftovers.
- Reproducible APK/AAB hashes recorded in VERIFICATION §21; screenshots 46–48.

## [Unreleased] — IDE-style code workspace: tabs, quick open, templates, split preview (2026-11-18, increment 15)

### Added

**Multi-file tabs**
- `CodeWorkspaceScreen` keeps up to 8 open files (saveable) with an active tab; a horizontally
  scrollable strip (`code_tabs`/`code_tab`/`code_tab_close`) switches files, closes tabs (neighbour
  activates), keeps tabs when Back returns to the tree, re-activates existing tabs on re-open and
  shows a non-secret `code_tab_limit` notice at the cap.
- Each editor ViewModel is keyed per item id and editors are kept alive in a
  `SaveableStateHolder`, so switching tabs preserves drafts, undo history and per-tab find/preview
  chrome; the existing `code_editor`/toolbar/search/export tags are unchanged.

**Quick open**
- A code-file switcher (`code_quick_open`) dialog with search field/results, filtered by
  title/path/extension, newest-updated first, locked/hidden files excluded; choosing a file opens
  it in a tab (or activates its existing tab).

**New-file templates**
- `NewCodeFileDialog` gains template chips (Blank, Kotlin class, Python script, Markdown note, HTML
  page, JSON config) that swap the suggested name/extension/starter body and returns a
  `NewCodeFileRequest(name, body)`; the workspace and notes-shell create paths persist the body
  (notes-shell `NotesViewModel.createCodeFile(name, folderId, body)` wired by the Lead).

**Split Markdown preview**
- On wide layouts (≥600dp) the existing Preview toggle exposes a Split mode rendering
  `MarkdownPreview` beside the editor; compact layouts keep the single-pane preview.
  Wide-layout selection is injectable for tests (no display qualifiers).

### Fixed
- Line-number gutter draw could crash (`maxHeight(-46) must be >= minHeight(0)`) when the editor
  was laid out at zero height during tab creation/rotation; it now draws pre-measured text layouts
  and skips empty frames (found by device QA on the new tab flow).

### Verified (measured)
- **1397 unit tests / 0 failures** (113 classes; +46 code-workspace tests: tabs 10, quick open 7,
  templates 5, workspace tabs screen 6, quick-open screen 7, dialog 9 + extended VM/editor cases)
  and **23 instrumented tests OK**; existing notes/EPUB/PDF/workspace suites stay green.
- Emulator QA: opened `hello.kt`, quick-opened a Markdown file into a second tab, typed a draft,
  switched tabs and back (draft preserved), toggled Preview, rotated to landscape and used the
  split editor/preview; no crash after the gutter fix; 0 NoteZero FATAL/ANR.
- Offline guard OK; no new permissions/dependencies; no debug/logging leftovers.
- New screenshots `43-code-tabs`, `44-code-quick-open`, `45-code-split-preview`;
  docs `docs/CONTRACTS-PHASE14.md`, `docs/CODE-WORKSPACE.md`, VERIFICATION §20.

## [Unreleased] — more reading formats: TXT, FB2, CBZ comics (2026-11-18, increment 14)

### Added

**Format engine (dependency-free, pure JVM)**
- `BookContent` interface implemented by `EpubDocument`, so EPUB/FB2/TXT share the same reader
  surface (chapters, TOC, cover, lazy images, close lifecycle).
- `BookFormat` + `BookDetector` (content-based: EPUB -> CBZ -> FB2 -> TXT) + `BookLoader`
  (single UI entry point returning `LoadedBook.Reflowable`/`LoadedBook.Comic`).
- **TXT**: BOM-aware UTF-8/UTF-16LE/BE decoding, no-BOM UTF-8 with windows-1252 fallback,
  blank-line paragraphs, heading-aware auto-chaptering (`Chapter/Part/...`) with "Part N"
  fallback, caps (2,000 chapters / 20,000 blocks / 2M chars per chapter + whole-book caps).
- **FB2**: secure XML (no DTD/XXE/external entities), metadata (title/author/language), body
  sections -> chapters + TOC, nested sections -> headings, p/emphasis/strong/code/a/poem/stanza/v/
  epigraph/cite/empty-line/subtitle/text-author mapping, lazy base64 `<binary>` images (bounded,
  copies), cover from `<coverpage>`; malformed/XXE -> null.
- **CBZ**: natural-sorted image pages (jpg/jpeg/png/webp/gif), ≤500 pages, bounds, lazy page
  byte copies, close lifecycle; unsupported/corrupt archives -> null.
- All decoders never throw, never write temp files/caches, no logging, no new dependencies.
- **Tests**: 107 new engine tests (BookDetector 18, TxtBookParser 27, Fb2BookParser 35,
  CbzParser 15, BookLoader 12) + all 104 existing EPUB/book tests green; docs/BOOK-FORMATS.md.

**Library + reader UI**
- Book import is now format-generic: `BookDetector`/`BookLoader` derive the detected MIME,
  title and author, store the encrypted `ReadingState` metadata and remove the placeholder on
  unsupported/corrupt/DRM input (non-secret `library_import_unsupported`).
- `BookReaderScreen` loads through `BookLoader`: EPUB/FB2/TXT use the existing TOC/search/
  bookmarks/appearance/progress/resume tools over `BookContent`; CBZ dispatches to a new
  **ComicReaderScreen** (paged images decoded lazily in memory, page progress/jump, encrypted
  page bookmarks and resume, loading/page-error/comic-error states, no text search/TOC).
- Library rows show the detected format label (`EPUB`/`FB2`/`TXT`/`Comic`) and "Page x of y"
  progress for comics while books keep chapter progress.
- **Tests**: +25 UI tests (reader formats 7, comic reader 11, library import 7); existing
  EPUB/PDF/workspace/library suites stay green; docs/READER-UI formats section.

### Verified (measured)
- **1351 unit tests / 0 failures** (108 classes; +132: 107 engine + 25 UI) and **23 instrumented
  tests OK**.
- Emulator QA: imported TXT (`qa-book`, label TXT, reader + search "txt-words" 1 result), FB2
  (title/author detected, label FB2, sections/poem/epigraph render, 3-entry TOC) and CBZ
  (label Comic, pages 1–3 decoded, page jump/bookmark persisted across relaunch); an unknown
  `.bin` was rejected with the unsupported message and left no row; 0 crashes.
- Offline guard OK; token lint clean; no debug/logging leftovers; no new permissions/deps.
- New screenshots `38-txt-reader`, `39-fb2-reader`, `40-comic-reader`, `41-library-formats`,
  `42-comic-bookmarks`; docs CONTRACTS-PHASE13, BOOK-FORMATS, READER-UI, DECISIONS #26,
  THREAT-MODEL §3.9.6, VERIFICATION §19.

## [Unreleased] — universal Workspace / IDE layer (2026-11-17, increment 13)

### Added

**Workspace ("Files") — one browser over the encrypted index**
- New `WorkspaceScreen` reachable from the Notes top-bar files icon: every `ItemKind` (notes, code,
  PDFs, EPUB books, sketches) in one list with type filter chips, search, sort (last updated / name)
  and a footer storage summary (items · attachments · human-readable size, from the decrypted
  index only).
- Unified offline search over titles/bodies/tags/file names/book authors with body/code snippets;
  masked locked items and items inside hidden locked folders are excluded from rows, filters,
  counts and search (fail-closed), and no locked content can reach the UI.
- Type-aware opening: notes/code deep-link into the existing editors hosted by the notes shell
  (new `requestedItemId` hand-off in `NotesListScreen`/`AppRoot`); PDFs, books and sketches open
  the existing viewer/reader/studio screens inside the Workspace; verified-locked rows route
  through the standard re-auth controller.
- Multi-select (long-press) with Select all / Clear, bulk Move to folder (existing dialog),
  Lock/Unlock and Move to trash (confirmation); operations act only on visible selected ids,
  failures show a non-secret message and selection is cleared.
- Pure JVM-tested model layer (`WorkspaceModel`: fail-closed visibility, row/filter/search/sort,
  summary, selection algebra, folder flattening) plus 25 Robolectric screen tests.
- No new data model, payload, permission or dependency; all copy is resource strings
  (`workspace_*`), colours from existing tokens, no content logging.

### Verified (measured)
- **1219 unit tests / 0 failures** (101 classes; +42: 16 workspace JVM + 25 Robolectric + 1 shell
  routing) and **23 instrumented tests OK**.
- Emulator QA: Workspace listed seeded note/PDF/code items with storage summary and hid the locked
  note; search "E2E" returned the code file (snippet) and the note; long-press selection showed
  "1 selected" with Select all/Clear/Move/Lock/Trash; bulk trash confirmation removed the row and
  the summary updated to `3 items · 1 attachments · 431 B`; tapping a note row opened the editor
  via the deep link.
- Offline guard OK; token lint clean; no new permissions/deps; no debug/logging leftovers.
- New screenshots `34-workspace`, `35-workspace-selection`, `36-workspace-search`,
  `37-workspace-trash`; docs `docs/CONTRACTS-PHASE12.md`, `docs/WORKSPACE.md`, DECISIONS #25,
  THREAT-MODEL §3.9.5, VERIFICATION §18.

## [Unreleased] — drawing studio: encrypted standalone sketches (2026-11-17, increment 12)

### Added

**Encrypted sketch data model (`ItemCodec` payload v6)**
- `model/SketchDocument`: blank/ruled/grid/dotted template, freehand `SketchStroke`s (normalised
  points, 7 inks, 3 widths, pen/highlighter kind) and `SketchShape`s (line/rectangle/ellipse/
  arrow); A4-portrait normalised geometry; bounds ≤2,000 strokes / ≤100,000 points / ≤500 shapes.
- `data/SketchCodec` v1 (`"NZSK"`): deterministic 1/10,000-quantised binary codec, ≤4 MiB, null
  (never throws) on corruption/trailing/oversize; 1,000-sample fuzz test.
- Payload **v6** appends the sketch document after the annotation layer (presence byte + u32
  length + codec); v1–v5 payloads still decode (`sketch = null`). `Item.sketch` is
  repository-owned: `saveSketch` is the only writer (`null`/empty clears), `saveItem` preserves
  it, backup v2 carries it, and a locked vault can neither read nor write it.
- `ItemKind.SKETCH_FILE` appended after `BOOK_FILE` (ordinal 4; ordinals frozen).

**Drawing studio**
- Full-screen studio for `SKETCH_FILE` items: A4 page with blank/ruled/grid/dotted templates,
  pen/highlighter/eraser/lasso/shape (line/rect/ellipse/arrow) tools, 7 token-derived inks,
  3 widths, undo/redo (≥50 steps), clear-with-confirm, bounded pinch zoom/pan with fit/zoom
  buttons, rename dialog and an empty-state hint.
- Autosave of the encrypted vector after each committed mutation, flushed on exit; lasso/eraser
  operate on the in-memory vector and are undoable.
- In-memory flattened PNG preview rendered on exit and imported through the existing encrypted
  attachment pipeline (previous preview replaced via `deleteAttachment`); no plaintext file or
  cache, preview failures never affect the vector save.
- Library integration: "New drawing" action creates + saves a default-titled sketch then opens
  the studio; the Drawings shelf lists both note-owned drawings and sketches (preview-less
  sketches included); tapping a sketch opens the studio; rename/lock/trash rules unchanged.

### Verified (measured)
- **1177 unit tests / 0 failures** (99 classes; +49: sketch data/codec/payload/repository, studio
  editor/geometry/renderer/screen, library integration, updated v5 fixtures) and **23
  instrumented tests OK** (+4 real renderer PNG/bounds/hostile cases).
- Emulator QA: created a sketch from Library, pen stroke + green rectangle + grid template,
  renamed, exited and reopened — vector restored exactly from the encrypted item; preview
  attachment blob starts with `NZAT` (encrypted), no plaintext image files in app data.
- `scripts/check-offline.sh --gradle` OK; token lint clean; no new permissions/dependencies;
  no debug/logging leftovers (the Robolectric hang was traced to display qualifiers + AlertDialog
  text fields, not product code; dialog tests now follow the known `@Config(sdk=[34])` rule).
- New screenshots `31-drawing-studio`, `32-sketch-drawn`, `33-sketch-library`; docs:
  `docs/CONTRACTS-PHASE11.md`, `docs/DRAWING-STUDIO.md`, DECISIONS #24, DATA-MODEL/BACKUP-FORMAT
  v6, THREAT-MODEL §3.9.4, VERIFICATION §17.

## [Unreleased] — PDF annotation editor + flattened annotated export (2026-11-17, increment 11)

### Added

**Encrypted PDF annotation layer (`ItemCodec` payload v5)**
- `model/PdfAnnotations.kt`: ink/highlighter strokes, rectangle highlights and sticky notes with
  normalised (0..1) geometry, 6 palette indices and 3 width levels; fail-closed bounds (≤2,000
  strokes, 2..2,000 points/stroke, ≤100,000 total points, ≤1,000 highlights, ≤500 notes, notes
  ≤4,000 UTF-8 bytes).
- `data/PdfAnnotationCodec` v1 (`"NZPA"`): deterministic, 1/10,000-quantised binary codec,
  ≤4 MiB, null (never throws) on corruption/trailing/oversized data; 1,000-sample fuzz test.
- Payload **v5** appends the layer after the reading state (presence byte + u32 length + codec);
  v1–v4 payloads still decode (`pdfAnnotations = null`). `Item.pdfAnnotations` is
  repository-owned: `savePdfAnnotations` is the only writer (`null`/empty clears), `saveItem`
  preserves it, backup v2 carries it, and it is never a plaintext column.

**PDF annotation editor (viewer)**
- Annotate mode toggle in the PDF toolbar (Back exits the mode first), bottom tool palette:
  ink, highlighter, highlight box, sticky note, fingertip eraser, undo/redo, 6 token-derived
  colours, 3 widths and a drawing hint; page scrolling is locked while annotating.
- Per-page Canvas overlay with resolution-independent drawing; sticky-note markers reopen a
  note dialog for edit/delete; eraser hit-tests strokes/highlights/notes; 64-step undo/redo;
  every mutation persists to the encrypted layer (silent failures while locked), and the layer
  reloads after app restarts/lock-unlock.
- Export: when annotations exist, the export action offers original vs **annotated copy
  (flattened)** behind the existing explicit UNENCRYPTED warning. The flattened path renders
  pages + layer in memory through `PdfFlattenedExporter`/`AndroidPdfFlattenedExporter` (A4,
  white, scaled-to-fit, note markers, no temp files, null on OOM/errors, 32-page defensive cap)
  and writes through SAF with a vault re-check; the original path is unchanged.

### Verified (measured)
- **1118 unit tests / 0 failures** (95 classes; +38 annotation UI/editor/palette tests,
  +12 annotation data/codec/repository/payload tests) and **19 instrumented tests OK** (+4 real
  `PdfDocument`/`PdfRenderer` flatten/caps/hostile-input cases).
- Emulator QA: 3-page PDF imported, ink/highlighter/highlight-box/sticky-note created,
  undo/redo and eraser exercised, annotations persisted across force-stop + vault unlock;
  annotated export produced `qc-3page-annotated.pdf` — `pdfinfo` 3 pages, 595×842 pt A4,
  70,333 B — and the rendered page shows the annotations over the text.
- `scripts/check-offline.sh --gradle` OK; no new permissions; annotation palette uses design
  tokens only; no TODO/logging/network additions.
- New screenshots `27-pdf-annotate-tools`, `28-pdf-annotations`, `29-pdf-export-choice`,
  `30-pdf-flattened-page`; docs: `docs/CONTRACTS-PHASE10.md`, `docs/PDF-ANNOTATION-UI.md`,
  DECISIONS #23, DATA-MODEL/BACKUP-FORMAT v5, THREAT-MODEL §3.9.3, VERIFICATION §16.

## [Unreleased] — all-in-one workspace: Library, EPUB/PDF readers, encrypted reading state (2026-11-17, increment 10)

### Added

**Library (one encrypted shelf)**
- New `Library` screen reachable from the Notes top bar (book glyph, `library_button`):
  All / Books / PDFs / Drawings filters, progress + Continue per document, one-tap opening,
  and the shared rename/lock/trash rules from the notes list. Locked+unverified documents are
  never listed (same fail-closed masking as the code workspace).
- Import through SAF `OpenDocument` (MIME-tolerant picker, parser is the validator): a PDF is
  stored as an encrypted `application/pdf` attachment; an EPUB is parsed first (title/author/
  language/chapter count) and stored as an encrypted `application/epub+zip` attachment under
  `ItemKind.BOOK_FILE`. Unsupported/DRM-protected/corrupt files are rejected with a non-secret
  message and the placeholder item is removed — no orphan rows. The picker is bracketed with
  the external-activity API so a background stop does not lock the vault mid-import.

**EPUB 2/3 reader (dependency-free engine + native Compose rendering)**
- Pure-Kotlin/JVM engine in `book/` (2,200 LoC, no new dependencies): container/OPF/spine,
  EPUB3 nav and EPUB2 NCX TOC, metadata, cover, XHTML → semantic block model (headings,
  paragraphs, lists, quotes, code, rules, images, inline bold/italic/code/link), bounded ZIP
  (≤10k entries, 128 MiB total, 16 MiB/entry, path-traversal rejection) and XXE-hardened XML,
  DRM refusal, `close()` lifecycle; **104 JVM tests** with generated EPUB fixtures including a
  1,000-sample fuzz/malformed-input suite.
- Reader UI: chapter list with rendered blocks/images (decoded in memory only), TOC dialog,
  in-book search with snippets, bookmarks, appearance sheet (font size, line spacing, margins,
  paper/sepia/night), progress bar/percent, tap zones, resume to the last chapter. Non-secret
  reader preferences in app-private SharedPreferences.
- Bookmarks/position persisted through the encrypted reading state (below) and verified across
  app restart + vault lock/unlock on device.
- Fixed during device QA: duplicate chapter heading when a chapter's `<h1>` already carries the
  title, and night-theme code-block contrast.

**PDF reader tools**
- Page slider/jump (`Go to page` with validation), bookmark add/list/delete with jump, resume
  position; existing encrypted-attachment rendering, SAF export and FLAG_SECURE behaviour
  unchanged.

**Encrypted reading state (`ItemCodec` payload v4)**
- `ItemKind.BOOK_FILE` appended after `PDF_FILE` (ordinals frozen). New `ReadingState`
  (position, fraction, total, bookmarks, book author/language) lives inside the AES-GCM item
  envelope, is repository-owned like attachments/locks (a stale autosave cannot roll it back),
  travels with backup v2, and is never in a plaintext column. Payload v1–v3 still decode.

### Changed
- `ItemCodec.ENVELOPE_VERSION` 3 → 4 (v1–v3 decode preserved); backup v1 fixtures and lock-byte
  invariants re-tested against the new layout.
- Notes list row routing: `BOOK_FILE` opens the reader; `PDF_FILE` keeps the upgraded viewer.
- Offline guarantee test now allow-lists XML parser namespace identifiers (not fetch targets)
  while still failing on any real URL literal in app sources.

### Verified (measured)
- **1068 unit tests / 0 failures / 0 errors** (91 classes; +155: EPUB engine 104, Library/reader
  UI 40, PDF tools 5, reading-state data 6); **15 instrumented tests OK** on the emulator.
- Emulator (Android 17 image) device QA: imported a 2-chapter EPUB (`The Quiet Library`, author
  Ada Test) → TOC, search ("secret" → 1 result), bookmarks, night theme + larger font, image
  rendering; imported a 3-page PDF → page jump, bookmark on page 2 (`Page 2 of 3`, 66%),
  progress persisted after force-stop + unlock; library row progress reflected both documents.
- No new permissions: release surface remains `USE_BIOMETRIC` only; offline guard, FLAG_SECURE
  and reproducible builds re-run for the release commit (hashes in VERIFICATION §15).
- New screenshots: `23-library`, `24-epub-reader`, `25-pdf-viewer-tools`,
  `26-reader-bookmarks-night`.

## [Unreleased] — Phase 4 complete: drawing canvas, note export, tables, quick capture (2026-11-15, increment 6)

### Added

**Drawing canvas (encrypted attachment)**
- Drawing surface (Compose Canvas, full-screen dialog) reachable from the attachment add menu
  (`attachment_add_drawing`): freehand finger/stylus strokes, 7 theme-derived inks, 3 stroke
  widths, bounded undo, clear, cancel, save.
- Save renders in memory to a white page (long edge ≤ 2048 px), encodes PNG, and imports it
  through the existing encrypted attachment pipeline (`image/png`, 10 MiB cap; the renderer
  halves resolution if the encoded PNG would exceed the cap). No plaintext file or cache on
  disk, no new permission; the drawing behaves like any other image attachment (thumbnail,
  viewer, delete, export).

**Note export (Markdown / HTML, explicit plaintext action)**
- Row-menu export action → format choice → explicit "writes an UNENCRYPTED copy outside the
  vault" warning → SAF `CreateDocument` only. Suggested sanitised `.md`/`.html` filename;
  non-secret status/error states; no clipboard/share; pending plaintext dropped on lock.
- New pure `model/NoteExport`: Markdown and fully escaped HTML renderer (headings, paragraphs,
  inline bold/italic/code/link/strike, bullets/numbered, disabled checkboxes, fenced code,
  quotes, rules, tables). HTML escapes all text/attributes and drops `javascript:`/`data:`/
  `vbscript:` link targets (labels stay as text), with unit tests for injection cases.

**Markdown tables editing**
- `editor_insert_table` action + dialog (1–20 rows/cols, header toggle) inserts a GFM table at
  the real caret/selection; overflow actions add/remove a row or column for the table under the
  caret. Pure `MarkdownTableEdit` helpers (clamping, delimiter handling, escaped pipes) are
  unit-tested; block preview rendering unchanged.

**Quick capture (launcher shortcut)**
- Static "New note" launcher shortcut (`com.cipherwell.notezero.SHORTCUT_NEW_NOTE`). The
  request survives a locked vault: `MainActivity` → `AppRoot` → notes creates the note after
  unlock through the normal encrypted repository path and opens the editor. Unit-tested
  (`QuickCaptureTest`) and covered by the device E2E.

### Verified (measured)

- **866 unit tests / 0 failures** (72 classes; +102 over increment 5): drawing 41,
  note export/tables/quick capture 59, plus updated editor/notes suites.
- **10 instrumented tests OK**: `VaultFlowE2ETest` now also covers insert-table dialog →
  persisted markdown table, drawing canvas open/cancel (no attachment created), and the
  quick-capture shortcut launched while locked → unlock → new note created. New screenshots
  `14-table-insert`, `15-drawing-canvas`, `16-quick-capture`.
- Reproducibility re-verified for the release commit (signed APK + AAB byte-identical; hashes
  in VERIFICATION §11 / `releases/SHA256SUMS`); FLAG_SECURE (screencap 0 bytes) and the
  permission surface re-checked.

### Still open

- Camera scan (would add the CAMERA permission; deliberately last), home-screen widgets,
  PDF export, encrypted per-note version history, translations, and the F-Droid window
  (2027-02-15).

## [Unreleased] — PDF export and quick-note widget (2026-11-17, increment 9)

### Added

**Export note as PDF**
- A third export format: row menu → **Export note** → Markdown / HTML / **PDF (.pdf)** → the
  same explicit "writes an UNENCRYPTED copy" warning → SAF `CreateDocument`. The PDF is rendered
  in memory by `NotePdfRenderer` (`android.graphics.pdf.PdfDocument`): A4 pages with the title
  heading, styled inline text (bold/italic/`code`/strikethrough/link labels), bullet/numbered
  lists, checklists (`[ ]`/`[x]`), fenced code with a light background, quotes, rules, simple
  tables and page numbers. Bytes go once to the user-chosen file — no temp PDF, no cache, no log.
- Bounds: 1,000,000 input chars, 200 content pages (+ a truncation notice page), 8 table
  columns / 200 rows, 200,000 chars per code block; any renderer failure (including OOM) maps to
  the existing non-secret export-failed status. Rendering runs off the main thread and the
  session/lock is re-checked before writing.
- `PdfInlineMarkdown` (pure Kotlin, JVM-tested) parses the inline grammar with bounded
  runs/depth and degrades unclosed/unknown markers to literal text.

**Quick-note home-screen widget**
- Static "New note" tile (`QuickNoteWidgetProvider`) whose click launches `MainActivity` with the
  existing `SHORTCUT_NEW_NOTE` quick-capture action: the request survives a locked vault and the
  note is only created after unlock. The tile shows **no vault content** (a home screen is
  visible while locked), never reads the repository, has `updatePeriodMillis=0`, an immutable
  PendingIntent, a non-exported receiver and no configuration activity. No new permissions.

### Verified (measured)

- **913 unit tests / 0 failures** (79 classes; +14 over increment 8): `PdfInlineMarkdownTest` (9),
  PDF request/selector tests in the export ViewModel/UI suites (2), widget tests (3: intent
  contract, RemoteViews inflation, static metadata).
- **15 instrumented tests OK** on the emulator, including `NotePdfRenderInstrumentedTest` (3):
  a full multi-page note renders into a PDF that the real `PdfRenderer` accepts (≥2 pages, real
  pixels), an empty note is a valid 1-page PDF, and hostile inputs never throw.
- Device validation: exported PDF from the emulator (94,980 B, A4, 1 page; `pdftotext` shows the
  title, heading, checklist markers, code line and page number) and from the owner's phone
  (11,791 B, A4, 1 page; file removed afterwards). Widget provider is registered and parsed by
  the system on both devices (`updatePeriodMillis=0`, `widgetCategory=1`, `resizeMode=1`,
  `initialLayout`); the tile appears in the launcher widget picker (screenshot
  `20-widget-picker`). Export dialog screenshot `19-export-pdf`.
- Reproducibility re-verified for the release artifacts (hashes in VERIFICATION §14); release
  permissions remain `USE_BIOMETRIC` only and FLAG_SECURE blank capture was re-checked on the
  final release install.

## [Unreleased] — Create menu, code formats and encrypted PDF documents (2026-11-15, increment 8)

### Added

**Create menu instead of an instant "New note"**
- The Notes `+` button is now an **icon-only FAB**; tapping it opens a bottom sheet to choose
  what to create: **Note**, **Drawing**, **Code** or **PDF**. Creation goes through the normal
  unlocked repository path (same locks, autosave and encryption as any other item).
- **Drawing**: creates a note and opens the editor with the drawing canvas already visible
  (one-shot request; attachments are saved through the encrypted pipeline as before).
- **Code**: a name + format dialog with all well-known extensions (`.txt .md .html .css .js .ts
  .json .xml .yml .toml .py .kt .java .c .cpp .h .cs .go .rs .rb .php .swift .sql .sh .csv .ini
  .properties`); the workspace "new file" dialog uses the same picker. A typed known extension
  is kept; formats without a lexer edit as plain text instead of being mis-highlighted.
- **PDF**: creates a blank, standards-valid one-page PDF (pure-Kotlin `BlankPdf` with computed
  xref offsets) and stores it as an encrypted `application/pdf` attachment on a new
  `ItemKind.PDF_FILE` item. The item is trashed again if the attachment import fails, so no
  empty row or orphan blob can be left behind.

**PDF viewer**
- Full-screen viewer renders the decrypted PDF with the platform `PdfRenderer`: bounded
  rendering (≤ 8 pages, ≤ 1600 px long edge), page indicator, in-memory bitmaps recycled on
  close, and a clean non-secret error for corrupt files. "Export decrypted copy" goes through
  the same explicit `UNENCRYPTED copy` warning + SAF `CreateDocument` + external-activity
  bracket as attachment export. Rendering needs a seekable descriptor, so bytes go through an
  ephemeral app-private cache file that is deleted in a `finally` block (DECISIONS #20).

### Fixed during device QA (Pixel 9 Pro XL, Android 17, font scale 1.15)

- The create sheet was first a full-screen `Dialog`; the edge-to-edge device laid that window
  out taller than the visible display, so the PDF option slipped under the gesture bar at font
  scale 1.15. It is now an in-window overlay with the real window insets, a scrollable capped
  card and scrim dismissal — all four options are visible and reachable on the device.
- Code files created from the menu now open the **code editor** (lexer/format-aware) instead of
  the Markdown editor; tapping a code row in Notes does the same. PDF rows open the PDF viewer.
- Verified on-device end to end: the exported PDF (`pdfinfo`: 1 page, no JavaScript) is byte-
  valid, the PDF viewer renders it, drawings open straight into the canvas and save as encrypted
  attachments, and attachment-only notes are kept.

### Verified (measured)

- **899 unit tests / 0 failures** (77 classes; +23 over increment 7): blank-PDF wire format +
  codec round trip, code-format name rules, create sheet/code dialog/PDF viewer Robolectric
  tests, `NotesViewModel.createPdf/createCodeFile`.
- **12 instrumented tests OK** on the emulator, including a real-`PdfRenderer` test that proves
  the generated PDF renders (1 page, real pixels) and leaves no render temp file, plus the E2E
  flow creating a note from the sheet and an encrypted PDF through the viewer (screenshots
  `17-create-sheet`, `18-pdf-viewer`).
- Reproducibility, FLAG_SECURE, permissions and device checks repeated (hashes in
  VERIFICATION §13).

## [Unreleased] — Device QA on a real phone (2026-11-15, increment 7)

First run of the **release build** on the owner's device (Pixel 9 Pro XL, Android 17, font
scale 1.15). Everything below was reproduced on-device, fixed, and re-verified on-device.

### Fixed

- **Invisible status/navigation bar in light theme**: system bar icons followed the platform
  instead of the app theme (white icons on the `#FAFAFA` surface). `NoteZeroTheme` now sets
  `isAppearanceLightStatusBars`/`isAppearanceLightNavigationBars` from the resolved
  light/dark mode (DECISIONS #19a).
- **Drawing canvas tools cut off at a larger font scale**: the tool rows (ink palette, stroke
  widths, Undo/Clear) could be pushed below the screen, making them unreachable. The page is
  now a fixed 3:4 scroll-contained sheet (normalized stroke coordinates and the saved PNG are
  unchanged) and the full-screen dialog applies insets exactly once (DECISIONS #19b).
- **Misleading biometric error copy**: a failed/timed-out Face unlock surfaced
  "Biometrics are not available on this device." even though fingerprint/face were enrolled
  and the prompt itself had opened. Prompt errors are now mapped to specific, non-secret
  messages — not enrolled ("No fingerprint or face enrolled. Add biometrics in system
  settings."), temporarily unavailable, lockout, cancelled (silent) and retry
  ("…didn't complete. Try again.") (DECISIONS #19c).
- **Biometric CTA on a vault that never enabled biometrics**: the button was based only on
  hardware availability, so it could only fail. The repository now exposes the non-secret
  enrolled flag (wrapped-VMK check) that the unlock screen gates the CTA on, including while
  locked (DECISIONS #19d).
- **Attachment-only notes silently trashed on back**: the empty-note cleanup that keeps quick
  capture tidy treated "no title/body" as empty even when an attachment existed. Attachments
  now count as content and are kept (DECISIONS #19e).
- **Stale settings banner**: a previous "Vault locked."/error message stayed on screen when
  Settings was re-opened later. The banner is cleared on entry (DECISIONS #19f).
- Pluralised the remaining "locked item(s)" settings counter (`plurals.xml`).

### Verified (measured on the Pixel 9 Pro XL, Android 17)

- All fixed areas re-checked from the actual release/QA build: status bar readable in light
  theme, drawing canvas draws/saves/views an encrypted attachment with every tool visible at
  font scale 1.15, note export writes the plaintext file via SAF, encrypted backup export
  writes `NZBK` bytes with no plaintext, biometric unlock unlocks via fingerprint, and a note
  with only a drawing survives back-out.
- Unit suite grown to **876 tests / 0 failures** (73 classes); instrumented suite **OK
  (10 tests)**; reproducibility re-run for the release artifacts (hashes in VERIFICATION §12).

## [Unreleased] — Phase 5: audit remediation, strings, reproducible APK+AAB (2026-11-15, increment 5)

### Added

**Security audit & remediation**
- Independent adversarial audit `docs/AUDIT-2026-11-15-phase5.md`: **0 HIGH / 0 CRITICAL**,
  2 MEDIUM, 8 LOW, 8 INFO, 12 doc/code mismatches; crypto core, AAD map, envelope parser,
  backup authentication, lock masking and the offline/permission surface held up.
- All findings remediated (remediation log in the report): decrypted editor state is wiped on
  lock/verification re-arm (notes + code, incl. undo/redo/conflict/find state); recovery-reset
  copy/docs now say the phrase is unchanged (rotation stays an explicit settings action);
  credentials no longer live in saved instance state; **security build flags are debug-variant
  only — release builds hardcode FLAG_SECURE and auto-lock on**; rotated recovery phrases are
  dropped on lock; code-editor plaintext export is status-gated; code editor gained a
  locked-content gate; biometric/recovery-reset session boundaries clear lock verifications;
  backup OOM is a clean failure; the auto-lock race surfaces as a stale-session state instead
  of crashing.
- Documented deliberate non-fix: KDF re-wrap-on-unlock was claimed but never implemented —
  the claim was removed (DECISIONS #17); a vault keeps its creation iteration count until an
  explicit password change re-wraps the envelope.
- Dependency/licence inventory updated (Apache-2.0, protobuf-lite BSD-3 transitive, packaged
  native libs, test-only JUnit EPL-1.0 not shipped).

**Translation-ready UI**
- All user-visible strings extracted to Android resources: **336 strings + 6 plurals**
  (screen-prefixed names, 33 content descriptions), Composables use
  `stringResource`/`pluralStringResource`. Pure-JVM helpers and glyphs stay inline with
  documented reasons in `docs/STRINGS.md`. Visible English is unchanged except the corrected
  recovery-reset copy.

**Reproducibility finalised**
- Signed **APK and AAB are both byte-identical** across two independent clean builds from
  different paths (`--no-daemon --no-build-cache`, `SOURCE_DATE_EPOCH=1763054400`):
  APK `c48de399…68da`, AAB `00a66b1e…f22a`; published artifacts are exactly those builds.
- `scripts/verify-reproducible.sh` gained `--artifact aab` and `--unsigned` modes; CI now runs
  the unsigned reproducibility check on every push, so nondeterministic builds fail the
  pipeline without signing secrets.

**Store readiness**
- `fastlane/metadata/android/en-US/`: title, short/full description, changelog, 8 phone
  screenshots; new `PRIVACY.md`; updated F-Droid recipe (pinned toolchain, reproducibility
  notes, privacy-policy link). README/SECURITY link the audit, strings doc and privacy policy.

### Verified (measured)

- **764 unit tests / 0 failures** (60 classes; +24 audit regression tests) and **10
  instrumented tests OK** on the emulator, including the full E2E flow.
- Reproducible signed APK **and** AAB (hashes in `releases/SHA256SUMS` and
  `docs/VERIFICATION-2026-11-15.md` §10); certificate unchanged
  (`FA:CA:DD:67:…:92:78`).
- FLAG_SECURE active on the normal build (`screencap` 0-byte); a release build passed
  `-PallowScreenshots=true -PdevDisableAutoLock=true` still generates `false` flags.
- Release permissions unchanged (`USE_BIOMETRIC` only + androidx.core signature guard);
  offline guard green; 0 TODO/Log/hex-outside-tokens/network imports; 14 emulator screenshots.

### Still open

- F-Droid submission after the 3-month window (2027-02-15); tag the release.
- UI translations (infrastructure ready), cross-toolchain reproducibility with F-Droid's
  build image, and the remaining INFO/low-impact audit notes.

## [Unreleased] — Locked notes/folders + reproducible release builds (2026-11-15, increment 4)

### Added

**Locked notes & folders (last missing SPEC security/UX feature)**
- `Item.locked` / `Folder.locked` are content: they live **inside** the AES-GCM envelope
  (`ItemCodec` payload v3, `FolderCodec` v2; v1/v2 still decode with defaults, no migration).
  `saveItem` preserves the stored lock flag, so a stale editor autosave can never unlock.
- Masking: locked+unverified notes render a content-free "Locked note" row (no title, preview,
  tags, folder path or attachment names); search, tag chips and content aggregates exclude
  them. Locked folders hide their name and descendants until verified.
- Re-auth: device biometric presence prompt (platform `BiometricPrompt` without a
  CryptoObject — a visibility gate; content stays VMK-encrypted) with master-password
  fallback. `verifyMasterPassword()` compares the unwrapped VMK in constant time with rate
  limiting (1–4 s growing delay, 30 s cooldown after 5 failures) and wipes the input array.
- Verification is memory-only: cleared on lock, real background stop/screen-off, manual lock
  and session end — but **not** when an SAF picker defers the background lock. Backup/restore
  preserves lock flags and never verification.
- Code workspace defense in depth: locked+unverified code files and all descendants of a
  locked+unverified folder are filtered out before tree/counts/search, with a
  "N locked item(s) hidden — verify in Notes" hint (DECISIONS #16).
- Settings: "Locked notes & folders" section with "Re-arm locked notes now"
  (`settings_relock`); note/folder row menus gain Lock/Remove lock.
- Docs: DECISIONS #15 (lock semantics, threat model) and #16 (code-workspace hiding),
  DATA-MODEL version tables, ARCHITECTURE session re-arm, THREAT-MODEL handoff/forensic rows,
  CONTRACTS-PHASE5.

**Reproducible release builds (Phase 5)**
- `scripts/verify-reproducible.sh`: two clean builds from two **different absolute paths**,
  pinned `SOURCE_DATE_EPOCH`, `--no-daemon --no-build-cache`, then byte comparison of the
  signed APKs (plus per-entry diff report on mismatch).
- **Measured: byte-identical APKs** (`sha256 18254b31…5eaa`), including the APK Signing Block;
  methodology, pinned toolchain and limits (AAB equality not claimed) in
  `docs/REPRODUCIBLE-BUILDS.md`; RELEASING links the verification command.

### Verified (measured)

- **740 unit tests / 0 failures** (59 classes): data 123 (incl. 24 lock tests), code engine 139,
  ui code 103 (incl. locked-content hiding), ui notes 57, ui editor 45, ui locks 18,
  crypto 61, model 43, backup/settings 37, attachments 47, security 5, other UI 62.
- **10 instrumented tests OK** on the emulator; `VaultFlowE2ETest` now also covers: lock a note
  from its row menu → masked "Locked note" row → tap → re-auth dialog → master password →
  revealed note opens in the editor; the encrypted lock flag survives the session and
  verification does not.
- **Reproducible APK**: two independent clean builds byte-identical
  (`18254b31…5eaa`); signed with the NoteZero upload key (`FA:CA:DD:67:…:92:78`).
  AAB (not yet claimed reproducible): `d26bf216…6cf5`.
- FLAG_SECURE re-verified (`screencap` 0 bytes on the normal build); release permissions still
  `USE_BIOMETRIC` only; `check-offline.sh` green; 0 TODO/Log/hex-outside-tokens/network imports.
- 14 real-emulator screenshots committed (00-setup … 13-locked-note-revealed).

### Notes / known limits

- Lock is a **visibility gate**, not a second encryption layer: locked content is still under
  the vault VMK; the threat addressed is shoulder-surfing/handoff, not a fully compromised
  unlocked device (documented in THREAT-MODEL residual risks).
- Locked code files can only arise via restore/backup; the code workspace hides them and points
  to Notes for re-auth (no second re-auth surface).
- AAB byte equality and cross-toolchain reproducibility remain open Phase-5 items; string
  extraction and store assets are still pending. F-Droid window opens 2027-02-15.

## [Unreleased] — Phase 4 attachments + Phase 3 polish (2026-11-15, increment 3)

### Added

**Encrypted attachments (Phase 4)**
- `AttachmentRef` metadata (display name, MIME, size, sha256) lives **inside** the item's
  AES-GCM envelope (`ItemCodec` payload v2, still decodes v1 rows); the bytes live in
  app-private `filesDir/attachments/<id>.nzb` as `NZAT` v1 blobs (magic+version+AES-256-GCM
  under the vault VMK, AAD `notezero.attachment.v1|<noteId>|<attachmentId>`), SHA-256 verified
  on read, bounds-checked, atomic import (temp + rename, no orphan blob/ref on failure).
- Limits enforced while streaming: 10 MiB per attachment, 100 per note, 512 MiB vault quota;
  path-traversal-safe ids; locked vault fails closed.
- Lifecycle: trash keeps blobs, delete-forever/empty-trash removes them; startup orphan sweep
  (skipped when corrupt rows exist); attachment refs are repository-owned, so a stale editor
  autosave can never add or drop refs.
- Encrypted backup **format v2** carries attachment bytes; v1 backups still import;
  duplicate-safe restore with SHA-256 verification, missing/invalid blobs skipped+counted.
- Notes editor: attachment strip between title and body — Photo Picker image import
  (`PickVisualMedia`, no camera/storage permission), SAF file import, in-memory thumbnails
  (no plaintext cache), full-screen decrypted viewer, delete with confirm, decrypted export
  behind an explicit UNENCRYPTED-copy warning; all system pickers bracketed with
  `beginExternalActivity`/`endExternalActivity`.

**Code workspace polish (Phase 3 completion)**
- Workspace-wide search (`code/CodeSearch`, pure Kotlin): titles + bodies, line/column +
  snippet, case toggle, bounded; a result opens the file at the matched line.
- Sensitivity-aware copy (selection or all, `EXTRA_IS_SENSITIVE`) and explicit-warning
  decrypted export via SAF; multi-line indent/outdent toolbar actions and Tab/Shift+Tab.
- Docs: `docs/ATTACHMENTS.md`, `docs/ATTACHMENTS-UI.md`, `docs/CONTRACTS-PHASE4.md`,
  `docs/EDITOR-SPEC.md` §10, and `docs/DECISIONS.md` #14 (attachment storage/AAD/quota,
  including the documented 64 MiB backup cap consequence).

### Verified (measured)

- **663 unit tests / 0 failures** (51 classes): data 99, code engine 139, ui code 100,
  ui attachments 47, ui editor 38, crypto 61, model 43, backup/settings 37, security 5,
  shell/notes 94. Includes attachment AAD swap/tamper/SHA-mismatch, quota, trash-vs-delete,
  orphan sweep, backup v2 round trip incl. bytes + frozen v1 fixture, and code-search tests.
- **10 instrumented tests OK**; `VaultFlowE2ETest` extended with the code-workspace search
  flow (search `CodeE2E_secret` → 1 match → result opens at line) and the attachments strip
  renders in the real editor. Plaintext-at-rest scan still clean for note/code content.
- Signed release rebuilt and verified: APK `d498b1f3…cc30`, AAB `a270d6d5…591f`, certificate
  `FA:CA:DD:67:…:92:78`. FLAG_SECURE re-verified (`screencap` 0 bytes on the normal build);
  release permissions unchanged (`USE_BIOMETRIC` only); `check-offline.sh` green.
- Screenshots refreshed (11 PNGs incl. workspace search + search-result editor).

### Notes / known limits

- Backup format v2 keeps the existing 64 MiB `MAX_BACKUP_BYTES` OOM guard; a vault with more
  attachment bytes than that fails export **cleanly** (returns false, no partial file) instead
  of streaming — chunked backup v3 is future work (documented in BACKUP-FORMAT §6).
- Per-note count and vault quota share `AttachmentError.QUOTA_EXCEEDED` (UI differentiates).
- Drawing canvas, camera scan, tables editing UI and widgets remain Phase 4 follow-ups.
- Byte-for-byte reproducible release builds remain a Phase-5 task.

## [Unreleased] — Phase 2 complete + Phase 3 start (2026-11-15, increment 2)

### Added

**Encrypted backup UI (closes the Phase-2 gap)**
- SAF export/import of encrypted backup envelopes: `CreateDocument`/`OpenDocument`, suggested
  name `notezero-backup-YYYYMMDD-HHmm.nzbk`, no plaintext leaves the app.
- External-activity lock deferral: the SAF picker's background stop no longer locks the vault
  (`beginExternalActivity`/`endExternalActivity`); idle timeout still applies, end-while-
  background locks immediately, no permanent suppression, foreground return clears state.
- Export modes: vault-key (phrase-only restore) and optional separate backup password.
- Import: duplicate-safe verified restore, report counts (imported/duplicated/skipped/folders),
  cross-vault credential prompt accepting the source master password **or** 12-word phrase
  (3-attempt cap), lock-resume keeps the chosen file and continues after unlock.
- Settings "Data & backup" section with progress/success/report/error states; passwords are
  zeroized and never logged.

**Code editor (Phase 3)**
- Pure-Kotlin (JVM-testable) engine `com.cipherwell.notezero.code`: lexers for Kotlin, Java,
  JS, TS, Python, JSON, XML, Markdown and Shell (single-pass scanners, 200k token cap, no
  catastrophic regexes), `LineIndex`, and editing operations (indent/outdent, language-aware
  auto-indent, bracket matching, literal find/replace, go-to-line).
- Code workspace UI: nested file/folder tree over the SAME encrypted item model and folder
  tree as notes (`ItemKind.CODE_FILE`), create file/folder, rename, move, trash, empty state.
- Compose code editor: line-number gutter, current-line highlight, lexer token colours,
  auto-indent, bracket-match highlight, find/replace bar (next/prev/replace all/case/count),
  go-to-line, bounded undo/redo, font size 12–24 sp and wrap toggles persisted in non-secret
  prefs, 600 ms autosave with save-on-leave, dirty/conflict banner, 2 MiB open guard and
  >256 KiB plain-mono guard, Markdown preview reuse for .md files.
- Notes screen gains a Code workspace entry; `AppRoot` routes code workspace ↔ notes ↔ settings.
- Docs: `docs/CONTRACTS-PHASE3.md`, `docs/EDITOR-SPEC.md`, backup flow in
  `docs/BACKUP-FORMAT.md`, external-activity mechanism in `docs/ARCHITECTURE.md`,
  `docs/DECISIONS.md` #13.

### Verified (measured)

- **533 unit tests / 0 failures** (43 classes; code engine 126 incl. 200 KB lexer perf
  Kotlin 6.42 ms / JS 7.85 ms / XML 3.21 ms and fuzz invariants, ui code 72, backup 37,
  data 57 incl. 8 external-activity tests).
- **10 instrumented tests OK** on the emulator; `VaultFlowE2ETest` now also covers: Settings
  backup section, and the code workspace (create `hello.kt` → syntax-highlighted editor →
  `Saved` → back → file persisted). The raw SQLite plaintext scan was extended to the code
  file's name and body — both stay encrypted at rest.
- Signed release rebuilt and re-verified with the NoteZero upload key (APK sha256
  `2998c954…3195`, AAB `60203086…21c2`; certificate `FA:CA:DD:67:…:92:78`).
- E2E screenshot review caught two real defects and both were fixed with regression tests:
  always-plural workspace header ("1 files") and a scrolled-away settings back button
  (E2E now uses the system back action).
- `scripts/check-offline.sh` green; no new dependencies; no network imports.

### Notes

- Device E2E capture requires `-PallowScreenshots=true` (debug-only; normal builds keep
  FLAG_SECURE and `screencap` returns 0 bytes).
- Byte-for-byte reproducible release builds remain a Phase-5 task.

## [Unreleased] — Phase 1: security core + notes MVP (2026-11-15)

### Added

**Project & identity**
- Android project scaffold: AGP 9.1.1 / Gradle 9.3.1 / Kotlin 2.2.10 / KSP 2.3.5,
  `compileSdk 36`, `minSdk 24`, package `com.cipherwell.notezero`, MIT licence.
- **Independent NoteZero upload key** (not VaultZero's, not the debug key), generated outside
  the repo at `~/.cipherwell-release/notezero-upload.jks`; release signing via
  `NOTEZERO_KEYSTORE_PATH`/`NOTEZERO_STORE_PASSWORD`/`NOTEZERO_KEY_PASSWORD`; unsigned
  release fallback for F-Droid when secrets are absent. Certificate SHA-256 recorded in
  SECURITY.md.
- Repo `cipherwell/NoteZero` (public GitHub + GitLab mirror), CI (build, unit tests, offline
  audit, committed-key scan), signed-release workflow, fastlane metadata and F-Droid recipe
  scaffolds, dependabot policy.

**Security core (Phase 1)**
- `NoteCrypto`: AES-256-GCM (`iv||ct||tag`, 96-bit random IV, 128-bit tag), PBKDF2-HMAC-SHA256
  600,000 iterations (10,000 floor for legacy envelopes), VMK wrapping/unwrapping, SHA-256,
  HMAC-SHA256, constant-time compare, zeroization; pure-JVM (testable without Robolectric).
- Versioned `KeyEnvelope` (NZEN v1) codec with bounds-checked, hostile-input-safe parsing.
- BIP-39 12-word recovery phrases: embedded 2048-word English list, 128 bits entropy + 4-bit
  checksum, unique-word generation, standard-phrase validation (legacy-compatible), NFKD
  canonical form.
- Android Keystore biometric unlock: two-phase enrollment (`prepareEnroll` → BiometricPrompt →
  `completeEnroll`) and unlock (`prepareDecrypt` → prompt → `completeDecrypt`), StrongBox→TEE,
  per-use `AUTH_BIOMETRIC_STRONG`, invalidated by biometric re-enrolment with stale-wrap wipe.
  Platform BiometricPrompt is used from Android 9 (API 28+); API 24–27 falls back to password.
- Random 256-bit VMK; notes/folders AES-256-GCM encrypted under it with AAD bound to item id
  and kind; password change and recovery reset re-wrap the VMK without re-encrypting data.
- Redacted Room schema: items/folders rows hold only random id, kind, timestamps and
  ciphertext; titles, bodies, tags, folder names and flags live inside the envelope; corrupt
  rows skipped+counted; non-secret presence marker for correct initial lock state.
- In-memory decrypted index (empty when locked), encrypted stream backup export/import
  (vault-key/phrase-only and extra-password modes, duplicate-safe, all-or-nothing import),
  auto-lock (on background immediately + idle timeout) and DataStore preferences.

**Notes MVP UI**
- One UI-inspired Compose design system: true black/#FAFAFA grounds, one accent blue,
  squircle surfaces, floating pill navigation, large collapsing titles, WCAG-AA-checked
  tokens (contrast maths unit-tested), 48 dp targets, light/dark/system.
- Setup (password policy, one-time phrase with sensitive-flagged clipboard, 3-word
  confirmation gate), unlock (password + biometric CTA + recovery route), recovery
  (12-word phrase → new password), settings (vault info, change password, rotate recovery
  kit, two-phase biometric enrollment, auto-lock, theme, lock-now).
- Notes list: pinned/favourites/recent, nested folders + breadcrumbs, tags, in-memory search
  (title/body/tags/folder), trash with restore/delete-forever/empty, long-press actions
  (pin/favourite/rename/move/trash), move-to-folder picker, quick-note FAB.
- Markdown editor: source editing + live block preview (headings, inline bold/italic/code/
  links/strikethrough, bullets, numbered lists, tappable checklists that rewrite the
  markdown, fenced code, quotes, rules, tables), 800 ms debounced autosave, save-on-leave,
  dirty/conflict banner with overwrite/reload, undo/redo, word/char count, monospace option.

**Docs & proof**
- README, SECURITY, BUILDING, TESTING, RELEASING, DEPENDENCIES, DECISIONS, CONTRACTS,
  CRYPTO-SPEC, THREAT-MODEL, ARCHITECTURE, DATA-MODEL, BACKUP-FORMAT and
  `docs/VERIFICATION-2026-11-15.md` (measured evidence).
- Offline guarantee enforced three ways: `tools:node="remove"` for INTERNET /
  ACCESS_NETWORK_STATE (+ deprecated USE_FINGERPRINT), `scripts/check-offline.sh --gradle`,
  `OfflineGuaranteeTest` source/manifest scan. Network security config denies cleartext;
  Android backup/device-transfer excluded.

### Verified (measured, not claimed)

- `:app:assembleDebug` / `:app:assembleRelease` / `:app:bundleRelease` green (signed release
  APK sha256 `a23f8972…28d9`, AAB `1488edd3…27a5`); unsigned-release fallback verified.
  Caveat: two local builds of the same commit produced different packaging hashes, so
  byte-for-byte reproducibility is **not** claimed yet (Phase 5 work, docs/RELEASING.md).
- **290 unit tests / 0 failures** (27 classes) incl. RFC 7914 PBKDF2, NIST AES-GCM KATs,
  official BIP-39 vectors, AAD-swap rejection, ciphertext-unchanged password change.
- **10 instrumented tests OK** on a real emulator incl. an end-to-end vault lifecycle + UI
  flow; raw SQLite scan finds no plaintext title/body/phrase; checklist preview bug found by
  the E2E screenshot and fixed with regression assertions.
- FLAG_SECURE default-on verified (screencap returns 0 bytes on the normal build).
- Release permission list: `USE_BIOMETRIC` + androidx.core signature-level receiver guard only.

### Known gaps (next phases)

- SAF backup **UI** (export/import engine is done; the picker triggers the background lock, so
  the UI must resume post-unlock) — first Phase-2 item.
- Code editor (Phase 3), attachments/camera (Phase 4), reproducible-build verification and
  F-Droid submission after the 3-month window (Phase 5).
- Biometric CTA on API 24–27 is password-only by design (see docs/DECISIONS.md #11).

### Security baseline

- No cloud, accounts, telemetry, analytics or ads; no network permissions or APIs.
- Random 256-bit VMK; notes encrypted per-item with AES-256-GCM and AAD bound to item
  id + type; titles/tags/folder names/flags encrypted inside the envelope; Room columns
  hold only ids/kinds/timestamps/ciphertext.
- FLAG_SECURE on by default; encrypted backup excludes OS backup/device transfer.
