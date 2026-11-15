# NoteZero — Phase 5 (increment 4) contracts

Scope: **locked notes & folders** (last missing SPEC security/UX feature: "Lock note / lock
folder via biometrics; hidden until unlocked") plus Phase-5 reproducibility work (Lead).
Same rules as `docs/CONTRACTS.md`: no new dependencies, no network, no plaintext secrets in
logs, stay in scope, no git/build-file/manifest/res edits, pure-JVM logic where possible.

Current state: Phases 1–3 complete, Phase 4 attachments shipped (commit `c2beb0b`).
The **phase-start** test baseline was 663 unit / 10 device tests green; the phase ended at
740 unit / 10 device tests (recorded in `CHANGELOG.md` and
`docs/VERIFICATION-2026-11-15.md` §9). Treat the 663 figure as historical, not current
(audit D12).

---

## A. Locked notes & folders — data layer (owner: lock-data)

### A.1 Model

```kotlin
// model/Item.kt
data class Item(..., val locked: Boolean = false)
// model/Folder.kt
data class Folder(..., val locked: Boolean = false)
```

Both flags are content → they live **inside** the encrypted envelopes.

### A.2 Codecs (versioned, backward compatible)

- `ItemCodec` payload **v3** appends `locked` (byte). Must still decode v1 (no attachments, not
  locked) and v2 (attachments, not locked) — no re-encryption/migration.
- `FolderCodec` payload **v2** appends `locked`; v1 decodes to `locked = false`.
- Document both version tables in `docs/DATA-MODEL.md`.

### A.3 Repository API (`data/NoteRepository.kt` + `EncryptedNoteRepository`)

```kotlin
/** Locks/unlocks one note. Locking never changes the VMK or the item ciphertext AAD. */
suspend fun setItemLocked(id: String, locked: Boolean): Boolean
/** Locks/unlocks a folder; descendants are hidden by the UI while unverified. */
suspend fun setFolderLocked(id: String, locked: Boolean): Boolean

/**
 * Re-authentication for locked content: verifies the master password against the stored
 * password KeyEnvelope WITHOUT changing the session (vault stays unlocked). Constant-time
 * comparison of the unwrapped VMK; failed attempts are rate-limited (>= 1 s growing delay
 * after each failure, max 5 then a 30 s cooldown) and never leak whether the vault is corrupt.
 */
suspend fun verifyMasterPassword(password: CharArray): Boolean

/** Ids (notes AND folders) whose locked content is revealed for this session. */
val verifiedLockedIds: StateFlow<Set<String>>
/** Call after a successful biometric/password re-auth for [id]. */
fun markLockVerified(id: String)
/** Clears all lock verifications (e.g. app backgrounded/locked). */
fun clearLockVerifications()
```

Semantics:
- Lock flags persist (encrypted) across restarts; **verification is memory-only and session
  scoped**. Clear verifications when the vault locks, when the app backgrounds *for real*
  (i.e. exactly when the background lock applies — do NOT clear while an external activity
  such as the SAF picker is deferring the lock), on screen-off, and on manual "lock now".
- `lock()` must clear them. Session close/`close()` clears them.
- Locked content is NOT hidden at the repository level (`items` still exposes decrypted Item,
  the UI must mask); but **search helpers in `model/` must accept a hide predicate** or the
  UI must filter before searching — decide and document; JSON of requirements: a locked note
  that is not verified must never appear in search results, folder lists, counts or previews.
- Wrong/blank passwords: false, no state change, char array wiped.
- No logging; refs/counts only.

### A.4 Tests (Robolectric + real crypto/Room)

- ItemCodec v1/v2 fixtures decode (locked=false, attachments preserved) and v3 round-trips
  locked=true; FolderCodec v1/v2 same.
- setItemLocked/setFolderLocked persist (lock, unlock vault, re-read) and change ciphertext
  (new envelope) while AAD stays valid.
- verifyMasterPassword: correct → true; wrong/blank → false; rate limit behaviour deterministic
  with injected clock (no real sleeps in tests — inject a `now: () -> Long`).
- Verified ids: mark/clear; cleared on lock(); cleared on real background lock; NOT cleared
  when `beginExternalActivity()` defers a background stop; memory-only (not persisted).
- No plaintext lock metadata outside envelopes (row scan).

### A.5 Docs

`docs/DECISIONS.md` #15 (lock semantics: visibility gate, not a second encryption layer; content
stays VMK-encrypted; threat = shoulder-surfing/handoff; verification memory-only, re-armed on
background), `docs/DATA-MODEL.md`, `docs/ARCHITECTURE.md`, `docs/THREAT-MODEL.md` (new threat
row + residual risk).

Do NOT edit UI packages, build files, manifest, res, git. Build/tests JDK17, no clean, other
agents may build concurrently (retry once on transient KSP FileNotFoundException).

---

## B. Locked notes & folders — UI (owner: lock-ui; starts after A compiles)

You are the single writer for `ui/notes/**`, `ui/editor/NoteEditorScreen.kt`, new
`ui/locks/**` (reauth helpers/dialogs) and their tests. Existing tags/tests must keep working.

- **Notes list**: locked + unverified rows show a mask: title "Locked note", no preview, no
  tags, lock icon, `testTag("locked_note_row")`; tapping opens the re-auth flow instead of the
  editor. Verified rows render normally. Search/Favourites/Recent/Trash/counts must exclude
  masked content (mask-title searchable only as "Locked note" if at all).
- **Row actions**: "Lock note" / "Remove lock" in the existing row menu; removing a lock while
  unverified requires re-auth. Folder menu: "Lock folder" / "Remove lock"; locked+unverified
  folder rows show a lock icon and entering them prompts re-auth (children hidden).
- **Editor gate**: opening a locked+unverified note (deep link/last-opened) shows a locked
  screen (`testTag("editor_locked_gate")`) with a re-auth action; no body/title rendered.
- **Re-auth flow** `ui/locks/`: try device biometric presence prompt when available
  (platform `android.hardware.biometrics.BiometricPrompt` **without** a CryptoObject —
  presence-only; this is a UI visibility gate, content stays VMK-encrypted), else/also offer
  master-password dialog (`testTag("reauth_dialog")`, `reauth_password`, `reauth_confirm`,
  `reauth_cancel`, `reauth_error`); on success call `repository.markLockVerified(id)`.
  Cancel/failure leaves content hidden. 5 failures → cooldown message.
- **Auto re-mask**: observe `repository.verifiedLockedIds`; when backgrounded/locked the
  repository clears them and the UI must re-mask immediately (no stale decrypted content in
  list state). Session must not cache decrypted bodies of masked items in ViewModels beyond
  what `repository.items` already holds; do not log.
- Settings: a "Re-arm locked notes now" action (`settings_relock`) that calls
  `clearLockVerifications()`.
- Tests: Robolectric Compose with a fake repository — masked row rendering, tap → reauth path,
  password success reveals, cancel keeps masked, search excludes masked titles/bodies, folder
  gate, editor gate, auto re-mask on cleared flow, settings re-arm. No sleeps.

Tags contract: `locked_note_row`, `locked_folder_row`, `reauth_dialog`, `reauth_password`,
`reauth_confirm`, `reauth_cancel`, `reauth_error`, `editor_locked_gate`, `settings_relock`,
`note_lock_action`, `folder_lock_action` (menu items).

Constraints: no new deps; biometric presence prompt must degrade gracefully to password (no
crash on devices/emulators without biometrics); no logging of content; 48dp targets;
contentDescriptions; don't edit data/model/crypto/code/ui/code/build/manifest/res/git.

---

## C. Lead: Phase-5 reproducible builds (this increment)

`scripts/verify-reproducible.sh`: two clean release builds into separate output dirs with fixed
`SOURCE_DATE_EPOCH`, then compare APK sha256 + per-entry CRC/sha256 and report the differing
entries. Document measured result + open causes in `docs/REPRODUCIBLE-BUILDS.md`; update
RELEASING/TESTING/CHANGELOG/VERIFICATION with the honest outcome (claimed only if byte-equal).
