package com.cipherwell.notezero.code

/** Kotlin lexer: nested block comments, `"""raw"""` strings, `$name` / `${expr}` templates, char literals. */
internal class KotlinLexer : CLikeLexer(
    language = CodeLanguage.KOTLIN,
    keywords = KEYWORDS,
    types = TYPES,
    constants = CONSTANTS,
    nestedBlockComments = true,
    charLiterals = true,
    tripleQuotedStrings = true,
    rawTripleQuoted = true,
    dollarTemplates = true,
    backtickIdentifiers = true,
    dollarInIdentifiers = false,
    digitSuffixChars = "lLfFuU",
) {
    private companion object {
        val KEYWORDS: Set<String> = setOf(
            "as", "break", "by", "catch", "class", "companion", "const", "constructor", "continue",
            "crossinline", "data", "delegate", "do", "dynamic", "else", "enum", "expect", "external",
            "false", "field", "file", "final", "finally", "for", "fun", "get", "if", "import", "in",
            "infix", "init", "inline", "inner", "interface", "internal", "is", "lateinit", "noinline",
            "null", "object", "open", "operator", "out", "override", "package", "param", "private",
            "property", "protected", "public", "receiver", "reified", "return", "sealed", "set",
            "setparam", "super", "suspend", "tailrec", "this", "throw", "true", "try", "typealias",
            "typeof", "val", "value", "var", "vararg", "when", "where", "while", "actual", "abstract",
            "annotation",
        )

        val TYPES: Set<String> = setOf(
            "Any", "Array", "Boolean", "BooleanArray", "Byte", "ByteArray", "Char", "CharArray",
            "CharSequence", "Collection", "Comparable", "Double", "DoubleArray", "Enum", "Error",
            "Exception", "Float", "FloatArray", "Function", "Int", "IntArray", "Iterable", "Iterator",
            "Lazy", "List", "Long", "LongArray", "Map", "MutableCollection", "MutableIterable",
            "MutableIterator", "MutableList", "MutableMap", "MutableSet", "Nothing", "Number", "Pair",
            "Regex", "Result", "Sequence", "Set", "Short", "ShortArray", "String", "StringBuilder",
            "Throwable", "Triple", "UByte", "UInt", "ULong", "UShort", "Unit", "Void",
        )

        val CONSTANTS: Set<String> = emptySet()
    }
}
