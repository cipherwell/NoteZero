# NoteZero

**Offline-first, end-to-end encrypted notes and code editor for Android.**
No account. No cloud. No network permission. Your notes never leave the device except
through an encrypted backup file you export yourself.

NoteZero is the second Cipherwell app, built to the same security bar as
[VaultZero](https://github.com/cipherwell/Vault-Zero) with an independent package
(`com.cipherwell.notezero`), independent signing key and independent threat model.

> Status: **Phase 1 in progress** — security core + notes MVP. The project scaffold,
> crypto contracts and decision record land first; features are listed in [SPEC.md](SPEC.md).

## Security properties (by construction)

- **No network permissions.** `INTERNET` and `ACCESS_NETWORK_STATE` are stripped from the
  merged manifest with `tools:node="remove"`, no network library is a dependency, and
  `scripts/check-offline.sh` + `OfflineGuaranteeTest` fail the build if that changes.
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
./gradlew :app:testDebugUnitTest
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
| [docs/TESTING.md](docs/TESTING.md) | Test strategy and commands |
| [docs/RELEASING.md](docs/RELEASING.md) | Release checklist, F-Droid eligibility |

## License

MIT — see [LICENSE](LICENSE). NoteZero is independent from VaultZero; they share design
patterns and test vectors, not code signing identity or product identity.
