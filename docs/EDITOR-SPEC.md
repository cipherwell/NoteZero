# NoteZero — code editor engine spec (Phase 3, package `com.cipherwell.notezero.code`)

Owner: `code-engine`. Frozen by `docs/CONTRACTS-PHASE3.md` §B.
Pure Kotlin/JVM: **no `android.*` / Compose imports, no network, no new dependencies, no regex**.
The UI layer (`ui/code/**`, owner `code-ui`) consumes this API; colours live there
(`CodeSyntaxPalette`), the engine only emits token types.

## 1. Files and public API

| File | Contents |
|---|---|
| `Language.kt` | `CodeLanguage` enum, `Languages` object |
| `SyntaxToken.kt` | `TokenType`, `SyntaxToken`, `CodeLexer`, `MAX_TOKENS` |
| `CodeLexers.kt` | `CodeLexers.forLanguage(language)` registry (one shared instance per language) |
| `BaseLexer.kt`, `CLikeLexer.kt` | internal scanner infrastructure |
| `KotlinLexer.kt`, `JavaLexer.kt`, `JavaScriptLexer.kt`, `TypeScriptLexer.kt`, `PythonLexer.kt`, `JsonLexer.kt`, `XmlLexer.kt`, `ShellLexer.kt`, `MarkdownLexer.kt` | per-language scanners |
| `LineIndex.kt` | immutable line-start index |
| `CodeEditing.kt` | `TextRange` + editing/search helpers |

```kotlin
enum class CodeLanguage { KOTLIN, JAVA, JAVASCRIPT, TYPESCRIPT, PYTHON, JSON, XML, MARKDOWN, SHELL, PLAIN }

object Languages {
    fun fromFileName(fileName: String): CodeLanguage
    val editorLanguages: List<CodeLanguage>   // all except PLAIN, enum order
    fun defaultFileName(language: CodeLanguage): String
}

enum class TokenType { KEYWORD, TYPE, STRING, CHAR, NUMBER, COMMENT, ANNOTATION, OPERATOR,
                       PUNCTUATION, IDENTIFIER, PROPERTY, TAG, ATTRIBUTE, HEADING, LINK, CODE_SPAN, ERROR }

data class SyntaxToken(val start: Int, val end: Int, val type: TokenType)  // [start, end), UTF-16 indices
const val MAX_TOKENS: Int = 200_000

interface CodeLexer { val language: CodeLanguage; fun tokenize(text: String): List<SyntaxToken> }
```

`SyntaxToken` requires `start >= 0` and `end > start`. Every list returned by a lexer is
non-overlapping and ascending, all offsets are inside the input, and `size <= MAX_TOKENS`.

## 2. Languages and file-name mapping

Extension matching is case-insensitive and uses the **last** dot of the name/path. Unknown,
missing or trailing-dot extensions, dotfiles and extensionless files map to `PLAIN`.

| Language | Extensions | Default file name | Lexer |
|---|---|---|---|
| `KOTLIN` | `kt`, `kts` | `untitled.kt` | `KotlinLexer` |
| `JAVA` | `java` | `untitled.java` | `JavaLexer` |
| `JAVASCRIPT` | `js`, `mjs`, `cjs`, `jsx` | `untitled.js` | `JavaScriptLexer` |
| `TYPESCRIPT` | `ts`, `mts`, `cts`, `tsx` | `untitled.ts` | `TypeScriptLexer` |
| `PYTHON` | `py`, `pyw`, `pyi` | `untitled.py` | `PythonLexer` |
| `JSON` | `json`, `jsonc`, `json5` | `untitled.json` | `JsonLexer` |
| `XML` | `xml`, `xsl`, `xslt`, `svg`, `xhtml`, `html`, `htm`, `plist`, `rss`, `atom` | `untitled.xml` | `XmlLexer` |
| `MARKDOWN` | `md`, `markdown`, `mdown`, `mkd`, `mdx` | `untitled.md` | `MarkdownLexer` |
| `SHELL` | `sh`, `bash`, `zsh`, `ksh`, `fish`, `command`, `bats` | `untitled.sh` | `ShellLexer` |
| `PLAIN` | everything else | `untitled.txt` | none (empty token list) |

`editorLanguages` is the frozen "new file" picker list: all languages except `PLAIN` in enum
declaration order. For every non-`PLAIN` language,
`Languages.fromFileName(Languages.defaultFileName(l)) == l` (unit-tested).

HTML is lexed by the XML lexer (tags/attributes/comments); it is a pragmatic approximation —
HTML void elements and unquoted attributes are tolerated but not validated.

## 3. Token mapping per language

Whitespace is **never** tokenized. A renderer must paint any gap between tokens with the base
text colour, in ascending token order. Tokens are sparse for markup/prose (text content has no
token) and dense for code (identifiers are `IDENTIFIER` tokens).

| Language | KEYWORD / TYPE | STRING / CHAR | COMMENT | Other notable tokens |
|---|---|---|---|---|
| Kotlin | keywords; built-in types (`Int`, `String`, `List`, …) as `TYPE` | `"…"` with escapes and `${'$'}{…}`/`${'$'}name` templates (one span including interpolation), `"""raw"""`, `'c'` as `CHAR` | `//`, **nested** `/* … */` | `@Annotation` incl. use-site targets (`@get:X`) and qualified names; `` `backtick ident` `` as `IDENTIFIER` |
| Java | keywords; primitives + common `java.lang/util` types as `TYPE` | `"…"`, `'c'` as `CHAR`, `"""text blocks"""` | `//`, `/* … */` (not nested) | `@Annotation`; `true/false/null` as `KEYWORD` |
| JavaScript | keywords; global constructors (`Promise`, `Map`, …) as `TYPE` | `'…'`, `"…"`, `` `…${'$'}{expr}…` `` templates (one span, nested templates handled) | `//`, `/* … */` | regex literals (`/re/gi`) as `STRING` via a conservative context heuristic; `true/false/null/undefined/NaN/Infinity` as `KEYWORD` |
| TypeScript | JS keywords + type keywords (`interface`, `type`, `readonly`, `keyof`, …); primitive type names (`string`, `number`, …) as `TYPE` | same as JS | same as JS | same as JS |
| Python | keywords, soft keywords (`match`, `case`); builtin types as `TYPE`; `False/None/True` as `KEYWORD` | `'…'`, `"…"`, `'''…'''`, `"""…"""` (docstrings are plain `STRING`), prefixed (`rb`, `f`, …), f-string `{expr}`/format spec nesting inside one span | `#` to end of line | `@decorator` (dotted) as `ANNOTATION`; numbers incl. `0x`, `0o`, `0b`, `_`, `j` |
| JSON | `true/false/null` | strings as `STRING` | — | object keys as `PROPERTY`; numbers; structural `{}[],:` as `PUNCTUATION`; invalid characters as `ERROR` |
| XML | — | quoted attribute values as `STRING`; CDATA sections as `STRING` | `<!-- … -->`, `<!DOCTYPE …>` (incl. `[ … ]` internal subset) as `COMMENT` | element names `TAG` (closing tags too), attribute names `ATTRIBUTE`, `<`/`>`/`/>`/`<?`/`?>` as `PUNCTUATION`; PI target as `TAG`; text content uncoloured; a bare/unparsable `<` as `ERROR` |
| Shell | reserved words + common builtins as `KEYWORD` | single-quoted literal `STRING`; double-quoted strings split into literal `STRING` pieces and `PROPERTY` expansions | `#` at a token boundary to end of line (`foo#bar` stays one word) | `$VAR`, `${'$'}{…}`, `$1`, `$?`, `$(…)`, backticks as `PROPERTY`; `VAR=value` name as `PROPERTY` + `=` as `OPERATOR`; heredoc body `STRING`, terminator `IDENTIFIER`; `\|&;<>!` as `OPERATOR` |
| Markdown | — | — | — | ATX headings (`#`…`######`) as `HEADING` (whole line); fenced blocks (~~~ / ```) as **one** `CODE_SPAN` from opening to closing fence; inline code as `CODE_SPAN`; `[text](url)`, `[text][ref]`, `![alt](url)`, `<https://…>` as `LINK`. Plain prose/bold/italic/lists uncoloured. |
| PLAIN | — | — | — | token list is always empty |

`ERROR` is only emitted where a lexer can positively identify invalid input: JSON characters
that cannot start any token (including a lone `-`) and XML's unparsable `<`.

### Unterminated / malformed input policy

Lexers **never throw** and never scan past the end of the input:

- single-line strings stop at the line break (the rest of the line is lexed normally);
- block comments / triple-quoted strings / XML comments+CDATA / fenced Markdown blocks /
  heredocs run to end of input and are emitted as one token of their construct type;
- unbalanced brackets, stray `*/`, unterminated `${'$'}{…}` etc. are simply bounded token spans;
- a defensive `try/catch (Exception)` in `BaseLexer.tokenize` truncates the token list rather
  than crashing the editor if a scanner bug is ever hit (fatal JVM errors still propagate).

### Bounded work

Every scanner is a single forward pass over the `String` (UTF-16 chars). No regexes, no
per-character lists, no recursion over arbitrary input. Scanning stops as soon as `MAX_TOKENS`
(200 000) tokens have been produced, so hostile input cannot inflate work: `",".repeat(250_000)`
returns exactly 200 000 tokens and stops. A 1 MB single identifier/word is one token.

### Threading

`CodeLexers.forLanguage(...)` returns a shared instance per language. `tokenize` is
`@Synchronized`: concurrent callers block briefly rather than corrupt scan state. Lexing 200 KB
is a few ms, so highlighting off the main thread is still recommended but not required for
correctness. Token lists are immutable copies; callers may cache/compare them.

## 4. LineIndex

`LineIndex.of(text)` is O(n) and stores only line-start offsets (IntArray); no copies of the
text. Lines are separated by `\n`; a preceding `\r` is part of the terminator (CRLF = one
break). A lone `\r` is an ordinary character. A text ending in `\n` has an extra empty final
line, so a cursor can sit on it (`"a\n"` has 2 lines).

```kotlin
val lineCount: Int
val textLength: Int
fun lineOf(offset: Int): Int              // clamps offset into [0, textLength]
fun startOfLine(line: Int): Int           // clamps line into [0, lineCount-1]
fun endOfLine(text: String, line: Int): Int  // exclusive content end; CRLF points at '\r'
```

Line 0 is the first line; all APIs are 0-based. `endOfLine` takes the text again so the index
stays allocation-free; it tolerates a text argument of a different length (clamps).

## 5. Editing helpers (`CodeEditing`, all pure/immutable)

Indentation units: `"\t"` when `useTabs`, otherwise `tabWidth` spaces clamped to 1..16.

### `autoIndent(text, cursor, language, useTabs, tabWidth): String`

Returns **the indentation string to insert after a newline** (no newline itself). The caller
inserts `"\n"`, then appends the result at the cursor. Contract-relevant rules:

- the cursor is the offset where the line break was inserted (it may equal the offset of the
  inserted `\n`, as `ui/code` uses it) — the engine evaluates the line **being split**;
- the new line starts at the current line's leading whitespace;
- **blank-line no-indent**: if the split line has no non-whitespace content before the cursor,
  the result is `""` (repeated Enter does not accumulate indentation);
- one extra unit after `{`, `(`, `[` in every language;
- one extra unit after `:` in **Python** only;
- one extra unit after an opening XML tag (`<div>`, `<ns:tag attr="v">`); closing tags,
  self-closing tags, comments and processing instructions do not trigger;
- trailing spaces/tabs before the cursor are ignored when detecting the trigger;
- out-of-range cursors are clamped.

### `indentSelection(text, selection, outdent, useTabs, tabWidth): Pair<String, TextRange>`

Indents/outdents every line the selection touches and maps the selection onto the new text.
Standard editor rule: when the selection ends exactly at a line start, that line is not touched.
Blank lines are indented like any other line. Outdent removes one tab, or up to one tab-width of
leading spaces, or whatever partial indentation exists. Reversed/out-of-range selections are
normalised and clamped. CRLF line endings are preserved byte-for-byte. Lines without leading
whitespace are unchanged by outdent. The returned selection is normalised (not reversed).

### `matchingBracket(text, offset): Int?`

- The bracket at the cursor wins; otherwise the bracket immediately before it (so both
  `offset` at `(` and `offset` after `(` find the pair).
- Only `()`, `[]`, `{}` are matched (angle brackets would be ambiguous with comparisons/XML).
- Nesting is counted per bracket-kind; brackets inside strings (`"`, `'`, backtick templates),
  `//` line comments, `#` comments at a word boundary, and `/* … */` block comments are skipped.
  Returns `null` if the anchor itself is inside a string/comment, if no bracket is adjacent, or
  if no partner exists.
- Offsets are clamped. Heuristic notes: `#` is a comment start only at a word boundary (JS
  `#private` fields unaffected); block comments are not modelled as nestable (Kotlin is the only
  supported language that nests them) — best effort by design.

### `findMatches(text, query, caseSensitive, limit = 10_000): List<TextRange>`

Literal search, ascending, **non-overlapping** (advances by the match length), stops at `limit`.
Empty query, `limit <= 0`, empty text or a query longer than the text return an empty list.
`caseSensitive = false` uses `String.regionMatches(ignoreCase = true)` (no locale-dependent
lowercasing of the whole text).

### `replaceAll(text, query, replacement, caseSensitive): String`

Literal replacement — the query is not a regex and replacement text is inserted verbatim
(`$1`, `\1` have no special meaning; no regex injection). Empty query returns the text unchanged.

### `offsetForLine(text, line): Int`

`LineIndex.of(text).startOfLine(line)` — clamps out-of-range lines to the first/last line.

`TextRange(start, end)` is half-open; helpers `length`, `isEmpty()`, `normalized()`,
`clampedTo(textLength)`, `containsInside(offset)`. It does not throw on reversed ranges.

## 6. Limits and size guards (UI contract)

- Engine hard cap: 200 000 tokens per call (`MAX_TOKENS`), enforced by every lexer.
- `ui/code` (not this package) owns the file-size policy from the contract: > 2 MiB refuses to
  open; > 256 KiB renders as plain monospace with syntax colours disabled. The engine would
  handle those sizes within the token cap too, but the UI guard protects typing latency and
  memory on device.
- `PLAIN`/disabled highlighting must skip `tokenize` entirely (it returns an empty list anyway).

## 7. Performance evidence

Contract target: tokenize 200 KB of source in **< 250 ms**; the test asserts a generous
**< 2000 ms** bound for CI stability and prints the measured wall time.

Measured through Gradle on this machine (JVM/desktop, warmup pass excluded):

```
[perf] Kotlin:     204911 chars, 40369 tokens, 6.42 ms (target < 250 ms, asserted < 2000 ms)
[perf] JavaScript: 204886 chars, 40535 tokens, 7.85 ms (target < 250 ms, asserted < 2000 ms)
[perf] XML:        204918 chars, 24682 tokens, 3.21 ms (target < 250 ms, asserted < 2000 ms)
```

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests "com.cipherwell.notezero.code.*" --console=plain`
(measurements captured from the JUnit XML `system-out`, since Gradle does not echo test stdout by
default). JVM warmup dominates first calls; UI callers should keep highlighting off the main
thread for large documents anyway.

## 8. Test evidence

`app/src/test/java/com/cipherwell/notezero/code/` — 139 pure-JVM JUnit tests, 0 failures:

| Class | Tests | Covers |
|---|---|---|
| `LanguageTest` | 5 | extension mapping, defaults, round-trip, registry |
| `LexersTest` | 66 | exact spans (Kotlin hello-world, JSON object), keywords/types/strings/chars/comments/annotations/numbers/regex/heredocs/CDATA/PI/DOCTYPE/fences/links, unterminated constructs, Unicode/CRLF, kitchen sink per language |
| `LexerFuzzTest` | 7 | 120 random strings × 10 languages, 150 random construct sequences × 10 languages, token-budget cap, 1 MB single token, 500 KB unterminated constructs, 200 K quote runs |
| `LexerPerformanceTest` | 3 | 200 KB Kotlin / JS / XML under the asserted bound |
| `LineIndexTest` | 8 | empty text, trailing newline, CRLF, clamping, mismatched text argument |
| `CodeEditingTest` | 37 | indentUnit/autoIndent (braces, Python `:`, XML tags, blank lines, tabs, CRLF), indent/outdent selection mapping incl. clamping bug, bracket matching (nesting, strings, comments, both cursor conventions), find/replace literal + case modes + limit, go-to-line clamp, TextRange helpers |
| `CodeSearchTest` | 13 | title/body hits, 1-based line/column, snippets, multi-line order, case toggle, limit (`0`/negative), trashed + non-code skipped, CRLF/trailing-newline, long-line windowing, tabs, hostile input never throwing |

No test is `assertTrue(true)`: every assertion checks a concrete token span/type, edited text,
selection mapping or measured number.

## 9. Known deviations / limitations

- **JS/TS regex literals** are detected with a conservative context heuristic (after an
  operator, `(`, `[`, `{`, `,`, `;`, `:` or keywords such as `return`/`typeof`). After `)` or an
  identifier a `/` is treated as division. Mis-detected regex literals never break offsets or
  throw — they only colour as operators/identifiers.
- **JSONC/JSON5** file names map to `JSON`; comments are then reported as `ERROR` tokens
  (documented, bounded) rather than comments.
- **Markdown** highlights ATX headings, fenced blocks, inline code and links only (no setext
  headings, bold/italic, lists, tables). Fenced blocks are a single multi-line `CODE_SPAN`.
- **XML** treats CDATA as `STRING` and DOCTYPE/`<!…>` declarations as `COMMENT`; text content is
  not tokenized; entity references are left as plain text.
- **Shell** supports best-effort heredocs (`<<`, `<<-`, quoted delimiters); two heredocs on the
  same command line are not queued (last delimiter wins). `$((…))` arithmetic is one `PROPERTY`
  span rather than being recursively highlighted.
- **matchingBracket** treats `/* … */` as non-nesting and does not match angle brackets.
- **autoIndent after Python `:`** indents for any line ending in `:` (including dict/slice
  colons); the UI may ignore the suggestion in that rare case.
- Theme colours are not part of this package: they live in `ui/code/CodeSyntaxPalette` and are
  derived from `MaterialTheme`, so no hex literals leak out of `ui/theme`.

## 10. Polish extensions (Phase 4, `docs/CONTRACTS-PHASE4.md` §C)

Owner: `code-polish`. Additive to the frozen Phase 3 engine: no existing signature changed and
no new dependency. All new pure logic lives in `code/CodeSearch.kt`; everything else is
`ui/code/**`.

### 10.1 Workspace search (`code/CodeSearch.kt`)

```kotlin
data class CodeSearchMatch(
    val itemId: String,
    val fileName: String,
    val line: Int,     // 1-based
    val column: Int,   // 1-based UTF-16 code units within the line, tabs = 1
    val snippet: String,
)

object CodeSearch {
    const val DEFAULT_LIMIT: Int = 200
    const val MAX_SNIPPET_CHARS: Int = 160
    fun search(items: List<Item>, query: String, caseSensitive: Boolean, limit: Int = DEFAULT_LIMIT): List<CodeSearchMatch>
}
```

- Searches **file names (titles) and bodies** of `ItemKind.CODE_FILE` items only; trashed
  items and notes are never searched. Matching is literal (never a regex/pattern) and reuses
  `CodeEditing.findMatches`, so case-insensitivity is `regionMatches(ignoreCase = true)`.
- A name hit is reported as line 1 with its column inside the name and the name as snippet; it
  appears before that same file's body hits. Body hits are ascending, non-overlapping.
- `line`/`column` feed the editor's existing 1-based `goToLine` unchanged. Line numbers come
  from `LineIndex`, so CRLF counts as one break, a trailing `\n` adds an empty last line, and
  the column excludes the terminator.
- Snippet = trimmed line content around the hit, `\r` replaced by spaces, never containing
  `\n`, at most `MAX_SNIPPET_CHARS` (+ one ellipsis on each cut edge). Lines longer than that
  are windowed so the hit always stays visible; a surrogate pair is never split.
- Bounded and side-effect free: empty query, `limit <= 0` or an empty item list returns empty;
  at most `limit` hits; one `LineIndex` per file and one literal scan per name/body (no regex,
  no I/O, no logging). **Never throws** — a defensive `catch (Exception)` returns the hits
  accumulated so far, so hostile restored content cannot take the workspace down.

`ui/code/CodeWorkspaceViewModel` owns only the non-secret search chrome
(`searchVisible`, `searchQuery`, `searchCaseSensitive`) and derives `searchResults` inside its
existing `combine`, so an item update (autosave, rename, unlock/lock) refreshes results with no
extra subscription. A blank query keeps the tree visible (`searchActive = false`); locking the
vault empties the results while the query string survives until the bar is closed.

### 10.2 Search UI (`CodeWorkspaceScreen`, `CodeSearchBar.kt`)

- Top-bar toggle `code_search_toggle`; the search surface shows `code_search_query` (mono,
  single line), `code_search_case` (Aa chip), a close icon and `code_search_status`
  ("N matches" / "No matches" / "Type to search"; never echoes the query).
- While a query is active the tree is replaced by `code_search_results` with one
  `code_search_result_row` per hit (file name, `line:column`, snippet). Tapping a row opens the
  file at that line. The back arrow closes the search bar first, then leaves the workspace.
- No `rememberSaveable` for search state: closing the bar discards query and results, so
  decrypted snippets never outlive the visible surface.

### 10.3 Open-at-line

`CodeEditorScreen(repository, itemId, onBack, initialLine: Int? = null, openToken: Int = 0,
exportTarget: …)`; `CodeEditorViewModel`/Factory take `initialLine` (1-based). The draft is
loaded with the caret on that line when the file opens; `openToken` bumped on every workspace
open re-applies a new target line even though the per-file ViewModel survives, via a
`LaunchedEffect` once the draft is no longer loading. `State.navigationSeq` is bumped by
`goToLine`/open-at-line and consumed once by `CodeEditorField`, which scrolls the caret line
into view (never on ordinary typing/scrolling). Out-of-range lines are ignored.

### 10.4 Copy / export (memory, clipboard or the user's own file only)

- `code_copy`: `CodeEditorViewModel.copySelectionOrAll()` returns the normalized selection,
  else the whole draft, else `""` (nothing loaded). The UI writes it through
  `ui/components/common/SensitiveClipboard`, which marks the clip with
  `ClipDescription.EXTRA_IS_SENSITIVE` (backported extra below API 33). No file is written and
  nothing is logged. The ViewModel itself has no Android clipboard imports.
- `code_export`: explicit gate first — `code_export_confirm` / `code_export_cancel` in the
  "Export an UNENCRYPTED copy?" dialog (`code_export_dialog`) — then SAF
  `ActivityResultContracts.CreateDocument("text/plain")` with the sanitized current file name
  (`sanitizeExportName`: path separators/control characters → `_`, blank → `code.txt`).
  The whole current draft is written UTF-8 to the user-chosen `content://` URI (`wt`, with a
  plain-write fallback); a cancelled picker writes nothing. The launch is bracketed with
  `NoteRepository.beginExternalActivity()`/`endExternalActivity()` (decision #13) so the picker
  does not lock the vault, and a missing SAF handler settles the bracket without exporting.
  This is the only plaintext-to-disk path in the code editor and it requires the confirmation.
- Both live in a dedicated second action row under the main toolbar (`EditorActionBar`) so the
  find/font/wrap/preview controls keep their positions and E2E taps.

### 10.5 Multi-line indent

- `code_indent_increase` / `code_indent_decrease` call
  `CodeEditorViewModel.indentSelection()` / `outdentSelection()`, which delegate to the frozen
  `CodeEditing.indentSelection(text, selection, outdent, useTabs = false, tabWidth = 4)` and
  apply the returned text **and mapped selection** through the normal edit path (undo snapshot,
  dirty flag, 600 ms autosave, find-match refresh). An empty selection indents the caret's line;
  an outdent that changes nothing is a no-op (no undo entry, no dirty flag, no save).
- `Tab` / `Shift+Tab` are intercepted in `CodeEditorField` with
  `Modifier.onPreviewKeyEvent` (KeyDown only) and forwarded to the same two actions, so Tab
  indents instead of moving focus. The auto-indent on Enter is untouched.
- Indentation is spaces (4) as before; the engine still supports tabs for future preferences.

### 10.6 Test evidence (this increment)

`app/src/test/java/com/cipherwell/notezero/code/` — `CodeSearchTest` 13 tests.
`app/src/test/java/com/cipherwell/notezero/ui/code/` — 100 Robolectric/JUnit tests, 0 failures:
`CodeEditorScreenTest` 15 (adds clipboard sensitive flag + selection copy, export
confirm/cancel gate, toolbar indent/outdent mutation, Tab indent, initial line),
`CodeWorkspaceScreenTest` 16 (search bar/results/case/name hits/no hits/back-closes),
`CodeEditorViewModelTest` 34 (adds initialLine, navigationSeq, indent/outdent mappings, copy
payload), `CodeWorkspaceViewModelTest` 17 (search state + results/lock/bounds),
plus `CodeTreeTest` 10, `CodeEditorPrefsTest` 5, `CodeExportTest` 3.

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests
"com.cipherwell.notezero.code.*" --tests "com.cipherwell.notezero.ui.code.*" --console=plain`
(no sleeps; ViewModel tests use `StandardTestDispatcher` virtual time, UI tests use the Compose
test clock only).

### 10.7 Known limitations

- Search runs on the flow-collection (main) dispatcher against the decrypted in-memory index;
  the `limit` (200) and the literal scan keep it well under a frame for the MVP corpus, but a
  megabyte-scale vault would warrant moving it to a background dispatcher.
- The search bar does not request focus automatically (avoids keyboard churn); the user taps
  the field.
- `Tab`/`Shift+Tab` require a hardware/IME key event; soft keyboards have no Tab key, so the
  toolbar action row is the touch path.
