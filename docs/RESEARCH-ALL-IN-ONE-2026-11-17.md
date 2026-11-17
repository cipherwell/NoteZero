# Research — "simple notes app on the surface, all-in-one workspace underneath"

Date: 2026-11-17. Method: web research (Exa/Jina via agent-reach fallback; official product
pages, Play Store listings, open-source repositories, comparison guides) + analysis of this
codebase. Sources are linked inline; competitor claims are as published by the vendor/reviewer
at the time of reading. This document feeds `docs/ROADMAP-ALL-IN-ONE.md`,
`docs/DECISIONS.md` #22 and the increment-10 contracts.

## 1. Landscape: who does what

| Category | Apps studied | What they prove |
|---|---|---|
| Ink notes + PDF annotation | [Goodnotes](https://www.goodnotes.com/), Notability, Samsung Notes, Noteshelf, Nebo | A notebook app can absorb PDFs as first-class documents: import, mark up with ink, search handwriting, organise in notebooks/sections. Goodnotes' Android push groups **Notetaking, Whiteboard, Text Document, PDF Annotation** in one app; their tool palette (pen/pencil/highlighter/eraser/lasso/shapes, paper templates) is the reference for ink UX. |
| PDF reading/research | [Xodo](https://xodo.com/), PDF Expert, Adobe Acrobat, [MarginNote](https://alternativeto.net/software/marginnote/), [LiquidText](https://paperlike.com/de/blogs/paperlikers-insights/liquidtext-vs-marginnote), Flexcil | PDF work splits into **annotation** (highlights, ink, comments, signatures, forms, page ops) and **true content editing**. Consumer "PDF editors" on mobile mostly do annotation + page/form operations; Adobe is the exception with a proprietary text/object engine. Research apps add excerpt/notes/mindmap layers around the PDF. |
| Ebook readers | [ReadEra](https://readera.org/), [Moon+ Reader](https://www.moondownload.com/), [Librera](https://librera.mobi/), [KOReader](https://koreader.com/), [Aurora Reader](https://aurorareader.com/blog/best-ebook-reader-android/) | Reader feature baseline: EPUB/PDF (+MOBI/AZW3/FB2/TXT/CBZ/DjVu/DOCX on the widest apps), library shelf with covers/progress, TOC, bookmarks/highlights/notes, themes (incl. true-black AMOLED and sepia), font size/line spacing/margins, search in book, tap-zone navigation, TTS (premium), OPDS catalogues, per-book or global settings. DRM-protected store books are out of scope for third-party readers. Reflowing PDF text is possible only with a text layer and degrades layout/tables. |
| Reading engine architecture | [Readium Mobile (EDRLab)](https://readium.org/mobile/) | The reference open toolkit: EPUB 2/3 + fixed layout, PDF, audiobooks; user preferences, night/sepia, search, highlights/annotations, TTS, pagination & scrolling. Its existence shows the feature set is well-understood; for this app a dependency-free engine was chosen (see §4). |
| Notes + files + ext. | [Obsidian](https://obsidian.md/), Anytype, Logseq, Joplin, Apple Notes | Local-first Markdown vault + file/PDF viewer + canvas works as an all-in-one product. The winning pattern is one unified library with type-aware previews, global search, quick capture, and a plain-file core. Cloud/sync/AI are orthogonal — this app stays offline. |

## 2. Patterns worth copying (professional baseline)

1. **One library, type-aware opening.** Goodnotes/ReadEra/Xodo all start from a document shelf
   (notebooks, PDFs, books) and open the correct surface. Notes-only home screens fail the
   "all-in-one" test; the fix is a `Library` entry that lists every document kind with a preview,
   progress and a Continue action, while the default screen stays the notes list (simple surface).
2. **Reader chrome that gets out of the way.** Tap the centre to toggle bars, left/right zones to
   turn chapter/page, thin progress bar, TOC drawer, bookmarks, search, and one *Appearance* sheet
   (font scale, line height, margins, themes paper/sepia/night). This is consistent across
   Aurora, Moon+, ReadEra, Kindle and KOReader.
3. **Progress is a first-class encrypted field.** Every serious reader resumes exactly where the
   user left, per document, and shows percentage on the shelf. Bookmarks/settings are separate
   from content and must survive lock/unlock and backup.
4. **Annotation is a layer, not file surgery.** Goodnotes/Xodo store ink+hits over the document;
   export is an explicit flattened copy. This keeps originals intact, makes undo/erase possible
   and avoids a full PDF writer.
5. **True PDF text editing is a different product.** Mobile "PDF editor" almost always means
   annotate/sign/forms/page ops; object-level editing (Adobe) is proprietary and reflow is
   lossy. An offline FOSS app should implement annotation + page operations + flattened export,
   and say so.
6. **Format priority driven by real shelves.** EPUB is the open standard; PDF is the universal
   fixed document; TXT/FB2/CBZ are cheap wins; MOBI/AZW3 are niche, need their own parsers and
   are usually DRM-locked; DjVu/DOCX need heavyweight engines. Start EPUB+PDF, add text formats.
7. **Reflowable vs fixed layout are different readers.** EPUB reflows (typography controls);
   PDF zooms/crops (fixed). Do not pretend one mode covers both.
8. **Ink + text must coexist.** Goodnotes' strength is mixing handwriting, text and images on one
   page. For a notes+drawing+reader app, the drawing canvas should become a shared surface that
   can also annotate books (phase 2/3).
9. **Search spans the whole workspace.** Goodnotes advertises search across typed and handwritten
   content; Obsidian searches the vault. A unified search across notes, code, document text
   (where available) and book titles is the "IDE" feel users notice.
10. **Security must survive new surfaces.** Readers add file parsers (EPUB ZIP/XML, PDF), which is
    new attack surface: harden parsers (bounded, no XXE, no network), decrypt only in memory,
    keep covers/thumbnails in memory (never plaintext caches), FLAG_SECURE everywhere, store
    reading state in the encrypted item envelope, and keep the widget/library titles behind lock.

## 3. What to explicitly NOT do (learned from competitor weaknesses)

- **No DRM circumvention**: Kindle/Adobe DRM, `.acsm` tickets → show a clear error, never attempt.
- **No fake PDF text editing**: don't promise reflow/word-level edits without a text layer; offer
  annotation + page ops + flattened export and label it honestly.
- **No network**: no cloud sync, no OPDS catalogue browsing*, no telemetry, no account. (*OPDS is
  network; excluded by the offline invariant, unlike Moon+.)
- **No WebView for EPUB**: vendor pages show high-fidelity HTML engines, but a WebView adds a
  large dynamic attack surface and a JS bridge is needed for highlights/TTS; this app's threat
  model is stricter. Use a native block renderer (documented CSS-fidelity limits).
- **No infinite-canvas-only drawing** for everyday notes: infinite canvases (Concepts/Procreate)
  are great for art, but reading/writing apps need fixed paper pages + templates.
- **No settings dump**: Moon+ Reader's depth is also its UX cost; keep one appearance sheet with
  presets and per-book overrides only where they matter.

## 4. Technical decisions derived from the research

| Area | Decision |
|---|---|
| EPUB engine | Dependency-free pure Kotlin: ZIP (`java.util.zip`), container/OPF/NCX/nav XML (`javax.xml`), XHTML→native block model. Bounded parsing (bomb protection), no scripts/styles/CSS beyond a small typographic subset, DRM detection → clean "cannot open" error. Expected limits documented (complex layout/tables approximate). |
| Ebook formats | Phase 1: **EPUB 2/3**. PDF already supported. Phase 4: TXT/FB2/CBZ (image comic), then evaluate MOBI/AZW3 (own parser, DRM caveat). |
| PDF reader | Keep platform `PdfRenderer` (PDFium) for fidelity/perf. Add reading state, page navigation, bookmarks, thumbnails, zoom. Text search/annotation requires either a renderer text layer (not exposed by Android's API) or an embedded engine → deferred to a dedicated phase with a vetted library decision. |
| PDF editor scope | Annotation layer (ink, highlight rectangle, text note, bookmark) + page operations + explicit flattened-copy export. No fake text editing. |
| Reading state | Encrypted inside the item envelope (`ItemCodec` v4): position (chapter/page), fraction, bookmarks, book metadata. No separate plaintext DB. |
| Reader settings | Non-secret global preferences (font scale, line height, margins, theme) in app-private SharedPreferences; per-book override deferred. |
| Library | New `Library` surface reachable from Notes (book icon), preserving the notes-first layout. Filters: All / Books / PDFs / Drawings; import buttons (PDF, EPUB); progress + Continue. |
| Drawing | Keep the existing encrypted-canvas pipeline; phase 3 promotes it to a sketch library with templates, shapes/lasso/highlighter, and (phase 2) book annotation. |
| IDE direction | Treat every item as a file: unified search + file browser in phase 4; code workspace remains the code surface. |
| Security | EPUB/PDF parsers are attack surface: fuzz-ish property tests, strict size/entry/count caps, no XXE, no script execution, no network, decrypted bytes only in memory, thumbnails/cover decoded in memory, FLAG_SECURE everywhere, titles/locked rules unchanged, widget never shows content. |

## 5. Sources

- Goodnotes product/features: <https://www.goodnotes.com/>, PDF annotation feature page.
- Notability vs Goodnotes comparison (2026): <https://www.itechguides.com/best-note-taking-apps-for-handwritten-notes-and-pdf-annotation/>
- Android ebook readers comparison (ReadEra/Moon+/Librera/KOReader/Aurora): <https://aurorareader.com/blog/best-ebook-reader-android/>
- Readium Mobile toolkit: <https://readium.org/mobile/>
- KOReader: <https://koreader.com/>
- Xodo / MarginNote / LiquidText comparisons: <https://alternativeto.net/software/marginnote/>, <https://paperlike.com/de/blogs/paperlikers-insights/liquidtext-vs-marginnote>
- MOBI/AZW3 vs EPUB background: <https://blog.fileformat.com/es/ebook/mobi-vs-epub-vs-azw3-which-e-book-format-is-best-today/>
- Reading UX principles (flow, legibility, distraction-free chrome): <https://www.linkedin.com/posts/mdrimel15_5-principles-behind-a-high-retention-reading-activity-7429024143049936896-eNad>
- Android open-source EPUB/PDF implementation examples: GitHub topics (`ebook`, `epub`, `pdf-annotations`).
