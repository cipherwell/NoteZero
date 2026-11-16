package com.cipherwell.notezero.code

/**
 * File formats offered when creating a code file, mapped to the extension written into the
 * item title and to the lexer language used by the editor.
 *
 * The extension is a technical label (shown as `.kt`, `.py`, …), never localised copy. Formats
 * without a dedicated lexer map to [CodeLanguage.PLAIN]: the file name/extension is still
 * correct (e.g. `.css`, `.go`), the editor simply renders it as plain text instead of
 * mis-highlighting it with the wrong grammar.
 *
 * Pure Kotlin so the name/extension rules are plain JVM unit tests.
 */
data class CodeFormat(
    /** Extension WITHOUT the dot, e.g. `kt`, `html`, `md`. */
    val extension: String,
    /** Editor language used for highlighting (PLAIN when there is no lexer). */
    val language: CodeLanguage,
)

object CodeFormats {

    /** Every format offered by the "new code file" dialog, in a stable display order. */
    val all: List<CodeFormat> = listOf(
        CodeFormat("txt", CodeLanguage.PLAIN),
        CodeFormat("md", CodeLanguage.MARKDOWN),
        CodeFormat("html", CodeLanguage.XML),
        CodeFormat("css", CodeLanguage.PLAIN),
        CodeFormat("js", CodeLanguage.JAVASCRIPT),
        CodeFormat("ts", CodeLanguage.TYPESCRIPT),
        CodeFormat("json", CodeLanguage.JSON),
        CodeFormat("xml", CodeLanguage.XML),
        CodeFormat("yml", CodeLanguage.PLAIN),
        CodeFormat("toml", CodeLanguage.PLAIN),
        CodeFormat("py", CodeLanguage.PYTHON),
        CodeFormat("kt", CodeLanguage.KOTLIN),
        CodeFormat("java", CodeLanguage.JAVA),
        CodeFormat("c", CodeLanguage.PLAIN),
        CodeFormat("cpp", CodeLanguage.PLAIN),
        CodeFormat("h", CodeLanguage.PLAIN),
        CodeFormat("cs", CodeLanguage.PLAIN),
        CodeFormat("go", CodeLanguage.PLAIN),
        CodeFormat("rs", CodeLanguage.PLAIN),
        CodeFormat("rb", CodeLanguage.PLAIN),
        CodeFormat("php", CodeLanguage.PLAIN),
        CodeFormat("swift", CodeLanguage.PLAIN),
        CodeFormat("sql", CodeLanguage.PLAIN),
        CodeFormat("sh", CodeLanguage.SHELL),
        CodeFormat("csv", CodeLanguage.PLAIN),
        CodeFormat("ini", CodeLanguage.PLAIN),
        CodeFormat("properties", CodeLanguage.PLAIN),
    )

    /** Default format for a new file (`.txt` plain text). */
    val default: CodeFormat = all.first()

    private val byExtension: Map<String, CodeFormat> =
        all.associateBy { it.extension.lowercase(java.util.Locale.ROOT) }

    /** Format matching [extension] (case-insensitive, without a dot), or null. */
    fun forExtension(extension: String): CodeFormat? =
        byExtension[extension.lowercase(java.util.Locale.ROOT)]

    /**
     * Renames [name] to [format]'s extension. A blank name becomes `untitled.<ext>`; a name
     * with any extension has its extension replaced; a name without one gets the extension
     * appended. Keeps everything else (including dots inside the base name) untouched.
     */
    fun applyFormat(name: String, format: CodeFormat): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "untitled.${format.extension}"
        val dot = trimmed.lastIndexOf('.')
        val base = if (dot > 0) trimmed.substring(0, dot) else trimmed
        return "$base.${format.extension}"
    }

    /**
     * Final name for the create dialog: a name the user typed with a **known** extension keeps
     * that extension (typing `hello.kt` must not silently become `hello.txt`); anything else
     * gets the selected format's extension.
     */
    fun resolveName(name: String, selected: CodeFormat): String {
        val trimmed = name.trim()
        val dot = trimmed.lastIndexOf('.')
        if (dot > 0 && dot < trimmed.length - 1 && forExtension(trimmed.substring(dot + 1)) != null) {
            return trimmed
        }
        return applyFormat(trimmed, selected)
    }

    /** Initial suggested name for a brand-new file of [format]. */
    fun defaultName(format: CodeFormat = default): String = "untitled.${format.extension}"
}
