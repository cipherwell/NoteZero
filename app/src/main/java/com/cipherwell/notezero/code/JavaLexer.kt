package com.cipherwell.notezero.code

/** Java lexer: slash-star block comments (not nested), `"""text blocks"""`, `@Annotations`, char literals. */
internal class JavaLexer : CLikeLexer(
    language = CodeLanguage.JAVA,
    keywords = KEYWORDS,
    types = TYPES,
    constants = CONSTANTS,
    nestedBlockComments = false,
    charLiterals = true,
    tripleQuotedStrings = true,
    rawTripleQuoted = false,
    dollarTemplates = false,
    dollarInIdentifiers = true,
    digitSuffixChars = "lLfFdD",
) {
    private companion object {
        val KEYWORDS: Set<String> = setOf(
            "abstract", "assert", "break", "case", "catch", "class", "const", "continue", "default",
            "do", "else", "enum", "exports", "extends", "final", "finally", "for", "goto", "if",
            "implements", "import", "instanceof", "interface", "module", "native", "new", "non",
            "opens", "package", "permits", "private", "protected", "provides", "public", "record",
            "requires", "return", "sealed", "static", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "to", "transient", "transitive", "try", "uses", "var",
            "volatile", "while", "with", "yield",
        )

        val TYPES: Set<String> = setOf(
            "boolean", "byte", "char", "double", "float", "int", "long", "short", "void",
            "Boolean", "Byte", "Character", "CharSequence", "Class", "Collection", "Comparable",
            "Double", "Error", "Exception", "Float", "Integer", "Iterable", "Iterator", "List",
            "Long", "Map", "Number", "Object", "RuntimeException", "Set", "Short", "String",
            "StringBuilder", "Throwable", "Void", "ArrayList", "HashMap",
        )

        val CONSTANTS: Set<String> = setOf("true", "false", "null")
    }
}
