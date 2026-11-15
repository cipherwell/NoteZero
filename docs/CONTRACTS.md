# NoteZero — module contracts & teammate ownership

Read this before writing code. The scaffold compiles; your job is to fill in one
disjoint slice without crossing into another teammate's files.

## Non-negotiable rules

1. **No new dependencies.** The version catalog (`gradle/libs.versions.toml`) and
   `app/build.gradle.kts` are owned by the Lead. Need one? Message the Lead with the
   exact coordinate and why; if approved, the Lead adds it and records it in
   `docs/DEPENDENCIES.md`.
2. **No network APIs, ever.** No `java.net`, okhttp, ktor, retrofit, urls in code.
   `scripts/check-offline.sh` runs in CI and will fail the build.
3. **No plaintext secrets on disk, in logs or in tests.** Never log titles/bodies,
   passwords, VMKs, phrases, keystore material. Diagnostics use ids/counts only.
4. **Crypto/parser logic stays pure JVM** (no `android.*` imports) so tests run
   with plain JUnit. Android-only code goes behind a small interface tested on device.
5. **Stay in your file scope** (below). Shared file collisions are the main way this
   parallel build breaks.
6. Build often: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:compileDebugKotlin`
   (or the gradle_run tool). Do not run `clean`. Do not `--stop` daemons.
7. The Lead runs the integration build and the emulator verification. Report what
   you ran and what it printed.

## File ownership

| Owner | Write scope |
|---|---|
| Lead | `build.gradle.kts`, `settings.gradle.kts`, `gradle/**`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/**`, `scripts/**`, `.github/**`, `docs/DECISIONS.md`, `docs/CONTRACTS.md`, `docs/BUILDING.md`, `docs/TESTING.md`, top-level README/SECURITY/LICENSE/CHANGELOG, `fastlane/**`, `fdroid/**` |
| crypto-core | `app/src/main/java/com/cipherwell/notezero/crypto/**`, `app/src/test/java/com/cipherwell/notezero/crypto/**`, `app/src/androidTest/java/com/cipherwell/notezero/crypto/**`, `docs/CRYPTO-SPEC.md`, `docs/THREAT-MODEL.md` |
| data-layer | `app/src/main/java/com/cipherwell/notezero/model/**`, `.../data/**`, `.../backup/**`, matching `test/**` packages, `docs/ARCHITECTURE.md`, `docs/DATA-MODEL.md`, `docs/BACKUP-FORMAT.md` |
| ui-shell | `app/src/main/java/com/cipherwell/notezero/MainActivity.kt`, `.../ui/theme/**`, `.../ui/screens/setup/**`, `.../screens/unlock/**`, `.../screens/recovery/**`, `.../screens/settings/**`, `.../ui/AppRoot.kt`, `.../ui/VaultViewModel.kt`, `.../ui/components/common/**` |
| ui-notes | `.../ui/notes/**`, `.../ui/editor/**`, ViewModels under those packages |

Files seeded by the Lead (`model/Item.kt`, `model/Block.kt`, `data/NoteRepository.kt`,
crypto contracts) are **owned by their package owner from handoff on** — extend them,
keep the public shape the UI compiles against, tell the Lead before changing a signature.

## Crypto contract (crypto-core implements; data-layer consumes)

```kotlin
NoteCrypto.randomVmk(): ByteArray                                   // 32 bytes
NoteCrypto.deriveKek(password: CharArray, salt: ByteArray, iterations: Int = 600_000): SecretKey
NoteCrypto.deriveKekFromPhrase(phrase: List<String>, salt: ByteArray, iterations: Int = 600_000): SecretKey
NoteCrypto.wrapVmk(vmk: ByteArray, kek: SecretKey, aad: ByteArray): ByteArray     // iv||ct||tag
NoteCrypto.unwrapVmk(wrapped: ByteArray, kek: SecretKey, aad: ByteArray): ByteArray?  // null on failure
NoteCrypto.encrypt(key: SecretKey, plaintext: ByteArray, aad: ByteArray): ByteArray
NoteCrypto.decrypt(key: SecretKey, blob: ByteArray, aad: ByteArray): ByteArray?
NoteCrypto.secretKey(vmk: ByteArray): SecretKey
Bip39Recovery.generatePhrase(): List<String>          // 12 unique words
Bip39Recovery.validatePhrase(phrase): Boolean
Bip39Recovery.phraseToEntropy(phrase): ByteArray?
KeyEnvelope(type, iterations, salt, wrappedVmk).toBytes() / KeyEnvelope.fromBytes(bytes)
BiometricKeyManager(context): canUseBiometrics / isEnrolled / prepareEnroll() / completeEnroll(enroll, vmk) / prepareDecrypt() / completeDecrypt / disable
```

AAD domain separation (`CryptoAad`):
- `VMK_WRAP = "notezero.vmk.v1"` — password-wrapped VMK
- `RECOVERY_WRAP = "notezero.recovery.v1"` — recovery-phrase-wrapped VMK
- `BACKUP = "notezero.backup.v1"` — backup header/envelope
- `item(id, kind) = "notezero.item.v1|<id>|<KIND>"` — per-item envelope

### Vault lifecycle (data-layer)

1. **Setup**: `vmk = randomVmk()`; password env = `KeyEnvelope(PASSWORD, iter, salt16, wrapVmk(vmk, deriveKek(password), VMK_WRAP))`;
   recovery env = `KeyEnvelope(RECOVERY, iter, salt16, wrapVmk(vmk, deriveKekFromPhrase(phrase), RECOVERY_WRAP))`;
   store both blobs + created_at in `vault_meta`. Return the phrase once.
2. **Unlock**: derive KEK, unwrap VMK, keep VMK **in memory only**, load + decrypt item index.
3. **Change password**: unwrap VMK with old env, wrap with new env, replace row. Notes untouched.
4. **Recovery reset**: derive recovery KEK, unwrap VMK, wrap under new password env, re-wrap the **same** recovery phrase under a fresh salt (decision #12 — no phrase rotation here; rotation is `rotateRecoveryPhrase(currentPassword)` from settings, which shows the new phrase once).
5. **Biometric**: enable is two-phase while unlocked — `prepareEnroll()` → BiometricPrompt
   (CryptoObject) → `completeEnroll(enroll, vmk)`; per-use-auth Keystore keys cannot encrypt without
   a prompt. Unlock is `prepareDecrypt()` → prompt → `completeDecrypt()`. Re-enrolment invalidation
   → wipe stale wrap, fall back to password.

## Data contract (data-layer implements `data.NoteRepository`)

`data/NoteRepository.kt` is the frozen UI-facing interface — read it there.
Key invariants:
- Room rows: `id` (random UUID text), `kind`, `created_at`, `updated_at`, `ciphertext` blob.
  All content (title/body/tags/folder/flags) lives *inside* the AES-GCM envelope; no
  searchable plaintext column.
- `items` / `folders` are `StateFlow`s of the decrypted in-memory index; empty when locked.
- Item envelope payload: binary, versioned, `ItemCodec` in `data/` — see `docs/DATA-MODEL.md`.
- Corrupt/tampered row → skipped + counted; never crashes the vault.
- All suspend entry points dispatch to `Dispatchers.IO` (main-safe).

## Model contract (data-layer owns; ui-notes consumes)

- `model/Item.kt`: `Item`, `Folder`, `ItemKind`, `ThemeMode`.
- `model/Block.kt`: `Block` sealed hierarchy + `BlockDocument` + `BlockMarkdownCodec`.
  Implement `MarkdownBlockCodec` (pure Kotlin) doing markdown ↔ blocks; never throw on
  user input; round-trip stable.
- Status/search/filtering helpers may live in `model/` with unit tests.

## UI contract (ui-shell + ui-notes)

- `ui-shell` builds `MainActivity` → `ui/AppRoot.kt`, which observes
  `NoteRepository.status` and routes: SETUP_REQUIRED → setup, LOCKED → unlock,
  UNLOCKED → notes. It owns the theme (`ui/theme/**`), the lock/setup/unlock/recovery/
  settings screens, and the app-level `VaultViewModel`.
- `ui-notes` builds `ui/notes/NotesListScreen.kt` (folders, tags, pins, favourites,
  search, recent, trash) and `ui/editor/NoteEditorScreen.kt` (markdown source editing,
  block preview via `MarkdownBlockCodec`, checklists, autosave, word count) plus their
  ViewModels. ui-notes may add widgets under `ui/notes/components/**`.
- UI gets a repository instance via `VaultViewModel`/a factory the Lead wires; do not
  construct `EncryptedNoteRepository` directly in composables.
- Accessibility: 48dp targets, content descriptions, WCAG 2.2 AA contrast, dynamic type.
- Design tokens: `ui/theme/Tokens.kt` is the single source of colour/spacing/shape/type.
  No hex literals outside it.
- FLAG_SECURE handling stays in `MainActivity` as seeded (Lead-owned behaviour via
  `BuildConfig.ALLOW_SCREENSHOTS`).

## Shared string resources

`app/src/main/res/**` is Lead-owned. UI strings you need: put them inline for now and
send the Lead a one-line list of new string names; the Lead batches res changes to avoid
merge churn. (This is temporary for the bootstrap session.)

## Definition of done per teammate

- Your code compiles: `./gradlew :app:compileDebugKotlin` (or `testDebugUnitTest`).
- Your unit tests pass and actually assert behaviour (no `assertTrue(true)`).
- No secrets/plaintext in logs; no network APIs; no new dependencies.
- Report to the Lead: files written, commands run, exact test counts, blockers.
