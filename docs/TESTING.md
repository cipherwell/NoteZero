# NoteZero — testing strategy

## Layers

| Layer | What it proves | Where |
|---|---|---|
| Pure JVM unit tests | Crypto primitives vs published vectors, envelope codecs, markdown codec, search/sort helpers | `app/src/test/java/com/cipherwell/notezero/**` |
| Robolectric unit tests | Room encryption/redaction, repository lifecycle (setup→unlock→lock), UI state machines, Compose screens with a fake repository | same, annotated `@RunWith(RobolectricTestRunner::class)` |
| Instrumented tests | Android Keystore biometric enrolment/invalidation, SAF backup round-trips, no-network runtime check | `app/src/androidTest/java/com/cipherwell/notezero/**` |
| Build-time guards | Offline guarantee (permissions, imports, deps), merged-manifest redaction | `scripts/check-offline.sh`, `OfflineGuaranteeTest`, CI |
| Emulator smoke | App launches, setup/unlock/notes flow, FLAG_SECURE behaviour, screenshots | Lead-run on `VaultZero-Live` AVD |

## Commands

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
./gradlew :app:testDebugUnitTest                  # all unit tests
./gradlew :app:testDebugUnitTest --tests "com.cipherwell.notezero.crypto.*"
./gradlew :app:connectedDebugAndroidTest          # device/emulator attached
./scripts/check-offline.sh --gradle
```

CI runs `assembleDebug`, `testDebugUnitTest`, and `check-offline.sh --gradle` on every push.

## Increment-10 areas (Library + readers)

| Area | Tests | What it proves |
|---|---|---|
| `book.*` (EPUB engine, plain JVM) | 104 | EPUB 2/3 container/OPF/spine/TOC/metadata/cover parsing; XHTML → block model; bounded ZIP/XML, XXE-off, traversal/duplicate/truncation handling; DRM refusal; fuzz/malformed input never throws; `close()` lifecycle and image/cover bounds |
| `ui.library.*` (Robolectric) | 12 | Shelf rows/filters/progress/empty state, masked documents hidden, open→reader routing, rename/trash rules, import menu |
| `ui.reader.*` (Robolectric) | 28 | Loading→content, block rendering, TOC scroll, in-book search hit/empty/scroll, bookmark add/delete/persist, appearance theme+font persistence, corrupt/DRM `book_error`, debounced progress save + restore |
| `ui.pdf.*` reading tools | 5 | Page total persisted after render, bookmark add/list/delete, out-of-range page-jump validation, resume from encrypted position |
| `data.*` reading state | 6 | Payload v4 round-trip, v3 compatibility, bounds enforcement, repository persistence through lock/unlock, backup round-trip, no plaintext bookmark/author in Room ciphertext |
| Instrumented E2E | 15 | Unchanged flows still pass with the new Library route; real Keystore/SAF/FLAG_SECURE coverage |

## Increment-11 areas (PDF annotation + flattened export)

| Area | Tests | What it proves |
|---|---|---|
| `data.PdfAnnotationCodecTest` | 6 | Round-trips, density cap, truncation/corruption/trailing bytes, 1,000-sample fuzz, empty layer |
| `data.PayloadCodecTest` / `LockedPayloadCodecTest` / `encrypted repository` | 6 | Payload v5 round-trip, v4 compatibility, boundary-byte invariants, persist/preserve/locked-write-rejection/backup/no plaintext note text/clear |
| `ui.pdf.PdfAnnotationScreenTest` | 14 | Annotate mode, palette tags, gestures create strokes/highlights/notes, persistence, export-choice gating |
| `ui.pdf.PdfAnnotationEditorTest` | 16 | State machine: tools, undo/redo, eraser hit-tests, note edit/delete, bounds/fail-closed |
| `ui.pdf.PdfAnnotationPaletteTest` | 8 | Token-derived colours/widths, index bounds, shared overlay/exporter palette |
| Instrumented `PdfFlattenedExportInstrumentedTest` | 4 | Real `PdfDocument` output accepted by `PdfRenderer` with matching page count, hostile/oversized fail-closed, max-density export |
| `PdfPagePlanTest` | 23 | Pure plan algebra: rotate/move/delete/insert/reset, bounds, 100-page cap, last-page refusal, hostile positions |
| `PdfPageToolsScreenTest` + `PdfPageCopySaverTest` | 20 + 8 | Overlay controls/notices and save wiring via fake exporter/saver; encrypted-copy creation, import failure cleanup, name bounds |
| Instrumented `PdfPageToolsExportInstrumentedTest` | 4 | Real `PdfRenderer`/`PdfDocument`: reorder/rotation/blank/delete page output, map/annotation handling, hostile input fail-closed |

## Increment-12 areas (drawing studio)

| Area | Tests | What it proves |
|---|---|---|
| `data.SketchCodecTest` | 6 | Round-trips, bounds, corruption/truncation, 1,000-sample fuzz, dense-cap |
| `data.PayloadCodecTest` / locked-boundary / encrypted repository | 8 | Payload v6 round-trip, v5/v4 compatibility, lock-byte boundaries, persist/preserve/locked-write/backup/clear |
| `ui.sketch.SketchEditorStateTest` | 19 | Pure editing state machine: tools, bounds dropping, undo/redo, clear, template, lasso/eraser commits |
| `ui.sketch.SketchRendererTest` | 8 | In-memory PNG render parity/bounds, templates, shapes/arrow heads, off-main-thread render |
| `ui.sketch.SketchStudioScreenTest` | 17 | Studio composition, gestures (pen/highlighter/shape/eraser/lasso), template/colour/width, undo/redo, rename, autosave, back-flush preview import/replace, empty hint |
| `ui.library` | 17 | Sketch rows/open-studio, New drawing action + failed-create cleanup, preview-less sketches, existing PDF/EPUB/drawing behaviour |
| Instrumented `SketchRendererInstrumentedTest` | 4 | Real in-memory PNG decode/bounds, hostile/oversized document fail-closed |

## Increment-13 areas (universal Workspace)

| Area | Tests | What it proves |
|---|---|---|
| `ui.workspace.WorkspaceQueryTest` (plain JVM) | 16 | Fail-closed visibility, row/filter/search(+snippet)/sort, storage summary, selection algebra, folder flattening |
| `ui.workspace.WorkspaceScreenTest` (Robolectric) | 25 | Rows/filters/sort/search/summary, masked+hidden items absent, long-press multi-select + select-all, bulk move/lock/trash + confirmation, non-secret failures, note/code open callbacks, hosted PDF/book/sketch routing with test seams, re-auth for verified-locked rows |
| `ui.AppRootTest` shell routing | +1 | Workspace opens from the Notes top bar and back returns to Notes |

## Increment-14 areas (TXT / FB2 / CBZ formats)

| Area | Tests | What it proves |
|---|---|---|
| `book.BookDetectorTest` | 18 | Content-based detection matrix (EPUB/CBZ/FB2/TXT), ambiguity, truncation, random blobs |
| `book.TxtBookParserTest` | 27 | BOM UTF-8/UTF-16, windows-1252 fallback, chaptering/paragraph mapping, caps, empty/oversize |
| `book.Fb2BookParserTest` | 35 | Metadata/TOC/sections/inline styles/poems/epigraphs/cover/base64 images, XXE/DTD refusal, limits |
| `book.CbzParserTest` | 15 | Natural page order, page bytes/copies/close, corrupt/missing entries, caps |
| `book.BookLoaderTest` | 12 | Dispatch, `BookContent`/`ComicBook` lifecycle, fuzz |
| `ui.reader.BookReaderScreenTest` | +7 (23) | TXT render/search/TOC, FB2 metadata/sections/nested TOC/search, unsupported bytes -> `book_error`; EPUB cases unchanged |
| `ui.comic.ComicReaderScreenTest` | 11 | Page rendering/navigation, page jump validation, bookmark add/delete/resume persistence, missing/empty comic errors |
| `ui.library.LibraryBookImportTest` | 7 | Format detection/MIME/title/author import + unsupported cleanup (no orphan row) |

## Increment-15 areas (code workspace IDE)

| Area | Tests | What it proves |
|---|---|---|
| `ui.code.CodeTabsTest` + `CodeWorkspaceTabsScreenTest` | 10 + 6 | Tab open/close/neighbour/limit algebra, back-keeps-tabs, strip semantics, draft/undo preserved across switches |
| `ui.code.CodeQuickOpenTest` + `CodeQuickOpenScreenTest` | 7 + 7 | Filtering/ordering, locked/hidden exclusion, dialog open/empty state, opening activates/creates a tab |
| `ui.code.CodeTemplatesTest` + `NewCodeFileDialogTest` | 5 + 9 | Template name/extension/body swaps, format/template mismatch -> blank body, cancel creates nothing |
| `ui.code.CodeEditorScreenTest` / `CodeEditorViewModelTest` / `CodeWorkspaceViewModelTest` | 17 / 37 / 26 | Split-preview gating, editor behaviour unchanged, workspace create-body/quick-open state, existing tags |
| Full suite | **1451 / 0 failures / 116 classes** | No regressions in notes/reader/PDF/workspace/library suites |

## Backup-flow tests (Phase-2 SAF gap)

| Test | What it proves | Tooling |
|---|---|---|
| `data/ExternalActivityLockTest` | The SAF picker's `onStop` does not lock the vault; `endExternalActivity` while backgrounded locks immediately; the idle timeout still locks during an external activity (no permanent suppression); nested brackets; a lost picker result cannot suppress later background locks | Robolectric, real repository + in-memory Room + real crypto |
| `ui.backup/BackupFlowControllerTest` | Vault-key and separate-password export write a non-empty envelope and wipe the password; import report/duplicate counts; cross-vault credential prompt; password **or** phrase submitted joined by single spaces; 3-attempt cap; `INVALID_FILE` structural probe; locked-picker resume via `onVaultUnlocked()`; cancelled/failed picker settles the external-activity bracket | plain JUnit, fake repository + in-memory streams |
| `ui.backup/BackupSectionContentTest` | Every `BackupUiState` renders the contract tags, non-secret copy, credential dialog, confirmation gate and disabled in-progress actions | Robolectric Compose |
| `ui.screens.settings/BackupSettingsSectionTest` | Settings screen shows the "Data & backup" section with stable tags; empty-vault export is refused without launching SAF | Robolectric Compose |

Commands:

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.cipherwell.notezero.data.*" \
  --tests "com.cipherwell.notezero.ui.backup.*" \
  --tests "com.cipherwell.notezero.ui.screens.settings.*"
```

## Required coverage by area (from the security bar)

- **Crypto:** PBKDF2-HMAC-SHA256 RFC 7914 vectors, AES-GCM NIST vectors, BIP-39 official
  English vectors, AAD cross-context swap failures, tamper/truncation rejection, wrong
  password returns failure (never throws AEADBadTag), constant-time compare sanity.
- **Key lifecycle:** setup→unlock; change password re-wraps VMK **without touching item
  ciphertext** (assert byte-identical rows); recovery reset; recovery rotation invalidates
  the old phrase; biometric wrap invalidation falls back to password.
- **Data at rest:** Room rows contain no plaintext title/body/tag/folder; corrupt row is
  skipped not surfaced; item AAD swap rejected; DB copy without VMK is useless (assert
  ciphertext non-plaintext and decrypt fails without VMK).
- **Backup:** export→import round trip, duplicate-safe import (same id+updatedAt counted,
  not overwritten), wrong password rejected, phrase-only mode.
- **UI/security UX:** FLAG_SECURE on by default, recovery phrase displayed once and gated
  behind confirmation, clipboard marked sensitive, password fields masked, no plaintext in
  logs, auto-lock triggers on background/timeout; the SAF picker background is deferred
  without cancelling the idle timeout and later background locks still lock.

## Measured baseline (2026-11-15, Phase 4 complete + Phase 5 hardening)

| Suite | Command | Result |
|---|---|---|
| Unit (JVM + Robolectric) | `./gradlew :app:testDebugUnitTest` | **1451 tests / 0 failures / 0 errors / 0 skipped** (116 classes) |
| Instrumented (emulator) | `adb shell am instrument -w com.cipherwell.notezero.test/androidx.test.runner.AndroidJUnitRunner` | **OK (27 tests)** incl. `NotePdfRenderInstrumentedTest`, `VaultFlowE2ETest` (notes + backup settings + code workspace + create sheet + PDF) and a real-`PdfRenderer` instrumented test |
| Offline guarantee | `./scripts/check-offline.sh --gradle` | exit 0 |
| Release signing | `apksigner verify --print-certs app-release.apk` | NoteZero upload key SHA-256 `FA:CA:DD:67:…:92:78` |

Per-area counts: crypto 61, data 125, model 65, security 5, code engine 143, ui code 121,
ui attachments 88, ui notes 91, ui editor 74, other UI 62, backup 33, ui locks 18, ui screens 12,
ui pdf 12, widget 3.
Audit remediation added 24 regression tests (editor state wipe on lock/re-arm, code editor
locked gate, export status gating, stale-session handling, recovery-reset boundaries).
Device QA (Pixel 9 Pro XL, Android 17, font scale 1.15) added 10: biometric prompt-error
mapping + enrolled-flag gating (VaultViewModel/AppRoot) and the empty-note-on-leave predicate
(attachments count as content). The create-menu/PDF increment added 13: blank-PDF wire
format, code-format name rules, PDF item codec round trip, create sheet/viewer Robolectric
tests, `NotesViewModel.createPdf/createCodeFile`, and the device-only real-`PdfRenderer` test.
The PDF-export/widget increment added 14: `PdfInlineMarkdownTest` (9), PDF request/selector
tests in the export ViewModel/UI suites, PDF content extraction via the export path, the
device-only `NotePdfRenderInstrumentedTest` (3, real `PdfRenderer`) and 3 widget tests
(intent contract, RemoteViews inflation, static metadata).
Highlights: code engine includes 200 KB lexer performance (Kotlin 6.42 ms, JS 7.85 ms,
XML 3.21 ms), fuzz invariants and `CodeSearch`; attachments cover NZAT blob format,
import limits/quota, AAD note/id swap rejection, tamper + SHA mismatch, trash-vs-delete
lifecycle, orphan sweep, locked fail-closed, backup v2 round trip incl. bytes, duplicate-safe
re-import and a frozen v1 backup fixture; E2E extends the SQLite plaintext scan to attachments.
Device E2E also scans the raw SQLite files for plaintext and captures the screenshots in
`docs/screenshots/`. Full evidence: `docs/VERIFICATION-2026-11-15.md`.

## Conventions

- Tests must assert behaviour, not existence (`assertTrue(true)` is a review reject).
- Deterministic: fixed test vectors/entropy where possible; no sleeps — inject a clock or
  use `kotlinx-coroutines-test` virtual time for autosave/auto-lock.
- No secrets in test fixtures: generated keys only, wiped after use.
- Crypto tests must not require Robolectric or a device.
- Every fixed security bug gets a regression test in the same commit.
