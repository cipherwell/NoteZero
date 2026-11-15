package com.cipherwell.notezero.code

/**
 * Registry of the pure-Kotlin lexers, one shared instance per language.
 *
 * Instances are safe to reuse: [CodeLexer.tokenize] serializes access internally. Callers that
 * highlight off the main thread and on the main thread for the same language will therefore
 * block briefly rather than corrupt each other's scan state.
 */
object CodeLexers {

    private val KOTLIN: CodeLexer = KotlinLexer()
    private val JAVA: CodeLexer = JavaLexer()
    private val JAVASCRIPT: CodeLexer = JavaScriptLexer()
    private val TYPESCRIPT: CodeLexer = TypeScriptLexer()
    private val PYTHON: CodeLexer = PythonLexer()
    private val JSON: CodeLexer = JsonLexer()
    private val XML: CodeLexer = XmlLexer()
    private val MARKDOWN: CodeLexer = MarkdownLexer()
    private val SHELL: CodeLexer = ShellLexer()

    /** No highlighting: always returns an empty token list. */
    private object PlainLexer : CodeLexer {
        override val language: CodeLanguage = CodeLanguage.PLAIN
        override fun tokenize(text: String): List<SyntaxToken> = emptyList()
    }

    fun forLanguage(language: CodeLanguage): CodeLexer = when (language) {
        CodeLanguage.KOTLIN -> KOTLIN
        CodeLanguage.JAVA -> JAVA
        CodeLanguage.JAVASCRIPT -> JAVASCRIPT
        CodeLanguage.TYPESCRIPT -> TYPESCRIPT
        CodeLanguage.PYTHON -> PYTHON
        CodeLanguage.JSON -> JSON
        CodeLanguage.XML -> XML
        CodeLanguage.MARKDOWN -> MARKDOWN
        CodeLanguage.SHELL -> SHELL
        CodeLanguage.PLAIN -> PlainLexer
    }
}
