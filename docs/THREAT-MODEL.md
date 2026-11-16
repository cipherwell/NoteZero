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
shared-device vaults; drawing/handwriting (still out of scope); code editor LSP/AI (none
exists by design); cloud sync (none exists by design).

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
