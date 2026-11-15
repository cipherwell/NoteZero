package com.cipherwell.notezero.code

/** TypeScript lexer: JavaScript plus type-level keywords (`interface`, `type`, `keyof`, ...). */
internal class TypeScriptLexer : CLikeLexer(
    language = CodeLanguage.TYPESCRIPT,
    keywords = KEYWORDS,
    types = TYPES,
    constants = JavaScriptLexer.CONSTANTS,
    nestedBlockComments = false,
    charLiterals = false,
    tripleQuotedStrings = false,
    backtickTemplates = true,
    regexLiterals = true,
    dollarInIdentifiers = true,
    digitSuffixChars = "n",
) {
    private companion object {
        val EXTRA_KEYWORDS: Set<String> = setOf(
            "abstract", "accessor", "asserts", "declare", "enum", "implements", "infer", "interface",
            "is", "keyof", "module", "namespace", "override", "private", "protected", "public",
            "readonly", "satisfies", "type", "unique", "using", "out",
        )

        val KEYWORDS: Set<String> = JavaScriptLexer.KEYWORDS + EXTRA_KEYWORDS

        val TYPE_KEYWORDS: Set<String> = setOf(
            "any", "unknown", "never", "string", "number", "boolean", "object", "symbol", "bigint",
            "undefined",
        )

        val TYPES: Set<String> = JavaScriptLexer.TYPES + TYPE_KEYWORDS
    }
}
