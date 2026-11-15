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

## Inventory (bootstrap 2026-11-15; licences added by the Phase-5 remediation)

Licences below were read from the cached POMs of the resolved coordinates
(`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`; audit D9). All are
MIT-compatible; the app itself is MIT.

### Runtime (shipped in the APK)

| Coordinate | Version | Purpose | Licence (POM) | Offline-safe |
|---|---|---|---|---|
| `androidx.core:core-ktx` | 1.18.0 | KTX helpers | Apache-2.0 | yes |
| `androidx.activity:activity-compose` | 1.10.1 | Compose activity host | Apache-2.0 | yes |
| `androidx.compose:compose-bom` (ui, graphics, tooling-preview, material3, icons-core) | 2024.09.00 | Compose UI | Apache-2.0 | yes |
| `androidx.lifecycle:lifecycle-runtime-{ktx,compose}`, `viewmodel-compose` | 2.8.7 | lifecycle + ViewModels | Apache-2.0 | yes |
| `androidx.navigation:navigation-compose` | 2.8.9 | screen navigation | Apache-2.0 | yes |
| `androidx.room:room-{runtime,ktx}` (+ `room-compiler` build-time) | 2.7.0 | encrypted-field SQLite store | Apache-2.0 | yes |
| `androidx.datastore:datastore-preferences` | 1.1.7 | non-secret preferences | Apache-2.0 | yes |
| `androidx.biometric:biometric` | 1.2.0-alpha05 | availability checks only (platform prompt used) | Apache-2.0 | yes (no network) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-{android,core}` | 1.10.2 | concurrency | Apache-2.0 | yes |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.10 | Kotlin runtime | Apache-2.0 | yes |
| `com.google.guava:listenablefuture` | 1.0 (transitive) | coroutines/AndroidX bridge | Apache-2.0 | yes |
| `com.squareup.okio:okio` | 3.4.0 (transitive, via DataStore `datastore-core-okio`) | I/O only — no network client, no permission | Apache-2.0 | yes |
| `androidx.datastore:datastore-preferences-external-protobuf` | 1.1.7 (transitive) | protobuf-lite for DataStore | **BSD-3-Clause** | yes; retain notice |
| `org.jspecify:jspecify` | 1.0.0 (transitive) | nullability annotations | Apache-2.0 / BSD-style (per POM) | yes |

Prebuilt native libraries packaged from those dependencies (no NDK build in this repo, so
`fdroid/…yml` says `ndk: absent`): `libdatastore_shared_counter.so` (DataStore) and
`libandroidx.graphics.path.so` (androidx.graphics), four ABIs. They are local I/O/graphics
code, not network or telemetry, and do not change the offline guarantee.

Packaging note: `/META-INF/{AL2.0,LGPL2.1}` is excluded and `dependenciesInfo` is disabled,
which removes bundled Apache notice text from the APK. That is legal hygiene to revisit
(notices file or store metadata), not a shipping defect.

### Build-time only (not in the APK)

| Coordinate | Version | Purpose | Licence |
|---|---|---|---|
| `com.android.application` (AGP) | 9.1.1 | Android build | Apache-2.0 |
| `org.jetbrains.kotlin.plugin.compose` | 2.2.10 | Compose compiler | Apache-2.0 |
| `com.google.devtools.ksp` | 2.3.5 | Room annotation processing | Apache-2.0 |

### Test-only (not shipped)

| Coordinate | Version | Purpose | Licence |
|---|---|---|---|
| `junit:junit` | 4.13.2 | unit tests | **EPL-1.0** (test-only, not distributed) |
| `org.robolectric:robolectric` | 4.16.1 | JVM Android tests | MIT |
| `androidx.test:*` | 1.6.1 / 1.3.0 / 1.6.2 / 3.7.0 | test infrastructure | Apache-2.0 |
| `cash.z.ecc.android:kotlin-bip39-jvm` | 1.0.9 | independent BIP-39 cross-check | MIT |

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
