package com.cipherwell.notezero.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Offline guarantee as a unit test (runs on every CI build without a device).
 *
 * The manifest is the enforcement point: NoteZero must declare exactly one permission
 * (USE_BIOMETRIC) and must keep the explicit `tools:node="remove"` entries for the
 * network permissions so a future transitive dependency cannot smuggle them back in.
 * The source scan mirrors scripts/check-offline.sh for IDE/test visibility.
 */
class OfflineGuaranteeTest {

    private val appDir: File = findAppDir()
    private val manifest: String = File(appDir, "src/main/AndroidManifest.xml").readText()

    @Test
    fun `only USE_BIOMETRIC permission is declared`() {
        val declared = permissionNames(manifest)
        assertEquals("NoteZero must declare exactly USE_BIOMETRIC", listOf("USE_BIOMETRIC"), declared)
    }

    @Test
    fun `network permissions are explicitly stripped in the manifest`() {
        // The guard entries must stay: they win over any library manifest contribution.
        assertTrue(
            "INTERNET must be stripped via tools:node=\"remove\"",
            manifest.contains("android.permission.INTERNET") &&
                manifest.contains("tools:node=\"remove\""),
        )
        assertTrue(
            "ACCESS_NETWORK_STATE must be stripped via tools:node=\"remove\"",
            manifest.contains("android.permission.ACCESS_NETWORK_STATE"),
        )
    }

    @Test
    fun `app cannot be backed up or send cleartext`() {
        assertTrue("allowBackup must be false", manifest.contains("android:allowBackup=\"false\""))
        assertTrue(
            "cleartext traffic must be disabled",
            manifest.contains("android:usesCleartextTraffic=\"false\""),
        )
        assertTrue(
            "network security config must be set",
            manifest.contains("android:networkSecurityConfig="),
        )
    }

    @Test
    fun `no network APIs are imported in app sources`() {
        val patterns = listOf(
            Regex("""^import\s+(java|javax)\.net\.""", RegexOption.MULTILINE),
            Regex("""^import\s+okhttp3\.""", RegexOption.MULTILINE),
            Regex("""^import\s+io\.ktor\.""", RegexOption.MULTILINE),
            Regex("""^import\s+retrofit2\.""", RegexOption.MULTILINE),
            Regex("""^import\s+com\.android\.volley\.""", RegexOption.MULTILINE),
        )
        val sources = File(appDir, "src/main/java")
            .walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()
        assertTrue("expected app sources to be found", sources.isNotEmpty())

        val offenders = sources.flatMap { file ->
            val text = file.readText()
            patterns.mapNotNull { p -> p.find(text)?.let { "${file.name}: ${it.value}" } }
        }
        assertTrue("network APIs imported in app sources: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no plaintext URL literals in app sources`() {
        val url = Regex("""https?://[A-Za-z]""")
        val offenders = File(appDir, "src/main/java")
            .walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { file -> file.readLines().any { line -> !isComment(line) && url.containsMatchIn(line) } }
            .map { it.name }
            .toList()
        assertTrue("URL literals in app sources: $offenders", offenders.isEmpty())
    }

    private fun isComment(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    /** Names of permissions declared for real (not stripped via tools:node="remove"). */
    private fun permissionNames(xml: String): List<String> =
        xml.lines()
            .filter { it.contains("<uses-permission") && !it.contains("tools:node=\"remove\"") }
            .flatMap { line ->
                Regex("android:name=\"android\\.permission\\.([A-Z_]+)\"")
                    .findAll(line)
                    .map { it.groupValues[1] }
            }
            .toList()
            .sorted()

    /** Unit tests run with the module directory as cwd; walk up defensively. */
    private fun findAppDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val candidate = dir ?: return@repeat
            if (File(candidate, "src/main/AndroidManifest.xml").isFile) return candidate
            val parent = candidate.parentFile
            dir = parent
        }
        error("could not locate app module from user.dir=${System.getProperty("user.dir")}")
    }
}
