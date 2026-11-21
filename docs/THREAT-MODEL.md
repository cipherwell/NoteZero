# NoteZero — threat model (v1)

Scope: the NoteZero Android app as shipped in Phase 1 (setup/unlock/recovery, encrypted
notes storage, backup/restore), with the crypto core described in
`docs/CRYPTO-SPEC.md`. Assets, attackers and mitigations are mapped to the code that
implements each control. This is a living document: when a control moves, update the
reference here in the same change.

Method: asset → adversary → attack path → mitigation → residual risk. Priorities follow
the SPEC's security bar ("same bar as VaultZero", zero-knowledge, offline-only).

---

## 1. Assets (what we protect, and for how long)

| # | Asset | Sensitivity | Lifetime |
|---|---|---|---|
| A1 | Note/code-file content (title, body, tags, folder, flags) | High — personal/secret data | Until user deletes; backups extend it |
| A2 | Vault Master Key (VMK) | Critical — decrypts everything | Vault lifetime |
| A3 | Master password | Critical | Vault lifetime |
| A4 | BIP-39 recovery phrase (12 words) | Critical — recovers the VMK without the password | Vault lifetime |
| A5 | Wrapped VMK envelopes (password + recovery) | High (offline-attackable) | Vault lifetime |
| A6 | Biometric-wrapped VMK copy + Keystore key | High | Until re-enrolment/disable |
| A7 | Backup export (`CryptoAad.BACKUP`) | High | Until restored/deleted |
| A8 | Decrypted in-memory index/VMK while unlocked | Critical | While unlocked |
| A9 | Metadata: vault existence, approximate item count, timestamps of access | Low–medium | Vault lifetime |
| A10 | Integrity of all of the above (no silent tampering) | High | Vault lifetime |
| A11 | Locked-content visibility (which records are locked; the plaintext of a locked record once re-authenticated) | Medium — a handoff/shoulder-surfing barrier, not a crypto boundary | While unlocked; verification is per-session |
| A12 | Attachment blob plaintext/ciphertext (`filesDir/attachments/<id>.nzb`, `NZAT` v1: `iv‖AES-256-GCM ct‖tag` under the VMK, AAD bound to note+attachment id) | High — arbitrary user files (documents, images) | Vault lifetime; trash keeps blobs, delete-forever/empty-trash removes them |
| A13 | Attachment ref metadata (random id, display name, MIME, plaintext size, createdAt, SHA-256) — stored **inside** the v2 item envelope, never in a plaintext column | Medium — the display name/MIME are content | Same as the owning item |

Non-assets (explicitly not protected): public app code, the BIP-39 wordlist itself, the
app's package name, store listing.

---

## 2. Assumptions

1. The device OS and its Keystore implementation are not compromised; Android sandboxing
   and file permissions hold for other apps.
2. The user's device lock screen is enabled; biometric unlock requires a secure lock
   screen in practice (Keystore refuses auth-gated keys otherwise on most devices).
3. The user's master password has not been reused elsewhere, and the recovery phrase is
   stored offline by the user.
4. Compile-time decisions are enforced by the build (Lead-owned `app/build.gradle.kts`):
   the **release** variant hardcodes `ALLOW_SCREENSHOTS = false` and `DEV_DISABLE_AUTO_LOCK
   = false`, and the opt-in `-PallowScreenshots` / `-PdevDisableAutoLock` flags exist only
   on the **debug** variant (audit F4/D3). A release build therefore cannot turn off
   FLAG_SECURE or the background/idle lock, and no network permission is compiled in. This
   document still describes only the runtime crypto that remains if those controls were
   misconfigured.
5. The app process is not actively debugged by an attacker with `run-as`/root at the
   moment the vault is unlocked (see §3.1 — accepted, documented residual risk).

---

## 3. Adversaries and attack paths

### 3.1 Lost or stolen device (opportunistic thief, non-technical)

| Attack | Mitigation | Residual |
|---|---|---|
| Open the app and read notes | Vault locks on background/screen-off; unlock requires password or biometric (`NoteCrypto` + `BiometricKeyManager`) | None if password strong |
| Read the SQLite file / app-private files | Only AES-256-GCM ciphertext is at rest; titles/tags/folders are inside the envelope; searchable plaintext columns do not exist (`CryptoAad.item`, data-layer redaction) | Item count/timestamps visible (A9) |
| Enable USB debugging and `adb pull` app data on a debug build | Debug builds are for development only; release build is signed and not debuggable | Debug device with unlocked bootloader: see 3.2 |
| Shoulder-surf the unlock screen | `FLAG_SECURE` (MainActivity, Lead-owned) blocks screenshots/recents preview; UI masks secrets | Visual observation of physical screen is out of scope |
| Read a specific note while the device is handed over unlocked, or shoulder-surf a list/editor | Per-note/folder **lock** flag (decision #15): locked records render as a mask until the session re-authenticates (`verifyMasterPassword` or a presence-only biometric prompt); `verifiedLockedIds` is memory-only and re-masked on every background/screen-off/manual lock. The flag itself is content inside the AEAD envelope | This is a visibility gate, not a second encryption layer: a locked record's plaintext is still in RAM/decryptable while the vault is unlocked, and an attacker who obtains the VMK or DB file is unaffected (see A8 / 3.2). A user who leaves the vault unlocked and hands the device over without a lock cannot be protected |
| Brute-force the password offline from the copied envelope | PBKDF2-HMAC-SHA256, 600k iterations, 128-bit salt per envelope (`KeyEnvelope.iterations`, `NoteCrypto.deriveKek`); VMK is random 256-bit so no shortcut | Password entropy is the limiting factor; PBKDF2 is GPU-friendly — Argon2id upgrade reserved (`docs/CRYPTO-SPEC.md` §2.2) |
| Brute-force the recovery phrase | 128-bit entropy (12 BIP-39 words) — infeasible to brute force | Phrase theft via physical/paper compromise is out of scope |

### 3.2 Rooted device / malware with app data or process access

| Attack | Mitigation | Residual |
|---|---|---|
| Read ciphertext, try to decrypt | No plaintext keys on disk; VMK only in RAM while unlocked | — |
| Read the device Keystore-wrapped VMK copy | Keystore key is non-exportable and inside TEE/StrongBox; unwrap requires a per-use biometric-authenticated cipher (`BiometricKeyManager.isEnrolled`/`prepareDecrypt`) | Root can hook the app process and steal the VMK **while unlocked**: accepted (same bar as VaultZero), mitigated only by auto-lock and user behaviour |
| Hook the app to call `completeDecrypt` after spoofing biometric auth | Auth is enforced by secure hardware per cipher; the app cannot bypass it without a successful prompt | A rooted device can fake the *result* the app receives from the prompt (UI-level), but the Keystore will still refuse `doFinal` unless the hardware gate accepted the cipher — the VMK stays encrypted |
| Read logs for secrets | No secrets/tokens/titles are logged; diagnostics are ids/counts only (CONTRACTS rule; code has no logging in crypto) | Third-party crash reporters are not shipped |
| Dump process memory during unlock | Accepted residual (above); screen-off/background lock reduces the window | — |

### 3.3 Forensic backup / data extraction (physical, cloud-backup capture, image of flash)

| Attack | Mitigation | Residual |
|---|---|---|
| Extract `vault_meta` + Room DB from a full device/cloud backup | All values are ciphertext or wrapped keys; the Keystore key is device-bound and never exported (`BiometricKeyManager` stores only the wrapped copy) | Envelope iteration count/type/salt are visible metadata; this is necessary for KDF agility |
| Restore app data onto another device and unlock | Password/recovery envelope is device-independent by design (the user must be able to restore); biometric wrap is useless on the new device because the Keystore key is gone — `prepareDecrypt` wipes the orphaned blob and forces the password path (`BiometricKeyManager.prepareDecrypt`) | The restored device can brute-force with the password (same as 3.1) |
| Forge/replay a backup file | Backup header/envelope authenticated with `CryptoAad.BACKUP`; tampered restore fails (`docs/BACKUP-FORMAT.md`, data-layer) | Restoring a *valid old* backup is a rollback the user initiated |
| Determine which notes/folders are locked from the DB or a backup | The lock flag is content: a trailing byte inside the item/folder AES-GCM envelope (`ItemCodec` v3 / `FolderCodec` v2), with no plaintext `locked` column and no separate lock table; a v1/v2 backup decodes as unlocked | An attacker with the VMK can read everything (lock is not encryption); without it they cannot tell locked from unlocked records, only row counts/timestamps (A9) |

### 3.4 Malicious or corrupted data at rest (SQLite tampering, file corruption)

| Attack | Mitigation | Residual |
|---|---|---|
| Swap ciphertext between item rows / kinds | AAD binds `item(id, kind)`; GCM tag fails and the row is skipped (`NoteCrypto.decrypt` → null) | Attacker can delete rows (availability only) |
| Move the password wrap into the recovery slot or vice versa | Distinct AAD domains `notezero.vmk.v1` / `notezero.recovery.v1` | — |
| Tamper a wrapped VMK | GCM tag + `unwrapVmk` 32-byte length check → null; vault fails closed (`KeyEnvelope.fromBytes` rejects malformed envelopes) | DoS (cannot unlock) requires the user's recovery path — acceptable fail-closed |
| Corrupt an envelope's header (version/kdf/iterations) | Strict parser rejects unknown/absurd values instead of trying them (`KeyEnvelope.fromBytes`) | DoS as above |
| Hostile iteration count to burn CPU | `[10_000, 10_000_000]` bounds on read and write | A 10M-iteration envelope costs ~seconds once — bounded |

### 3.5 Biometric-specific adversaries

| Attack | Mitigation | Residual |
|---|---|---|
| Add a new fingerprint after enrolment (attacker with device PIN) | Key created with `setInvalidatedByBiometricEnrollment(true)`; unwrap fails with `KeyPermanentlyInvalidatedException`, stale wrap wiped (`BiometricKeyManager.prepareDecrypt`), password required to re-enable | Attacker with PIN + password can re-enable; PIN compromise is out of scope |
| Brute-force biometrics | Biometric stack owns rate limiting/lockout; Keystore requires a successful strong-biometric auth per use | — |
| Silent/unattended hardware unlock from a background service | Per-use auth (`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`) + `setUnlockedDeviceRequired`; every unwrap needs a fresh prompt with the authorised cipher | — |
| Attacker replaces the app's Keystore alias / pre-generates a key | Key generation is app-private (app UID); `prepareEnroll` reuses the existing alias and `disable()` deletes it; no way to import a key | Compromised app UID = compromised app (3.2) |
| Prompt-spoofing overlay | Screen overlays are blocked from sensitive UI by OS requirements for biometric prompts; UI must rely on the system prompt (UI-owned) | Accessibility abuse is an OS-level concern |
| Fallback to a prompt-free path | None exists: `enroll` cannot wrap without a prompt (two-phase contract), and there is no decrypt API without an authorised cipher | — |

### 3.6 Screenshots, recents and clipboard

| Attack | Mitigation | Residual |
|---|---|---|
| Screenshot / recents thumbnail of notes or recovery phrase | `FLAG_SECURE` on the activity (MainActivity, build-config gated off only for automated screenshots in debug); recovery screen shows the phrase once and is covered; locked+unverified rows render a mask, never the title/body/preview | External camera out of scope |
| Clipboard sniffing (Android 10+ denies background clipboard access; older versions allow) | Sensitive clipboard handling with auto-clear timer and `ClipDescription.EXTRA_IS_SENSITIVE` where available (UI-layer, Lead/ui-shell) | Rooted/older devices: accepted, documented |
| Screen recording/accessibility service | FLAG_SECURE blocks recording; accessibility services are an OS trust decision | Malicious accessibility service = 3.2 |

### 3.7 Network (non-adversary, but a hard invariant)

Not part of the threat model because NoteZero ships **no network permission and no
network APIs**: there is no remote attacker surface, no telemetry exfiltration path, no
sync MITM. This is enforced by the manifest `tools:node="remove"`, `scripts/check-offline.sh`
and `OfflineGuaranteeTest` (Lead-owned); the crypto layer neither imports `java.net` nor
any client library.

### 3.8 Supply chain / build

| Attack | Mitigation | Residual |
|---|---|---|
| Malicious dependency | No new dependencies (CONTRACTS rule); JCA/Android Keystore are platform APIs; test-only oracle dependency is not shipped | A compromised AGP/Kotlin toolchain is out of scope |
| Backdoored release binary | Reproducible build + release signing via env-var keystore outside the repo; separate NoteZero upload key (decision #6); F-Droid rebuild path | F-Droid's own build server is trusted as configured |
| Test vectors/fixtures leaking real secrets | Tests use only published vectors and scheme-format constants (`docs/CRYPTO-SPEC.md` §9); no vault/password/phrase from a real device | — |

---

### 3.9 Attachments and PDF rendering (shipped in Phase 4/5; detail in `docs/ATTACHMENTS.md` §9)

**PDF documents (increment 8).** A `PDF_FILE` item stores a blank one-page PDF as an encrypted
`NZAT` blob, so at rest nothing changes: ciphertext only, AEAD + AAD bound to item id and kind.
Viewing uses the platform `PdfRenderer`, which requires a **seekable file descriptor**: the
decrypted bytes are written to an app-private `cacheDir` file for the duration of the
rasterisation and deleted in a `finally` block before the page images are shown. Exposure window
is one render call, the file is excluded from backup/device-transfer, the cache is app-private
and the rendered bitmaps are bounded (≤ 8 pages, ≤ 1600 px long edge) and recycled on dispose.
An attacker with offline access to flash *between* the write and the delete could recover a
plaintext PDF page — accepted as a bounded, explicit trade-off because Android offers no
in-memory seekable PDF API (THREAT-MODEL §6 R7). The alternative (no PDF viewing) has no such
window but removes the feature the owner asked for; the temp file is therefore the only
plaintext-at-rest exception in the app, documented in DECISIONS #20 and guarded by the
instrumented test that asserts no `pdf-render-*` file remains after rendering.

**PDF export (increment 9).** Exporting a note as PDF is the same explicit plaintext-egress
class as Markdown/HTML export: the user picks the target through SAF after an explicit
"UNENCRYPTED copy" warning, the renderer writes only into memory and the chosen
`OutputStream`, and the session is re-checked after rendering. No temp file is created for this
path (unlike PDF *viewing*, which needs a seekable descriptor).

**Quick-note widget (increment 9).** The home-screen tile is content-free by design because a
home screen is visible while the vault is locked. It renders only a glyph and the static label
"New note", never reads the repository, has no periodic update and no configuration activity;
its PendingIntent is immutable and its receiver is not exported. The only effect of tapping it
is the same pending quick-capture request as the launcher shortcut, which is honoured only
after a successful unlock.

### 3.9.9 Full-document lazy PDF viewer (increment 18)

The viewer now keeps one open platform `PdfRenderer` session instead of eagerly rasterising the
first 8 pages. The decrypted bytes are written to one app-private cache file, opened read-only and
**unlinked immediately**, so no plaintext PDF path exists for the session; if the filesystem
refuses unlink, the file is deleted on close (documented fallback). Page bitmaps are rendered only
while their `LazyColumn` row is composed, under IO + a `Mutex` (PdfRenderer is not thread-safe),
≤1600 px long edge and never upscaled; each bitmap is recycled when its row leaves composition and
a per-page failure shows a non-secret placeholder. `MAX_VIEW_PAGES = 2000` bounds a hostile page
count. The session closes on viewer dispose/item change, and close is idempotent even if a render is
in flight. No page/thumbnail file, database column or plaintext cache is added; no title/page
content is logged; no new permission or dependency. Annotated copies use the all-pages page-tools
exporter (≤100 pages, refuse above without creating a destination file), not the old rendered-prefix
flattener. `PdfPageRenderer`/`PdfPages`/`PdfFlattenedExporter` remain legacy utilities with their
own instrumented tests but are no longer reachable from the viewer.

### 3.9.8 PDF page thumbnails (increment 17)

Thumbnails are **in-memory only** for one viewer session: the viewer lazily asks
`AndroidPdfThumbnailRenderer` once (on first Pages/bookmarks/page-tools open), which writes the
decrypted bytes to a single ephemeral app-private cache file (same bounded seekable-FD trade-off
as the other renderers, deleted in `finally`), renders ≤100 pages at ≤144 px long edge on white
ARGB bitmaps, and returns them as a caller-owned set. The viewer holds no PDF bytes in state, recycles
every bitmap on replace/dispose, and fails closed to an empty set (numeric page fallback) on
corrupt input/oversized pages/OOM; cancellation is rethrown. No thumbnail file, database column,
backup field or plaintext cache exists; no titles/page content are logged; no new permission or
dependency. The Pages overview grid intentionally covers the pages the viewer has rasterised (the
existing 8-page render cap) so a cell is always a valid target; page tools still cover all pages up
to 100. A full-document lazy-render viewer is a separate queued change; until then the numeric field
retains the document's full range.

### 3.9.7 PDF page tools (increment 16)

Page operations (rotate/delete/reorder/insert blank) are a **plan**, not an in-place edit: the
`PdfPagePlan` value holds only source-page indices/blank markers and quarter-turns (≤100 pages),
and the output is always a new flattened PDF item. The exporter re-runs `PdfRenderer` on the
decrypted bytes through one ephemeral app-private cache file (same bounded, no-backup, deleted-in-
`finally` trade-off as viewing/annotated export), renders each source page at ≤1600 px with a
~48 MiB total pixel budget, and flattens the page's annotations through the shared
`PdfPageDrawing` transform; deleted pages' annotations are dropped, reordered/rotated pages keep
them. Bad plans, out-of-range pages, corrupt PDFs, missing native renderer and OOM return null
(no throw, no partial item); the save path re-reads bytes, re-checks unlocked/item-alive before
writing, and imports only through the encrypted `NZAT` attachment pipeline (half-created row is
trashed on failure). No plaintext output file/URI, no logging of titles/page content, no new
permissions/dependencies. The original PDF and its annotation envelope are never mutated.

### 3.9.6 Additional book formats and the shared reader (increment 14)

`BOOK_FILE` now accepts EPUB/FB2/TXT/CBZ, with the format detected from the decrypted bytes at
open time (EPUB -> CBZ -> FB2 -> TXT) instead of a schema change. All parsers are dependency-free
pure JVM code with the same fail-closed discipline as the EPUB engine: bounded entries/sizes/
chapters/blocks/images/pages, XML external entity/DTD resolution disabled (FB2), no scripts/CSS/
HTML execution, corrupt/ambiguous/unsupported input returns `null` (clean reader error, no partial
book) and never throws. Comics (CBZ) are an image archive: page entries are bounded (≤500 pages,
≤16 MiB each), decoded lazily per page in memory via the existing Android decoder, recycled on
dispose, with no extraction to disk and no plaintext thumbnail cache. The shared `BookContent`
interface lets EPUB/FB2/TXT reuse the existing reader (TOC/search/bookmarks/appearance/progress)
without duplicating UI, while comics get a paged reader storing only encrypted `ReadingState`
progress/bookmarks (no content). Import detects the format and metadata before surfacing the item,
stores the detected MIME, and removes the placeholder on failure; no content/name logging.

### 3.9.5 Universal workspace and bulk operations (increment 13)

The Workspace is a second view over the already-decrypted item index, not a second store: no new
payload, table, file or permission. It inherits the fail-closed visibility rules of the code
workspace/Library — `LockVisibility.isItemMasked` items and items inside hidden locked folders are
excluded from rows, filters, counts and search, so a locked title/body/snippet cannot leak through
this surface; opening a verified-locked item goes through the standard re-auth controller. Bulk
operations (move to folder, lock/unlock, move to trash) act only on the visible selected ids via
`moveItem`/`setItemLocked`/`trashItem`; a vault lock mid-operation makes repository calls fail
closed, the UI shows a non-secret error and refreshes, and selection is cleared. Search is an
offline substring query over decrypted titles/bodies/tags/file names/book authors only (no PDF/EPUB
content text extraction yet). The storage summary is computed from the decrypted index (counts +
attachment sizes), never from a filesystem walk, and never logs names/content.

### 3.9.4 Standalone sketches and the drawing studio (increment 12)

A `SKETCH_FILE` item stores its editable **vector** document (`SketchDocument`: template, strokes,
shapes) inside the item's AES-256-GCM envelope (`ItemCodec` payload v6 -> `SketchCodec` "NZSK"
v1). Geometry is normalised and quantised, bounded (≤2,000 strokes / ≤100,000 points / ≤500
shapes / ≤4 MiB) and the codec fails closed (null, never throws) on corruption/oversize; it is
repository-owned like annotations/reading state (`saveSketch` only; `saveItem` preserves; empty
clears), never a plaintext column, unavailable while locked, and carried by backup v2.

The studio renders the document on a Compose canvas and produces the Library preview by
rendering a bounded PNG **in memory** and importing it through the existing encrypted attachment
pipeline (`image/png`, ≤10 MiB, same NZAT/AAD/quota rules). No plaintext bitmap/PNG file or cache
is ever written; the replace-preview path deletes the previous attachment after the new one is
imported. Rendering input is bounded by the model/codec, exported previews are capped in
resolution, and no sketch content is logged. The lasso/eraser operate on the in-memory vector and
are undoable; no new permissions or dependencies are introduced.

### 3.9.3 PDF annotation layer and flattened export (increment 11)

**Annotation layer as content.** Ink/highlighter strokes, rectangle highlights and sticky notes
are stored inside the item's AES-256-GCM envelope (`ItemCodec` payload v5 -> `PdfAnnotationCodec`
"NZPA" v1). The geometry is normalised and quantised, so it is resolution-independent and bounded
(<=2,000 strokes, <=100,000 points, <=1,000 highlights, <=500 notes, <=4 MiB). The codec is
fuzz-tested and fails closed (null) on truncation, out-of-range counts/indices, trailing bytes or
oversized blobs; the model constructor validates the same bounds before anything is encoded. It is
repository-owned exactly like reading state and attachments (`savePdfAnnotations` only; `saveItem`
preserves it; null/empty clears), never appears in a plaintext Room column, is unreadable while
locked, travels inside backup v2, and annotation text is never logged.

**Rendering/export boundary.** Annotations are drawn as a Compose canvas overlay from normalised
coordinates; nothing is written to disk. The **flattened annotated copy** is the only new
plaintext-egress path: it is reachable only through the existing explicit UNENCRYPTED warning and
SAF `CreateDocument`, renders in memory via `android.graphics.pdf` (no temp file, page bitmaps
already bounded by the viewer), re-checks the vault is unlocked/owned before writing, and drops
pending bytes on lock. It is a rasterised copy (text not selectable, sticky-note markers only) —
documented in the UI copy and `docs/PDF-ANNOTATION-UI.md`. The original encrypted PDF bytes are
never mutated by annotation or export; page operations (increment 16) are a separate
flatten-and-copy flow (§3.9.7), and text-object editing remains out of scope.

**Bounds/robustness.** The exporter returns null (never throws) for empty/mismatched input,
recycled bitmaps, platform errors or OOM, hard-fails above 32 pages instead of silently truncating,
and ignores annotations on pages that were not rendered. Annotate mode is explicit (scroll locked,
Back exits the mode first) so a drawing gesture can never trigger an accidental navigation/lock
side effect; the tool palette uses design tokens only.

### 3.9.2 Books, readers and encrypted reading state (increment 10)

**EPUB as attachments.** A `BOOK_FILE` item is the same `NZAT` blob class as a PDF or an image:
the EPUB bytes are encrypted under the VMK with AAD bound to item id + kind, and the item title/
author/language/bookmarks live inside the item envelope (`ItemCodec` v4). No plaintext cover,
thumbnail or extracted chapter is ever written to disk: the EPUB is parsed in memory when the
reader opens and `EpubDocument.close()` drops the archive bytes; decoded images live only in the
compose state and are not cached.

**Parser attack surface.** EPUB is a ZIP of XML from an untrusted file, so the engine is written
fail-closed: bounded entry count/size/total, path traversal rejected, duplicate names first-wins,
truncated archives rejected, XML external entities/DTDs disabled (XXE), no script/style/HTML
execution, no CSS/network references, no `WebView`, no external resolver. Any malformed required
part returns `null` (clean error, never a partial book); DRM (`META-INF/encryption.xml` /
`rights.xml`) is refused with a non-secret message and no circumvention attempt. ZIP/XML caps and
the 1,000-sample malformed-input test live in `book/` and `docs/EPUB-ENGINE.md`; the parser is
pure JVM code with no Android I/O beyond the bytes handed to it.

**Reading state.** Position, fraction, total, bookmarks and book author/language are **content**:
they live inside the GCM item envelope (payload v4), are repository-owned like attachments and
the lock flag, and are never present in a plaintext Room column. A stale reader autosave cannot
roll them back; a locked vault cannot read or write them (`saveReadingState` returns false).
They travel with backup v2 inside the same ciphertext.

**Library and import.** The shelf lists only decrypted, unlocked documents; locked+unverified
items are filtered exactly like the code workspace. Import goes through SAF, is bracketed with
the external-activity API, re-checks the vault is unlocked after the picker returns, and rejects
unsupported/DRM/corrupt files by removing the placeholder item and showing a static message.
Neither the picker response nor any title/URI is logged. The MIME filter is deliberately generic
(`*/*`) because providers report EPUB types inconsistently; validation is the parser/viewer, not
the picker.

**Reader settings.** Font size/line spacing/margins/theme are non-secret preferences in
app-private SharedPreferences (clamped); they contain no document content.

### 3.9.1 Attachment boundaries

| Attack | Mitigation | Residual |
|---|---|---|
| Swap/substitute a blob between notes, attachment ids or vaults | Per-blob AES-256-GCM under the VMK with AAD `CryptoAad.attachment(noteId, attachmentId)`; a moved blob fails authentication and is counted/skipped (`AttachmentBlob.decode`, tests: `AttachmentBlobTest`) | An attacker with the VMK can rewrite content; AAD still binds it to the id (no cross-note substitution) |
| Roll back a blob to an older valid ciphertext | Each ref stores the SHA-256 and exact plaintext size; both are re-checked on every read (`attachmentBytesLocked`) | A rollback to the *same* bytes with the same AAD+size+SHA is byte-identical content and is not detected (documented ATTACHMENTS §9) |
| Force huge allocations with a hostile blob/large file | 10 MiB per file enforced while streaming (`readAtMost` never buffers the over-limit byte), 100/note, 512 MiB vault quota, 64 MiB backup stream cap; the reader re-checks size/SHA before exposing bytes | Peak heap on backup import/export is several times the stream cap; OOM is now caught at the repository boundary (returns `false`/`null`) instead of crashing (audit F9) |
| Path traversal through a crafted attachment id | Ids are generated randomly and re-validated (`[A-Za-z0-9_-]{1,64}`) before any file path is built; no user-supplied path reaches the filesystem | A crafted backup can carry ids that are rejected (skipped+counted) |
| Restore duplicates / ref growth | Import dedupes by attachment id, re-encrypts under the local VMK and only then commits the ref; a failed import rolls the blob back | Orphan blobs from a crashed restore are swept on unlock unless a row failed to decode |
| Read a blob's name/size/MIME from disk | Ref metadata lives inside the item envelope; blob files carry only magic/version/ciphertext with a random name | Existence/count/approximate size/mtime are visible (A9) |

## 4. In scope vs out of scope

**In scope (this model covers, Phase 1):** at-rest encryption of notes/metadata; password
and recovery key envelopes; VMK handling in memory; biometric gating and invalidation;
envelope/backup integrity and rollback resistance of individual records; offline-only
guarantees; screenshots/clipboard of secrets; fail-closed parsing of corrupted data.

**Out of scope for Phase 1:** compromised OS/firmware with kernel or TEE compromise;
physical attacks (cold boot, chip-off, evil maid), power/EM side channels; malicious
accessibility services; camera-based observation; coercion/"$5 wrench"; multi-user or
shared-device vaults; PDF ink/annotation editing and true PDF text-object editing (planned,
not shipped); MOBI/AZW3/KFX parsing or DRM circumvention (not shipped); code editor LSP/AI
(none exists by design); cloud sync, OPDS or online book catalogues (none exist by design).

**Attachments are no longer an unscoped Phase-4 item**: blob storage, quotas, AAD binding,
SHA-256-on-read, path-traversal guards and backup restore shipped in Phase 4/5. Their asset
(A12/A13), adversary rows (§3.9) and residual risks are part of this model, with the
detailed per-blob design in `docs/ATTACHMENTS.md` §9 (audit D4).

---

## 5. Security-relevant invariants and where they are enforced/tested

| Invariant | Enforcement | Test |
|---|---|---|
| No plaintext secrets at rest or in logs | `NoteCrypto`/`Bip39Recovery` write nothing; envelopes carry ciphertext only; logging rule in CONTRACTS | code review + `OfflineGuaranteeTest` (Lead) |
| Every content blob bound to id+kind | `CryptoAad.item`, used by data layer | `NoteCryptoTest.item ciphertext cannot be swapped...` |
| Wrong password never throws a distinguishable error or returns data | `unwrapVmk` → null on any failure | `NoteCryptoTest.unwrapVmk fails...` |
| Tamper anywhere ⇒ null | GCM tag verification, strict envelope parser | `NoteCryptoTest`, `KeyEnvelopeTest` |
| VMK is random 32 bytes, never derived | `randomVmk`, `wrapVmk` length checks | `NoteCryptoTest.wrapVmk...` |
| Recovery phrase is 128-bit BIP-39 with checksum | `Bip39Recovery` | `Bip39RecoveryTest` (official vectors) |
| Biometric unwrap requires hardware auth per use | Keystore key params in `BiometricKeyManager.buildKeySpec` | `BiometricKeyManagerTest` (androidTest) |
| Re-enrolment kills the wrap | `setInvalidatedByBiometricEnrollment(true)` + `disable()` on invalidation | `BiometricKeyManagerTest` / manual device test |
| No network path | manifest/removal/CI script, no network imports | `OfflineGuaranteeTest` (Lead) |
| KDF work factor floor on read | `KeyEnvelope.fromBytes` bounds | `KeyEnvelopeTest` |
| Lock state is content, invisible at rest | `ItemCodec` v3 / `FolderCodec` v2 trailing lock byte inside the AEAD envelope; no lock column | `LockedItemsRepositoryTest.lockFlagLivesOnlyInsideTheAuthenticatedEnvelope`, `LockedPayloadCodecTest` |
| Lock verifications never outlive the session | memory-only `verifiedLockedIds`; cleared by `lock()`/`close()`/background lock/session start; `markLockVerified` ignored while locked | `LockedItemsRepositoryTest.verificationsClearOnLockUnlockAndCloseAndAreNeverPersisted`, `backgroundLockClearsVerificationsButPickerDeferralDoesNot` |
| Lock re-auth cannot be brute-forced cheaply | rate limiter on the injected clock (growing 1–4 s delay, 30 s cooldown after 5), constant-time VMK comparison, wiped `CharArray` | `LockedItemsRepositoryTest.verifyMasterPassword*` |
| Decrypted editor content does not outlive the session | `EditorViewModel`/`CodeEditorViewModel` (Activity-scoped) observe `status` + `verifiedLockedIds` and wipe the draft, file name, undo/redo stacks and find state on lock or re-arm; a new session reloads from the index | `EditorViewModelTest.lock while the editor is open drops the draft and the undo history`, `CodeEditorViewModelTest.lock while the code editor is open drops the source and the undo history` |
| Auto-lock cannot crash the UI | UI mutation coroutines convert the `IllegalStateException` of a lost lock race into non-secret refresh/cleared state | `NotesViewModelTest.a mutation that loses the auto-lock race…`, `CodeWorkspaceViewModelTest.a file mutation that loses the auto-lock race…` |
| Locked code files cannot be opened through the editor | `CodeEditorScreen` gate before the ViewModel (defence in depth behind the workspace filter) | `CodeEditorLockedGateTest` |
| Reading state (position/bookmarks/book metadata) is encrypted content only | `ItemCodec` payload v4 inside the item AEAD; repository-owned; no plaintext column | `PayloadCodecTest`, `EncryptedNoteRepositoryTest.readingStateIsPersistedEncryptedAndRepositoryOwned` |
| A locked vault cannot read or write reading state | `saveReadingState` returns false while locked; readers drop state on lock | `EncryptedNoteRepositoryTest`, reader UI lock-reset tests |
| A malformed/hostile EPUB cannot crash or execute code | pure bounded ZIP/XML parser, XXE-off, no WebView/scripts, DRM refused, fuzz tests | `book.EpubSecurityTest`, `EpubLimitsTest`, `Epub3ParserTest`/`Epub2ParserTest` |
| No plaintext book covers/chapters/thumbnails on disk | parse/decrypt in memory, no cache writes; `EpubDocument.close()` on dispose | code review + `EpubDocumentLifecycleTest`, reader dispose path |
| PDF annotation layer is encrypted content only | `ItemCodec` v5 + `PdfAnnotationCodec` inside the AEAD; repository-owned; no plaintext column | `PdfAnnotationCodecTest`, `PayloadCodecTest`, `EncryptedNoteRepositoryTest.pdfAnnotationsArePersistedEncryptedAndRepositoryOwned` |
| A locked vault cannot read/write annotations | `savePdfAnnotations` false while locked; readers drop state on lock | repository tests + UI lock-reset tests |
| Hostile annotation payloads cannot crash/allocate unbounded | model/codec bounds, 1,000-sample fuzz, codec null-on-corruption, exporter null-on-error and 32-page cap | `PdfAnnotationCodecTest`, `PdfFlattenedExportInstrumentedTest` |
| Flattened PDF export is the same explicit plaintext-egress class as MD/HTML/PDF export | UNENCRYPTED warning + SAF CreateDocument only; in-memory render; vault re-check; no temp file; page bytes bounded | `ui.pdf` export tests + device QA (`pdfinfo` A4 3 pages) |
| Standalone sketches are encrypted vector content only | `ItemCodec` v6 + `SketchCodec` inside the AEAD; repository-owned; no plaintext column/cache | `SketchCodecTest`, `PayloadCodecTest`, repository sketch tests |
| A locked vault cannot read/write sketches | `saveSketch` false while locked; studio drops state on lock | repository tests + studio lock-reset tests |
| Hostile sketch payloads cannot crash/allocate unbounded | model/codec bounds, 1,000-sample fuzz, null-on-corruption; bounded preview renderer | `SketchCodecTest`, instrumented renderer test |
| Sketch previews never exist as plaintext files | in-memory bitmap/PNG only, imported through the NZAT pipeline; previous preview deleted | code review + attachment tests |
| Workspace surfaces cannot leak locked/hidden content | same `LockVisibility` masking as code/Library; search/counts built from visible items only; re-auth on open | workspace helper + Robolectric tests |
| Bulk workspace operations fail closed on lock | only visible selected ids; repository false/status handled; non-secret error + refresh | workspace tests |
| Workspace adds no storage/permission surface | view over the existing decrypted index; no new payload/table/permission/dependency | code review + offline guard |
| Hostile TXT/FB2/CBZ files cannot crash or execute code | pure bounded parsers, XXE/DTD off, DRM/unsupported → null, fuzz tests; no WebView/scripts | `book.*` format tests, `docs/BOOK-FORMATS.md` |
| Comic pages never exist as plaintext files outside the vault | page bytes decrypted in memory, decoded/recycled per page; no temp/cache writes | code review + comic reader tests |
| New e-book formats add no storage/permission surface | `BOOK_FILE` reused; format detected from bytes; no new kind/payload/permission | code review + offline guard |

---

## 6. Residual risks (accepted, with rationale)

- **R7 — transient plaintext PDF while rendering.** The platform `PdfRenderer` needs a
  seekable fd, so decrypted PDF bytes exist briefly in an app-private cache file during
  rasterisation (deleted in `finally`; cache excluded from backup). A flash-recovery attacker
  active in that window could read a page that is already visible on screen. Accepted under the
  same assumption as screen privacy (device unlocked, attacker at the device) and documented in
  §3.9; no other code path writes plaintext to disk.


1. **VMK in RAM while unlocked** — inherent to an offline vault that must decrypt content
   on unlock. Mitigated by auto-lock, background/screen-off lock, and lock-on-task-switch
   (UI/Lead), not by crypto. A root-level attacker present during unlock wins; documented.
2. **PBKDF2 is GPU-friendly** — 600k iterations raises the cost but does not make weak
   passwords strong. Argon2id is the reserved upgrade; password strength feedback (UI) is
   required, and recovery phrases are 128-bit random so they are not affected.
3. **Metadata leakage (A9)** — vault existence, envelope parameters, approximate item count
   and access timestamps are unavoidable without padding/traffic analysis defences;
   decision #10 already accepts an in-memory search index and no blind index.
4. **User-held recovery phrase** — physical compromise of the written phrase bypasses all
   cryptography; the app can only warn and show it once.
5. **OEM Keystore variance** — some devices have no StrongBox or buggy auth-per-use keys;
   the manager falls back securely (TEE) or to password unlock; device tests skip when
   unsupported.
6. **Rollback of whole app-data backups** — a restored old backup is indistinguishable from
   a legitimate restore; users must recognise that restoring an old backup rolls back
   content (documented in the backup UX, data-layer).
7. **Locked notes are a visibility gate, not encryption (A11)** — while the vault is
   unlocked, a locked note's plaintext exists in RAM and is decryptable with the VMK, so
   the lock only defeats shoulder-surfing/handoff scenarios. It is re-armed on every
   background/screen-off/lock and verification is never persisted; the residual risk is
   an already-compromised unlocked process or an attacker holding the VMK/DB file, both
   covered by the accepted VMK-in-RAM risk (1).

---

## 7. Reporting security issues

See `SECURITY.md` (Lead-owned) for the disclosure process. Do not file public issues for
vulnerabilities. No accounts, no telemetry: a report is the only channel, keep it offline
and encrypted where possible.
