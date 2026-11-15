// NoteZero — top-level build file.
// Offline-first, encrypted notes app + code editor. No network dependencies allowed.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
}
