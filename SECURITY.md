# Security Policy — NoteZero

## Reporting a vulnerability

**Do not open a public issue for a security problem.**

- Preferred: GitHub private security advisory —
  <https://github.com/cipherwell/NoteZero/security/advisories/new>
- Include: affected version/build (`versionName`, `versionCode`), device/Android version,
  reproduction steps, impact, and any proof-of-concept. If it involves crypto, say which
  primitive/envelope you attacked.
- We aim to acknowledge within 72 hours and to ship a fix or a mitigation plan within
  30 days for high-severity findings. A coordinated disclosure date will be agreed with you.

## Supported versions

| Version | Support |
|---|---|
| 0.1.x (pre-release, Phase 1) | Best effort during active development |
| ≥ 1.0 | Security fixes + signed patches |

Pre-release builds are development artifacts; do not rely on them to protect
high-value data yet.

## What is in scope

- Vault key hierarchy: VMK generation/wrapping, password KDF, recovery phrase handling,
  password change/recovery rotation (must never re-encrypt item data).
- Item envelope format: AES-256-GCM parameters, IV uniqueness, AAD binding to item id/type,
  tamper/corruption handling.
- Biometric unlock: Android Keystore key parameters, per-use auth, re-enrolment invalidation,
  fallback to password.
- Data at rest: Room schema redaction, app-private storage, backup exclusions,
  no plaintext metadata leaks (names, tags, folder structure).
- Backup envelope: confidentiality/integrity, duplicate-safe verified import, phrase-only mode.
- Offline guarantee: any network API / permission that appears in the merged manifest or
  runtime behaviour.
- UI leaks: FLAG_SECURE behaviour, sensitive clipboard handling, logs/crash output,
  recovery-phrase presentation.
- Dependency/lifecycle issues that break the above.

## What is out of scope

- A fully compromised OS, root malware with live memory access, or a malicious keyboard
  that logs the master password before it reaches NoteZero. NoteZero defends data at rest
  and the vault while locked; it cannot defend an unlocked vault on a fully compromised
  device (documented in `docs/THREAT-MODEL.md`).
- Physical access attacks against the Keystore/TEE that require a device exploit.
- Loss of data because the user lost both the master password and recovery phrase —
  by design there is no recovery backdoor.
- Denial of service by deleting app data / clearing storage.
- Findings that require the user to install a modified APK signed with a different key.
- Issues in third-party libraries that are not reachable from NoteZero's code paths
  (report upstream; we will track).

## Audits

- `docs/AUDIT-2026-11-15-phase5.md` — independent adversarial audit of the Phase-5 codebase
  (0 HIGH/CRITICAL; 2 MEDIUM + 8 LOW findings). All findings were remediated or explicitly
  deferred with reasons; the report contains the per-finding remediation log and evidence.

## Security expectations for contributions

- Crypto changes require a spec update in `docs/CRYPTO-SPEC.md` and test vectors.
- No new dependency without a review note in `docs/DEPENDENCIES.md`; no network dependency
  is ever acceptable.
- No secrets, keys, passwords, recovery phrases or plaintext note content in logs, tests,
  fixtures, commits or CI output.
- The offline guard (`scripts/check-offline.sh`, `OfflineGuaranteeTest`) must stay green.

## Release signing

NoteZero's upload key is independent from VaultZero's and never committed:

```
SHA-256: FA:CA:DD:67:B2:82:AF:A5:E7:F4:C9:C4:70:61:71:80:C9:EF:9E:2D:12:39:AD:F1:0C:29:C4:F9:95:AA:92:78
```

Verify any release APK against that certificate before trusting it. F-Droid builds are
signed by F-Droid's own key (reproducible-build verification applies).
