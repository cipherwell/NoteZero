# Universal file workspace ("Files" / IDE layer) — Phase 12, increment 13

Status: implemented in `app/src/main/java/com/cipherwell/notezero/ui/workspace/`.
Contract: [`docs/CONTRACTS-PHASE12.md`](CONTRACTS-PHASE12.md). Strings: pre-added `workspace_*` /
`cd_workspace*` in `app/src/main/res/values/strings.xml` (no `res/` change in this increment).

The workspace is one encrypted file-manager/IDE view over the **same decrypted index** as Notes,
the Library shelf, the code workspace and the readers. It adds no data model, no payload/schema
change, no repository API, no permission, no dependency and no logging of content.

## Files

| File | Purpose |
| --- | --- |
| `ui/workspace/WorkspaceScreen.kt` | `WorkspaceScreen(repository, onBack, onOpenNote, modifier)` — top bar, search/filter/sort, list, multi-select bulk actions, storage summary, dialogs and the hosted PDF/BOOK/SKETCH routing. |
| `ui/workspace/WorkspaceModel.kt` | Pure, Compose-free rules: fail-closed visibility, row model, filtering, search + snippet, sorting, storage totals, selection set algebra, move-to-folder flattening. |
| `test/.../ui/workspace/WorkspaceQueryTest.kt` | 16 plain-JVM tests for the pure rules. |
| `test/.../ui/workspace/WorkspaceScreenTest.kt` | 25 Robolectric + Compose tests for the screen (`@Config(sdk=[34])`, no display qualifiers). |

The public entry point has exactly the frozen signature. A package-internal
`WorkspaceScreenContent(repository, onBack, onOpenNote, modifier, pdfHost, bookHost, sketchHost,
biometrics)` is the test seam for the hosted-document routing (same idea as the renderer
injection used by the existing PDF/reader suites); production calls it through the default
hosts, which are the real existing screens.

## Rows and routing

Visible rows = every non-trashed item that passes the fail-closed gate (see below), across all
five `ItemKind`s. Each row (`testTag = workspace_item`, 48 dp minimum target) shows:

* type label from resources — `workspace_type_note` / `_code` / `_pdf` / `_book` / `_sketch`
  (each label Text also carries the matching test tag),
* title (item title, falling back to the type label when blank),
* folder name, or `workspace_no_folder` at the vault root,
* metadata line: visible attachment count (`plurals/attach_count`), human-readable summed
  attachment size (reused `ui/attachments/AttachmentFormat.size`) and relative updated time
  (reused `ui/notes/NoteFormatting.relativeTime`),
* a lock glyph (`workspace_locked_content` content description, `workspace_locked` in the
  metadata line) for a locked item that is verified in this session,
* an optional one-line body/code search snippet.

Row tap:

| kind | route |
| --- | --- |
| `NOTE`, `CODE_FILE` | `onOpenNote(itemId)` — the Lead's notes-shell deep link (NOTE → editor, CODE_FILE → code editor). |
| `PDF_FILE` | existing `PdfViewerScreen`, hosted inside the workspace; its back returns to the list. |
| `BOOK_FILE` | existing `BookReaderScreen`, hosted; back returns to the list. |
| `SKETCH_FILE` | existing `SketchStudioScreen`, hosted; back returns to the list (studio flush/preview semantics unchanged). |

A locked row always calls `LockReauthController.request(LockedTarget(id, NOTE))` before running
the open action, exactly like the Notes list; a session-verified id short-circuits inside the
controller, an unverified one can never be reached because it is not rendered. The standard
`ReauthDialog` is hosted by the screen. While a document is hosted, the workspace list is not
composed and its BackHandler is disabled (the hosted screen owns back).

## Masking rules (fail closed, decision #15)

Implemented once in `WorkspaceQuery.visibleRows`; every row, search match, filter result and
storage total is derived from it.

1. Trashed items are excluded.
2. `LockVisibility.isItemMasked(item, verifiedLockedIds)` items are **excluded entirely** (the
   contract's fail-closed choice for this surface — not rendered as a mask row).
3. Items whose `folderId` is in `LockVisibility.hiddenFolderIds(folders, verifiedLockedIds)` are
   excluded; that set is each locked+unverified folder **plus all descendants**.
4. Folder display names are resolved only from the visible folder map, so a hidden folder name
   can never leak through a row.
5. Search, snippets and the storage summary operate only on rows that already passed this gate,
   so title/body/tags/attachment names/author of a masked item can never contribute.
6. The screen also renders nothing when `repository.status` is not `UNLOCKED`, and clears the
   selection, dialogs, query and any hosted screen when the vault locks mid-session.

## Search

`workspace_search` (placeholder `workspace_search_placeholder`, trailing clear action
`cd_workspace_clear_search`). Case-insensitive substring match over the decrypted metadata of
visible rows only:

* item title,
* item body (notes and code files),
* tags,
* attachment display names (= the document "file name"),
* EPUB author from `Item.readingState.author`.

A match that comes from the **body** attaches a bounded one-line snippet
(`WorkspaceQuery.searchSnippet`, ~96 chars + ellipses, word-boundary aware) rendered under the
title. Title/tag/file-name/author matches render no snippet. No full-text extraction from
PDF/EPUB bytes (explicitly out of scope). Empty states: `workspace_empty_title` +
`workspace_empty_message` when the vault has no visible record at all, `workspace_no_matches`
when records exist but the current search/filter matches none.

## Filter and sort

* Type chips `workspace_filter_all / _notes / _code / _pdfs / _books / _drawings`
  (`DRAWINGS` = standalone `SKETCH_FILE`; note-owned drawings are `NOTE`s, as in the data model).
* Sort control `workspace_sort` → menu `workspace_sort_menu` with `workspace_sort_updated`
  (default, last-updated descending) and `workspace_sort_name` (title A–Z, case-insensitive).
  Ordering is deterministic (title then id tie-breaks). Reorder keeps the LazyColumn's current
  anchor item visible (Compose key semantics); the device-QA list notes this.

## Multi-select and bulk operations

* Long-press a row enters selection and selects it; taps in selection mode toggle checkboxes
  (`workspace_item_checkbox`, content description `cd_workspace_select` with the title). The
  top bar swaps the title for `workspace_selected_count` (`%1$d selected`).
* The top-bar overflow (`workspace_more_actions`) offers **Select all** (`cd_workspace_select_all`);
  the selection action row also has `cd_workspace_select_all` (select every *currently displayed*
  row) and `workspace_clear_selection`. Back/clear-selection exits the mode.
* `workspace_move` opens the existing `MoveToFolderDialog` (`FolderRowModel`s built from
  `repository.folders` with depth/counts; hidden locked folders are excluded and never offered
  as a target). Selecting a folder calls `repository.moveItem(id, folderId)` for every selected
  visible id, including "No folder" (`null`).
* `workspace_lock` / `workspace_unlock` (one button whose label/tag reflects the selection:
  unlock only when every selected row is locked) calls `repository.setItemLocked`. Unlocking goes
  through `LockReauthController.request` first (the standard re-auth path); locking needs no
  re-auth. Locking a selected row re-masks it immediately, so it disappears from this surface.
* `workspace_trash` opens the confirmation dialog (`workspace_trash_dialog`,
  `workspace_trash_confirm_title` with the count, `workspace_trash_confirm_message`,
  `workspace_trash_confirm` = `workspace_trash_confirm_action`, `workspace_cancel`) and then
  calls `repository.trashItem` for each selected visible id.
* Every bulk action applies only to currently selected, currently visible ids
  (`WorkspaceQuery.selectedVisibleIds`), clears the selection afterwards, and swallows
  repository exceptions/`false` returns (the vault may lock mid-flight; nothing crashes). Any
  failure shows the non-secret `workspace_bulk_failed` notice; the screen keeps rendering the
  repository's flows, which is the "refresh from repository" path. No decrypted name/content is
  ever put in the notice or a log.

## Storage summary

`workspace_storage_summary` (`%1$d items · %2$d attachments · %3$s`) is rendered under the
search/filter header in list mode. It is computed from the visible decrypted index only
(`WorkspaceQuery.storageSummary`): visible item count, summed attachment ref count and summed
`AttachmentRef.sizeBytes` formatted with the app's existing `AttachmentFormat.size`. It does not
walk the disk, does not use a new API, ignores masked/hidden/trashed records and does **not**
change while the user filters or searches (it is the vault-level summary, not the filtered view).

## Accessibility / layout

48 dp targets for back, sort, overflow, chips, checkboxes, bulk actions and rows
(`NoteZeroSpacing.minTarget`); content descriptions from resources for every icon-only action;
row metadata/type/lock state are text, not colour-only. The top bar is single-line with an
ellipsised title, the filter chips and bulk action row scroll horizontally with the system
scroll state (no fixed widths), and rows ellipsise title/folder/metadata so 1.15×+ font scales
and narrow widths do not clip actions. All colours come from `MaterialTheme` /
`LocalNoteZeroColors`; no colour literals, no new tokens.

## Tests

Command: `./gradlew :app:testDebugUnitTest --tests "com.cipherwell.notezero.ui.workspace.*"`

* `WorkspaceQueryTest` — **16** plain-JVM tests: fail-closed visibility (trashed/masked/hidden
  folder + descendants, verified reveal), folder-name leak prevention, type filters, search over
  title/body/tags/file name/author, masked-content non-match, snippet creation/edge cases,
  default + name sorting, filter+search composition, empty vs no-match states, storage totals
  (ignoring trashed/masked/hidden, filter-independent), selection toggle/select-all/visible
  retention, folder flattening (depth, hidden exclusion, counts, orphans/cycles), kind mapping.
* `WorkspaceScreenTest` — **25** Robolectric tests (`@Config(sdk=[34])`, no display qualifiers):
  rows/type labels/folder fallback/title bar; every type filter; masked+hidden items absent from
  rows/search/summary; verified locked row lock glyph; search filtering + snippet + clear;
  file-name search + no-matches; updated/name sort; storage summary values and filter
  independence; empty state; long-press selection + select-all + clear; overflow-menu select-all; bulk move (dialog,
  repository, clear); bulk lock (repository, rows re-masked); bulk unlock (verified locked);
  trash confirmation cancel/confirm; repository `false` and thrown-exception failures; note/code
  open callbacks; verified locked note opens through re-auth (no dialog); PDF/sketch hosted
  routing via injected hosts + close back to list; real EPUB reader open/back; vault lock closes
  a hosted screen and clears a selection.

**Result: 41 workspace tests, 41 passed, 0 failed/skipped** (16 + 25). The full module main source
compiles (`:app:compileDebugKotlin`); no existing file was modified.

## Strings

Every `workspace_*` string added by the Lead is rendered or used as its contract test tag, with
one exception: `workspace_search_snippet_masked` ("Locked content") is unreachable by contract —
masked content is excluded from the workspace entirely, so no masked-content snippet can ever be
rendered (kept in `res/` untouched). `workspace_sort_menu` is consumed as the sort-menu test tag
rather than visible text. `cd_workspace` belongs to the Lead's entry icon. No string was missing
for the implemented surface; one optional control was dropped rather than exceed `res/` (see
limitations).

## Limitations / device-QA watch list

* **Long-press discoverability.** Selection is entered by long-press (and the top-bar overflow
  "Select all"). A per-row overflow "Select" item was not added because no "Select" label string
  exists outside `cd_workspace_select`/`cd_workspace_select_all`; TalkBack still exposes the
  long-click action. Consider a follow-up string if product wants a visible select mode.
* **Locked+unverified records are invisible here** (fail-closed exclusion per contract). There is
  no re-auth entry point from the workspace itself; users re-auth from Notes/Library first. This is
  intentional, but differs from the Notes list's masked rows — worth a device walkthrough.
* **Hosted screens swap memory.** PDF page bitmaps, EPUB chapters and the sketch canvas are the
  existing screens; opening them from the workspace is the same path as the Library. Watch
  memory/back-stack behaviour when bouncing list → PDF → back → BOOK on a low-RAM device, and
  confirm the vault auto-lock closes the hosted screen (covered in tests via status flow).
* **Sort reorder keeps the anchored item visible** (LazyColumn key retention). Verify the list
  feels right on device when switching Last updated ↔ Name mid-scroll.
* **Search is metadata-only.** No PDF/EPUB body text search (needs parsing/text engines — out of
  scope); file-name matching uses attachment display names.
* **Storage summary is ref metadata.** It sums decrypted `AttachmentRef.sizeBytes`; it is not a
  disk-usage gauge (encryption overhead/keys/Room not included).
* **Bulk unlocking** shows the re-auth prompt once for the first locked selected id; already
  verified ids short-circuit. If a future surface ever selects unverified locked ids, the
  follow-up would need per-id verification.
* **RTL / very large display fonts:** row ellipsis and horizontal scrolling were exercised in
  Robolectric at the default width only; verify 1.3×–2× font scale, 320 dp width and RTL on a
  device (top bar: back/title/sort/overflow, bulk action row).
* **Test seam:** the hosted PDF/sketch routing is asserted with injected hosts because the
  platform `PdfRenderer`/canvas are not Robolectric-capable; the real screens themselves are
  covered by their own (device/PDF) suites. The EPUB route is asserted end-to-end with a real
  in-memory book.
