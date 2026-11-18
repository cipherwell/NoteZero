package com.cipherwell.notezero.ui.code

import com.cipherwell.notezero.code.CodeFormat
import com.cipherwell.notezero.code.CodeFormats
import com.cipherwell.notezero.code.CodeLanguage

/**
 * Result of the new-code-file dialog: the validated file name plus the initial body the
 * chosen template contributes (blank for the default template).
 *
 * Immutable and content-bearing: it only lives in memory between the dialog and the
 * repository create call; nothing is logged or persisted in plaintext.
 */
data class NewCodeFileRequest(
    val name: String,
    val body: String,
)

/**
 * One local, static starter snippet offered by [NewCodeFileDialog]. Templates are shipped
 * code — never user data — and each carries the extension/name suggestion and the body.
 *
 * [format] must be a format known to [CodeFormats] so the created item always gets a
 * well-known extension and the editor picks the matching lexer.
 */
data class CodeTemplate(
    val id: String,
    val format: CodeFormat,
    val suggestedName: String,
    val body: String,
)

/**
 * Built-in new-file templates (docs/CONTRACTS-PHASE14.md §C): a blank default plus Kotlin,
 * Python, Markdown, HTML and JSON starters. Pure Kotlin so the snippet/extension rules are
 * plain JVM unit tests.
 */
object CodeTemplates {

    /** Default template: no body, plain-text `.txt` suggestion, exactly the old create flow. */
    val blank = CodeTemplate(
        id = "blank",
        format = CodeFormats.default,
        suggestedName = CodeFormats.defaultName(),
        body = "",
    )

    val kotlin = CodeTemplate(
        id = "kotlin",
        format = CodeFormat("kt", CodeLanguage.KOTLIN),
        suggestedName = "Main.kt",
        body = """
            class HelloWorld {
                fun greet(): String = "Hello, NoteZero!"

                companion object {
                    const val NAME = "HelloWorld"
                }
            }
        """.trimIndent() + "\n",
    )

    val python = CodeTemplate(
        id = "python",
        format = CodeFormat("py", CodeLanguage.PYTHON),
        suggestedName = "script.py",
        body = "\"\"\"Starter script for NoteZero.\"\"\"\n\n\n" +
            "def main() -> None:\n" +
            "    print(\"Hello, NoteZero!\")\n\n\n" +
            "if __name__ == \"__main__\":\n" +
            "    main()\n",
    )

    val markdown = CodeTemplate(
        id = "markdown",
        format = CodeFormat("md", CodeLanguage.MARKDOWN),
        suggestedName = "notes.md",
        body = """
            # Untitled

            What do you want to remember?

            - [ ] First task
            - [ ] Second task
        """.trimIndent() + "\n",
    )

    val html = CodeTemplate(
        id = "html",
        format = CodeFormat("html", CodeLanguage.XML),
        suggestedName = "index.html",
        body = """
            <!doctype html>
            <html lang="en">
            <head>
                <meta charset="utf-8">
                <title>Untitled</title>
            </head>
            <body>
                <h1>Hello, NoteZero!</h1>
            </body>
            </html>
        """.trimIndent() + "\n",
    )

    val json = CodeTemplate(
        id = "json",
        format = CodeFormat("json", CodeLanguage.JSON),
        suggestedName = "config.json",
        body = """
            {
              "name": "untitled",
              "version": 1,
              "enabled": true
            }
        """.trimIndent() + "\n",
    )

    /** Every template in stable display order; [blank] is always the default. */
    val all: List<CodeTemplate> = listOf(blank, kotlin, python, markdown, html, json)

    /** Template lookup by id, falling back to [blank] for an unknown/restored id. */
    fun byId(id: String): CodeTemplate = all.firstOrNull { it.id == id } ?: blank
}
