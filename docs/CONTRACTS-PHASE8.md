# NoteZero — Phase-8 (increment 9) contracts: PDF export + quick-note widget

Frozen symbols/tags for this increment. Tests address these names only; renaming requires
updating tests and this file in the same commit.

## 1. Export note as PDF

| Contract | Value |
|---|---|
| Format enum | `NoteExportFormat.MARKDOWN`, `.HTML`, `.PDF` — PDF has `mimeType = "application/pdf"`, `extension = "pdf"` |
| Content handed to the target | PDF uses the Markdown rendering of the note (`NoteExport.toMarkdown(title, body)`), so the renderer receives the title as a level-1 heading |
| Renderer | `ui/pdf/NotePdfRenderer.render(markdown): ByteArray?` — A4 595×842 pt, in-memory `PdfDocument`, null on failure (incl. OOM) |
| Inline parser | `ui/pdf/PdfInlineMarkdown.parse(text): List<PdfInlineRun>` — pure Kotlin; bold/italic/code/strike/link label; never throws; ≤4000 runs, ≤4 nesting |
| SAF wiring | `rememberSafNoteExportTarget` adds a `CreateDocument("application/pdf")` launcher; renders off the main thread; re-checks `canWriteNoteExport` before writing; writes with `writeNoteExportBytes`; `beginExternalActivity`/`endExternalActivity` bracket unchanged |
| Dialog | format chooser tags `note_export_markdown`, `note_export_html`, **`note_export_pdf`**; warning/confirm/cancel tags unchanged (`note_export_warning`, `note_export_confirm`, `note_export_cancel`) |
| Bounds | input ≤1,000,000 chars; ≤200 content pages + one truncation-notice page; ≤8 table columns, ≤200 rows; ≤200,000 chars per code block; hostile/unclosed Markdown is literal text |
| Filesystem | no temp file/cache for export: bytes exist in memory and in the user-picked destination only |
| Tests | `PdfInlineMarkdownTest` (JVM), `NoteExportViewModelTest` PDF request, `NoteExportScreenTest` PDF option, `NotePdfRenderInstrumentedTest` (real `PdfRenderer`, device/emulator only) |

## 2. Quick-note widget

| Contract | Value |
|---|---|
| Provider | `com.cipherwell.notezero.widget.QuickNoteWidgetProvider` (receiver `exported=false`, only `android.appwidget.action.APPWIDGET_UPDATE`) |
| Metadata | `res/xml/quick_note_widget_info.xml`: `updatePeriodMillis=0`, `minWidth=110dp`, `minHeight=40dp`, `resizeMode=horizontal`, `widgetCategory=home_screen`, `initialLayout=@layout/widget_quick_note`, description string |
| Layout ids | `widget_quick_note_root`, `widget_quick_note_icon`, `widget_quick_note_label`; text = `@string/widget_quick_note_label` ("New note") |
| Click intent | action `com.cipherwell.notezero.SHORTCUT_NEW_NOTE`, component `MainActivity`, flags `NEW_TASK|CLEAR_TOP`; PendingIntent `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE` |
| Content policy | the tile renders **no vault data** (no titles, counts, previews, timestamps); the provider never touches `NoteRepository`; no periodic updates, no configuration/boot receiver |
| Tests | `QuickNoteWidgetProviderTest` (intent contract, RemoteViews inflation/label, compiled metadata attrs); device check = provider visible to the system AppWidget service + launcher picker |
