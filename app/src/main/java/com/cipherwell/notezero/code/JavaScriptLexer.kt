package com.cipherwell.notezero.code

/**
 * JavaScript lexer: `//` line comments and slash-star block comments, single/double strings, template literals with
 * `${expr}` nesting, and best-effort regex-literal detection (`return /re/g`).
 */
internal class JavaScriptLexer : CLikeLexer(
    language = CodeLanguage.JAVASCRIPT,
    keywords = KEYWORDS,
    types = TYPES,
    constants = CONSTANTS,
    nestedBlockComments = false,
    charLiterals = false,
    tripleQuotedStrings = false,
    backtickTemplates = true,
    regexLiterals = true,
    dollarInIdentifiers = true,
    digitSuffixChars = "n",
) {
    internal companion object {
        internal val KEYWORDS: Set<String> = setOf(
            "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
            "default", "delete", "do", "else", "export", "extends", "finally", "for", "from",
            "function", "get", "if", "import", "in", "instanceof", "let", "meta", "new", "of",
            "return", "set", "static", "super", "switch", "target", "this", "throw", "try",
            "typeof", "var", "void", "while", "with", "yield", "as",
        )

        internal val TYPES: Set<String> = setOf(
            "Array", "ArrayBuffer", "BigInt", "BigInt64Array", "BigUint64Array", "Boolean",
            "DataView", "Date", "Error", "EvalError", "Float32Array", "Float64Array", "Function",
            "Int8Array", "Int16Array", "Int32Array", "Intl", "JSON", "Map", "Math", "Number",
            "Object", "Promise", "Proxy", "RangeError", "ReferenceError", "Reflect", "RegExp", "Set",
            "String", "Symbol", "SyntaxError", "TypeError", "Uint8Array", "Uint8ClampedArray",
            "Uint16Array", "Uint32Array", "URIError", "WeakMap", "WeakSet",
        )

        internal val CONSTANTS: Set<String> = setOf(
            "true", "false", "null", "undefined", "NaN", "Infinity",
        )
    }
}
