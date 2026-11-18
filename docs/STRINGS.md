# NoteZero UI strings

All user-visible Compose copy lives in `app/src/main/res/values/strings.xml` (625 strings);
count strings live in `app/src/main/res/values/plurals.xml` (7 plurals). Composables read them through
`stringResource(...)` / `pluralStringResource(...)`. There is no one-off string
constant for UI copy in a screen as of the extraction pass (exceptions below).

Create-sheet option labels, the PDF viewer copy, and the Library/EPUB-reader copy
(`library_*`, `book_*`, `pdf_*` reading tools, and the PDF annotation palette/dialog/export copy (`pdf_tool_*`,
`pdf_annotate_*`, `pdf_note_*`, `pdf_export_*`, and the drawing-studio copy (`sketch_*`,
`library_new_sketch*`, the Workspace copy (`workspace_*`), and the additional-format/comic copy
(`book_format_*`, `book_untitled`, `comic_*`, and the code IDE copy (`code_quick_open*`,
`code_template_*`, `code_tab*`, `cd_code_*`)) are resources like every other screen string.
The code-format chips are **technical labels** (`.py`, `.html`, …) derived from
`code/CodeFormats`, not localisable copy — the same convention as language names in the code
editor status bar.

Visible English is byte-identical to the pre-extraction literals: the existing
Robolectric Compose tests assert those literals, so keeping values identical is part
of the contract, not a nicety.

## Naming convention

```
<screen>_<purpose>          e.g. unlock_error_lockout, code_delete_file_title
common_<purpose>            cross-screen wording (Cancel, Preview, Saved, …)
cd_<purpose>                accessibility label (contentDescription, onClickLabel,
                            stateDescription), e.g. cd_back_to_notes
```

| Prefix | Owner surface |
|---|---|
| `setup_` | first-run vault creation (password, phrase, confirmation) |
| `unlock_` | locked-vault screen and biometric unlock copy |
| `recovery_` | recovery-phrase reset flow |
| `settings_` | settings screen (vault info, security, appearance, about) |
| `notes_` | notes list, folders, search, trash, note/folder menus |
| `editor_` | Markdown note editor (except shared `common_*` controls) |
| `lock_` | locked note/folder re-auth, gate and settings section |
| `attach_` | attachment strip, viewer and import errors owned by UI state |
| `code_` | code workspace, tree rows, search, editor and dialogs |
| `backup_` | "Data & backup" settings section |
| `common_` | wording reused by 2+ screens or shared components |
| `cd_` | accessibility labels, always this prefix regardless of screen |

Prefer a `common_*` key over duplicating an identical value in two screens. Prefer a
screen prefix when the same English word has different translator context (e.g.
"Export" in attachments vs code is intentionally one `code_export` + one
`attach_*` per surface where the surrounding sentence differs).

### Placeholders

Use positional format arguments only: `%1$s`, `%2$d`, etc. Never `%s` / `%d`
(translators must be able to reorder). Examples:

- `unlock_error_lockout`: `Too many attempts. Try again in %1$ds.`
- `editor_count`: `%1$d words · %2$d chars`
- `backup_status_restored`: `Restored %1$d notes and %2$d folders · %3$d duplicates ignored · %4$d conflicts skipped.`

Formatting-sensitive strings keep their exact punctuation/spacing, including the
middle dot `·`, em dash `—`, ellipsis `…`, and the leading whitespace in
`notes_folder_path_suffix` (Android preserves it because the XML value is quoted).

## Plurals

`res/values/plurals.xml` holds every count that is rendered as a sentence fragment:

| Key | one | other |
|---|---|---|
| `notes_note_count` | `%1$d note` | `%1$d notes` |
| `notes_folder_count` | `%1$d folder` | `%1$d folders` |
| `attach_count` | `%1$d attachment` | `%1$d attachments` |
| `code_file_count` | `%1$d file` | `%1$d files` |
| `code_folder_count` | `%1$d folder` | `%1$d folders` |
| `code_match_count` | `%1$d match` | `%1$d matches` |

Usage: `pluralStringResource(R.plurals.code_file_count, count, count)`.

Deliberate non-plural count copy:

- `code_locked_hidden` and `lock_settings_count` use the source wording
  `"N locked item(s)"`. The literal `(s)` is intentionally quantity-neutral today and
  tests assert the exact line; change it only together with those tests.
- `backup_status_restored` uses the pre-existing `"1 folders"` grammar verbatim
  because a UX copy change is out of scope for this extraction and the test asserts
  it. If copy is fixed later, split it into plurals in the same change.
- `editor_count` is `%1$d words · %2$d chars` even for 1, matching the previous
  rendered English. Do not "fix" it in a translation-only change.
- `settings_autolock_minutes` (`%1$d min`) and `settings_autolock_hour` (`1 hour`)
  are unit labels, not sentence counts.

## Deliberately not extracted (with reasons)

These strings are user-visible but are produced outside a Composable or are asserted
directly by pure-JVM tests, so moving them behind resources would require a `Context`
in a ViewModel/pure helper (and in several cases changes the tested public API).

| Location | Copy | Why it stays inline |
|---|---|---|
| `ui/screens/setup/PasswordPolicy.kt` `label()` | "At least 12 characters", "A lowercase letter", … | Pure Kotlin policy object (no `Context`); used by the checklist and by setup/recovery error sentences; `PasswordPolicyTest` calls `label()` directly. The error *templates* around it are resources with `%1$s`. |
| `ui/notes/NoteFormatting.kt` | "Just now", "5 min ago", "Yesterday", "3 days ago", "New Note" | Pure formatting helpers, covered by JVM tests. Relative time needs a clock/locale strategy of its own; `displayTitle` fallback is data-layer-ish. Date patterns (`d MMM`, `d MMM yyyy`) are `SimpleDateFormat` specs, not user copy. |
| `ui/attachments/AttachmentFormat.kt` | size units `B/KB/MB/GB`, default `"Attachment"`, export fallback `"attachment"` | Pure JVM formatting helper (`AttachmentFormatTest` asserts exact values). `AndroidAttachmentSource` resolves the picker fallback names ("Photo"/"Attachment") through resources instead. |
| `ui/attachments/AttachmentUiState.kt` `Error.message` | quota/integrity/IO error sentences | Immutable UI-state object created by `AttachmentController` outside composition; no `Context`. Rendering them through a resource map would either move `Context` into the controller or make the error state a resource id (API/test churn). |
| `ui/locks/LockReauthController.kt` | `"Enter your master password."`, `"Incorrect master password."`, cooldown, biometric-cancelled | Controller constants asserted by `LockReauthControllerTest`; controller has no `Context`. |
| `ui/locks/LockVisibility.kt` | `MASKED_NOTE_TITLE` / `MASKED_FOLDER_NAME` | Pure masking helper asserted by `LockVisibilityTest`; it must stay Android-free so it is unit-testable. The gate/dialog titles are resources with the same English. |
| `ui/VaultViewModel.kt` `SettingsUiState.messageFor()` | settings snackbar mapping (14 enum values) | ViewModel-level enum→message mapping; no `Context`, and tests assert the enum not the text. The two inline settings-field errors are resources. |
| `ui/code/CodeEditorViewModel.kt` `matchLabel` | "No matches", "1/3", "N matches" | ViewModel state, asserted by `CodeEditorViewModelTest`. |
| `ui/code/CodeEditorScreen.kt` `saveStateLabel()` | "Loading…", "Saved", "File too large", … | Internal pure helper asserted directly by `CodeEditorViewModelTest` (not reachable via `stringResource` without changing that contract). |
| `ui/code/CodeTree.kt` `validateName()` | name validation errors | Pure tree helper; returned as dialog validation text. |
| `ui/code/CodeTreeRows.kt` `languageLabel()` | Kotlin, Java, JavaScript, … | Non-translatable language names. |
| `ui/notes/NotesModels.kt` | folder path separator `" / "` | Pure path formatting, not sentence copy. |
| `ui/screens/settings/SettingsScreen.kt` `formatDate()` / `"—"` / `"AES-256-GCM"` | vault-created date, empty placeholder, cipher name | `DateFormat` already localises the date; `—` is a symbol; `AES-256-GCM` is a non-translatable algorithm identifier. |
| `ui/backup/BackupFlowController.kt`, `ui/code/CodeExport.kt` | `.nzbk` file name, MIME types, fallback export file name | File-system/SAF metadata, not in-app copy. |
| glyphs / symbols | `</>`, `✓`, `○`, `›`, `•`, `Aa`, `A-`, `A+`, `IMG`, `FILE`, `  $count`, `  line:column`, `sp` | Non-translatable glyphs, number/format specifiers or generated badges. They are intentionally inline. |

If one of these needs localisation later, prefer passing a resolved `String` (or a
string-resource id) down from the Composable rather than adding `Context` to a pure
helper/ViewModel.

## Adding a new string

1. Add `<string name="screen_purpose">…</string>` to `strings.xml` (or a `<plurals>`
   entry to `plurals.xml`). Escape `'` as `\'` and `&` as `&amp;`.
2. Reference it with `stringResource(R.string.…)` inside the Composable. In a
   `semantics { }`, `clickable(onClickLabel = …)`, SAF-result callback or
   `validate = { }` lambda, resolve it in composition first:
   `val label = stringResource(...)`.
3. Do not change rendered English for existing keys unless UI copy is in scope;
   Compose tests assert the literals.
4. Keep `testTag`/semantics identifiers and placeholder positions untouched.

## Adding a locale (`values-xx`)

1. Copy `values/strings.xml` to `values-<language>/strings.xml` (e.g.
   `values-fr/strings.xml`) and translate only element text; never rename `name`
   attributes or renumber placeholders. Keep `translatable="false"` entries
   (`code_untitled_placeholder`) out of the translated file.
2. Copy the `<plurals>` entries used by that locale from `plurals.xml` to
   `values-<language>/plurals.xml` and supply the quantity forms that language needs
   (e.g. `one`, `few`, `many`, `other` for Polish/Russian/Arabic). Android selects the
   form; English keeps `one`/`other`.
3. Do not translate algorithm names (`AES-256-GCM`, `PBKDF2-HMAC-SHA256`), language
   names (`Kotlin`, `Java`, …), MIME types or file extensions.
4. Check placeholder parity with `lint`/`aapt2` and re-run the unit suite; Robolectric
   resolves the default locale, so an accidental value change still fails the English
   assertions.

## Verification (this extraction)

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:compileDebugKotlin --console=plain
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --console=plain
# 59 classes / 740 tests / 0 failures / 0 errors / 0 skipped
```

`res/values/strings.xml` has 336 `<string>` entries (11 were seeded before this
pass), 33 `cd_*` accessibility labels and one `translatable="false"` value;
`res/values/plurals.xml` has 6 `<plurals>` entries. `new_note`, `search_notes` and
`recovery_title` are pre-existing seeded keys kept for compatibility; they are not
referenced by the current UI.

### Drawing canvas (increment 6)

`drawing_save`, `drawing_clear`, `drawing_canvas_hint`, `drawing_save_failed`, `attach_drawing`
plus `cd_drawing_canvas`, `cd_drawing_ink_{black,grey,red,orange,green,blue,purple}` and
`cd_drawing_width_{thin,medium,bold}` live in `res/values/strings.xml` with the `drawing_`
prefix. Stroke colours/tools are symbols + resource labels only; the canvas itself is
rendered content, never persisted as plaintext.
