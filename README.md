# NoteZero

![CI](https://github.com/cipherwell/NoteZero/actions/workflows/ci.yml/badge.svg)

**Offline-first, end-to-end encrypted notes and code editor for Android.**
No account. No cloud. No network permission. Your notes never leave the device except
through an encrypted backup file you export yourself.

NoteZero is the second Cipherwell app, built to the same security bar as
[VaultZero](https://github.com/cipherwell/Vault-Zero) with an independent package
(`com.cipherwell.notezero`), independent signing key and independent threat model.

> Status: **Phase 4 complete, Phase 5 hardening** — 2026-11-15.
> Security core, notes MVP, encrypted SAF backup/restore, code editor (workspace
> search/copy/export/indent), encrypted attachments (images/files/**drawings**),
> **locked notes/folders**, **Markdown/HTML note export**, **table editing** and
> **quick-capture launcher shortcut**, a **create menu (Note / Drawing / Code / PDF)** and
> **blank encrypted PDF documents** are in; an independent security audit found
> **no HIGH/CRITICAL issues** and its findings are remediated. Builds green,
> **899 unit tests + 12 device tests**, UI is translation-ready, and **signed APK + AAB
> release builds are byte-for-byte reproducible**. A device-QA pass on a Pixel 9 Pro XL
> (Android 17) found and fixed system-bar, large-font drawing, biometric-copy and
> empty-note bugs. What ships now, evidence and the remaining Phase-5 tail: [docs/VERIFICATION-2026-11-15.md](docs/VERIFICATION-2026-11-15.md).
> Delivery phases: [SPEC.md](SPEC.md).


## What exists today

| Setup (first run) | Unlock | Notes MVP |
|---|---|---|
| ![Setup](docs/screenshots/00-setup.png) | ![Unlock](docs/screenshots/00-unlock.png) | ![Notes](docs/screenshots/01-notes-list.png) |
| **Markdown editor** | **Block preview (checklists tap to toggle)** | **Second note persisted** |
| ![Editor](docs/screenshots/02-editor-edit.png) | ![Preview](docs/screenshots/03-editor-preview.png) | ![Notes 2](docs/screenshots/04-notes-list-two-notes.png) |
| **Encrypted backup (SAF)** | **Code workspace (file tree)** | **Kotlin editor (syntax highlighting)** |
| ![Backup](docs/screenshots/05-settings-backup.png) | ![Code workspace](docs/screenshots/06-code-workspace-empty.png) | ![Code editor](docs/screenshots/07-code-editor-kotlin.png) |
| **Encrypted attachments in the editor** | **Workspace-wide search** | **Search result opens at the line** |
| ![Attachments](docs/screenshots/02-editor-edit.png) | ![Search](docs/screenshots/09-code-workspace-search.png) | ![Search result](docs/screenshots/10-code-editor-search-result.png) |
| **Insert markdown table** | **Drawing canvas (encrypted PNG)** | **Quick capture shortcut** |
| ![Table](docs/screenshots/14-table-insert.png) | ![Drawing](docs/screenshots/15-drawing-canvas.png) | ![Quick capture](docs/screenshots/16-quick-capture.png) |
| **Locked note masked in the list** | **Re-auth with master password** | **Revealed after verification** |
| ![Locked](docs/screenshots/11-locked-note-masked.png) | ![Re-auth](docs/screenshots/12-reauth-dialog.png) | ![Revealed](docs/screenshots/13-locked-note-revealed.png) |
| **Create menu: Note / Drawing / Code / PDF** | **Blank PDF document viewer** | **Encrypted PDF stored as an attachment** |
| ![Create menu](docs/screenshots/17-create-sheet.png) | ![PDF viewer](docs/screenshots/18-pdf-viewer.png) | ![PDF in list](docs/screenshots/18-pdf-viewer.png) |
| ![Device status bar](docs/screenshots/20-device-status-bar-light.png) | ![Device drawing](docs/screenshots/21-device-drawing-canvas-1-15x.png) | *(last row: Pixel 9 Pro XL, Android 17, font scale 1.15 — device QA)* |

Screenshots are real emulator captures from the instrumented E2E run (debug build with
`-PallowScreenshots=true`; normal builds set FLAG_SECURE, which makes `screencap` return a
0-byte file — verified).

## Verified end to end (2026-11-15)

- 899 unit tests / 0 failures (77 classes); 12 instrumented tests on a real emulator.
- Real Android crypto: setup → BIP-39 phrase → lock/unlock; **password change leaves note
  ciphertext byte-identical**; recovery reset invalidates the old password; a raw SQLite scan
  of the database files (main + WAL + SHM) finds no plaintext title/body/phrase — including a
  code file created through the code editor.
- SAF encrypted backup/restore is wired end to end: the picker's background stop no longer
  locks the vault (deferred, idle timeout still applies), exports are vault-key or
  separate-password protected, imports are duplicate-safe and accept a source master password
  or 12-word phrase for cross-vault restores.
- Code editor: pure-Kotlin lexers for Kotlin/Java/JS/TS/Python/JSON/XML/Markdown/Shell
  (200 KB in ~3–8 ms measured), file tree, line numbers, syntax colours, auto-indent,
  bracket matching, find/replace, go-to-line, undo/redo, font size/wrap, 600 ms autosave,
  workspace-wide search that opens a file at the matched line, sensitive-flagged copy and an
  explicit-warning decrypted export.
- Attachments: images and files imported through the Photo Picker / SAF, encrypted at rest in
  app-private storage as AES-256-GCM blobs (`NZAT` v1) under the vault VMK with AAD bound to
  note id + attachment id, SHA-256 verified on read, 10 MiB/file, 100/note, 512 MiB quota;
  in-memory thumbnails only (no plaintext cache); decrypted export only behind an explicit
  "writes an UNENCRYPTED copy" confirmation. Backups (v2) carry the attachment bytes.
- **Create menu**: the Notes "+" opens a sheet to create a **Note** (Markdown), a
  **Drawing** (editor opens with the canvas ready), a **Code** file (name + picker with 27
  well-known extensions: `.txt .md .html .css .js .ts .json .xml .yml .toml .py .kt .java .c
  .cpp .h .cs .go .rs .rb .php .swift .sql .sh .csv .ini .properties`; a typed known extension
  wins, formats without a lexer edit as plain text rather than mis-highlighted) or a **PDF**
  (blank one-page document stored as an encrypted `application/pdf` attachment and rendered by
  the platform PDF viewer; export only behind the explicit decrypted-copy warning).
- Locked notes/folders: the lock flag lives inside the encrypted envelope; locked content is
  masked in lists/search/counts and in the code workspace until re-authenticated with
  biometrics or the master password (presence gate, not a second encryption layer — content
  stays AES-256-GCM under the VMK). Verification is memory-only, wiped on background/lock;
  master-password re-auth is rate-limited. Backup/restore preserves lock flags but never
  verification.
- **Reproducible release builds**: `scripts/verify-reproducible.sh` rebuilds the same commit
  twice in different directories with a pinned `SOURCE_DATE_EPOCH` and no build cache; the
  signed **APK and AAB are both byte-identical** (methodology/limits:
  docs/REPRODUCIBLE-BUILDS.md), and CI enforces the unsigned check on every push.
- **Audited**: independent adversarial audit of crypto/storage/session/offline surface found
  **0 HIGH / 0 CRITICAL**; 2 MEDIUM + 8 LOW findings all remediated (report:
  docs/AUDIT-2026-11-15-phase5.md). All UI strings are in Android resources (translation-ready).
- **Drawing canvas**: in-memory PNG drawings imported as encrypted attachments (no plaintext
  cache, same 10 MiB pipeline). **Note export**: explicit-warning SAF Markdown/HTML export with
  a fully escaped HTML renderer. **Tables**: insert-table dialog + row/column edits on the
  Markdown table under the caret. **Quick capture**: launcher shortcut that survives a locked
  vault and creates the note only after unlock, through the encrypted repository.
- FLAG_SECURE default-on proof; release APK permission dump: only `USE_BIOMETRIC` (+
  androidx.core's signature-level receiver guard) — no network, camera, location or storage.
- Signed release verified with the independent NoteZero upload key; unsigned-release fallback
  for F-Droid also verified.
- Full evidence trail incl. commands and hashes:
  [docs/VERIFICATION-2026-11-15.md](docs/VERIFICATION-2026-11-15.md).

## Security properties (by construction)

- **No network permissions.** `INTERNET` and `ACCESS_NETWORK_STATE` are stripped from the
  merged manifest with `tools:node="remove"` (plus the deprecated `USE_FINGERPRINT` the
  biometric library drags in), no network library is a dependency, and
  `scripts/check-offline.sh` + `OfflineGuaranteeTest` fail the build if that changes.
  The only app permission left is `USE_BIOMETRIC` plus `androidx.core`'s signature-level
  dynamic-receiver guard.
- **Random 256-bit vault master key (VMK).** Notes are AES-256-GCM encrypted under the
  VMK; the VMK is only ever wrapped by your password/recovery phrase, never derived from
  them. Changing the master password re-wraps the VMK and does **not** re-encrypt notes.
- **Per-item AAD.** Every ciphertext is bound to its item id and type, so a row cannot be
  swapped between notes/vaults without failing authentication.
- **Redacted storage.** Titles, tags, folder names, bodies and flags live *inside* the
  encrypted envelope. Room columns hold only random ids, kinds, timestamps and ciphertext.
- **PBKDF2-HMAC-SHA256, 600,000 iterations**, 128-bit salts, versioned KDF slot so
  Argon2id can be added without breaking existing vaults.
- **12-word BIP-39 recovery phrase** (128-bit entropy, checksum validated, unique words).
- **Hardware biometric unlock** via Android Keystore, per-use CryptoObject, invalidated by
  re-enrolment.
- **Auto-lock** on background/screen-off/timeout; sensitive clipboard handling; FLAG_SECURE.
- **Encrypted backup** export/restore through the Storage Access Framework only.
- **No telemetry, analytics, ads, accounts or third-party sync.**

See [docs/CRYPTO-SPEC.md](docs/CRYPTO-SPEC.md), [docs/THREAT-MODEL.md](docs/THREAT-MODEL.md),
[docs/DECISIONS.md](docs/DECISIONS.md) and [SECURITY.md](SECURITY.md).

## Build

Requires JDK 17 and Android SDK 36 (`compileSdk 36`, `minSdk 24`).

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk   # JDK 17 is mandatory, 21+ not supported by AGP 9.1
./scripts/gen-debug-keystore.sh                 # local debug key (gitignored)
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest                # 290 tests, 0 failures (2026-11-15)
./scripts/check-offline.sh                      # offline guarantee check
```

Full instructions (including signed release builds and this machine's exact setup):
[docs/BUILDING.md](docs/BUILDING.md).

## Docs

| Document | Contents |
|---|---|
| [SPEC.md](SPEC.md) | Product specification and delivery phases |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Frozen first-session decisions incl. signing key fingerprint |
| [docs/CONTRACTS.md](docs/CONTRACTS.md) | Module contracts, ownership, crypto/data/UI interfaces |
| [docs/CRYPTO-SPEC.md](docs/CRYPTO-SPEC.md) | Primitives, key hierarchy, envelope formats, AAD map |
| [docs/THREAT-MODEL.md](docs/THREAT-MODEL.md) | Assets, adversaries, mitigations, out-of-scope |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers, Room schema, session/lock lifecycle |
| [docs/DATA-MODEL.md](docs/DATA-MODEL.md) | Item/folder envelopes, redaction model |
| [docs/BACKUP-FORMAT.md](docs/BACKUP-FORMAT.md) | Encrypted backup envelope format |
| [docs/BUILDING.md](docs/BUILDING.md) | Toolchain, debug/release, signing, reproducibility |
| [docs/TESTING.md](docs/TESTING.md) | Test strategy and commands, measured baseline |
| [docs/CONTRACTS-PHASE3.md](docs/CONTRACTS-PHASE3.md) | Phase-2/3 increment contracts (backup UI, editor engine/UI) |
| [docs/EDITOR-SPEC.md](docs/EDITOR-SPEC.md) | Code editor languages, tokens, editing behaviour, performance |
| [docs/CONTRACTS-PHASE4.md](docs/CONTRACTS-PHASE4.md) | Attachment storage + attachments UI + code-polish contracts |
| [docs/CONTRACTS-PHASE5.md](docs/CONTRACTS-PHASE5.md) | Locked notes/folders + reproducibility increment contracts |
| [docs/CONTRACTS-PHASE6.md](docs/CONTRACTS-PHASE6.md) | Drawing, note export, tables, quick-capture increment contracts |
| [docs/REPRODUCIBLE-BUILDS.md](docs/REPRODUCIBLE-BUILDS.md) | Verified reproducible APK+AAB builds, pinned toolchain, limits |
| [docs/AUDIT-2026-11-15-phase5.md](docs/AUDIT-2026-11-15-phase5.md) | Adversarial security audit + per-finding remediation log |
| [docs/STRINGS.md](docs/STRINGS.md) | String-resource conventions and deliberate inline exceptions |
| [PRIVACY.md](PRIVACY.md) | Privacy policy (no collection, no network) |
| [docs/ATTACHMENTS.md](docs/ATTACHMENTS.md) | Encrypted attachment blob format, limits, AAD, lifecycle, threat notes |
| [docs/VERIFICATION-2026-11-15.md](docs/VERIFICATION-2026-11-15.md) | Phase-1 evidence: builds, 290 unit tests, device E2E, signing, FLAG_SECURE |
| [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md) | Dependency policy/inventory (offline-safe only) |
| [docs/RELEASING.md](docs/RELEASING.md) | Release checklist, F-Droid eligibility |

## Privacy

No accounts, no telemetry, no network permission. See [PRIVACY.md](PRIVACY.md) for the full
privacy statement.

## License

MIT — see [LICENSE](LICENSE). NoteZero is independent from VaultZero; they share design
patterns and test vectors, not code signing identity or product identity.
