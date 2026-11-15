# NoteZero — cryptography specification (v1)

Status: implementation reference for Phase 1. Code lives in
`app/src/main/java/com/cipherwell/notezero/crypto/`; tests in
`app/src/test/java/com/cipherwell/notezero/crypto/` (plain JVM) and
`app/src/androidTest/java/com/cipherwell/notezero/crypto/` (Keystore/device only).

This document is normative for the v1 on-disk format. Changing any constant, AAD string,
binary layout or KDF parameter is a **crypto-spec revision**: bump the version suffix,
never edit a published value, and keep old envelopes readable.

---

## 1. Goals and non-goals

Goals:

- Confidentiality and integrity of all user content at rest, with authentication bound to
  the record identity so ciphertext cannot be moved between rows, item kinds or backups.
- Zero knowledge: no key, password, phrase or plaintext is ever written to disk or logs.
- Password change and recovery rotate **wrappers only**; note ciphertext is never
  re-encrypted (frozen decision #8 in `docs/DECISIONS.md`).
- Pure-JVM crypto core: every primitive except the biometric Keystore manager is testable
  with plain JUnit, no Robolectric/device.

Non-goals for v1 (do not add without review):

- Deniability / hidden volumes, multi-user key hierarchies, key escrow, network anything.
- SQLCipher or full-database encryption (field-level envelopes only — decision #9).
- Blind indexes; search runs in memory over decrypted metadata while unlocked (decision #10).

---

## 2. Primitives

| Purpose | Primitive | Parameters | Code |
|---|---|---|---|
| Content encryption | AES-256-GCM (AEAD) | 96-bit random IV per operation, 128-bit tag | `NoteCrypto.encrypt` / `NoteCrypto.decrypt` |
| Password KDF | PBKDF2-HMAC-SHA256 | 600,000 iterations default, ≥10,000 accepted, 128-bit salt | `NoteCrypto.deriveKek`, `NoteCrypto.pbkdf2Raw` |
| Recovery KDF | PBKDF2-HMAC-SHA256 over canonical phrase | same as password KDF | `NoteCrypto.deriveKekFromPhrase` |
| VMK generation | `SecureRandom` | 32 bytes (256 bits) | `NoteCrypto.randomVmk` |
| Recovery phrase | BIP-39 English, 12 words | 128-bit entropy + 4-bit SHA-256 checksum | `Bip39Recovery` |
| Biometric wrapping | AES-256-GCM in Android Keystore | per-use strong biometric auth, invalidated by re-enrolment | `BiometricKeyManager` |
| Hashes/MAC | SHA-256, HMAC-SHA256 | — | `NoteCrypto.sha256`, `NoteCrypto.hmacSha256` |
| Comparison | JDK constant-time equality | length leak accepted | `NoteCrypto.constantTimeEquals` |
| Zeroization | best-effort in-place wipe | JVM cannot guarantee it | `NoteCrypto.wipe` |

Design rules enforced by the code and tests:

- VMKs are generated, never derived from passwords (`randomVmk`, `wrapVmk` rejects a
  non-32-byte payload).
- Every AES-GCM operation binds an AAD string; wrong AAD, wrong key, tampered IV/tag or a
  truncated blob makes `decrypt`/`unwrapVmk` return `null`, never throw.
- `unwrapVmk` additionally rejects a payload that decrypts to something other than
  32 bytes, so a valid ciphertext under the wrong domain cannot masquerade as a VMK.
- Encryption with a caller-supplied IV exists only as `internal fun sealWithIv` for
  known-answer tests; production code always draws a fresh random IV.
- Passwords are consumed: `deriveKek` wipes the `CharArray` it receives and clears the
  `PBEKeySpec`.

### 2.1 Blob layout

All AES-GCM blobs in NoteZero (note envelopes, wrapped VMK, backups) use one layout:

```
iv (12 bytes) || ciphertext (n bytes) || tag (16 bytes)
```

`NoteCrypto.encrypt`/`wrapVmk` return exactly that; `decrypt`/`unwrapVmk` require
`n ≥ 0` and reject any blob shorter than 28 bytes.

### 2.2 KDF upgrade path (Argon2id reserved)

`KeyEnvelope` has a `kdf` byte. v1 recognizes exactly one value:

| id | KDF |
|---|---|
| 1 | PBKDF2-HMAC-SHA256 |
| 2 | *reserved for Argon2id* (not implemented; unknown ids are rejected) |

When Argon2id lands (decision: only if a vetted, auditable pure/NDK-free implementation is
available and reviewed first):

1. Add `KdfId.ARGON2ID(2)` and its parameters (memory, passes, lanes) to the envelope. That
   requires envelope `version = 2` because the v1 layout has no parameter bytes.
2. `fromBytes` accepts v1 and v2; derive with the recorded KDF, then transparently re-wrap
   the VMK under a fresh v2 envelope on the next successful unlock (same pattern as the
   iteration upgrade path below).
3. Old v1 envelopes stay readable forever; nothing re-encrypts note content.

The iteration count is recorded **per envelope**, so raising `DEFAULT_ITERATIONS` is
backwards compatible: an envelope is opened with its recorded work factor and re-wrapped
with the current default on the next successful password unlock (data-layer policy).
`KeyEnvelope` rejects counts below `NoteCrypto.MIN_ACCEPTED_ITERATIONS` (10,000) and above
`MAX_ITERATIONS` (10,000,000) on both read and write, bounding offline and online DoS.

---

## 3. Key hierarchy

```
                         ┌──────────────────────────────┐
   password ──PBKDF2──►  │ PASSWORD KEK (32 bytes, AES) │──wrap──┐
                         └──────────────────────────────┘        │
                                                                 ▼
                         ┌──────────────────────────────┐   ┌────────────┐
   BIP-39 phrase ─PBKDF2►│ RECOVERY KEK (32 bytes, AES) │──►│ VMK (32 B) │
                         └──────────────────────────────┘   └────────────┘
                                                                 │
                                         AES-256-GCM per item, AAD bound to id+kind
                                                                 ▼
                                                    encrypted item envelopes (Room blob)

                         ┌──────────────────────────────┐
   strong biometric ────►│ Android Keystore AES-256-GCM│──unwraps the VMK copy above
   (per-use CryptoObject)└──────────────────────────────┘   (hardware gate, no password)
```

- **VMK (Vault Master Key)**: 32 random bytes; the only key that encrypts notes. Held in
  memory only while unlocked, wiped on lock where possible.
- **Password KEK**: PBKDF2-HMAC-SHA256(password, salt, iterations). Only wraps/unwraps the
  VMK. Password change = unwrap with old envelope, wrap with new envelope, replace row.
- **Recovery KEK**: PBKDF2-HMAC-SHA256(canonical 12-word phrase, separate salt,
  iterations). Recovery reset = unwrap with recovery KEK, wrap under a new password
  envelope, rotate the recovery envelope **and phrase**.
- **Biometric wrapping key**: AES-256-GCM key created inside Android Keystore with
  `setUserAuthenticationRequired(true)`, per-use strong biometric auth
  (`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` on API 30+,
  `setUserAuthenticationValidityDurationSeconds(-1)` below), `setUnlockedDeviceRequired`
  (API 28+), StrongBox preferred with TEE fallback, and
  `setInvalidatedByBiometricEnrollment(true)`. It wraps a copy of the VMK; the plaintext
  VMK never touches disk.

### 3.1 Vault lifecycle (normative, data-layer drives it)

1. **Setup** — `vmk = randomVmk()`; generate a 12-word phrase; create
   `KeyEnvelope(PASSWORD, iterations=600_000, salt=random16, wrapVmk(vmk, deriveKek(password), VMK_WRAP))`
   and
   `KeyEnvelope(RECOVERY, iterations=600_000, salt=random16, wrapVmk(vmk, deriveKekFromPhrase(phrase), RECOVERY_WRAP))`.
   Persist both blobs plus `created_at`; show the phrase exactly once.
2. **Unlock** — parse envelopes with `KeyEnvelope.fromBytes`; derive the KEK; unwrap the
   VMK; keep it in memory only; decrypt the item index. `fromBytes` returning `null` means
   the vault meta is corrupt: fail closed, never guess.
3. **Change password** — unwrap VMK with the old password envelope, wrap under new KEK +
   new salt, replace the row. Note ciphertext untouched.
4. **Recovery reset** — unwrap with the recovery KEK, wrap under the new password
   envelope, replace the recovery envelope and phrase. Old phrase stops working.
5. **Biometric enrol/unlock** — see §5.

---

## 4. AAD map (domain separation)

Defined in `CryptoAad` (frozen v1 values):

| AAD constant | Bytes | Used by |
|---|---|---|
| `VMK_WRAP` | `notezero.vmk.v1` | password-wrapped VMK envelope |
| `RECOVERY_WRAP` | `notezero.recovery.v1` | recovery-phrase-wrapped VMK envelope |
| `BACKUP` | `notezero.backup.v1` | backup header/envelope |
| `item(id, kind)` | `notezero.item.v1\|<item-id>\|<KIND>` | every note/code-file envelope |

Consequences, covered by tests:

- A password wrap cannot be opened with the recovery KEK and vice versa (different AAD,
  independent of the salt).
- Ciphertext for item `A` cannot be moved to item `B` or from `NOTE` to `CODE_FILE`; the
  GCM tag verification fails. This is the row-swap / type-confusion defence.
- The item id and kind are authenticated, so an attacker with write access to SQLite
  cannot re-label a note.

No other AAD shapes may be introduced without a spec revision; the item template lives in
exactly one function.

---

## 5. Biometric unlock (Android Keystore)

Implemented in `BiometricKeyManager.kt`; device tests in
`app/src/androidTest/java/com/cipherwell/notezero/crypto/BiometricKeyManagerTest.kt`.

A Keystore key configured for per-use authentication **cannot encrypt without a
BiometricPrompt**: `Cipher.init` is allowed, `doFinal` throws
`UserNotAuthenticatedException` until the prompt authorises that exact cipher. Enrolment is
therefore two-phase, and the contract is frozen that way:

1. `prepareEnroll(): BiometricEnroll?` — get/create the auth-gated key, return an
   `ENCRYPT_MODE` cipher (UI wraps it in `BiometricPrompt.CryptoObject`).
2. Prompt: "Authenticate to enable biometric unlock" with that CryptoObject.
3. `completeEnroll(enroll, vmk): Boolean` — after auth, `doFinal(vmk)`, persist
   `iv||ciphertext||tag` in app-private SharedPreferences; any failure returns `false`
   and persists nothing.
4. Unlock: `prepareDecrypt(): BiometricDecrypt?` → prompt with `CryptoObject` →
   `completeDecrypt(decrypt): ByteArray?` returns the VMK only after successful auth.
5. `disable()` — delete the Keystore key **and** the wrapped VMK. Idempotent.

Failure handling:

- `KeyPermanentlyInvalidatedException` / `UnrecoverableKeyException` during
  `prepareDecrypt`/`isEnrolled` → the stale wrap is wiped (`disable()`) and the app falls
  back to the master password; re-enrolment requires the password (by design).
- If the Keystore key is gone but a wrapped blob survived (app data restore/keystore
  reset), `prepareDecrypt` wipes the orphaned blob instead of reusing it.
- No prompt-free fallback exists anywhere: the only decryption path is the
  prompt-authorised cipher. `isEnrolled()` returns false (and never secrets) when the
  key/blob are missing or invalid.

Bias/security notes: the manager prefers StrongBox when the device reports it and falls
back to the TEE; both are hardware-backed on modern devices. The wrapped VMK is stored in
the app's private `SharedPreferences`, which the OS protects with app sandboxing; the
Keystore key is non-exportable and never leaves secure hardware.

---

## 6. KeyEnvelope binary format (v1)

```
offset  size  field
0       4     magic        "NZEN" (ASCII)
4       1     type         0 = PASSWORD, 1 = RECOVERY
5       1     version      1
6       1     kdf          1 = PBKDF2-HMAC-SHA256
7       4     iterations   u32 big-endian
11      1     saltLen      must be 16 in v1
12      16    salt
28      2     wrappedLen   u16 big-endian, 28..994
30      n     wrapped      iv || ciphertext || tag from NoteCrypto.wrapVmk
```

Total = `30 + n` bytes, capped at `KeyEnvelope.MAX_BYTES = 1024`. Parser rejection rules
(see `fromBytes`, exhaustively tested): wrong magic, unknown type/version/kdf, iterations
outside `[10_000, 10_000_000]`, saltLen ≠ 16, wrappedLen < 28 or beyond the buffer,
trailing bytes, empty input, oversized input. Rejection returns `null`; no exception, no
partial object.

`EnvelopeType`/`KdfId` numeric ids are part of the on-disk format and asserted in tests.

---

## 7. Item / backup envelopes (data-layer contract)

- Item plaintext payload is the versioned binary `ItemCodec` format (title, body, tags,
  folder, flags, timestamps) defined in `docs/DATA-MODEL.md`; the crypto layer only sees
  bytes and the AAD. It is encrypted with the VMK using `CryptoAad.item(id, kind)`; the
  Room row stores `ciphertext` and redacted structural columns only.
- Backups are sealed under the same VMK (or a backup-specific wrapping, data-layer choice
  restricted to `CryptoAad.BACKUP`) and their header is authenticated with
  `CryptoAad.BACKUP`; see `docs/BACKUP-FORMAT.md`. A backup never contains plaintext
  titles, bodies or tags, and restore validates the AAD before writing anything.
- Corrupt/tampered rows must be skipped and counted, never crash the vault
  (data-layer invariant).

---

## 8. What must never happen (invariants + where tested)

| Invariant | Test |
|---|---|
| Same password+salt+iterations ⇒ same KEK; different any ⇒ different KEK | `NoteCryptoTest.deriveKek is deterministic...`, `...depends on...` |
| KDF matches RFC 7914 §11 vectors | `NoteCryptoTest.RFC 7914 ...` |
| AES-256-GCM matches NIST CAVP vectors (empty / 16-byte / AAD / large) | `NoteCryptoTest.NIST CAVP ...` |
| Every encryption has a fresh IV | `NoteCryptoTest.every encryption uses a fresh random IV` |
| Tampered IV/ciphertext/tag, wrong key, wrong AAD ⇒ null | `NoteCryptoTest.wrong key wrong AAD and tampering...` |
| Malformed/short blobs ⇒ null, no exception | `NoteCryptoTest.decrypt rejects malformed blobs...` |
| VMK wraps round-trip; wrong KEK/domain ⇒ null; non-32-byte payload rejected | `NoteCryptoTest.wrapVmk...` |
| AAD item binding blocks id/kind swaps | `NoteCryptoTest.item ciphertext cannot be swapped...` |
| Official BIP-39 12-word vectors entropy↔phrase; seed via canonical phrase | `Bip39RecoveryTest` |
| Wordlist is the canonical 2048-word English list (SHA-256) | `Bip39RecoveryTest.wordlist is the canonical...` |
| 11/13 words, unknown word, tampered checksum rejected; repeats accepted | `Bip39RecoveryTest.validatePhrase...` |
| Generated phrases: 12 unique valid words | `Bip39RecoveryTest.generatePhrase...` |
| KeyEnvelope byte layout, hostile-field and truncation rejection | `KeyEnvelopeTest` |
| SHA-256/HMAC known answers, constant-time compare, wipe | `NoteCryptoTest` |
| Keystore per-use auth refuses prompt-free `doFinal` | `BiometricKeyManagerTest` (androidTest) |

No secrets in tests: fixtures are published test vectors or scheme-format constants, never
real keys, passwords or phrases from a user vault.

---

## 9. Test vector citations

- **PBKDF2-HMAC-SHA256**: RFC 7914 §11, https://www.rfc-editor.org/rfc/rfc7914.txt
  (`passwd`/`salt`/c=1 and `Password`/`NaCl`/c=80,000, dkLen=64; the 32-byte key is the
  prefix).
- **AES-256-GCM**: NIST CAVP `gcmEncryptExtIV256.rsp`, Keylen=256, IVlen=96, Taglen=128,
  https://csrc.nist.gov/projects/cryptographic-algorithm-validation-program (four vectors
  with and without AAD, 0–51 byte plaintexts, 0–90 byte AADs; values are committed as hex
  literals in `NoteCryptoTest`).
- **BIP-39 English**: official Trezor vectors
  https://github.com/trezor/python-mnemonic/blob/master/vectors.json (eight 12-word
  vectors, plus official BIP-39 seed values under passphrase "TREZOR") and the canonical
  wordlist https://github.com/bitcoin/bips/blob/master/bip-0039/english.txt (SHA-256 of the
  newline-joined list is asserted in `Bip39RecoveryTest`).
- **HMAC-SHA256**: RFC 4231 test cases 1 and 2, https://www.rfc-editor.org/rfc/rfc4231.txt
- **SHA-256**: FIPS 180-2 test vector "abc"
  (ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad).
- **Base64/hex**: RFC 4648 §10 vectors (`""`, `f`, `fo`, `foo`, `foob`, `fooba`, `foobar`).

These are the only oracles used: no vector is produced by the implementation under test,
and the BIP-39 reference seed is computed with the JDK's PBKDF2-HMAC-SHA512 in the test
itself.

---

## 10. Known limitations / follow-ups

- **JVM zeroization is best-effort**: copies made by JCA providers, string interning and GC
  cannot be controlled. Passwords are handled as `CharArray` and phrases as `List<String>`
  (BIP-39 wordlists are public constants, so a phrase's word objects are only as sensitive
  as its ordering; the ordering is not zeroable via String).
- **No memory locking / no key-in-hardware for content**: the VMK is RAM-resident while
  unlocked. This is the same bar as VaultZero; a rooted device with process memory access
  can extract it while unlocked (see `docs/THREAT-MODEL.md`).
- **PBKDF2 600k is a floor, not a KDF race winner**: Argon2id is the reserved upgrade
  (§2.2) and must be adopted before any claim of best-practice password hashing.
- **Iteration upgrade is data-layer policy**: this module provides the versioned slot
  (envelope stores its own iterations); the re-wrap-on-unlock behaviour is implemented and
  tested in the data layer, not here.
- **Biometric prompt UX**: the crypto layer returns ciphers; it does not show prompts. The
  UI must pass the cipher through a `BiometricPrompt.CryptoObject` and handle
  cancel/lockout; UI-owned tests cover that.
- **Hardware variance**: StrongBox/TEE behaviour, auth-per-use timing and invalidation
  exceptions vary by OEM/API level; device tests skip gracefully where the hardware cannot
  support the flows.

---

## 11. Audit trail — implementation vs. bootstrap contract stubs

The bootstrap stubs said "CONTRACT ONLY"; this section records every deliberate
difference between those stubs / `docs/CONTRACTS.md` and the shipped implementation, so a
reviewer can see the crypto surface was not expanded silently.

1. **Biometric enrolment became two-phase (owner-approved contract change).** A
   per-use-auth Android Keystore key cannot encrypt without a successful BiometricPrompt
   (`Cipher.doFinal` throws `UserNotAuthenticatedException`), so a prompt-free
   `enroll(vmk): Boolean` was cryptographically impossible without weakening the gate. The
   Lead removed `enroll(vmk)` and froze `prepareEnroll(): BiometricEnroll?` +
   `completeEnroll(enroll, vmk): Boolean` (plus `data class BiometricEnroll`).
   `docs/CONTRACTS.md` and `data/NoteRepository.kt` were updated by the Lead accordingly;
   no prompt-free enrolment path exists in the implementation.
2. **`internal` test hooks (visibility only, no public API growth).**
   - `NoteCrypto.sealWithIv(key, plaintext, aad, iv)` — AES-GCM with caller-supplied IV;
     required to check NIST CAVP known-answer vectors (which fix the IV).
   - `NoteCrypto.pbkdf2Raw(password, salt, iterations)` — PBKDF2 without the production
     iteration floor, required to exercise the RFC 7914 vectors that use `c=1`.
   Both are `internal`; production encryption/KDF behaviour is unchanged and all public
   entry points still always draw a fresh random IV / enforce ≥ 10,000 iterations.
3. **`KeyEnvelope` hardening beyond the documented layout.** The byte layout is exactly as
   documented (no format change), but parser/writer add bounds the stub did not state:
   `MAX_ITERATIONS = 10_000_000` (rejects hostile/weak work factors on read *and* write),
   `saltLen` fixed to 16 for v1, `wrappedLen ≥ 28` (IV+tag), `MAX_BYTES = 1024` enforced,
   trailing bytes rejected. `fromBytes` returns `null` for every failure — never throws,
   never returns a partially-trusted envelope.
4. **`NoteCrypto.unwrapVmk` length check.** A payload that decrypts successfully but is not
   exactly 32 bytes returns `null` (and is wiped), so a same-domain ciphertext of the wrong
   shape can never be mistaken for a VMK.
5. **`NoteCrypto.secretKey` requires exactly 32 bytes** (AES-256 only) and throws
   `IllegalArgumentException` otherwise; the stub did not state the rejection behaviour.
6. **`NoteCrypto.constantTimeEquals`** delegates to `MessageDigest.isEqual` (JDK
   constant-time implementation); only the length leak is accepted, as documented in the
   stub.
7. **Biometric wrap storage and domain separation.** The wrapped VMK copy is stored in
   app-private `SharedPreferences` (ciphertext only), not in the Room DB, so a DB restore
   cannot resurrect a biometric unlock on another device. Both Keystore AES-GCM operations
   additionally bind the private AAD string `notezero.biometric.v1` (kept private to
   `BiometricKeyManager`; it never appears in `CryptoAad` because it is not an on-disk
   envelope domain). `prepareDecrypt()` wipes stale state on
   `KeyPermanentlyInvalidatedException`/`UnrecoverableKeyException`, and wipes an orphaned
   wrap when the Keystore key is gone.
8. **`isEnrolled()` probes with `Cipher.init`** (no prompt, no secret released) so that a
   key invalidated by biometric re-enrolment reads as "not enrolled" instead of failing
   later; it never throws and never returns key material.
9. **No other public crypto shape changed.** `docs/CONTRACTS.md` crypto contract lines
   (NoteCrypto, Bip39Recovery, KeyEnvelope, CryptoAad values) are implemented as written.
   `Encoding.kt` was already implemented and is unchanged (tests added for it).
   No new dependencies; no `android.*` imports outside `BiometricKeyManager`; every
   `app/src/test` crypto test runs without Robolectric.

### Verification evidence at handoff

JVM tests are plain JUnit 4 and were executed standalone against the same Kotlin 2.2.10
compiler the build uses (the full Gradle run was blocked by other owners' in-progress
files; the Lead owns the integration build):

```
# compile (crypto sources + tests, Kotlin 2.2.10, JUnit 4.13.2)
java -cp <kotlin-compiler-embeddable-2.2.10 + stdlib + coroutines + trove + annotations> \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
  -cp <kotlin-stdlib>:<junit-4.13.2>:<hamcrest-core-1.3> -d /tmp/cryptocheck/out \
  app/src/main/java/com/cipherwell/notezero/crypto/{NoteCrypto,KeyEnvelope,Bip39Recovery,Encoding}.kt \
  app/src/test/java/com/cipherwell/notezero/crypto/{NoteCryptoTest,KeyEnvelopeTest,Bip39RecoveryTest,EncodingTest}.kt

# run
java -cp /tmp/cryptocheck/out:<stdlib>:<junit>:<hamcrest> org.junit.runner.JUnitCore \
  com.cipherwell.notezero.crypto.{NoteCryptoTest,KeyEnvelopeTest,Bip39RecoveryTest,EncodingTest}
```

Result: `OK (61 tests)` — NoteCryptoTest 28, KeyEnvelopeTest 13, Bip39RecoveryTest 13,
EncodingTest 7. The Android-only `BiometricKeyManager.kt` was additionally compiled
standalone against `android-36/android.jar` + `androidx.biometric-1.2.0-alpha05` (0 errors);
its device tests (`app/src/androidTest/.../crypto/BiometricKeyManagerTest.kt`) run under
`connectedDebugAndroidTest` and skip gracefully via `Assume` without enrolled biometrics.
