package com.cipherwell.notezero

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cipherwell.notezero.crypto.Bip39Recovery
import com.cipherwell.notezero.data.NoteRepository
import com.cipherwell.notezero.data.UnlockResult
import com.cipherwell.notezero.data.VaultStatus
import com.cipherwell.notezero.model.ItemKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * End-to-end verification on a real device/emulator, run against a FRESH install.
 *
 * Proves the security bar end to end, not just in unit tests:
 *  1. real vault setup -> BIP-39 phrase -> lock/unlock with real Android crypto,
 *  2. password change re-wraps the VMK WITHOUT re-encrypting item ciphertext,
 *  3. recovery-phrase reset works and old password stops working,
 *  4. at-rest footprint: no plaintext title/body anywhere in the Room database files,
 *  5. real UI flow: unlock -> notes list -> create note -> editor -> back,
 *  6. screenshots of the real screens for the release evidence trail.
 *
 * The test intentionally fails (rather than skips) if the device is not freshly installed,
 * so it can never silently pass against a stale vault.
 *
 * Run: ./gradlew :app:connectedDebugAndroidTest \
 *        -Pandroid.testInstrumentationRunnerArguments.class=com.cipherwell.notezero.VaultFlowE2ETest
 * (build with -PallowScreenshots=true so captureToImage is not blocked by FLAG_SECURE.)
 */
@RunWith(AndroidJUnit4::class)
class VaultFlowE2ETest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var repository: NoteRepository

    private val firstPassword = "NoteZero-E2E-first-2026!".toCharArray()
    private val secondPassword = "NoteZero-E2E-second-2026!".toCharArray()
    private val wrongPassword = "not-the-password".toCharArray()

    private companion object {
        const val NOTE_TITLE = "E2E persisted secret title"
        const val NOTE_BODY = "# Heading\n- [ ] unchecked task\n- [x] done task\n```kotlin\nval answer = 42\n```"
        const val EDITOR_NOTE_TITLE = "Second note from editor"
        const val SECOND_BODY = "# Preview\n- [ ] tap me\n\n> quote\n\n| a | b |\n|---|---|\n| 1 | 2 |"
        const val CODE_FILE_NAME = "hello.kt"
        const val CODE_SECRET_MARKER = "CodeE2E_secret"
        const val CODE_BODY =
            "fun main() {\n    val secret = \"$CODE_SECRET_MARKER\"\n    println(secret)\n}"
        const val DB_NAME = "notezero-vault.db"
    }

    @Before
    fun requireFreshInstall() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Wipe any previous vault + non-secret prefs before the app-scoped repository is
        // constructed. NoteZeroApp.repository is lazy, so nothing has opened the DB yet.
        context.deleteDatabase(DB_NAME)
        File(context.filesDir, "datastore").deleteRecursively()
        // Non-secret presence marker owned by EncryptedNoteRepository.
        File(context.filesDir, "notezero.vault.presence").delete()
        repository = (context.applicationContext as NoteZeroApp).repository
        assertEquals(
            "E2E test requires a fresh install (found ${repository.status.value}); reinstall the APK",
            VaultStatus.SETUP_REQUIRED,
            repository.status.value,
        )
    }

    @After
    fun tearDown() {
        runBlocking { repository.lock() }
    }

    @Test
    fun fullVaultLifecycleAndUiFlow() {
        // ---- 1. Setup + real BIP-39 phrase ------------------------------------------------
        val phrase = runBlocking { repository.setupVault(firstPassword.copyOf()) }
        assertEquals(12, phrase.size)
        assertTrue("setup phrase must pass BIP-39 validation", Bip39Recovery.validatePhrase(phrase))
        assertTrue(runBlocking { repository.validateRecoveryPhrase(phrase) })
        assertFalse(
            "short phrase must be rejected",
            runBlocking { repository.validateRecoveryPhrase(listOf("abandon")) },
        )
        assertEquals(VaultStatus.UNLOCKED, repository.status.value)

        // ---- 2. Encrypted item + at-rest footprint ---------------------------------------
        val saved = runBlocking {
            val created = repository.createItem(ItemKind.NOTE)
            repository.saveItem(created.copy(title = NOTE_TITLE, body = NOTE_BODY))
        }
        val ciphertextBefore = itemCiphertextHex(saved.id)
        assertNotEquals("item ciphertext must not be empty", "", ciphertextBefore)

        // Password change must NOT re-encrypt notes (VMK re-wrap only).
        assertTrue(runBlocking { repository.changePassword(firstPassword.copyOf(), secondPassword.copyOf()) })
        assertEquals(
            "changePassword must not re-encrypt item ciphertext",
            ciphertextBefore,
            itemCiphertextHex(saved.id),
        )

        // ---- 3. Lock / wrong password / unlock with new password -------------------------
        runBlocking { repository.lock() }
        assertEquals(VaultStatus.LOCKED, repository.status.value)
        assertTrue("locked repository must expose no decrypted items", repository.items.value.isEmpty())
        assertEquals(
            UnlockResult.WRONG_PASSWORD,
            runBlocking { repository.unlock(wrongPassword.copyOf()) },
        )
        assertEquals(UnlockResult.SUCCESS, runBlocking { repository.unlock(secondPassword.copyOf()) })
        assertTrue(repository.items.value.any { it.id == saved.id && it.title == NOTE_TITLE })

        // ---- 4. Recovery phrase reset ----------------------------------------------------
        runBlocking { repository.lock() }
        assertEquals(
            UnlockResult.SUCCESS,
            runBlocking { repository.resetWithRecoveryPhrase(phrase, firstPassword.copyOf()) },
        )
        assertEquals(
            "old password must stop working after recovery reset",
            UnlockResult.WRONG_PASSWORD,
            runBlocking { repository.unlock(secondPassword.copyOf()) },
        )
        assertEquals(VaultStatus.LOCKED, repository.status.value)
        assertEquals(UnlockResult.SUCCESS, runBlocking { repository.unlock(firstPassword.copyOf()) })
        assertTrue(repository.items.value.any { it.id == saved.id && it.title == NOTE_TITLE })

        // ---- 5. Plaintext must not exist in the Room database files ----------------------
        runBlocking { repository.lock() }
        val onDisk = databaseBytes()
        assertTrue("expected database files on disk", onDisk.isNotEmpty())
        val latin = String(onDisk, Charsets.ISO_8859_1)
        assertFalse("plaintext note title leaked into the database files", latin.contains(NOTE_TITLE))
        assertFalse("plaintext note body leaked into the database files", latin.contains("unchecked task"))
        assertFalse(
            "recovery phrase word leaked into the database files",
            latin.contains(phrase.first()) && latin.contains(phrase.last()),
        )

        // ---- 6. Real UI flow: unlock -> notes -> editor ----------------------------------
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitForTag("unlock_screen", 20_000)
            saveScreenshot("00-unlock")
            compose.onNodeWithTag("unlock_password").performClick()
            compose.onNodeWithTag("unlock_password").performTextInput(String(firstPassword))
            compose.onNodeWithTag("unlock_button").performClick()
            compose.waitForTag("notes_list", 25_000)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(NOTE_TITLE, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            saveScreenshot("01-notes-list")

            // Create a note through the real editor.
            compose.onNodeWithTag("new_note_button").performClick()
            compose.waitForTag("editor_title", 15_000)
            compose.onNodeWithTag("editor_title").performClick()
            compose.onNodeWithTag("editor_title").performTextInput(EDITOR_NOTE_TITLE)
            compose.onNodeWithTag("editor_body").performClick()
            compose.onNodeWithTag("editor_body").performTextInput(SECOND_BODY)
            compose.waitForIdle()
            saveScreenshot("02-editor-edit")
            // Render the parsed block preview (headings, checklist, quote, table).
            compose.onNodeWithTag("editor_preview_toggle").performClick()
            compose.waitForIdle()
            saveScreenshot("03-editor-preview")
            compose.onNodeWithTag("editor_preview_toggle").performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("editor_back").performClick()
            compose.waitForTag("notes_list", 20_000)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(EDITOR_NOTE_TITLE, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            saveScreenshot("04-notes-list-two-notes")

            // ---- 6b. Settings with the backup section --------------------------------
            compose.onNodeWithTag("settings_button").performClick()
            compose.waitForTag("settings_screen", 15_000)
            // Scroll to the Data & backup section so the evidence screenshot shows it.
            repeat(3) {
                compose.onNodeWithTag("settings_screen").performTouchInput { swipeUp() }
                compose.waitForIdle()
            }
            saveScreenshot("05-settings-backup")
            // The settings header scrolls with the content, so use the system back action,
            // which is routed through SettingsScreen's BackHandler.
            pressSystemBack()
            compose.waitForTag("notes_list", 15_000)

            // ---- 6c. Code workspace: create a file, edit it, persist -----------------
            compose.onNodeWithTag("nav_code").performClick()
            compose.waitForTag("code_workspace", 20_000)
            saveScreenshot("06-code-workspace-empty")
            compose.onNodeWithTag("code_new_file").performClick()
            compose.waitForTag("code_dialog_field", 15_000)
            compose.onNodeWithTag("code_dialog_field").performClick()
            compose.onNodeWithTag("code_dialog_field").performTextInput(CODE_FILE_NAME)
            compose.onNodeWithTag("code_dialog_confirm").performClick()
            compose.waitForTag("code_editor", 20_000)
            compose.onNodeWithTag("code_editor").performClick()
            compose.onNodeWithTag("code_editor").performTextInput(CODE_BODY)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Saved", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            saveScreenshot("07-code-editor-kotlin")
            compose.onNodeWithTag("code_back").performClick()
            compose.waitForTag("code_workspace", 20_000)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(CODE_FILE_NAME, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            saveScreenshot("08-code-workspace-file")
        } finally {
            scenario.close()
        }

        // ---- 7. Editor content persisted encrypted (decrypts back, no plaintext at rest) --
        assertEquals(UnlockResult.SUCCESS, runBlocking { repository.unlock(firstPassword.copyOf()) })
        val editorItem = repository.items.value.firstOrNull { it.title == EDITOR_NOTE_TITLE }
        assertTrue("note created in the editor must persist", editorItem != null)
        assertTrue(editorItem!!.body.contains("tap me"))
        val codeItem = repository.items.value.firstOrNull { it.title == CODE_FILE_NAME }
        assertTrue("code file created in the workspace must persist", codeItem != null)
        assertEquals(ItemKind.CODE_FILE, codeItem!!.kind)
        assertTrue(
            "code editor body must persist through autosave",
            codeItem.body.contains(CODE_SECRET_MARKER),
        )
        runBlocking { repository.lock() }
        val afterUiDisk = String(databaseBytes(), Charsets.ISO_8859_1)
        assertFalse("editor plaintext title leaked into the database", afterUiDisk.contains(EDITOR_NOTE_TITLE))
        assertFalse("code file plaintext title leaked into the database", afterUiDisk.contains(CODE_FILE_NAME))
        assertFalse("code file plaintext body leaked into the database", afterUiDisk.contains(CODE_SECRET_MARKER))
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForTag(tag: String, timeoutMs: Long) {
        waitUntil(timeoutMs) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    /** System back (routes through the app's BackHandler instead of a scrolled-away button). */
    private fun pressSystemBack() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /** All sqlite files for the vault DB (main + WAL + SHM), concatenated. */
    private fun databaseBytes(): ByteArray {
        val dir = File(context.applicationInfo.dataDir, "databases")
        val files = dir.listFiles { f -> f.name.startsWith(DB_NAME) }?.sortedBy { it.name } ?: emptyList()
        val out = ByteArrayOutputStream()
        files.forEach { out.write(it.readBytes()) }
        return out.toByteArray()
    }

    /** Reads the raw AES-GCM envelope through a second read-only SQLite connection. */
    private fun itemCiphertextHex(id: String): String {
        val path = File(context.applicationInfo.dataDir, "databases/$DB_NAME").absolutePath
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT ciphertext FROM items WHERE id = ?", arrayOf(id)).use { cursor ->
                assertTrue("item row not found", cursor.moveToFirst())
                val bytes = cursor.getBlob(0)
                return bytes.joinToString("") { "%02x".format(it) }
            }
        }
    }
}
