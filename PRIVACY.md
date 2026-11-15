# NoteZero — privacy policy

**NoteZero does not collect, transmit or share any data. There is no server, no account and no
network access at all.**

## What the app accesses

- **Your notes, folders, attachments and code files** — stored only on your device, encrypted
  with AES-256-GCM under a random 256-bit vault key. They never leave the device except through
  a backup file or export **you** explicitly create through Android's Storage Access Framework.
- **Your master password / recovery phrase** — processed on-device only (PBKDF2-HMAC-SHA256,
  600,000 iterations). Neither is stored in plaintext, logged, transmitted or recoverable by
  Cipherwell. Losing both means losing the vault by design.
- **Biometrics** — Android's biometric prompt is used through the platform API; the wrapping key
  lives in the Android Keystore. NoteZero never sees or stores biometric data.
- **Photos/files you attach** — read only when you pick them through the system Photo Picker /
  Storage Access Framework, then encrypted and stored in the app's private storage.
- **Settings** — theme, auto-lock timeout, editor preferences; stored on the device only.

## What the app does NOT do

- No internet permission is declared or merged, so no network request can be made by the app
  itself. `INTERNET` / `ACCESS_NETWORK_STATE` are stripped in the manifest and enforced in CI.
- No telemetry, analytics, ads, crash reporting, cloud sync, accounts or third-party SDKs.
- No access to contacts, location, camera (there is no camera permission), or your files
  outside what you explicitly pick.
- Android system backup and device-to-device transfer exclude all vault data
  (`allowBackup=false` plus explicit data-extraction rules).

## Data deletion

Uninstalling NoteZero or clearing its storage deletes the encrypted vault and its keys. Exported
backup files are yours to keep/delete — they are encrypted, but anyone with the file plus your
credentials can restore them, so store them safely.

## Contact

Security/privacy questions: open a private security advisory at
<https://github.com/cipherwell/NoteZero/security/advisories/new> (see SECURITY.md).
