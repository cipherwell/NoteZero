# NoteZero — confirmed decisions (2026-11-15)

Recorded from the SPEC's "Open decisions for the first session" plus the product
owner's directives. Everything here is frozen unless the owner explicitly reopens it.

| # | Decision | Choice | Rationale |
|---|----------|--------|-----------|
| 1 | App ID / repo | `com.cipherwell.notezero`; GitHub `cipherwell/NoteZero`, GitLab mirror `cipherwell/NoteZero` | Owner-confirmed. Public repo starts the F-Droid 3-month clock. MIT license. |
| 2 | Rich-text engine | **Custom Compose block model** (`model/Block.kt`), Markdown canonical | Zero third-party editor code; fully auditable; export-friendly. |
| 3 | Attachments / camera | **Phase 4, out of MVP** | Keeps the manifest camera-free and the MVP shippable; camera permission is avoided entirely for now. |
| 4 | Code editor engine | **Compose-native editor + pure-Kotlin lexers** | Offline, auditable, no native/network blobs. Decide nothing that needs a third-party editor before phase 3. |
| 5 | Notes vs code files | **Single item model, type-tagged** (`ItemKind.NOTE` / `CODE_FILE`) | One envelope, one folder tree, one search; UI renders by kind. |
| 6 | Signing key | **Separate NoteZero upload key**, independent of VaultZero | Generated 2026-11-15 at `~/.cipherwell-release/notezero-upload.jks` (SHA-256 `FA:CA:DD:67:B2:82:AF:A5:E7:F4:C9:C4:70:61:71:80:C9:EF:9E:2D:12:39:AD:F1:0C:29:C4:F9:95:AA:92:78`). Never committed, never shared with VaultZero. |
| 7 | Network | **No INTERNET / ACCESS_NETWORK_STATE permission, ever** | SPEC non-goal; enforced by `tools:node="remove"`, `scripts/check-offline.sh`, and `OfflineGuaranteeTest`. |
| 8 | Vault key hierarchy | Random 32-byte VMK; password and recovery phrase wrap the VMK; notes are encrypted under the VMK | Password change / recovery rotate wrappers only — notes are never re-encrypted. |
| 9 | Storage encryption | Field-level AES-256-GCM envelopes in Room (no SQLCipher) | Same bar as VaultZero; ciphertext + redacted structural columns only; auditable in-repo format. |
| 10 | Search | In-memory decrypted index after unlock (title/tags/folder); no blind-index table | No equality leaks on disk; MVP corpus is small and indexed in RAM while unlocked. |

## Ownership of build scaffolding

The repository scaffold, Gradle files, version catalog, manifest, signing config and
CI belong to the Lead. Teammates request changes; they do not edit these files.

## Deferred (explicitly not in this session)

- Attachments, camera scan, drawing, tables UI (phase 4).
- Code editor screens (phase 3) — only the shared item model lands now.
- F-Droid recipe submission (repo must be public for 3 months first).
- Argon2id migration (versioned KDF slot reserved in `KeyEnvelope`).
