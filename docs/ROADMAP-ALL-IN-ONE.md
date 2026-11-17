# NoteZero roadmap — "simple notes app on the surface, all-in-one workspace underneath"

Product direction (owner request, 2026-11-17): keep the everyday notes surface simple, while making
NoteZero a single offline workspace for **notes, files/code, books, PDFs and drawings**. Research and
rationale: `docs/RESEARCH-ALL-IN-ONE-2026-11-17.md`; policies: `docs/DECISIONS.md` #22.

Guiding rules:
- **Notes first.** The app opens on the Notes list; the workspace surfaces (Library, readers,
  drawing, files) are one tap away, never forced.
- **Offline + encrypted by default.** Every new surface must obey the existing invariants: no network
  permission/APIs, AES-256-GCM item envelopes, AAD binding, locked-vault masking, FLAG_SECURE, no
  plaintext caches/metadata/logs, no new permissions.
- **One item model.** Books, PDFs and notes are vault items with encrypted attachments + encrypted
  `readingState`; the library, locks, trash, backup, search and export rules apply unchanged.
- **Honest scope.** Annotation, not fake PDF text editing; DRM books are refused with a clear error.

## Phase 10 — Library + reading foundation (increment 10, this cycle)

Deliver:
1. **Library** screen (from Notes top bar): one shelf with All / Books / PDFs / Drawings filters,
   covers/type badges, progress and Continue, document import (EPUB + PDF) through SAF, rename /
   lock / trash / export actions reused from the notes rules.
2. **EPUB reader** (EPUB 2/3, dependency-free engine): chapter navigation with TOC, in-book search,
   bookmarks, appearance (font size, line height, margins, paper/sepia/dark), tap zones, progress,
   resume exactly where left off.
3. **PDF reader upgrade**: page slider/jump, bookmarks, progress/resume, document title/metadata
   row; keeps the existing encrypted-attachment viewer.
4. **Encrypted reading state** (`ItemCodec` v4): position, fraction, total, bookmarks, book
   author/language; travels with backup; repository-owned (autosave cannot roll it back).
5. Security proof: parser hardening (bounded ZIP/XML, XXE off, DRM refusal, fuzz-ish tests), no
   plaintext cover/thumbnail cache, FLAG_SECURE, library titles behind lock; reproducible artifact.

## Phase 11 — PDF annotation & document editing (annotation layer + flattened export shipped, increment 11)

- Ink and highlighter layer over PDF pages, rectangle/underline highlights, text notes/sticky notes,
  colour palette + width, eraser, undo/redo; all stored encrypted (`ReadingState`/new annotation
  payload v5) keyed by page + normalised coordinates.
- Page tools: bookmark panel with thumbnails, rotate/delete/insert-blank page, page reorder; export
  a **flattened PDF copy** through the explicit plaintext-export flow (rendered pages + annotations;
  original untouched).
- Honest labelling: no text-object editing; scanned PDFs remain images; optional text extraction /
  search deferred to a vetted library decision (PDFBox audit) — if added, it ships behind the same
  in-memory, no-cache rules.
- Tests: annotation model/layer codecs, renderer round trips, flattened export validated with
  external tools, instrumented real-PdfRenderer acceptance, device QA with a real PDF.
- **Shipped in increment 11:** encrypted annotation layer (ink/highlighter/rectangle highlights/
  sticky notes/eraser/undo-redo, payload v5), annotate-mode UI, flattened annotated-copy export,
  codec/fuzz/UI/instrumented tests, docs + VERIFICATION §16. **Still queued:** page
  thumbnails, rotate/delete/insert/reorder page operations, text-layer search/extraction (needs a
  vetted PDF text engine) and rendering sticky-note text into flattened copies.

## Phase 12 — Drawing studio

- Standalone sketch documents (encrypted PNG/vector layer) in the Library's Drawings filter.
- Professional notebook tools learned from Goodnotes: pen/pencil/highlighter/eraser/lasso, shapes
  (line/rect/ellipse/arrow), colour palette + custom colour (token-bound), width presets, paper
  templates (blank/ruled/grid/dot/A4-letter), undo/redo, zoom/pan canvas, palm rejection heuristics,
  stylus pressure when the device reports it.
- Notes/PDF annotation can reuse the same canvas surface (shared stroke model + renderer).
- Preserve the existing no-plaintext rule: renders stay in memory, imported as encrypted PNG.

## Phase 13 — "IDE" file workspace

- Unified file browser across all item kinds (notes, code, PDFs, books, drawings) with folders,
  multi-select move/trash/export, sort/filter, storage/quota view.
- Workspace-wide search across titles/notes/code/book text (EPUB text searchable in memory) with
  type facets and recent filters.
- Code workspace polish: multi-tab recent files, split Markdown preview, project-level symbol-ish
  search (offline lexers only), file templates; keep no LSP/network/AI.

## Phase 14 — More formats, reading comfort, release polish

- Formats: TXT/FB2/CBZ (comics) first (cheap, offline parsers), then evaluate MOBI/AZW3 (own
  parser; DRM-locked files refused); DjVu/DOCX out of scope unless a vetted engine is chosen.
- Reader comfort: TTS via platform engine (optional, offline voices), hyphenation, per-book
  appearance overrides, statistics, OPDS explicitly **not** (network invariant).
- Translations, accessibility pass, F-Droid publication window (2027-02-15), store assets.

## Acceptance per phase

Every phase must ship: green unit + instrumented suites, a reproducible signed APK/AAB, updated
RESEARCH/ROADMAP/DECISIONS/CONTRACTS, VERIFICATION section with measured evidence, device QA on
the Pixel (Android 17, font scale 1.15) and a clean offline/security check (no network permission,
FLAG_SECURE, no plaintext caches, 0 crashes).
