package com.cipherwell.notezero.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTest {

    @Test
    fun mapsExtensionsCaseInsensitively() {
        assertEquals(CodeLanguage.KOTLIN, Languages.fromFileName("Main.kt"))
        assertEquals(CodeLanguage.KOTLIN, Languages.fromFileName("build.gradle.kts"))
        assertEquals(CodeLanguage.JAVA, Languages.fromFileName("Application.java"))
        assertEquals(CodeLanguage.JAVASCRIPT, Languages.fromFileName("app.js"))
        assertEquals(CodeLanguage.JAVASCRIPT, Languages.fromFileName("module.mjs"))
        assertEquals(CodeLanguage.JAVASCRIPT, Languages.fromFileName("legacy.cjs"))
        assertEquals(CodeLanguage.JAVASCRIPT, Languages.fromFileName("Component.jsx"))
        assertEquals(CodeLanguage.TYPESCRIPT, Languages.fromFileName("index.ts"))
        assertEquals(CodeLanguage.TYPESCRIPT, Languages.fromFileName("Component.tsx"))
        assertEquals(CodeLanguage.PYTHON, Languages.fromFileName("script.py"))
        assertEquals(CodeLanguage.PYTHON, Languages.fromFileName("stub.pyi"))
        assertEquals(CodeLanguage.JSON, Languages.fromFileName("data.json"))
        assertEquals(CodeLanguage.XML, Languages.fromFileName("layout.xml"))
        assertEquals(CodeLanguage.XML, Languages.fromFileName("icon.svg"))
        assertEquals(CodeLanguage.XML, Languages.fromFileName("index.html"))
        assertEquals(CodeLanguage.MARKDOWN, Languages.fromFileName("README.md"))
        assertEquals(CodeLanguage.MARKDOWN, Languages.fromFileName("notes.markdown"))
        assertEquals(CodeLanguage.SHELL, Languages.fromFileName("install.sh"))
        assertEquals(CodeLanguage.SHELL, Languages.fromFileName("run.bash"))
        assertEquals(CodeLanguage.KOTLIN, Languages.fromFileName("WeIrD.KT"))
        assertEquals(CodeLanguage.PYTHON, Languages.fromFileName("/path/to/deep/script.PY"))
    }

    @Test
    fun unknownOrMissingExtensionsFallBackToPlain() {
        assertEquals(CodeLanguage.PLAIN, Languages.fromFileName(""))
        assertEquals(CodeLanguage.PLAIN, Languages.fromFileName("Makefile"))
        assertEquals(CodeLanguage.PLAIN, Languages.fromFileName("noextension"))
        assertEquals(CodeLanguage.PLAIN, Languages.fromFileName("trailing."))
        assertEquals(CodeLanguage.PLAIN, Languages.fromFileName(".gitignore"))
        assertEquals(CodeLanguage.PLAIN, Languages.fromFileName("archive.tar.gz"))
        assertEquals(CodeLanguage.PLAIN, Languages.fromFileName("notes.txt"))
        assertEquals(CodeLanguage.PLAIN, Languages.fromFileName("photo.png"))
    }

    @Test
    fun editorLanguagesExcludePlainAndCoverEveryOtherLanguage() {
        assertEquals(CodeLanguage.entries.size - 1, Languages.editorLanguages.size)
        assertTrue(CodeLanguage.PLAIN !in Languages.editorLanguages)
        for (language in CodeLanguage.entries) {
            if (language != CodeLanguage.PLAIN) {
                assertTrue("$language missing from editorLanguages", language in Languages.editorLanguages)
            }
        }
    }

    @Test
    fun defaultFileNamesRoundTripThroughTheExtensionMapping() {
        assertEquals("untitled.kt", Languages.defaultFileName(CodeLanguage.KOTLIN))
        assertEquals("untitled.java", Languages.defaultFileName(CodeLanguage.JAVA))
        assertEquals("untitled.js", Languages.defaultFileName(CodeLanguage.JAVASCRIPT))
        assertEquals("untitled.ts", Languages.defaultFileName(CodeLanguage.TYPESCRIPT))
        assertEquals("untitled.py", Languages.defaultFileName(CodeLanguage.PYTHON))
        assertEquals("untitled.json", Languages.defaultFileName(CodeLanguage.JSON))
        assertEquals("untitled.xml", Languages.defaultFileName(CodeLanguage.XML))
        assertEquals("untitled.md", Languages.defaultFileName(CodeLanguage.MARKDOWN))
        assertEquals("untitled.sh", Languages.defaultFileName(CodeLanguage.SHELL))
        assertEquals("untitled.txt", Languages.defaultFileName(CodeLanguage.PLAIN))
        for (language in Languages.editorLanguages) {
            assertEquals(
                "default name for $language must map back to the language",
                language,
                Languages.fromFileName(Languages.defaultFileName(language)),
            )
        }
        assertEquals(
            CodeLanguage.PLAIN,
            Languages.fromFileName(Languages.defaultFileName(CodeLanguage.PLAIN)),
        )
    }

    @Test
    fun everyLanguageHasALexer() {
        for (language in CodeLanguage.entries) {
            assertEquals(language, CodeLexers.forLanguage(language).language)
        }
    }
}
