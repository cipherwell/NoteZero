# NoteZero — Phase 5 security audit

**Date:** 2026-11-15 (17:17–17:35 AST / America/Puerto_Rico)
**Auditor:** security-audit subagent (read-only review)
**Target:** `cipherwell/NoteZero`, commit `e87f15c` ("phase 5: locked notes/folders +
reproducible release builds") plus the working tree as it existed at audit time.
**Report path:** `docs/AUDIT-2026-11-15-phase5.md`

---

## (a) Scope, method and limitations

### Scope

Reviewed the shipped implementation, not only the prose:

- `crypto/` — `NoteCrypto.kt`, `KeyEnvelope.kt`, `Bip39Recovery.kt`, `Encoding.kt`,
  `BiometricKeyManager.kt` (+ the Keystore-facing UI launcher in
  `ui/components/common/BiometricUnlock.kt`).
- `data/` and `backup/` — `EncryptedNoteRepository.kt`, `ItemCodec.kt`, `FolderCodec.kt`,
  `AttachmentBlob.kt`, `BinaryCodec.kt`, `BackupFormat.kt`, `Entities.kt`, `Daos.kt`,
  `NoteZeroDatabase.kt`, `VaultPreferences.kt`.
- `model/` and `code/` — `Item`, `Folder`, `ItemQueries`, `CodeSearch`, `CodeTree`, lexers
  (only for content-flow and DoS bounds).
- Session/UI logic — `MainActivity`, `NoteZeroApp`, `AppRoot`, `VaultViewModel`,
  `NotesViewModel`/`NotesIndex`/`NotesListScreen`/widgets, `EditorViewModel`/`NoteEditorScreen`,
  `ui/locks/**`, `ui/code/**`, `ui/attachments/**`, `ui/backup/**`, setup/unlock/recovery/settings.
- Build/offline surface — `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`,
  `res/xml/{backup_rules,data_extraction_rules,network_security_config}.xml`,
  `gradle/libs.versions.toml`, `gradle.properties`, `scripts/check-offline.sh`,
  `proguard-rules.pro`, CI workflows, release artifacts in `releases/`.
- Docs reconciliation — `SPEC.md`, `SECURITY.md`, `docs/{CRYPTO-SPEC,THREAT-MODEL,DECISIONS,
  ARCHITECTURE,DATA-MODEL,ATTACHMENTS,BACKUP-FORMAT,DEPENDENCIES,CONTRACTS*,TESTING,
  VERIFICATION-2026-11-15,REPRODUCIBLE-BUILDS}.md`.

Out of scope (as agreed): dynamic instrumentation, memory forensics, emulator/device runs,
fuzzing, review of third-party library internals, and UI copy changes made by the concurrent
string-extraction agent beyond the security-relevant strings quoted below.

### Method

1. Read the security requirements and design docs first, then followed each claimed control
   to the code that enforces it.
2. Adversarial code reading along the requested lines: IV/nonce handling, AAD coverage and
   domain separation, KDF bounds, envelope parsing, zeroization, biometric state machine,
   redaction/atomicity/rollback, lock-mask filtering (list, search, tags, counts, editor,
   code workspace), SAF/lock deferral, build/offline surface, dependency licences.
3. Verified the offline guarantee independently of the CI script:
   `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` (exit 0),
   `aapt2 dump badging` and `aapt2 dump permissions` on
   `releases/notezero-0.1.0-release.apk`, `apksigner verify --print-certs`,
   `scripts/check-offline.sh` (exit 0), and direct grep for network imports/URLs.
4. Mapped cached Maven POM licences for every resolved runtime coordinate.
5. Counted tests statically (`@Test` annotations) and cross-checked the documented counts.

### Limitations

- **No test suite was re-run.** The working tree was being edited by a concurrent UI
  string-extraction agent (many `ui/**` and `res/values/strings.xml` files were modified);
  running `testDebugUnitTest` would have compiled a changing tree and risked colliding with
  that work. Test counts below are static and agree with the handoff claims.
- No device/emulator was used. Keystore behaviour is assessed from code and the existing
  instrumented test; hardware variance is called out as residual risk.
- **Line numbers in `ui/**` and `res/values/strings.xml` are not stable** while the string
  extraction is in flight. Data/crypto/backup/model and build/manifest files were unmodified
  during the audit, so their line numbers are accurate. UI evidence is quoted verbatim to
  survive renumbering.
- Static review cannot prove memory residence, but the editor/ViewModel finding below is a
  direct consequence of Compose `viewModel()` scoping and the absence of any clear path; it
  is not speculative.

### Snapshot evidence

```
$ ./gradlew :app:dependencies --configuration releaseRuntimeClasspath   # exit 0
releaseRuntimeClasspath: 245 distinct coordinates (tree in /tmp/nz-deps.txt)

$ aapt2 dump badging releases/notezero-0.1.0-release.apk
package: name='com.cipherwell.notezero' versionCode='1' versionName='0.1.0'
uses-permission: name='android.permission.USE_BIOMETRIC'
uses-permission: name='com.cipherwell.notezero.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
targetSdkVersion:'36'

$ apksigner verify --print-certs releases/notezero-0.1.0-release.apk
Signer #1 certificate DN: CN=Cipherwell, O=Cipherwell, OU=NoteZero, C=US
Signer #1 certificate SHA-256: facadd67…9278   # matches SECURITY.md / DECISIONS #6

$ scripts/check-offline.sh
OK: NoteZero is offline by construction.

$ grep -c '@Test' app/src/test/**/*.kt (aggregate)  -> 740
$ grep -c '@Test' app/src/androidTest/**/*.kt      -> 10
```

No release manifest entry for `INTERNET`, `ACCESS_NETWORK_STATE`, `USE_FINGERPRINT`,
camera, storage or location. The only exported app component is the launcher `MainActivity`;
library components (`androidx.startup` provider, Room invalidation service,
profile-installer receiver) are `exported=false` or protected by the system
`android.permission.DUMP` permission.

---

## (b) Findings summary (ranked)

**No HIGH or CRITICAL finding.** The AEAD/AAD design, envelope parser, lock masking in the
list/search/code surfaces, backup authentication and the offline/permission surface held up
under adversarial reading. The findings below are correctness/defence-in-depth issues; the
two MEDIUM items are a decrypted-content lifetime problem and a misleading recovery-reset
security claim.

| ID | Severity | Area | Finding |
|---|---|---|---|
| F1 | **MEDIUM** | Session / editor | Activity-scoped editor ViewModels retain decrypted note/code plaintext after lock, background and re-mask; never cleared |
| F2 | **MEDIUM** | Recovery UX + docs | "Recovery reset generates a new recovery phrase" is false: reset keeps the same phrase; UI and CRYPTO-SPEC tell the user otherwise |
| F3 | **LOW** | Credential handling | Master password / new password / recovery-confirmation words are stored with `rememberSaveable`, unlike the deliberately-safe backup credential fields |
| F4 | **LOW** | Build surface | `-PallowScreenshots=true` and `-PdevDisableAutoLock=true` are `defaultConfig` build fields, so a release build can silently disable FLAG_SECURE / background+auto lock; docs claim debug-only/enforced |
| F5 | **LOW** | Recovery UX | Rotated recovery phrase stays in `VaultViewModel.settings.rotatedPhrase` after lock and is re-shown when Settings is reopened, without re-auth |
| F6 | **LOW** | Plaintext export | Code-editor "export decrypted copy" writes the draft after the vault has locked (no status check in the SAF result path) |
| F7 | **LOW** | Code workspace | `CodeEditorScreen` has no locked-content gate (unlike the notes editor); safety relies solely on the workspace filter |
| F8 | **INFO** | Biometric state machine | `completeBiometricUnlock`/`resetWithRecoveryPhrase` do not clear `verifiedLockedIds`/the re-auth limiter, contrary to "every unlock/setup starts empty"; not reachable from today's UI |
| F9 | **LOW** | Backup caps | Import/export peak heap is several multiples of the documented 64 MiB "OOM guard"; OOM is not caught (`Exception` only), so a hostile/large backup can crash the app |
| F10 | **LOW** | Auto-lock race | Notes/code mutation coroutines can throw an uncaught `IllegalStateException` if auto-lock wins the race after the UI's `isUnlocked()` check |
| I1 | INFO | Attachments | Blob read calls `file.readBytes()` before the `MAX_BLOB_BYTES` check; document says the size is bounds-checked on read |
| I2 | INFO | Attachments | `isSafeAttachmentId` accepts Unicode letters/digits, broader than the documented `[A-Za-z0-9_-]{1,64}`; no traversal, but byte-length is not capped |
| I3 | INFO | Zeroization | Codec/builder intermediate plaintext copies (and `ByteArrayOutputStream` buffers) outlive the explicit wipes; `Item`/snapshot strings are unwipeable |
| I4 | INFO | Latent search | `ItemQueries.matchesQuery/tagCounts` have no lock-hide predicate (unused today; DECISIONS #15 says callers must pre-filter) |
| I5 | INFO | Lock ids | `verifiedLockedIds` is not kind-scoped; a crafted backup can alias a note id and a folder id, so verifying one also reveals the other |
| I6 | INFO | Persisted enum | `ItemCodec` stores `ItemKind.ordinal`; inserting an enum value would make old rows fail closed and become unreadable (AAD/kind cross-check) |
| I7 | INFO | Destructive lock actions | Locked+unverified trashed notes can be restored/deleted forever (and empty-trash) without re-auth; confidentiality intact, availability only |
| I8 | INFO | SAF write mode | Attachment export falls back to `openOutputStream(uri)` without `"wt"`, so a provider that does not truncate can leave stale trailing bytes |

Doc/code mismatches (D1–D10) are listed separately in §(f); they are not counted as
vulnerabilities.

---

## (c) Findings — evidence, exploit scenario, minimal fix

### F1 — MEDIUM — Editor ViewModels retain decrypted content after lock/re-mask

**Component:** `ui/editor/EditorViewModel.kt`, `ui/editor/NoteEditorScreen.kt`,
`ui/code/CodeEditorViewModel.kt`, `ui/code/CodeEditorScreen.kt`,
`data/EncryptedNoteRepository.kt` (`lock()`).

**Evidence**

```kotlin
// NoteEditorScreen.kt:110-112
val viewModel: EditorViewModel = viewModel(
    key = "editor_$itemId",
    factory = EditorViewModelFactory(repository, itemId),
)
```

```kotlin
// CodeEditorScreen.kt:103-105
val viewModel: CodeEditorViewModel = viewModel(
    key = "code_editor_$itemId",
    factory = CodeEditorViewModelFactory(repository, itemId, preferences, initialLine),
)
```

There is no `LocalViewModelStoreOwner`/`NavHost` anywhere in `app/src/main/java`; the
default owner is the `ComponentActivity`, so these ViewModels live in the Activity's
`ViewModelStore` until the Activity is finished. Neither `EditorViewModel` nor
`CodeEditorViewModel` implements `onCleared`, observes `repository.status`/
`verifiedLockedIds`, or drops its state on lock:

```kotlin
// EditorViewModel.kt:99-111 (init loads the decrypted item unconditionally)
val item = repository.items.first().firstOrNull { it.id == itemId }
if (item == null) { ... } else {
    baseTitle = item.title
    baseBody = item.body
    _state.value = EditorState(loading = false, title = item.title, body = item.body, ...)
}
```

```kotlin
// EditorViewModel.kt:89 / CodeEditorViewModel.kt:117
private val undoStack = ArrayDeque<Snapshot>()   // up to 100 full title/body snapshots
```

`lock()` wipes the VMK and clears the repository index (correct), but does not touch the
Activity's ViewModelStore:

```kotlin
// EncryptedNoteRepository.kt:358-374
override suspend fun lock() = withContext(ioDispatcher) {
    mutex.withLock {
        autoLockJob?.cancel(); autoLockJob = null
        wipeVmkLocked()
        clearLockVerificationsInternal()
        resetVerificationRateLimit()
        itemsById.clear(); foldersById.clear()
        ...
```

**Scenario.** User edits a confidential note, goes back to the list, then backgrounds the
app (or the idle timer locks it). The vault is LOCKED and `items` is empty, but the last
note/code body, its last 100 undo snapshots and any find/replace state remain reachable in
the still-alive editor `ViewModel` objects. Anything with process-memory access afterwards
(root, a debuggable build, a heap dump / `run-as`, a memory-corruption bug) recovers plaintext
that the lock was supposed to have dropped. This defeats the documented data-lifetime
property ("Decrypted in-memory index/VMK … While unlocked", THREAT-MODEL §1 A8) and the
comment "dropped with the ViewModel" (EditorViewModel KDoc): the ViewModel is not dropped on
lock/back, only on Activity destruction. It is not a UI-lock bypass: the gate still prevents
loading a locked note, and a fresh composition cannot show the stale text.

**Minimal fix.** Have both editor ViewModels observe
`combine(repository.status, repository.verifiedLockedIds)` and, when the vault is not
`UNLOCKED` (or the item's id is no longer verified), replace `_state` with an empty
`EditorState`/`State`, clear `undoStack`/`redoStack`/find state and cancel the autosave job.
Alternatively clear the Activity `ViewModelStore` on `VaultStatus.LOCKED` (or key editor
ViewModels by a session token that changes on lock). Add a regression test that creates the
VM, locks, and asserts its state is empty.

---

### F2 — MEDIUM — Recovery reset claims a new phrase but keeps the old one

**Component:** recovery/setup UI copy, `docs/CRYPTO-SPEC.md`, repository behaviour (correct
as designed by DECISIONS #12).

**Evidence**

```xml
<!-- res/values/strings.xml:120 (moved from RecoveryScreen.kt by the string-extraction work) -->
<string name="recovery_new_password_subtitle">Your notes stay encrypted as they are. Only the
key wrappers change, and a new recovery phrase is generated.</string>
```

```kotlin
// RecoveryScreen.kt:47-49 (KDoc still says)
 * Step 1 validates the 12-word checksum through the repository, step 2 re-wraps the
 * vault key under a new master password and rotates the recovery kit.
```

The implementation deliberately does **not** generate a phrase:

```kotlin
// EncryptedNoteRepository.kt:460-463
// Re-wrap the recovery envelope under a FRESH salt with the SAME phrase.
// ... the frozen interface returns no new phrase here, and rotating to a phrase the user can
// never see would silently kill their only kit. Real phrase rotation is
// rotateRecoveryPhrase(currentPassword) ...
```

The test pins the same-phrase behaviour, so the doc/UI are contradicting a tested contract:

```kotlin
// app/src/test/.../data/EncryptedNoteRepositoryTest.kt (recoveryResetRewrapsAndUnlocks)
// The phrase the user already wrote down stays valid (frozen interface returns no
// new phrase from reset; real rotation is rotateRecoveryPhrase).
assertEquals(UnlockResult.SUCCESS, repo.resetWithRecoveryPhrase(phrase, NEW_PASSWORD.toCharArray()))
```

`docs/CRYPTO-SPEC.md:147` states the opposite: *"replace the recovery envelope and phrase.
Old phrase stops working."* `docs/CONTRACTS.md` lifecycle step 4 says the same. DECISIONS #12,
DATA-MODEL and ARCHITECTURE correctly describe the as-built behaviour.

**Scenario.** A user who forgot their master password recovers with the phrase. The recovery
screen tells them a new phrase was generated, but none is ever shown. If they conclude the
old written phrase is obsolete and destroy it, they lose their only recovery kit the next
time the password is lost. This is a user-data-loss path caused by misleading security copy,
not a cryptographic flaw. It also gives an auditor a false description of the recovery
lifecycle.

**Minimal fix.** Change the subtitle to e.g. *"Your existing 12-word recovery phrase keeps
working. Rotate it from Settings if you want a new one; the new phrase is shown once."*
Update `docs/CRYPTO-SPEC.md` §3 step 4 and `docs/CONTRACTS.md` lifecycle step 4 to match
DECISIONS #12, and point at `rotateRecoveryPhrase()` for rotation. Keep the existing test as
the pin.

---

### F3 — LOW — Credentials placed in saved instance state with `rememberSaveable`

**Component:** setup, unlock, recovery, settings (change-password and rotate-recovery) screens.

**Evidence**

```kotlin
// SetupScreen.kt:78-79
var password by rememberSaveable { mutableStateOf("") }
var confirmation by rememberSaveable { mutableStateOf("") }
// SetupScreen.kt:225  (3 of the 12 recovery words, typed by the user)
var words by rememberSaveable { mutableStateOf(List(entries) { "" }) }

// UnlockScreen.kt:60
var password by rememberSaveable { mutableStateOf("") }

// RecoveryScreen.kt:173-174
var password by rememberSaveable { mutableStateOf("") }
var confirmation by rememberSaveable { mutableStateOf("") }

// SettingsScreen.kt:380-382 and :483
var current by rememberSaveable { mutableStateOf("") }
var next by rememberSaveable { mutableStateOf("") }
var confirmation by rememberSaveable { mutableStateOf("") }
```

The backup flow deliberately avoids exactly this, documenting why:

```kotlin
// BackupSection.kt:94-95
// Secrets stay in plain `remember` on purpose: rememberSaveable can be written to the
// saved instance state, and a backup password / source phrase must not outlive the frame.
```

**Scenario.** `rememberSaveable` serialises the value into the Activity's saved-instance-state
`Bundle` (via Compose's `SaveableStateRegistry`). That bundle leaves the app process
(system_server) and survives process death; it is reachable in some `dumpsys`/bug-report
flows and can be persisted/dumped by OEM customisations. The password screen itself also
claims the input lives "only in this composable / a wiped CharArray"; here it can outlive
the composable and the process. Severity is LOW because this is not app-private storage and
the value is not written by NoteZero itself, but it is an avoidable deviation from the
project's own credential rule and contradicts its safer sibling.

**Minimal fix.** Use plain `remember { mutableStateOf("") }` for every credential field (as
`BackupSection` does) and clear it as soon as the `CharArray` is handed over; if state must
survive rotation, use a custom `Saver` that returns `null` on save. Add a unit/Robolectric
test (or lint rule) that no screen under `ui/screens/{setup,unlock,recovery,settings}` uses
`rememberSaveable` for a variable named `password`/`current`/`next`/`confirmation`, and for
the confirmation words.

Related, lower-impact: `NotesDialogs.kt:40` and `CodeDialogs.kt:50` use `rememberSaveable`
for dialog text (note title / file name content). Same mechanism, but the content is ordinary
decrypted data rather than a credential.

---

### F4 — LOW — Release builds can be produced with screenshots and auto-lock disabled

**Component:** `app/build.gradle.kts`, `MainActivity.kt`, `EncryptedNoteRepository.kt`.

**Evidence**

```kotlin
// app/build.gradle.kts:11 (defaultConfig), :23-32
defaultConfig {
  buildConfigField("boolean", "ALLOW_SCREENSHOTS",
      (project.findProperty("allowScreenshots") as String? ?: "false"))
  // Dev-only convenience for instrumented tests; release builds never pass the flag.
  buildConfigField("boolean", "DEV_DISABLE_AUTO_LOCK",
      (project.findProperty("devDisableAutoLock") as String? ?: "false"))
}
```

Both fields are in `defaultConfig`, so they apply to **release** as well. The properties are
opt-in, and the documented release/CI commands do not pass them, so the shipped release APK
is safe (verified: no `debuggable`, FLAG_SECURE code path default false). But nothing in the
build fails if someone builds a release with either property.

```kotlin
// MainActivity.kt:33-34
if (!BuildConfig.ALLOW_SCREENSHOTS) {
    window.setFlags(FLAG_SECURE, FLAG_SECURE)
}

// EncryptedNoteRepository.kt:93 / onAppBackgrounded()
private val allowAutoLock: Boolean = !BuildConfig.DEV_DISABLE_AUTO_LOCK
...
if (!allowAutoLock) return   // also skips clearLockVerificationsInternal()
```

**Scenario.** A maintainer or downstream builder runs `./gradlew assembleRelease
-PallowScreenshots=true` (copying the documented screenshot command but omitting
`assembleDebug`) or `-PdevDisableAutoLock=true` for a demo. The resulting signed release
leaks screenshots/recents of decrypted notes and also never locks on background or idle; the
latter additionally means `verifiedLockedIds` is never cleared by backgrounding. The threat
model asserts these are compile-time decisions enforced by the build (THREAT-MODEL §2.4) and
that screenshot builds are "debug-only" (VERIFICATION §4), which is not enforced.

**Minimal fix.** Move these `buildConfigField`s into `buildTypes.debug` (keeping production
constants in `release`), or make the release variant fail the build when either property is
set. Add a CI assertion on `aapt2`/BuildConfig for a release build. Correct the threat-model
and verification wording.

---

### F5 — LOW — Rotated recovery phrase is retained across lock and re-displayed

**Component:** `ui/VaultViewModel.kt`, `ui/screens/settings/SettingsScreen.kt`.

**Evidence**

```kotlin
// VaultViewModel.kt:601-602
_settings.update {
    it.copy(isWorking = false, rotatedPhrase = phrase, message = SettingsMessage.RECOVERY_ROTATED)
}
```

The status collector resets `_unlock`, `_recovery` and `_setup.phrase`, but never `_settings`:

```kotlin
// VaultViewModel.kt:198-227
repository.status.collect { status ->
    when (status) {
        UNLOCKED -> { _unlock.value = ...; _recovery.value = ... }
        LOCKED -> { _unlock.value = ...; _setup.update { ... phrase = null } }
        SETUP_REQUIRED -> { _unlock...; _setup...; _recovery... }
    }
}
```

```kotlin
// SettingsScreen.kt:315-321
val rotatedPhrase = settings.rotatedPhrase
if (rotatedPhrase != null) {
    RotatedPhraseDialog(words = rotatedPhrase, onAcknowledge = onAcknowledgeRotatedPhrase)
}
```

**Scenario.** The user rotates the recovery kit, sees the new 12-word phrase, and backgrounds
the app (or taps Home) without acknowledging. The vault locks, but the phrase remains in the
app-scoped `VaultViewModel`. When Settings is reopened later in the same process, the dialog
is shown again without re-authentication; the seed also stays in RAM after lock, contrary to
"shown once" and to the care taken to clear `SetupUiState.phrase` on lock.

**Minimal fix.** In the `LOCKED`/`SETUP_REQUIRED` branches, reset `_settings` (or at least
`rotatedPhrase = null`). If re-display after an accidental navigation is desired, re-gate it
behind `verifyMasterPassword` or an explicit "show again" action rather than holding it in
the app-scoped VM.

---

### F6 — LOW — Code-editor plaintext export survives a lock

**Component:** `ui/code/CodeExport.kt`.

**Evidence**

```kotlin
// CodeExport.kt:46-57
val pending: MutableState<String?> = remember { mutableStateOf(null) }
val launcher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument(CODE_EXPORT_MIME_TYPE),
    onResult = { uri: Uri? ->
        val content = pending.value
        pending.value = null
        if (uri != null && content != null) {
            writePlaintextCopy(context.contentResolver, uri, content)
        }
        repository.endExternalActivity()
    },
)
```

There is no `repository.status` check before `writePlaintextCopy`. `endExternalActivity()`
may lock the vault (deferred background), and the idle timer may have locked it while the
SAF picker was open, yet the full draft is still written to the user-chosen URI after that.
The attachment and backup paths do guard this (`AttachmentController.onExportTarget` checks
`isUnlocked()`; `BackupFlowController` checks and retains pending for resume).

**Scenario.** User taps "Export decrypted copy", leaves the system file picker open past
`autoLockMinutes` (the vault locks), returns and picks a location. The app writes plaintext
from the pre-lock session even though the vault is locked. An attacker who induces the
situation (or simply gets the device at the picker) obtains a decrypted source file after
lock. The plaintext was already user-authorised before the lock, so impact is limited; the
issue is the inconsistent "locked ⇒ no decrypted output" property.

**Minimal fix.** In `onResult`, only write when `repository.status.value ==
VaultStatus.UNLOCKED`; otherwise drop `pending` and report a locked/IO error (mirror
`AttachmentController.onExportTarget`). Also clear `pending` when the repository leaves
`UNLOCKED` (e.g. observe status in the composable).

---

### F7 — LOW — Code editor lacks a locked-content gate

**Component:** `ui/code/CodeEditorScreen.kt` vs `ui/editor/NoteEditorScreen.kt`.

**Evidence**

```kotlin
// NoteEditorScreen.kt:99-107 — the notes editor refuses to create its VM before re-auth
val lockedGate = items.firstOrNull { it.id == itemId }?.locked == true &&
    itemId !in verifiedLockedIds
if (lockedGate) {
    LockedNoteGate(onReveal = ..., onClose = onClose)
    ReauthDialog(reauth)
    return
}
```

```kotlin
// CodeEditorScreen.kt:103-105 — no locked/verified check anywhere in the file
val viewModel: CodeEditorViewModel = viewModel(
    key = "code_editor_$itemId",
    factory = CodeEditorViewModelFactory(repository, itemId, preferences, initialLine),
)
```

The workspace does filter locked+unverified code files and descendants before tree/counts/
search (`CodeWorkspaceViewModel.kt:83-112`, verified), so today the only in-app route to the
code editor is through that filter. DECISIONS #16 explicitly says the workspace "does not
host a second re-auth surface", so this is a deliberate consequence. The risk is that the
single filter is now the only barrier: any future entry point (restored `openFileId`,
deep-link, task restore, a test/preview that composes the editor directly) renders a locked
code file without the notes editor's defence in depth. A restored `openFileId` after process
death plus the AppRoot route effect is a narrow but real path (the route is reset by
`LaunchedEffect`, but the editor VM/init runs during that composition).

**Minimal fix.** Mirror the notes gate in `CodeEditorScreen` (or observe
`repository.verifiedLockedIds`/`status` in `CodeEditorViewModel` and refuse to load/clear
when the item is locked+unverified). Keep the workspace filter as the primary control.

---

### F8 — INFO — Biometric unlock/reset do not reset lock verification state

**Component:** `EncryptedNoteRepository.kt`.

**Evidence**

```kotlin
// completeBiometricUnlock — :626-668
if (vmk != null) {
    wipeVmkLocked(); itemsById.clear(); foldersById.clear()
    publishIndexLocked(); _vaultInfo.value = null
    _status.value = VaultStatus.LOCKED
}
...
adoptVmk(candidate); handedOff = true; loadIndexLocked(candidate); ...
_status.value = VaultStatus.UNLOCKED
```

No `clearLockVerificationsInternal()` and no `resetVerificationRateLimit()` in this path, and
the same is true of `resetWithRecoveryPhrase` (:432-523). By contrast `setupVault` (:274-275),
`unlock` (:347-348) and `lock()` (:366-367) do clear both. DATA-MODEL says "every new session
starts unverified" and THREAT-MODEL's invariant row says "cleared by … session start".

**Why INFO.** Today `VaultViewModel.prepareBiometricDecrypt()` and the recovery flow are only
reachable from the LOCKED UI, and reaching LOCKED always goes through `lock()` (which clears)
or process start (empty set), so the stale-set state is not reachable from the shipped UI. It
is still a state-machine/contract gap that becomes a real reveal if a future screen calls
`completeBiometricUnlock`/`resetWithRecoveryPhrase` while UNLOCKED (the API permits it). The
KDF limiter (memory-only) would also survive a session boundary in that case.

**Minimal fix.** Clear verifications and the verification rate-limit state before publishing
`UNLOCKED` in both paths, matching `unlock()`.

---

### F9 — LOW — Backup caps bound bytes, not peak memory; OOM is not caught

**Component:** `backup/BackupFormat.kt`, `EncryptedNoteRepository.exportBackup/importBackup`.

**Evidence**

```kotlin
// BackupFormat.kt:93 / :96 / :101
const val MAX_BACKUP_BYTES: Long = 64L * 1024 * 1024
const val MAX_ITEM_COUNT: Int = 500_000
val MAX_PAYLOAD_BYTES: Int = (MAX_BACKUP_BYTES - MAX_HEADER_BYTES).toInt()
```

`read()` materialises the whole file, then `decodeEnvelope` copies the payload, then GCM
decrypt makes a plaintext copy, then `decodePayload` copies every record and instantiates up
to 500 000 `Item`/`Folder` objects:

```kotlin
val bytes = readBounded(input)                          // ≤64 MiB
val payload = reader.bytes(payloadLength.toInt())       // +≤64 MiB
NoteCrypto.decrypt(...)                                 // +≤64 MiB plaintext
val items = ArrayList<Item>(itemCount.toInt())          // +object graph
repeat(itemCount.toInt()) { reader.lenPrefixedU32(...) } // per-record copies
```

Export is similar (`collectAttachmentBlobsLocked` holds all ciphertext blobs, then
`encodePayload` builds the plaintext payload, then `NoteCrypto.encrypt` builds the sealed
copy). `importBackup` catches `Exception`, not `OutOfMemoryError`; an OOM therefore escapes
and takes the process down.

**Scenario.** A malicious or merely large (tens of MiB) backup is imported on a low-RAM
device; peak heap is several times the stream cap plus an unbounded object count, so the app
crashes before the structural validation can reject anything. Same on export of a
high-attachment vault. Availability only; no confidentiality/integrity impact. The docs call
64 MiB an "OOM guard", which overstates the property.

**Minimal fix.** Stream the payload to a temp file and decode incrementally, or at minimum
(a) cap total decoded allocation/object count and (b) catch `OutOfMemoryError` at the
repository boundary and return a clean failure. Re-document the cap as a stream cap, not an
OOM guarantee, until the chunked v3 format lands.

---

### F10 — LOW — Auto-lock race can surface an uncaught exception from UI mutations

**Component:** `ui/notes/NotesViewModel.kt`, `ui/code/CodeWorkspaceViewModel.kt`.

**Evidence**

```kotlin
// NotesViewModel.kt:198-205
private fun mutate(block: suspend () -> Unit) {
    if (!isUnlocked()) return
    viewModelScope.launch {
        if (isUnlocked()) block()   // no try/catch
    }
}
```

```kotlin
// EncryptedNoteRepository.kt:2109-2110
private fun requireUnlocked(): ByteArray =
    vmk ?: throw IllegalStateException("Vault is locked")
```

`setPinned`, `setFavorite`, `moveItem`, `trashItem`, `restoreItem`, `deleteItemForever`,
`emptyTrash`, folder mutations and `saveItem` call `requireUnlocked()`. The `isUnlocked()`
check is outside the repository mutex; the idle/background lock can run after the check and
before the suspend call acquires the mutex, throwing on the coroutine's default
`CoroutineExceptionHandler` (crash). `EditorViewModel.flush()` wraps the same call in
`try/catch`, showing the intended pattern; the notes/code list mutators do not.

**Scenario.** A user taps "trash"/"pin" exactly as the idle timer or a screen-off lock fires;
the app crashes instead of reporting that the vault locked. DoS/robustness only; no content
leak, and ciphertext is unchanged.

**Minimal fix.** Wrap `block()` in `runCatching` and map a locked vault to a no-op/snackbar
(the repository already returns `false` for the lock-flag APIs). Add a deterministic test
with a repository whose `isUnlocked` flips between the check and the call.

---

### INFO observations

- **I1 — attachment read order.** `attachmentBytesLocked` reads the whole file before
  `AttachmentBlob.decode` checks `MAX_BLOB_BYTES` (`EncryptedNoteRepository.kt:1525-1535`,
  `AttachmentBlob.kt:62`). A blob file larger than the format max would be fully allocated
  before rejection. Normal import/restore cannot create such a file (both bounded), so this
  only matters for local corruption/root write; still, the ATTACHMENTS claim "on read: file
  size is bounds-checked against the format" is stronger than the code. Check
  `file.length()` against `MIN_BLOB_BYTES..MAX_BLOB_BYTES` before `readBytes()`.
- **I2 — attachment id validation broader than documented.** `isSafeAttachmentId`
  (`EncryptedNoteRepository.kt:1613-1615`) uses `Char.isLetterOrDigit()`, which accepts
  Unicode letters, while DECISIONS #14 and ATTACHMENTS §2 say `[A-Za-z0-9_-]{1,64}`. `/`,
  `\`, `.` and `:` are rejected, so this is not path traversal; the length is in UTF-16
  chars, so a 64-emoji id yields a >255-byte filename that fails at write (skipped, not
  exploited). Use an explicit ASCII check and a byte-length cap if the documented contract
  matters.
- **I3 — zeroization intermediates.** `persistItemLocked`/`encryptItemRow` wipe the
  `ItemCodec.encode` result, but `BinaryWriter`'s internal `ByteArrayOutputStream` grew from
  the same plaintext and is never wiped (`BinaryCodec.kt:19-20`); `toByteArray()` also makes
  a copy that is temporarily outside the wipe. Likewise `BackupFormat.encodePayload` leaves
  the full plaintext payload in the writer. `Item`/`Snapshot` hold immutable `String`s that
  cannot be zeroed. The docs already say zeroization is best-effort, so this is a residual
  property, not a violation; adding a `BinaryWriter.wipe()`/`close()` and using
  `ByteArrayOutputStream.reset()` would narrow the window.
- **I4 — latent unguarded search helper.** `ItemQueries.matchesQuery/filter/tagCounts` do not
  take `verifiedLockedIds`; they are currently unused by the UI (`grep` found only
  `normaliseTags`), and DECISIONS #15 says "`ItemQueries`-based search is fed a pre-filtered
  list". A future caller could bypass the mask. Either add a hide predicate or document the
  pre-filter requirement at the function.
- **I5 — verification ids not kind-scoped.** `markLockVerified(id)` adds a bare id to one set
  for notes and folders. A crafted backup can import a note whose id equals a folder id (ids
  are arbitrary in the payload), so verifying one record also satisfies the other. This needs
  a crafted import and user action, and it reveals only records the user could verify anyway;
  prefix with `note:`/`folder:` for defence in depth.
- **I6 — persisted enum ordinal.** `ItemCodec.encode` writes `item.kind.ordinal`
  (`ItemCodec.kt:113`). Reordering/inserting `ItemKind` values would make old payloads decode
  to a different kind; the row/payload cross-check in `decodeItemRow` turns that into a
  skipped corrupt row rather than a wrong render, but it is irreversible data loss. Persist
  `kind.name` (or a stable numeric id) instead.
- **I7 — destructive actions on masked trashed notes.** `TrashRow` exposes Restore/Delete
  forever for masked (locked+unverified) rows (`NotesWidgets.kt:588-640`), and Empty trash
  deletes them without re-auth. This is availability-only (no plaintext is shown) and is
  consistent with "lock is not encryption", but it means a handoff attacker can destroy
  locked notes. Gate destructive actions on `isRevealed` if the product wants lock to protect
  against deletion too.
- **I8 — attachment export truncation.** `AttachmentViewerScreen` opens the SAF stream with
  `contentResolver.openOutputStream(uri)` (no `"wt"`), unlike `CodeExport`/backup which try
  `"wt"` first. With a provider that does not truncate, exporting into an existing document
  can leave trailing bytes from the previous content (corrupted export, not a leak).

---

## (d) No issue found (explicitly checked, with what was verified)

These areas were attacked and held; they are recorded so the reader knows the audit was not
limited to finding problems.

### Cryptography (`crypto/`)

- **Fresh IV per encryption.** `NoteCrypto.encrypt` always calls
  `sealWithIv(..., randomBytes(12))`; the caller-supplied-IV function is `internal` and has no
  production caller (`grep sealWithIv` only finds the definition and tests). No API publicly
  accepts an IV.
- **AAD coverage / domain separation.** `CryptoAad` constants are frozen and distinct:
  `notezero.vmk.v1`, `notezero.recovery.v1`, `notezero.backup.v1`,
  `notezero.item.v1|<id>|<kind>`, `notezero.attachment.v1|<noteId>|<attachmentId>`, plus a
  private biometric AAD. VMK wraps cannot cross password/recovery domains; item ciphertext
  cannot move between ids or `NOTE`/`CODE_FILE`; folders use kind `FOLDER`; attachment blobs
  cannot move between notes/ids; backup payload AAD covers the entire header including
  counts, mode, flags and embedded key envelopes. Test vectors cover id/kind swaps.
- **Envelope parsing.** `KeyEnvelope.fromBytes` rejects bad magic/type/version/KDF,
  saltLen ≠ 16, iterations outside `[10_000, 10_000_000]` (read and write), wrapped length
  `< 28`/`> 994`, trailing bytes and oversized input; returns `null`, never throws, never
  returns a partial object. Type is re-checked at every use site. `unwrapVmk` additionally
  rejects a successfully decrypted non-32-byte payload, so a same-domain ciphertext cannot
  masquerade as a VMK.
- **KDF bounds.** New envelopes use 600 000 iterations; the floor/ceiling is enforced on
  read and write. A 10 M-iteration hostile envelope is a bounded one-off CPU cost (documented
  residual), not unbounded work.
- **BIP-39.** Canonical 2048-word English list (size/duplicate assertions + published SHA),
  128-bit entropy, 4-bit checksum, NFKD lowercase canonical phrase, unique-word generation
  with a valid fallback, repeats accepted on validation for legacy compatibility. Phrase KDF
  is domain-separated by wrap AAD.
- **Biometric Keystore.** AES-256-GCM key in AndroidKeyStore, `setUserAuthenticationRequired`,
  per-use strong biometric (`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` on
  API 30+, `-1` legacy below), `setInvalidatedByBiometricEnrollment(true)`,
  `setUnlockedDeviceRequired` on API 28+, StrongBox preferred with TEE fallback. Two-phase
  enrol/decrypt; a prompt-free `doFinal` is refused by the TEE; stale/invalidated wraps are
  deleted by `prepareDecrypt`/`isEnrolled`; no prompt-free decrypt API exists; the wrapped VMK
  is ciphertext-only in private prefs and excluded from Android backup.
  `BiometricKeyManagerTest` (androidTest) covers prompt-free refusal and unauthenticated
  enrolment.
- **Zeroization on main paths.** Password char arrays are wiped on every repository path
  (including wrong/blank/corrupt and exception paths); VMK is wiped on `lock`/`close` and
  on failed index load; decrypted item/folder payloads are wiped after decode; plaintext item
  and backup payloads are wiped after encryption; the phrase char array is wiped. Residual
  copies are documented in I3 and the spec's best-effort caveat.

### Data layer, redaction and atomicity

- **Room redaction.** `items` carry only random id, kind, two structural timestamps and GCM
  ciphertext; `folders` only id/created_at/ciphertext; `vault_meta` only wrapped envelopes and
  non-secret metadata. `toString` redacts ciphertext/envelopes. There is no plaintext title,
  body, tag, folder-name, search-index or `locked` column. Structural columns are cross-checked
  against the authenticated payload (`id`, `kind`, `createdAt`, `updatedAt`) so a tampered
  plaintext column is treated as corrupt, not trusted.
- **Corrupt/tampered rows.** Decode failures are counted and skipped, never surfaced and never
  crash the index load. Attachments do not leave the repository as unauthenticated bytes.
- **Attachment storage.** `NZAT` v1 with magic/version, per-blob AES-GCM under the VMK,
  AAD bound to (noteId, attachmentId), SHA-256 of plaintext in the encrypted item payload and
  verified on every read together with exact size. Quotas are enforced while streaming
  (`readAtMost` never buffers the over-limit byte), before writing, and against the vault-wide
  total. Import is blob-temp+rename then ref; failure rolls the blob back. Blob ids are
  validated before path construction (no traversal). Orphan/temp sweep is skipped when any row
  failed to decode — I re-read `cleanupOrphanBlobsLocked` and the guard is present. Trash keeps
  blobs; delete-forever/empty-trash remove them; delete/restore do not touch refs otherwise.
  No thumbnails, names, MIME or sizes are written to disk; `AttachmentImages` decodes in memory
  with `inSampleSize` bounds.
- **Backup format and import.** Header (counts, mode, flags, exportedAt, embedded key
  envelopes) is authenticated as AAD; payload GCM-authenticated; exact stream consumption and
  count cross-checks; plaintext header carries only wrapped VMKs. Credential resolution is
  local VMK → backup-password envelope (BACKUP AAD) → source password (VMK_WRAP AAD) or
  source phrase (RECOVERY_WRAP AAD), with a 3-attempt UI cap. Import classifies against the
  live index and against earlier records in the file: duplicates (same id+updatedAt) counted,
  conflicts (same id, different updatedAt) skipped and never overwritten, repeated ids
  duplicated, only fully re-encoded/authenticated rows written, all rows in one Room
  transaction, blobs re-encrypted under the local VMK and renamed only after the commit.
  Cross-vault restore cannot read the source content without a credential; a wrong credential
  is indistinguishable from an invalid file at the repository boundary. v1 backups and
  ItemCodec v1/v2 rows remain readable.
- **Lock flag storage.** `ItemCodec` v3 / `FolderCodec` v2 trailing lock byte lives inside the
  GCM envelope; v1/v2 decode to `locked=false`; no plaintext column or side table; flipping
  the byte fails authentication. `saveItem` preserves the stored flag (`preserveLock = true`),
  only `setItemLocked`/`setFolderLocked`/restore can change it. Backups carry the flag inside
  the payload and never carry verification. A crafted backup cannot silently overwrite an
  existing row (conflict policy) or unlock a local note.
- **Index/VMK lifecycle.** `lock()` and `close()` wipe the VMK, clear the index, corrupt
  counters and verifications; `unlock()` on a live session tears the old session down first;
  `changePassword` rewraps only the password envelope (test asserts item ciphertext is
  byte-identical); password reset rewraps and keeps the recovered VMK; `rotateRecoveryPhrase`
  replaces only the recovery envelope and returns the new phrase once.

### Locked-content masking (list, search, tags, counts, folders, code)

- **Notes list.** Masked rows are built from `LockVisibility.maskItem`: title "Locked note",
  empty body, no tags, no folder path, no attachment refs. `NoteRow` additionally forces the
  preview empty for masked rows. `TrashRow` shows only the mask title/time. Sort order still
  uses timestamps (metadata, compared with A9).
- **Search and aggregates.** `NotesIndex` computes `revealedLive` (masked items excluded) and
  uses it for search results and `tags`; the search pool is the revealed set, so a query
  cannot match a locked title/body/tag/folder path, and the placeholder string is not indexed.
  The displayed list (`displayedLive`) is masked; tag filtering cannot match masked rows
  (they have no tags). `totalNotes`, trash count and per-folder counts are derived from
  filtered sets; hidden locked-folder descendants are excluded from content, search, tags,
  trash and folder counts. Masked folder rows show 0 item/child counts.
- **Folder semantics.** `hiddenFolderIds` is cycle-safe, includes every descendant of a
  locked+unverified folder, and is recomputed on every verification change. Entering a hidden
  folder is bounced to the root; the folder row itself stays visible as the re-auth entry
  point; `allFolders`/move targets exclude masked folders. Removing a folder lock while masked
  requires re-auth; rename/delete are hidden for masked folder rows; a masked note's
  content actions (pin/favourite/rename/move/trash) are hidden, and `NotesViewModel.renameItem`
  additionally guards on `isRevealed` as a belt-and-braces check.
- **Editor gate.** `NoteEditorScreen` checks `locked && id !in verifiedLockedIds` before
  creating the `EditorViewModel`; a masked note never loads title/body into editor state, and
  the tests (`EditorLockedGateTest`) cover gate/tap/re-auth/cancel/verified paths.
- **Code workspace.** `CodeWorkspaceViewModel` filters locked+unverified code files and all
  descendants of locked+unverified folders **before** tree, counts, move targets and
  `CodeSearch.search`; `CodeSearch` itself only searches `CODE_FILE` and non-trashed items;
  `lockedHiddenCount` is a count only. `CodeTree` filters on kind/trash. No locked title/body
  reaches the workspace search or counts.
- **Verification session semantics.** `verifiedLockedIds` is memory-only, never persisted,
  starts empty; `lock()`, `close()`, a real background lock and `endExternalActivity()` when it
  locks clear it; `onAppBackgrounded` clears before scheduling the lock; the SAF-picker
  deferral deliberately does not clear (decision #13) but the idle timer remains a backstop;
  `markLockVerified` refuses while locked or with no VMK; `verifyMasterPassword` compares the
  unwrapped VMK in constant time against the live VMK, rejects blank input without counting,
  applies the documented 1–4 s delays and 30 s cooldown, resets on success and wipes the array
  on every path. I traced the concurrent-lock path: if `lock()` wins, the verification returns
  false without recording a credential failure.
- **No search/tag/count leak found in the current UI.** The only unguarded helpers are unused
  (`ItemQueries`, I4) and the verification id namespace (I5).

### Session / UI / platform surface

- **FLAG_SECURE** is applied before `setContent` unless the build flag is set; release APK is
  not debuggable; no screenshot/recents path bypass was found; the recovery phrase screen is
  covered by the app-wide flag.
- **Clipboard.** The recovery phrase copy is behind an explicit warning, marked with
  `EXTRA_IS_SENSITIVE` (correct key on API 33+, backport extra below), auto-cleared after 60 s
  and on leaving the screen, with a manual clear action. Code copy uses the same sensitive
  marking. No other content-to-clipboard path was found.
- **Logging.** Zero `Log.*`, `println`, `printStackTrace`, `System.out/err` calls in
  `app/src/main/java`; no exception message is surfaced to the user (ViewModels map failures to
  enums/booleans); entity `toString` is redacted. A stray `Item.toString` would print content,
  but there is no log sink to reach it.
- **Lifecycle/locking.** `ON_STOP → onAppBackgrounded`, `ON_START → onAppForegrounded`; the
  repository owns the timer; background lock clears verifications before scheduling; the
  external-activity bracket is counted, bounded by the idle timer, settled on result/cancel/
  launch failure, and reset on foreground return if the picker result never arrives; a picker
  left open past the timeout locks; `endExternalActivity` while still backgrounded locks
  immediately. I checked the backup and attachment picker call sites for bracket leaks; both
  settle on launch failure.
- **No file exposure.** No `FileProvider`, `ContentProvider`, share intent, `Intent` with a
  data URI, or exported component other than the launcher. SAF is only used through
  user-initiated `CreateDocument`/`OpenDocument`; the only plaintext writes are the explicit
  attachment/code exports (F6/I8) and the encrypted backup. `HttpURLConnection`, `java.net`,
  `WebView`, `loadUrl`, sockets: none in app sources.

### Offline guarantee / build / supply chain

- Merged **release** manifest contains only `USE_BIOMETRIC` plus androidx.core's
  signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; the `tools:node="remove"` entries
  for `INTERNET`, `ACCESS_NETWORK_STATE`, `USE_FINGERPRINT` are effective in the release
  merged manifest and in the shipped APK (aapt2). `allowBackup=false`, both
  `dataExtractionRules` and `fullBackupContent` exclude database/sharedpref/file/external/root;
  cleartext traffic is false with a restrictive network-security-config.
- Library components: `androidx.startup.InitializationProvider` (exported=false), Room
  `MultiInstanceInvalidationService` (exported=false), profile-installer receiver
  (exported=true but guarded by `android.permission.DUMP`, a system permission). No third-party
  telemetry/network SDK is present in the resolved runtime graph.
- Release APK is signed with the expected certificate
  `FA:CA:DD:…:92:78` and is not debuggable; the debug keystore is gitignored and CI generates
  it; release signing material comes only from environment variables and the build stays
  unsigned when absent. `.github` secret-scan rejects committed keystores.
- `scripts/check-offline.sh` passes for source and the merged debug manifest; CI runs it with
  `--gradle` against `debugRuntimeClasspath`, and the release manifest/APK were checked
  independently here. Note the script's merged-manifest step only inspects the first
  `merged*` manifest it finds (debug); a release-specific network dependency would not be
  caught by that step alone. `aapt2`/`apksigner` evidence above closes it for this build.
- No plaintext URLs in app sources; `proguard-rules.pro` has `-dontwarn java.net.**` and an
  `-assumenosideeffects` rule on `URLConnection.connect()` (a belt-and-braces oddity, not a
  network path); R8 minification is on for release.

### Dependencies and licences

- Runtime graph: AndroidX (Compose, Lifecycle, Activity, Room, DataStore, Biometric, Core,
  Navigation), Kotlin stdlib/coroutines, `com.google.guava:listenablefuture`, JSpecify,
  `com.squareup.okio` (transitive via DataStore's `datastore-core-okio`), protobuf-lite
  (`datastore-preferences-external-protobuf`). All licences from cached POMs are Apache-2.0
  except that protobuf-lite artifact (BSD-3-Clause); both are MIT-compatible. No GPL/AGPL/
  unknown runtime licence.
- Test-only: JUnit 4.13.2 (EPL-1.0), Robolectric (MIT), androidx.test (Apache-2.0),
  `cash.z.ecc.android:kotlin-bip39-jvm` (MIT). Not shipped in the APK.
- No OkHttp/Retrofit/Ktor/Volley/Firebase/Crashlytics/Sentry. Okio is an I/O library (no
  network client used, no permission); the offline deny-list does not name it, but the
  release APK has no INTERNET permission and no app code imports it.
- Native libraries in the release APK come from dependencies, not the app:
  `libdatastore_shared_counter.so` (DataStore) and `libandroidx.graphics.path.so`
  (androidx.graphics), for four ABIs. They are not network/telemetry code and do not change
  the offline guarantee; they are worth noting for F-Droid/reproducibility documentation
  (`ndk: absent` means "no NDK build", not "no .so in the APK").
- `DEPENDENCIES.md` inventory has no licence column and omits transitive Okio/protobuf/native
  libraries; see D9.

---

## (e) By-design residual risks (accepted, with rationale)

1. **VMK and decrypted index in RAM while unlocked.** A root/malware process with live memory
   access can extract the VMK; auto-lock/background-lock only reduce the window. This is the
   accepted baseline in THREAT-MODEL §3.2/§6. (F1 widens the window for recently edited
   content and should still be fixed.)
2. **Lock is a visibility gate, not a second encryption layer.** Anyone with the VMK, or the
   DB plus the password, can read locked content; a backup taken while unlocked includes
   locked content and is protected only by the backup credential/phrase. The threat addressed
   is handoff/shoulder-surfing, as stated in DECISIONS #15.
3. **Offline brute force depends on password entropy.** 600 k PBKDF2 is GPU-friendly; the lock
   re-auth limiter and the unlock-screen 5-attempt/30 s lockout are memory-only and reset on
   process death. The 12-character/4-class password policy is the real defence. Argon2id is
   correctly reserved but not implemented; do not claim it.
4. **Metadata leakage.** Vault existence (presence marker), row counts, timestamps,
   ciphertext/blob sizes, attachment count/mtime, biometric-enabled flag, and the iteration
   count/salt are visible to a filesystem reader. This is acknowledged as A9; padding or
   blind indexes are out of scope.
5. **Plaintext exports are user-initiated.** The attachment/code exports deliberately write
   decrypted bytes to a SAF location after an explicit warning. F6 fixes the lock-during-
   picker edge; the product still permits plaintext copies outside the vault by design.
6. **OEM/Keystore variance.** StrongBox may be absent or buggy; the manager falls back to
   TEE/software-backed Keystore or password unlock, and device tests skip where hardware
   cannot support the flow. A compromised TEE/firmware is out of scope.
7. **JVM zeroization is best-effort.** Garbage-collected copies, provider-internal buffers,
   `String` interning (recovery phrase, passwords in TextFields) and snapshot stacks cannot
   be reliably wiped; I3 documents additional codec intermediates. No secure memory locking.
8. **Backup rollback.** A valid old backup is indistinguishable from a legitimate restore;
   importing it cannot overwrite newer local rows (conflict policy), but restoring old
   app data at the OS level rolls the vault back. Out of scope per THREAT-MODEL §6.6.
9. **Attachments do not hide existence/size.** A filesystem observer sees blob existence,
   count, approximate size, mtimes and random ids; they do not see names, MIME, hashes or
   content. A blob rollback within the same AAD+size+SHA is byte-identical content and is not
   detected. Documented in ATTACHMENTS §9.
10. **Locked content can be destroyed without re-auth** from Trash/Empty trash (I7); the lock
    feature is confidentiality-only, not an anti-destruction control.
11. **Clipboard/handoff on older Android.** `EXTRA_IS_SENSITIVE` is only honoured on
    Android 13+; a background clipboard reader on older versions (or a root process) can read
    a copied phrase/code until the 60 s clear (phrase) or next copy (code). Recovery copy has
    a warning and auto-clear; code copy does not auto-clear. Documented under THREAT-MODEL
    §3.6 with the OS caveat.
12. **Release-build footguns (F4).** The signed artifact is safe as built; the flags remain
    available to any builder with Gradle property access until moved to the debug variant.

---

## (f) Docs reconciliation — claims vs implementation

These are documentation/contract defects (not counted as vulnerabilities). Line numbers in
`docs/` were stable at audit time.

| ID | Claim | Where | Reality | Recommended action |
|---|---|---|---|---|
| D1 | Raising `DEFAULT_ITERATIONS` re-wraps the envelope "on the next successful password unlock"; "re-wrap-on-unlock … implemented and tested in the data layer" | `docs/CRYPTO-SPEC.md:93`, `:317` | No re-wrap on unlock. `updatePasswordEnvelope` is called only from `changePassword` (`EncryptedNoteRepository.kt:418`); no test covers an unlock-time upgrade. Vaults keep their creation iteration count until a password change. | Implement the upgrade on unlock (compare `envelope.iterations` with `effectiveIterations()`, rewrap after a successful unwrap) or delete the claim and the "implemented and tested" sentence. |
| D2 | Recovery reset "replace[s] the recovery envelope and phrase. Old phrase stops working." | `docs/CRYPTO-SPEC.md:147`; also `docs/CONTRACTS.md` lifecycle step 4 | Reset re-wraps the **same** phrase under a fresh salt; the old phrase still opens the vault. Test `recoveryResetRewrapsAndUnlocks` asserts this; DECISIONS #12/DATA-MODEL/ARCHITECTURE are correct. | Correct both docs to match DECISIONS #12 and point at `rotateRecoveryPhrase()`; pair with F2's UI copy fix. |
| D3 | "Compile-time decisions `allowScreenshots=false` … enforced by the build"; screenshot-enabled builds are "debug-only" | `docs/THREAT-MODEL.md:43`, `docs/VERIFICATION-2026-11-15.md:80` (§5) | Both `ALLOW_SCREENSHOTS` and `DEV_DISABLE_AUTO_LOCK` are `defaultConfig` fields and can be set on a release build; nothing fails. | Move to debug variant or add a release guard (F4); correct the claims. |
| D4 | Attachments are out of scope for Phase 1 and "their own threat model must be added before shipping"; asset/attack tables do not include attachment blobs | `docs/THREAT-MODEL.md:141`, §1 assets, §3.4 | Attachments shipped in Phase 4/5 with per-blob AEAD, SHA-256, quotas, AAD binding, path guard and a §9 threat note in `ATTACHMENTS.md`. The main threat model was not updated. | Add an attachment asset (blob plaintext/ciphertext, ref metadata), adversary rows (blob swap/substitution, size/quota DoS, path traversal, restore dedupe) and a link to ATTACHMENTS §9. |
| D5 | `verifiedLockedIds` is cleared "on every new session (setupVault/unlock)" / "session start" | `docs/ARCHITECTURE.md:180-186`, `docs/THREAT-MODEL.md:162`, `docs/DATA-MODEL.md:294` | `unlock()`/`setupVault()` clear, but `completeBiometricUnlock()` and `resetWithRecoveryPhrase()` do not (F8). Not currently reachable while unlocked, but the blanket claim is false. | Fix the code (preferred) or qualify the docs. |
| D6 | Backup wire layout shows `formatVersion = 1` at offset 4 | `docs/BACKUP-FORMAT.md:45` | Writer emits `FORMAT_VERSION = 2`; the same doc's prose and AAD rules correctly describe v2 (32-byte header). | Change the diagram to `2` (and keep the v1 compatibility note). |
| D7 | "Version 2 is written from now on"; item payload table lists only v1/v2 | `docs/ATTACHMENTS.md:72-79` | Phase 5 writes ItemCodec **v3** (lock byte); `DATA-MODEL.md` and `BACKUP-FORMAT.md` are correct. | Update the sentence and add the v3 row (attachments + lock flag). |
| D8 | APK **and** AAB release builds are verified byte-for-byte reproducible; measured hash `f7eb6284…` | `docs/REPRODUCIBLE-BUILDS.md` header/§Measured result | `docs/VERIFICATION` records the published APK `18254b31…` and explicitly says the AAB is **not** yet claimed reproducible; the two docs disagree on the verified hash. The release APK hash matches `releases/SHA256SUMS`. | State exactly which artifact/hash was verified in which run; remove the AAB claim until measured. Not a security issue, but it undermines the reproducibility evidence trail. |
| D9 | Dependency policy requires a licence per dependency and a licence column/inventory | `docs/DEPENDENCIES.md` policy §3, inventory | Inventory has no licence column and omits transitive Okio, protobuf-lite (BSD-3) and the packaged DataStore/graphics native libraries. | Add a licence/notice column (Apache-2.0 / BSD-3 / MIT) and list transitives + native libs; note test-only JUnit EPL-1.0 is not shipped. |
| D10 | Tests: "740 unit / 10 instrumented" and specific claim lists | `CHANGELOG.md`, `VERIFICATION-2026-11-15.md` §9 | Static counts match exactly (740 `@Test` in 67 files; 10 `@Test` in 2 androidTest files); docs list the right areas. No re-run was done here (concurrent edits). | Keep; re-run at release tag. |
| D11 | `resetWithRecoveryPhrase` KDoc "rotates the kit"; recovery screen comment "rotates the recovery kit" | `data/NoteRepository.kt` (KDoc near reset), `RecoveryScreen.kt:47-49`, `VaultViewModel.kt:484` | Reset rewraps the same phrase; rotation is a separate settings action. DATA-MODEL clarifies, but these code comments still mislead. | Correct the comments alongside F2/D2. |
| D12 | CONTRACTS-PHASE5 says "663 unit / 10 device tests green" | `docs/CONTRACTS-PHASE5.md` header | Historical at phase start; current is 740/10 and the changelog records it. | Optional: label the number as the phase-start state. |

Missing threat-model rows (beyond D4): backup credential brute force / backup file as
untrusted input; SAF-picker lock deferral as a bounded exception; lock re-auth rate limiting;
code-workspace hiding (DECISION #16); process-memory lifetime of decrypted editor content
(F1). The last item is the most relevant omission — the model says decrypted content lives
"while unlocked", which is not true for the editor VMs after a lock.

---

## Appendix A — dependency and licence inventory (runtime)

Resolved with `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` (exit 0;
245 distinct coordinates). Licences read from cached POMs under
`~/.gradle/caches/modules-2/files-2.1/`. All versions are pinned in
`gradle/libs.versions.toml` (no dynamic versions).

| Coordinate | Version | Licence (POM) | Offline verdict |
|---|---|---|---|
| `androidx.core:core-ktx` | 1.18.0 | Apache-2.0 | yes |
| `androidx.activity:activity-compose` | 1.10.1 | Apache-2.0 | yes |
| `androidx.compose:compose-bom` (ui, graphics, tooling-preview, material3, icons-core) | 2024.09.00 | Apache-2.0 | yes |
| `androidx.lifecycle:lifecycle-runtime-{ktx,compose}`, `viewmodel-compose` | 2.8.7 | Apache-2.0 | yes |
| `androidx.navigation:navigation-compose` | 2.8.9 | Apache-2.0 | yes |
| `androidx.room:room-{runtime,ktx}` | 2.7.0 | Apache-2.0 | yes |
| `androidx.datastore:datastore-preferences` | 1.1.7 | Apache-2.0 | yes |
| `androidx.biometric:biometric` | 1.2.0-alpha05 | Apache-2.0 | yes (availability checks only) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-{android,core}` | 1.10.2 | Apache-2.0 | yes |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.10 | Apache-2.0 | yes |
| `com.google.guava:listenablefuture` | 1.0 (transitive) | Apache-2.0 | yes |
| `com.squareup.okio:okio` (transitive, DataStore) | 3.4.0 | Apache-2.0 | yes; I/O only, no permission/client use |
| `androidx.datastore:datastore-preferences-external-protobuf` (transitive) | 1.1.7 | **BSD-3-Clause** (protobuf-lite) | yes; MIT-compatible, retain notice |
| `org.jspecify:jspecify` (transitive) | 1.0.0 | Apache-2.0/BSD-style (per POM) | yes |
| Test-only: `junit:junit` | 4.13.2 | **EPL-1.0** | not shipped |
| Test-only: `org.robolectric:robolectric` | 4.16.1 | MIT | not shipped |
| Test-only: `androidx.test:*` | 1.6.1 / 1.3.0 / 1.6.2 / 3.7.0 | Apache-2.0 | not shipped |
| Test-only: `cash.z.ecc.android:kotlin-bip39-jvm` | 1.0.9 | MIT | not shipped |

Observations:

- No runtime dependency with a copyleft/unknown licence; Apache-2.0/BSD-3/MIT are compatible
  with the app's MIT licence. The packaging excludes `/META-INF/{AL2.0,LGPL2.1}` and disables
  `dependenciesInfo`, which removes bundled Apache notice text from the APK; this is legal
  hygiene to fix (include a notices file / rely on F-Droid/Play metadata), not a security
  defect.
- The APK contains prebuilt native libraries from `datastore-core` and `androidx.graphics`
  (`libdatastore_shared_counter.so`, `libandroidx.graphics.path.so`, four ABIs). They are not
  network/telemetry code and do not affect the offline guarantee. `fdroid/…yml` says
  `ndk: absent` (no NDK build); document that prebuilt `.so` files are still packaged.
- No `INTERNET`/telemetry library appears in the resolved graph. `scripts/check-offline.sh`
  denies okhttp/retrofit/ktor/volley/grpc/protobuf-javalite; Okio is not on that deny list but
  is not a client and is only used by DataStore's file layer.

---

## Appendix B — what was executed vs inferred

Executed (evidence in this session):

- `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` — exit 0, full tree.
- `aapt2 dump badging` / `aapt2 dump permissions` on
  `releases/notezero-0.1.0-release.apk` — only `USE_BIOMETRIC` +
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, no `debuggable`, targetSdk 36.
- `apksigner verify --print-certs` — certificate SHA-256 starts `facadd67…`, matches
  `SECURITY.md`/`DECISIONS #6`.
- `scripts/check-offline.sh` — exit 0 (source + merged debug manifest + no network imports/URLs).
- `unzip -l` on the release APK — native library list; no suspicious assets.
- Cached POM licence extraction for the resolved coordinates.
- Static `@Test` counts: 740 unit / 10 instrumented.
- Grep sweeps: no `Log.`/`println`/`printStackTrace`; no `java.net`/okhttp/ktor/retrofit/volley/
  WebView/socket usage; no `FileProvider`/`ContentProvider`/exported non-launcher components.

Inferred / not executed:

- No Gradle test/assemble run (concurrent UI edits; task requested no long builds).
- No device/emulator/memory inspection; the ViewModel retention finding is a static
  object-lifetime argument, not a measured heap dump. Its severity reflects that.
- Release reproducibility and AAB equality were not re-run; the published APK hash and
  certificate were checked against `releases/SHA256SUMS`/`SECURITY.md` only.
- No fuzzing of codecs/lexers/backup; existing fuzz tests were read, not repeated.

---

---

## Remediation log — 2026-11-15 (post-audit)

**Writers.** Remediation agent for `app/src/main/java/com/cipherwell/notezero/{ui,data,backup}/**`,
`app/src/test/**`, `app/src/main/res/values/**` and the docs named below; the **Lead** for
`app/build.gradle.kts` (finding F4). No git commit was made; no build-file/manifest/gradle
edit was made by the remediation agent.

**Verification (JDK 17, `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`).**

| Command | Result |
|---|---|
| `./gradlew :app:compileDebugKotlin --console=plain` | **BUILD SUCCESSFUL** |
| `./gradlew :app:testDebugUnitTest --console=plain` (full suite) | **BUILD SUCCESSFUL — 764 tests, 0 failures, 0 errors, 0 skipped (60 classes)** |

The 764 figure is the pre-remediation 740 plus **24 new regression tests** (listed per finding
below). No existing test was deleted; two tests that pinned the *audited* behaviour
(`EditorViewModelTest.autosave is suspended while the vault is locked…`,
`CodeEditorViewModelTest.locking the vault stops writes`) were rewritten to pin the fixed
contract (draft cleared on lock), because they asserted the vulnerable keep-plaintext-on-lock
behaviour.

### Findings

| ID | Verdict | Fix | Regression tests / evidence |
|---|---|---|---|
| F1 (MEDIUM) | **FIXED** | `ui/editor/EditorViewModel.kt`, `ui/code/CodeEditorViewModel.kt`, `ui/editor/NoteEditorScreen.kt`, `ui/code/CodeEditorScreen.kt`, `ui/code/CodeTags.kt`: both Activity-scoped ViewModels now `combine(repository.status, repository.verifiedLockedIds)`, wipe the draft/source, file name, undo+redo stacks, conflict and find state on lock or re-arm (`clearDecryptedState`) and reload from the index when a new session makes the record visible again. Mutation entry points refuse a cleared state; the screens render a non-secret placeholder (`common_session_cleared`, tags `editor_session_cleared` / `code_session_cleared`) and no longer read a cleared draft as "empty note" (that path could have tried to trash a note while locked). | `EditorViewModelTest`: *lock while the editor is open drops the draft and the undo history*, *re-arming the lock while the editor is open drops the draft*, *a new unlocked session reloads the note into a cleared editor*, *a save that loses the auto-lock race clears the draft instead of keeping plaintext*; `CodeEditorViewModelTest`: the three equivalent code-file tests. |
| F2 (MEDIUM) | **FIXED** | Copy + docs: `res/values/strings.xml` (`recovery_new_password_subtitle` no longer claims a new phrase is generated; new `recovery_reset_notice`), `ui/screens/recovery/RecoveryScreen.kt` KDoc, `ui/VaultViewModel.kt` (reset/rotate KDocs + `recoveryResetNotice` raised on success), `ui/AppRoot.kt` (post-reset confirmation banner with explicit dismiss, dropped on lock), `data/NoteRepository.kt` reset KDoc, `docs/CRYPTO-SPEC.md` §2.2/§3 step 4/§10, `docs/CONTRACTS.md` lifecycle step 4. Truth (DECISIONS #12): reset re-wraps the **same** phrase under a fresh salt; rotation is `rotateRecoveryPhrase` in Settings. | `VaultViewModelTest.recovery reset reports that the phrase is unchanged and clears the notice on lock`; `AppRootTest.a recovery reset confirms the phrase is unchanged and can be dismissed`; existing `recoveryResetRewrapsAndUnlocks` (repository) remains the pin. |
| F3 (LOW) | **FIXED** | `ui/screens/setup/SetupScreen.kt` (password, confirmation, confirmation words), `ui/screens/unlock/UnlockScreen.kt` (password), `ui/screens/recovery/RecoveryScreen.kt` (new password, confirmation), `ui/screens/settings/SettingsScreen.kt` (change-password current/next/confirmation, rotate current) now use plain `remember`; non-secret flags stay `rememberSaveable`. Comment explains why, matching `BackupSection`. | Full suite green (Robolectric screen tests cover entry/submit paths); no test asserts saved-instance-state restoration for these fields. |
| F4 (LOW) | **FIXED — by Lead** | `app/build.gradle.kts`: both `buildConfigField`s moved out of `defaultConfig`; the release variant hardcodes `ALLOW_SCREENSHOTS = false` / `DEV_DISABLE_AUTO_LOCK = false`, the `-P` opt-ins exist only on `debug`. Docs corrected accordingly (D3). | Not regression-tested in this repo (no build-variant unit test); the release values are compile-time constants and the Lead's build compiled/ran the suite with the change. |
| F5 (LOW) | **FIXED** | `ui/VaultViewModel.kt`: the status collector resets `_settings` (drops `rotatedPhrase` and the acknowledgement message) and `recoveryResetNotice` on `LOCKED`/`SETUP_REQUIRED`; nothing re-shows the phrase without a new rotate action. | `VaultViewModelTest.rotated phrase is dropped on lock and cannot be re-shown without a new rotation`. |
| F6 (LOW) | **FIXED** | `ui/code/CodeExport.kt`: new pure `canWriteCodeExport(repository, itemId)` requires `status == UNLOCKED`, the item still present and not locked+unverified; the SAF result path re-checks it before writing, a lock clears the pending plaintext (`LaunchedEffect(status)`), and the request path refuses to stage plaintext when the session is not exportable. | `CodeExportTest.export is allowed only while unlocked with the item still visible`, `…export is refused for a locked file this session has not verified`. |
| F7 (LOW) | **FIXED** | `ui/code/CodeEditorScreen.kt`: same locked gate as the notes editor — `LockedNoteGate` + `ReauthDialog` are decided **before** the `CodeEditorViewModel` is created (`items`/`verifiedLockedIds` observed); injectable `biometricPrompt` for tests. Workspace filter remains the primary control (decision #16). | New `ui/code/CodeEditorLockedGateTest` (7 tests): gate-only render, back, successful re-auth reveals, cancel stays masked, pre-verified open, re-mask on `clearLockVerifications`, unlocked unaffected. |
| F8 (INFO) | **FIXED** | `data/EncryptedNoteRepository.kt`: `completeBiometricUnlock` clears verifications + the re-auth limiter both when tearing down a live session and before publishing `UNLOCKED`; `resetWithRecoveryPhrase` does the same before publishing `UNLOCKED`. Matches `unlock()`/`setupVault()`/`lock()`. | `LockedItemsRepositoryTest.recoveryResetAndBiometricUnlockStartUnverifiedWithACleanRateLimit` (real crypto + in-memory Room; reset clears verifications **and** the limiter, biometric teardown clears verifications). |
| F9 (LOW) | **FIXED (clean failure; streaming deferred)** | `backup/BackupFormat.kt`: `read` catches `OutOfMemoryError` explicitly (no longer relies on `Throwable`), `BackupEnvelope.decrypt` catches it around payload decode, `write` converts an allocation failure during encode/seal into an `IllegalStateException` before any byte is written; `data/EncryptedNoteRepository.exportBackup`/`importBackup` catch `OutOfMemoryError` and return `false`/`null`. `docs/BACKUP-FORMAT.md` §5/§6 and `docs/ATTACHMENTS.md` now call the 64 MiB limit a **stream cap**, not an "OOM guard". | No forced-OOM test (deliberate: allocating until OOM makes the suite flaky and can poison the Gradle worker); the change is a boundary catch, reviewed not measured. Chunked/streamed payload (format v3) remains the deferred real fix (DECISIONS #14). |
| F10 (LOW) | **FIXED** | `ui/notes/NotesViewModel.kt`, `ui/code/CodeWorkspaceViewModel.kt`: mutation coroutines catch `IllegalStateException` (rethrow `CancellationException`) and expose a non-secret `sessionStale: StateFlow<Boolean>` reset on the next unlocked session. `EditorViewModel.flush` / `CodeEditorViewModel.flush`: an `IllegalStateException` that arrives after the status check clears the draft (lock race) instead of leaving a "retryable" plaintext error; a generic failure keeps the old ERROR behaviour. | `NotesViewModelTest.a mutation that loses the auto-lock race reports stale state instead of crashing`, *stale state clears on the next unlocked session*; `CodeWorkspaceViewModelTest` equivalents; the two rewritten editor tests cover the flush path. |

INFO observations **I1–I8 were not changed** (they were explicitly out of the fix list and are
non-vulnerabilities/residual properties). Of those, I1 (blob size check before `readBytes`) and
I5 (kind-scoped verification ids) remain the most worth a future hardening pass.

### Doc corrections applied (D1–D12)

| ID | Where | Correction |
|---|---|---|
| D1 | `docs/CRYPTO-SPEC.md` §2.2, §3 (iteration paragraph), §10 | Deleted the "re-wrapped on the next successful unlock" and "implemented and tested in the data layer" claims. Documented reality: an existing vault keeps its recorded iteration count until `changePassword`/`resetWithRecoveryPhrase`/`rotateRecoveryPhrase` rewrites the envelope; **re-wrap-on-unlock is not implemented** (chosen remediation: doc correction, not a new unlock-path write). |
| D2 | `docs/CRYPTO-SPEC.md` §3 step 4 + recovery KEK bullet, `docs/CONTRACTS.md` lifecycle step 4 | Reset re-wraps the **same** phrase under a fresh salt (DECISIONS #12); rotation is `rotateRecoveryPhrase()` and shows the new phrase once. |
| D3 | `docs/THREAT-MODEL.md` §2.4, `docs/VERIFICATION-2026-11-15.md` §4/§5 | Release hardcodes screenshots/auto-lock **on**; the `-P` flags are debug-only (F4). |
| D4 | `docs/THREAT-MODEL.md` §1 (A12/A13), new §3.9, §4 | Attachments are in the model: asset rows, adversary rows (blob swap/rollback, quota/allocation DoS, path traversal, restore dedupe, at-rest metadata) and a pointer to `docs/ATTACHMENTS.md` §9. Removed the "their own threat model must be added before shipping" out-of-scope sentence for attachments. Also added invariant rows for F1/F7/F10. |
| D5 | `docs/DATA-MODEL.md` §7 clearing rules | Added `completeBiometricUnlock()`/`resetWithRecoveryPhrase()` as session boundaries (code fixed, so ARCHITECTURE/THREAT-MODEL's blanket claim is true again). |
| D6 | `docs/BACKUP-FORMAT.md` §2 | Wire diagram now shows `formatVersion = 2` with `attachmentCount` (32-byte header) plus the v1 offset note. |
| D7 | `docs/ATTACHMENTS.md` §3 | "Version 3 is written from now on" (v2 = same layout without the lock byte; v1/v2 still decode), payload table gained v1/v2/v3 rows with the lock-flag column and a pointer to DATA-MODEL §2. |
| D8 | `docs/REPRODUCIBLE-BUILDS.md` header + "Measured result", `docs/VERIFICATION-2026-11-15.md` §9 | REPRODUCIBLE-BUILDS no longer restates a hash or claims AAB equality; it names VERIFICATION §9 as the single source of measured hashes and marks the old `f7eb6284…` figure superseded. No hash was invented. |
| D9 | `docs/DEPENDENCIES.md` inventory | Added a licence column (Apache-2.0 / BSD-3-Clause / MIT / EPL-1.0 test-only), the transitive Okio/protobuf-lite/jspecify/listenablefuture coordinates, the packaged prebuilt native libraries (`libdatastore_shared_counter.so`, `libandroidx.graphics.path.so`) and the notice-exclusion note. |
| D10 | — | No doc change needed: 740/10 was accurate. The remediation run measured **764 unit tests** (60 classes); instrumented tests were not re-run (no device in this session). Recorded here and in the log header. |
| D11 | `data/NoteRepository.kt` reset KDoc, `ui/screens/recovery/RecoveryScreen.kt` KDoc, `ui/VaultViewModel.kt` KDocs | Reset re-wraps the same phrase; rotation is the separate settings action. |
| D12 | `docs/CONTRACTS-PHASE5.md` header | 663/10 labelled as the **phase-start** baseline; phase ended at 740/10 (this remediation: 764 unit). |

### Deviations / notes

- **D1 was remediated as a documentation correction**, not by implementing re-wrap-on-unlock.
  The audit allowed either; the upgrade path is currently dead code in production (the default
  iteration constant has not been raised), and touching the most security-critical unlock path
  for a write that no shipped configuration triggers was judged riskier than correcting the
  claim. If the KDF default is ever raised, implement and test the re-wrap first.
- **F9 has no forced-OOM test** (as instructed). The catches are defensive; the streaming v3
  format stays deferred.
- **`ui/notes/components/NotesDialogs.kt` / `ui/code/CodeDialogs.kt`** still use
  `rememberSaveable` for dialog *text* (note title / file name). The audit rated that
  "related, lower-impact" and outside the credential fix; it was left unchanged deliberately
  (decrypted content in saved instance state is a much smaller window than a password, and the
  dialogs are transient). If the project wants zero decrypted content in saved state, those two
  call sites are the remaining ones.
- **One extracted string value changed on purpose** (`recovery_new_password_subtitle`), because
  it stated a false security fact. `docs/STRINGS.md` still says the extracted English is
  byte-identical to the pre-extraction literals; that statement now has this single intentional
  exception and should be reconciled by the string-extraction owner. No test asserted the old
  literal.
- **Instrumented tests (androidTest) were not run** — no device/emulator in this session; the
  documented 10 device tests are unchanged.
- **AppRoot layout**: the unlocked surface is now wrapped in a `Column` so the post-reset
  banner can sit above whichever route is showing (notes/settings/code); no route behaviour
  changed.


*End of report.*
