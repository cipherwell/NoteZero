package com.cipherwell.notezero.code

import java.util.Locale

/**
 * Languages the code editor can highlight.
 *
 * [PLAIN] is the no-highlighting fallback (unknown extension, or a user turning highlighting
 * off); its lexer returns an empty token list.
 */
enum class CodeLanguage {
    KOTLIN,
    JAVA,
    JAVASCRIPT,
    TYPESCRIPT,
    PYTHON,
    JSON,
    XML,
    MARKDOWN,
    SHELL,
    PLAIN,
}

/**
 * Filename ↔ language mapping used by the code workspace.
 *
 * Extension matching is case-insensitive and based on the last `.` in the file name. Files
 * without an extension, dotfiles (`.gitignore`) and trailing dots map to [CodeLanguage.PLAIN].
 */
object Languages {

    private val byExtension: Map<String, CodeLanguage> = buildMap {
        for (ext in listOf("kt", "kts")) put(ext, CodeLanguage.KOTLIN)
        put("java", CodeLanguage.JAVA)
        for (ext in listOf("js", "mjs", "cjs", "jsx")) put(ext, CodeLanguage.JAVASCRIPT)
        for (ext in listOf("ts", "mts", "cts", "tsx")) put(ext, CodeLanguage.TYPESCRIPT)
        for (ext in listOf("py", "pyw", "pyi")) put(ext, CodeLanguage.PYTHON)
        for (ext in listOf("json", "jsonc", "json5")) put(ext, CodeLanguage.JSON)
        for (ext in listOf("xml", "xsl", "xslt", "svg", "xhtml", "html", "htm", "plist", "rss", "atom")) {
            put(ext, CodeLanguage.XML)
        }
        for (ext in listOf("md", "markdown", "mdown", "mkd", "mdx")) put(ext, CodeLanguage.MARKDOWN)
        for (ext in listOf("sh", "bash", "zsh", "ksh", "fish", "command", "bats")) put(ext, CodeLanguage.SHELL)
    }

    private val defaults: Map<CodeLanguage, String> = mapOf(
        CodeLanguage.KOTLIN to "untitled.kt",
        CodeLanguage.JAVA to "untitled.java",
        CodeLanguage.JAVASCRIPT to "untitled.js",
        CodeLanguage.TYPESCRIPT to "untitled.ts",
        CodeLanguage.PYTHON to "untitled.py",
        CodeLanguage.JSON to "untitled.json",
        CodeLanguage.XML to "untitled.xml",
        CodeLanguage.MARKDOWN to "untitled.md",
        CodeLanguage.SHELL to "untitled.sh",
        CodeLanguage.PLAIN to "untitled.txt",
    )

    /** All languages offered in the "new file" language picker, i.e. everything but [CodeLanguage.PLAIN]. */
    val editorLanguages: List<CodeLanguage> = CodeLanguage.entries.filter { it != CodeLanguage.PLAIN }

    /** Maps a file name (or path) to a language by its extension; never throws. */
    fun fromFileName(fileName: String): CodeLanguage {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return CodeLanguage.PLAIN
        val ext = fileName.substring(dot + 1).lowercase(Locale.ROOT)
        return byExtension[ext] ?: CodeLanguage.PLAIN
    }

    /** Suggested name for a brand-new file of [language], e.g. `untitled.kt`. */
    fun defaultFileName(language: CodeLanguage): String = defaults.getValue(language)
}
