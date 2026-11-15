# NoteZero — confirmed decisions (2026-11-15)

Recorded from the SPEC's "Open decisions for the first session" plus the product
owner's directives. Everything here is frozen unless the owner explicitly reopens it.

| # | Decision | Choice | Rationale |
|---|----------|--------|-----------|
| 1 | App ID / repo | `com.cipherwell.notezero`; GitHub `cipherwell/NoteZero`, GitLab mirror `cipherwell/NoteZero` | Owner-confirmed. Public repo starts the F-Droid 3-month clock. MIT license. |
| 2 | Rich-text engine | **Custom Compose block model** (`model/Block.kt`), Markdown canonical | Zero third-party editor code; fully auditable; export-friendly. |
| 3 | Attachments / camera | **Attachments phase 4 (decision 14); camera still out of scope** | Keeps the manifest camera-free; attachments are imported through the SAF picker, so no storage/camera permission is needed. |
| 4 | Code editor engine | **Compose-native editor + pure-Kotlin lexers** | Offline, auditable, no native/network blobs. Decide nothing that needs a third-party editor before phase 3. |
| 5 | Notes vs code files | **Single item model, type-tagged** (`ItemKind.NOTE` / `CODE_FILE`) | One envelope, one folder tree, one search; UI renders by kind. |
| 6 | Signing key | **Separate NoteZero upload key**, independent of VaultZero | Generated 2026-11-15 at `~/.cipherwell-release/notezero-upload.jks` (SHA-256 `FA:CA:DD:67:B2:82:AF:A5:E7:F4:C9:C4:70:61:71:80:C9:EF:9E:2D:12:39:AD:F1:0C:29:C4:F9:95:AA:92:78`). Never committed, never shared with VaultZero. |
| 7 | Network | **No INTERNET / ACCESS_NETWORK_STATE permission, ever** | SPEC non-goal; enforced by `tools:node="remove"`, `scripts/check-offline.sh`, and `OfflineGuaranteeTest`. |
| 8 | Vault key hierarchy | Random 32-byte VMK; password and recovery phrase wrap the VMK; notes are encrypted under the VMK | Password change / recovery rotate wrappers only — notes are never re-encrypted. |
| 9 | Storage encryption | Field-level AES-256-GCM envelopes in Room (no SQLCipher) | Same bar as VaultZero; ciphertext + redacted structural columns only; auditable in-repo format. |
| 10 | Search | In-memory decrypted index after unlock (title/tags/folder); no blind-index table | No equality leaks on disk; MVP corpus is small and indexed in RAM while unlocked. |
| 11 | Biometric prompt API | Platform `android.hardware.biometrics.BiometricPrompt` (**Android 9 / API 28+**); `androidx.biometric` used only for availability checks | androidx.biometric 1.2.0-alpha05's prompt requires `FragmentActivity`; the only fragment runtime on the classpath is the legacy 1.2.5 transitive, which we refuse to put in the launch path. Consequence: on API 24–27 the biometric CTA is hidden and password/recovery unlock is used; the Keystore per-use CryptoObject contract is unchanged on 28+. Revisit if a modern Fragment runtime is adopted. |
| 12 | Recovery-reset semantics | `resetWithRecoveryPhrase()` re-wraps the SAME phrase under a fresh salt; explicit phrase rotation is `rotateRecoveryPhrase(currentPassword)` | Frozen interface returns no new phrase from reset; silently rotating would strand users who wrote the old phrase. Rotation therefore stays a settings action that shows the new phrase once. |
| 13 | External-activity lock deferral (SAF backup picker) | `beginExternalActivity()` / `endExternalActivity()` on `NoteRepository` bracket the system picker. While one is active, the background caused by the picker does NOT lock the vault; `onAppBackgrounded` records the pending background state instead and `endExternalActivity` locks immediately if the app is still backgrounded. The idle auto-lock timer is never cancelled, so a picker left open past `autoLockMinutes` still locks — there is no permanent suppression. A foreground return also clears the deferral state, so a lost picker result cannot weaken later background locks. | The SAF picker is a separate Activity: without the bracket every export/import would stop the app, lock the vault and lose the user's chosen `content://` URI. The secure default (immediate background lock) is preserved for every non-picker exit; only a bounded, recorded exception exists, and idle timeout remains the backstop. |
| 14 | Attachment storage, AAD, quota & backup | **App-private `NZAT` blobs** in `filesDir/attachments/<attachmentId>.nzb`: magic `NZAT` + version `0x01` + AES-256-GCM (`iv||ct||tag`) under the vault VMK with AAD `CryptoAad.attachment(noteId, attachmentId)` = `"notezero.attachment.v1|<noteId>|<attachmentId>"`. Refs (random id, display name, MIME, plaintext size, createdAt, lowercase-hex SHA-256) are content and live **inside** the v2 item envelope; `ItemCodec` still decodes v1 rows (empty list). Limits: 10 MiB/file (enforced while streaming, source size not trusted), 100/note, 512 MiB vault quota; count and byte quota both surface as `QUOTA_EXCEEDED`. Reads verify AEAD + exact size + SHA-256 and fail closed counted; imports are atomic (ciphertext temp+rename, ref added only after the blob; rollback deletes the blob); trash keeps blobs, delete-forever/empty-trash removes them, an unlock-time sweep deletes unreferenced blobs/temp files but is skipped entirely when any row failed to decode. Blob ids are validated (`[A-Za-z0-9_-]{1,64}`) before any file path is built (path-traversal guard). Backup format v2 carries refs + blobs, each blob still individually sealed under the source VMK/AAD; restore verifies SHA-256 against the ref, re-encrypts under the local VMK, dedupes by attachment id, skips+counts missing/invalid blobs without failing the note, and still imports v1 files. The v2 backup keeps the existing 64 MiB file cap as its OOM guard (attachment bytes count toward it; over-cap export fails cleanly, no partial file). | No new dependency, no plaintext/thumbnail/name on disk or in logs, no new permission; swapping blobs between notes/ids/vaults fails authentication; a single-process vault cannot rely on SQLCipher-style whole-file encryption, so each blob gets its own AEAD envelope like the item rows. Requiring SHA-256-on-read catches restore/disk corruption that a valid AEAD of rolled-back bytes would not. Repository-owned refs make editor autosave races safe. |

## Ownership of build scaffolding

The repository scaffold, Gradle files, version catalog, manifest, signing config and
CI belong to the Lead. Teammates request changes; they do not edit these files.

## Deferred (explicitly not in this session)

- Attachments **storage/backup** landed in phase 4 (decision 14) with no camera permission and
  no new dependency; camera scan, drawing and tables UI remain out of scope.
- Rich attachment-capable backups over the 64 MiB single-file cap (chunked/streamed format v3).
- Code editor screens (phase 3) — only the shared item model lands now.
- F-Droid recipe submission (repo must be public for 3 months first).
- Argon2id migration (versioned KDF slot reserved in `KeyEnvelope`).
