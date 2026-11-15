# NoteZero — dependency policy & inventory

## Policy

1. Every runtime dependency must be **offline-safe**: no network client, no telemetry,
   no remote config, no analytics, no crash-reporting upload. This is verified by
   `scripts/check-offline.sh --gradle` and `OfflineGuaranteeTest`.
2. Prefer AndroidX/platform APIs and pure Kotlin over third-party libraries. Rich text,
   markdown, syntax highlighting and editor behaviour are **ours** (decisions 2026-11-15).
3. Any new dependency needs a review note here: coordinate, version, purpose, why the
   platform cannot do it, licence, offline verdict. Commit the note with the version bump.
4. No JSON/HTTP/crypto convenience libraries. Crypto is our audited core, not a wrapper.
5. Pin exact versions in `gradle/libs.versions.toml`; no dynamic versions.

## Inventory (bootstrap, 2026-11-15)

| Coordinate | Version | Purpose | Offline-safe |
|---|---|---|---|
| `com.android.application` (AGP) | 9.1.1 | Android build | build-time only |
| `org.jetbrains.kotlin.plugin.compose` | 2.2.10 | Compose compiler | build-time only |
| `com.google.devtools.ksp` | 2.3.5 | Room annotation processing | build-time only |
| `androidx.core:core-ktx` | 1.18.0 | KTX helpers | yes |
| `androidx.activity:activity-compose` | 1.10.1 | Compose activity host | yes |
| `androidx.compose:compose-bom` | 2024.09.00 | Compose BOM (ui, material3, icons-core, tooling) | yes |
| `androidx.lifecycle:*` | 2.8.7 | lifecycle-runtime/compose, viewmodel-compose | yes |
| `androidx.navigation:navigation-compose` | 2.8.9 | screen navigation | yes |
| `androidx.room:room-{runtime,ktx,compiler}` | 2.7.0 | encrypted-field SQLite store | yes |
| `androidx.datastore:datastore-preferences` | 1.1.7 | non-secret preferences | yes |
| `androidx.biometric:biometric` | 1.2.0-alpha05 | BiometricPrompt + CryptoObject | yes (no network) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-*` | 1.10.2 | concurrency | yes |
| `junit:junit` | 4.13.2 | unit tests | test-time |
| `org.robolectric:robolectric` | 4.16.1 | JVM Android tests | test-time |
| `androidx.test:*` | 1.6.1 / 1.3.0 / 1.6.2 / 3.7.0 | test infrastructure | test-time |
| `cash.z.ecc.android:kotlin-bip39-jvm` | 1.0.9 | **test-only** independent BIP-39 cross-check | test-time |

Notes:
- `androidx.biometric` is pinned to the 1.2.0 alpha line because it is the only line that
  supports `CryptoObject` with `setInvalidatedByBiometricEnrollment` semantics we rely on
  for the same security bar as VaultZero. Re-evaluate at first stable 1.2.x.
- No Google Play Services, Firebase, AppCompat, or Material Components dependency: the app
  is Compose + platform only, which keeps the manifest surface small.
- `androidx.test:core`/`runner` are test-only and contribute nothing to the release APK.

## Explicitly rejected

| Candidate | Why rejected |
|---|---|
| OkHttp / Retrofit / Ktor | Network; NoteZero has no network permission by design |
| Firebase / Crashlytics / Sentry | Telemetry/upload; violates non-goals |
| SQLCipher | Adds native blob; field-level AES-GCM envelopes are the audited design |
| Rich-text/editor libraries (e.g. Compose WYSIWYG) | Audit surface; custom block model decided |
| JSON libraries | No need; binary envelopes are versioned and bounds-checked in-house |
| BouncyCastle | Platform JCE covers AES-GCM/PBKDF2; no extra crypto provider |
