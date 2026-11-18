# NoteZero — Phase-12 (increment 13) contract: universal file workspace / IDE layer

Frozen for increment 13. UI/tests address these names only.

## Scope

One encrypted **Workspace** ("Files") across every `ItemKind`: notes, code, PDFs, EPUB books and
standalone sketches. Read/filter/search + multi-select bulk operations (move to folder, lock /
unlock, move to trash) + a storage summary. It does not replace the Notes home, the Library
document shelf, the code workspace or the readers; it is the file-manager/IDE view over the same
encrypted index.

Out of scope this increment: full-text search inside EPUB/PDF *content* (needs parsing/text
engines); trash management view (Notes Trash remains the surface); multi-select export; real
filesystem paths; cloud/sync.

## Data & rules (Lead — no new data model)

- No payload or schema change. Uses the existing repository APIs: `items`, `folders`,
  `verifiedLockedIds`, `moveItem`, `setItemLocked`, `trashItem`, `createFolder?` (not required).
- Visibility is fail-closed exactly like the code workspace/Library:
  `LockVisibility.isItemMasked(item, verifiedLockedIds)` items are excluded entirely; items in
  `LockVisibility.hiddenFolderIds(folders, verifiedLockedIds)` folders are excluded; trashed items
  are excluded. Locked **content** must never be shown: no title/body/author snippet from a masked
  item can reach the UI/search.
- Bulk actions act only on currently selected, visible item ids; if the vault locks mid-flight,
  repository calls fail/default false and the UI shows the non-secret `workspace_bulk_failed` and
  clears selection/refreshes.
- Storage summary: count items + attachments and sum `AttachmentRef.sizeBytes` from the visible
  decrypted index (no disk walk, no new API).

## Workspace screen (ui-agent; owns `ui/workspace/**`, tests, `docs/WORKSPACE.md`)

```kotlin
@Composable fun WorkspaceScreen(
    repository: NoteRepository,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,   // NOTE / CODE_FILE: open the editor hosted by the notes shell
    modifier: Modifier = Modifier,
)
```
- Root tag `workspace_screen`; top bar tag `workspace_back`; title `workspace_title`.
- Search field tag `workspace_search` (placeholder `workspace_search_placeholder`, clear
  `cd_workspace_clear_search`); searches visible items by title/body/author/tag/filename
  (case-insensitive substring; body matches produce a short snippet). Empty/search-empty states
  reuse `workspace_empty_title`/`workspace_empty_message` or `workspace_no_matches`.
- Type filter chips: `workspace_filter_all`, `workspace_filter_notes`, `workspace_filter_code`,
  `workspace_filter_pdfs`, `workspace_filter_books`, `workspace_filter_drawings`.
- Sort control (`workspace_sort`, menu `workspace_sort_menu`, options `workspace_sort_updated`,
  `workspace_sort_name`); default last-updated desc.
- Rows tag `workspace_item`; each shows a type badge/label (`workspace_type_*`), title, folder name
  (`workspace_no_folder` fallback), updated/type line, attachment/child hint, lock glyph for
  verified-locked items; row tap opens:
  * NOTE/CODE_FILE → `onOpenNote(id)`
  * PDF_FILE → existing `PdfViewerScreen` hosted inside the Workspace screen
  * BOOK_FILE → existing `BookReaderScreen` hosted inside
  * SKETCH_FILE → existing `SketchStudioScreen` hosted inside
  Before opening a verified-locked item, re-acquire verification through the standard
  `rememberLockReauthController`/`ReauthDialog` path used by the Notes list (do not bypass locks).
- Multi-select: long-press row or a select mode via row overflow; checkbox + select-all
  (`cd_workspace_select_all`); top bar shows `workspace_selected_count` and actions
  `workspace_move`, `workspace_lock`/`workspace_unlock`, `workspace_trash`,
  `workspace_clear_selection`. Move opens the existing `MoveToFolderDialog(folders: List<FolderRowModel>,
  onSelect: (String?) -> Unit)` (build `FolderRowModel`s from `repository.folders` with depth);
  trash shows a confirmation dialog (`workspace_trash_confirm_title`/`_message`/`_action`,
  cancel `workspace_cancel`); failures show `workspace_bulk_failed`. Trash/lock/move apply to all
  selected visible ids and clear selection.
- Header/footer storage summary tag `workspace_storage_summary` using
  `workspace_storage_summary` (items · attachments · human size; reuse the existing attachment size
  formatter from `ui/attachments`).
- 48dp targets, content descriptions, large-font safe, no colour literals outside existing tokens,
  no content logging, no new dependencies/permissions, strings from resources.

## Lead integration (outside agent scope)

- AppRoot route `workspace` + state; Notes top-bar workspace icon (`cd_workspace` /
  `workspace_button`) opens it.
- `NotesListScreen` gains `requestedItemId`/`onRequestedItemConsumed` deep-link: NOTE opens the
  editor, CODE_FILE the code editor; AppRoot feeds it from the Workspace `onOpenNote` callback and
  routes back to Notes.

## Tests (agent)
- Pure helper tests for search/filter/sort/summary/selection (JVM, no Compose).
- Robolectric with `FakeNoteRepository` seeded across all kinds + folders + a locked item +
  a hidden-folder item: rows/filters/search/sort/summary; masked/hidden items absent; multi-select
  + select-all; bulk move/lock/trash call repository and clear selection; trash confirmation;
  storage summary; open callbacks for note/code; PDF/book/sketch open the hosted screens (fake
  renderers/repos as needed).
- Keep existing suites green.
