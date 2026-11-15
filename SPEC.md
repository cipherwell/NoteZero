# NoteZero — project specification (bootstrap)

**Status:** new project, not yet scaffolded.
**Owner/identity:** Cipherwell (GitHub/GitLab `cipherwell`).
**Working directory:** `/home/cipherwell/Documents/deepseek-harness/default-workspace/notezero`
**Suggested app ID:** `com.cipherwell.notezero`
**License:** MIT.
**Platform:** Android only (Kotlin + Jetpack Compose), minSdk 24, target/compileSdk 36, JDK 17.

## Product

NoteZero is an **offline-first, encrypted notes app and code editor** for Android, built to the same
security bar as VaultZero (zero-knowledge, no network permission, no telemetry, no accounts).
It combines everyday note-taking with a serious, local code editor.

### Non-goals
- No cloud sync, accounts, collaboration, telemetry, analytics, ads, or network APIs.
- No web wrapping or third-party sync services.
- No network permissions in the manifest.

## Security requirements (same bar as VaultZero, independent app)

- Separate Android package and signing key from VaultZero; do not share its upload key.
- Random 256-bit vault master key (VMK); per-note AES-256-GCM with AAD bound to note id + type.
- Note titles/tags/folder names encrypted inside the note envelope; SQLite columns redacted.
- Password KDF: PBKDF2-HMAC-SHA256 600,000 iterations (or versioned Argon2id later), 128-bit salts.
- 12-word BIP-39 recovery phrase; 128-bit entropy; unique words; checksum validation; legacy-compatible.
- Hardware biometric unlock through Android Keystore per-use CryptoObject; invalidated by re-enrolment.
- Change master password without re-encrypting notes; rotate recovery kit.
- Encrypted backup export/restore through SAF only; verified/duplicate-safe imports; phrase-only mode.
- Auto-lock, background/screen-off lock, sensitive-clipboard handling, FLAG_SECURE.
- No plaintext secrets/tokens in logs; no INTERNET/ACCESS_NETWORK_STATE permission.
- Encrypted attachment blobs at rest (images/files/drawings) with size limits.
- Reproducible release builds + signed-release CI; F-Droid eligibility (3-month rule applies from
  public repo creation).
- Threat model, crypto spec, audit, security policy in `SECURITY.md`/`docs/`.

## Features

### Notes (iOS Notes-inspired, offline equivalent)
- Folders (nested), pinned notes, favourites, tags, smart search, recent notes.
- Rich text notes: headings, bold/italic/underline/strikethrough, bullet/numbered lists,
  checklists, links, code blocks, block quotes.
- Markdown editing + preview.
- Tables.
- Attachments: images, files, drawing canvas; optional camera scan (camera permission, purely local).
- Lock note / lock folder via biometrics; hidden until unlocked.
- Quick note capture, widgets, launcher shortcuts.
- Trash + undo, autosave, word/character count.
- Export note as Markdown/HTML/PDF? (SAF only; no share providers unless explicitly added later).

### Code editor
- File/folder workspace inside NoteZero (notes can be code files) with a tree.
- Syntax highlighting for common languages (Kotlin, Java, JS/TS, Python, JSON, XML, Markdown, shell).
- Line numbers, current-line highlight, indentation/tabs-vs-spaces, auto-indent, bracket matching,
  find/replace, go-to-line, undo/redo.
- Monospace themes (light/dark), adjustable font size, word wrap.
- Markdown preview, copy/export, file rename/move.
- Autosave with crash-safe drafts, optional local version history (encrypted).
- No network-based LSP/AI; all syntax/editor logic offline.

### Design
- One UI-inspired visual language: true black/#FAFAFA grounds, one accent blue, squircle containers,
  floating pill navigation, large collapsing title.
- Light/dark/system; accessibility labels; 48 dp targets; large-font support.
- Cipherwell brand, but distinct icon/identity from VaultZero.

## Suggested architecture

```
notezero/
├── app/                         single Android module
│   └── src/main/java/com/cipherwell/notezero/
│       ├── crypto/              AES-GCM, PBKDF2/BIP39, biometric keystore
│       ├── data/                Room DB, encrypted note/attachment repository, prefs
│       ├── model/               note/envelope/attachment models
│       ├── ui/                  Compose screens, editor, folder tree, settings
│       └── backup/              SAF envelopes
├── docs/                        security, crypto, backup format, architecture, testing
├── fastlane/                    store metadata
├── fdroid/                      F-Droid recipe
└── README.md, SECURITY.md, LICENSE, CHANGELOG.md
```

- Reuse the VaultZero *design patterns and test vectors*, not its signing key/package. Copying the
  crypto module into a new package with independent tests is acceptable for v1; extract a shared
  library only after both apps are stable.
- Room v1 for notes/folders/tags/attachments; metadata redaction; atomic migrations.
- Attachments stored as encrypted blobs in app-private storage, referenced by id.

## Delivery phases

1. **Bootstrap / security core:** project scaffold, crypto primitives, setup/unlock/recovery,
   encrypted DB, tests, CI skeleton.
2. **Notes MVP:** folders/tags/pins/search, rich text + Markdown, checklists, lock, autosave,
   trash/undo, encrypted backup/restore.
3. **Code editor:** file tree, syntax highlighting, line numbers, find/replace, themes.
4. **Attachments & iOS-like extras:** images/files/drawings, tables, quick capture, widgets,
   camera scan.
5. **Hardening/release:** pentest, reproducible builds, docs/store assets, F-Droid after the
   3-month maintenance window.

## Open decisions for the first session
- Confirm app ID `com.cipherwell.notezero` and repo `cipherwell/NoteZero`.
- Rich text engine choice: custom Compose model vs. a vetted FOSS rich-text library.
- Attachments/camera in MVP or phase 4.
- Code editor engine: implement in Compose vs. evaluate a FOSS syntax-highlighting library
  (must be offline, auditable, no native/network blobs).
- Whether code files and notes share one item model or separate note/file types.
