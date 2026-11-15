package com.cipherwell.notezero.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-language token assertions for the pure-Kotlin lexers: exact spans for small snippets,
 * and semantic assertions (keyword/type/string/comment/number) for realistic ones.
 */
class LexersTest {

    // ------------------------------------------------------------------ Kotlin

    @Test
    fun kotlinHelloWorldHasExactTokenSpans() {
        val source = "fun main() { val x = 42 }"
        val tokens = lex(CodeLanguage.KOTLIN, source)
        assertEquals(
            listOf(
                SyntaxToken(0, 3, TokenType.KEYWORD), // fun
                SyntaxToken(4, 8, TokenType.IDENTIFIER), // main
                SyntaxToken(8, 9, TokenType.PUNCTUATION), // (
                SyntaxToken(9, 10, TokenType.PUNCTUATION), // )
                SyntaxToken(11, 12, TokenType.PUNCTUATION), // {
                SyntaxToken(13, 16, TokenType.KEYWORD), // val
                SyntaxToken(17, 18, TokenType.IDENTIFIER), // x
                SyntaxToken(19, 20, TokenType.OPERATOR), // =
                SyntaxToken(21, 23, TokenType.NUMBER), // 42
                SyntaxToken(24, 25, TokenType.PUNCTUATION), // }
            ),
            tokens,
        )
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun kotlinKeywordsTypesAndStrings() {
        val source = "class Data(val id: Int) { private var name: String = \"\" }"
        val tokens = lex(CodeLanguage.KOTLIN, source)
        assertToken(source, tokens, "class", TokenType.KEYWORD)
        assertToken(source, tokens, "Data", TokenType.IDENTIFIER)
        assertToken(source, tokens, "val", TokenType.KEYWORD)
        assertToken(source, tokens, "Int", TokenType.TYPE)
        assertToken(source, tokens, "private", TokenType.KEYWORD)
        assertToken(source, tokens, "var", TokenType.KEYWORD)
        assertToken(source, tokens, "String", TokenType.TYPE)
        assertToken(source, tokens, "\"\"", TokenType.STRING)
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun kotlinLineCommentSpansToEndOfLine() {
        val source = "val a = 1 // note"
        val tokens = lex(CodeLanguage.KOTLIN, source)
        val comment = assertToken(source, tokens, "// note", TokenType.COMMENT)
        assertEquals(10, comment.start)
        assertEquals(source.length, comment.end)
    }

    @Test
    fun kotlinBlockCommentsNest() {
        val source = "/* outer /* inner */ still */ val x = 1"
        val tokens = lex(CodeLanguage.KOTLIN, source)
        assertEquals(TokenType.COMMENT, tokens[0].type)
        assertEquals("/* outer /* inner */ still */", tokens[0].source(source))
        assertToken(source, tokens, "val", TokenType.KEYWORD)
        assertToken(source, tokens, "1", TokenType.NUMBER)
    }

    @Test
    fun kotlinInterpolatedStringIsOneSpan() {
        val source = "val s = \"Hi \${name}!\""
        val tokens = lex(CodeLanguage.KOTLIN, source)
        val string = assertToken(source, tokens, "\"Hi \${name}!\"", TokenType.STRING)
        assertEquals(8, string.start)
        assertEquals(source.length, string.end)
    }

    @Test
    fun kotlinStringTemplateWithNestedQuotesAndBracesStaysOneSpan() {
        // val s = "${ "}" } tail"  — the "}" inside the inner string must not end the template.
        val source = "val s = \"\${ \"}\" } tail\""
        val tokens = lex(CodeLanguage.KOTLIN, source)
        val string = assertToken(source, tokens, "\"\${ \"}\" } tail\"", TokenType.STRING)
        assertEquals(source.length, string.end)
    }

    @Test
    fun kotlinTripleQuotedRawStringSpansNewlines() {
        val source = "val raw = \"\"\"line1\nline2\"\"\""
        val tokens = lex(CodeLanguage.KOTLIN, source)
        assertToken(source, tokens, "\"\"\"line1\nline2\"\"\"", TokenType.STRING)
    }

    @Test
    fun kotlinCharLiteralsIncludingEscapes() {
        val tokens = lex(CodeLanguage.KOTLIN, "val c = 'x'")
        assertToken("val c = 'x'", tokens, "'x'", TokenType.CHAR)

        val escaped = "val nl = '\\n'"
        val escapedTokens = lex(CodeLanguage.KOTLIN, escaped)
        assertToken(escaped, escapedTokens, "'\\n'", TokenType.CHAR)
    }

    @Test
    fun kotlinAnnotationsCoverQualifiedNames() {
        val source = "@Suppress(\"UNUSED\") fun f() {}"
        val tokens = lex(CodeLanguage.KOTLIN, source)
        assertToken(source, tokens, "@Suppress", TokenType.ANNOTATION)
        assertToken(source, tokens, "\"UNUSED\"", TokenType.STRING)
        assertToken(source, tokens, "fun", TokenType.KEYWORD)

        val qualified = "@kotlin.Suppress(\"X\") fun g() {}"
        val qualifiedTokens = lex(CodeLanguage.KOTLIN, qualified)
        assertToken(qualified, qualifiedTokens, "@kotlin.Suppress", TokenType.ANNOTATION)

        val useSite = "@get:JvmName(\"x\") val p = 1"
        val useSiteTokens = lex(CodeLanguage.KOTLIN, useSite)
        assertToken(useSite, useSiteTokens, "@get:JvmName", TokenType.ANNOTATION)
    }

    @Test
    fun kotlinBacktickIdentifiers() {
        val source = "fun `my fun`() {}"
        val tokens = lex(CodeLanguage.KOTLIN, source)
        assertToken(source, tokens, "`my fun`", TokenType.IDENTIFIER)
    }

    @Test
    fun kotlinNumbersIncludesHexBinaryExponentAndSuffix() {
        val source = "val a = 0xFF; val b = 0b1010; val c = 1.5e-3; val d = 1_000L; val e = 2f"
        val tokens = lex(CodeLanguage.KOTLIN, source)
        assertToken(source, tokens, "0xFF", TokenType.NUMBER)
        assertToken(source, tokens, "0b1010", TokenType.NUMBER)
        assertToken(source, tokens, "1.5e-3", TokenType.NUMBER)
        assertToken(source, tokens, "1_000L", TokenType.NUMBER)
        assertToken(source, tokens, "2f", TokenType.NUMBER)
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun kotlinUnterminatedStringAndCommentDoNotThrow() {
        val unclosedString = "val s = \"abc"
        val stringTokens = lex(CodeLanguage.KOTLIN, unclosedString)
        assertTokenInvariants(unclosedString, stringTokens)
        val string = assertToken(unclosedString, stringTokens, "\"abc", TokenType.STRING)
        assertEquals(unclosedString.length, string.end)

        val unclosedComment = "val x = 1 /* never closed"
        val commentTokens = lex(CodeLanguage.KOTLIN, unclosedComment)
        assertTokenInvariants(unclosedComment, commentTokens)
        val comment = assertToken(unclosedComment, commentTokens, "/* never closed", TokenType.COMMENT)
        assertEquals(unclosedComment.length, comment.end)
    }

    // ------------------------------------------------------------------ Java

    @Test
    fun javaKeywordsTypesAndLiterals() {
        val source = "public class A { private static int x = 0xFF; }"
        val tokens = lex(CodeLanguage.JAVA, source)
        assertToken(source, tokens, "public", TokenType.KEYWORD)
        assertToken(source, tokens, "class", TokenType.KEYWORD)
        assertToken(source, tokens, "private", TokenType.KEYWORD)
        assertToken(source, tokens, "static", TokenType.KEYWORD)
        assertToken(source, tokens, "int", TokenType.TYPE)
        assertToken(source, tokens, "A", TokenType.IDENTIFIER)
        assertToken(source, tokens, "x", TokenType.IDENTIFIER)
        assertToken(source, tokens, "0xFF", TokenType.NUMBER)
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun javaCharStringAndTextBlocks() {
        val source = "char c = '\\n'; String s = \"a\\tb\";"
        val tokens = lex(CodeLanguage.JAVA, source)
        assertToken(source, tokens, "'\\n'", TokenType.CHAR)
        assertToken(source, tokens, "\"a\\tb\"", TokenType.STRING)

        val textBlock = "String t = \"\"\"\nhello\n\"\"\";"
        val textBlockTokens = lex(CodeLanguage.JAVA, textBlock)
        assertToken(textBlock, textBlockTokens, "\"\"\"\nhello\n\"\"\"", TokenType.STRING)
    }

    @Test
    fun javaAnnotationsAndVoid() {
        val source = "@Override public void run() {}"
        val tokens = lex(CodeLanguage.JAVA, source)
        assertToken(source, tokens, "@Override", TokenType.ANNOTATION)
        assertToken(source, tokens, "void", TokenType.TYPE)
        assertToken(source, tokens, "run", TokenType.IDENTIFIER)
    }

    @Test
    fun javaCommentsAreNotNested() {
        val source = "int x = 1; /* one /* two */ int y = 2;"
        val tokens = lex(CodeLanguage.JAVA, source)
        assertToken(source, tokens, "/* one /* two */", TokenType.COMMENT)
        assertToken(source, tokens, "int", TokenType.TYPE)
        assertToken(source, tokens, "y", TokenType.IDENTIFIER)
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun javaUnterminatedCommentRunsToEnd() {
        val source = "/* start"
        val tokens = lex(CodeLanguage.JAVA, source)
        val comment = assertToken(source, tokens, "/* start", TokenType.COMMENT)
        assertEquals(source.length, comment.end)
        assertTokenInvariants(source, tokens)
    }

    // ------------------------------------------------------------------ JavaScript

    @Test
    fun javascriptTokenSpansForAssignment() {
        val source = "const x = 42;"
        val tokens = lex(CodeLanguage.JAVASCRIPT, source)
        assertEquals(
            listOf(
                SyntaxToken(0, 5, TokenType.KEYWORD), // const
                SyntaxToken(6, 7, TokenType.IDENTIFIER), // x
                SyntaxToken(8, 9, TokenType.OPERATOR), // =
                SyntaxToken(10, 12, TokenType.NUMBER), // 42
                SyntaxToken(12, 13, TokenType.PUNCTUATION), // ;
            ),
            tokens,
        )
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun javascriptTemplateLiteralsWithInterpolation() {
        val source = "const s = `a \${b + 1} c`;"
        val tokens = lex(CodeLanguage.JAVASCRIPT, source)
        val string = assertToken(source, tokens, "`a \${b + 1} c`", TokenType.STRING)
        assertEquals(10, string.start)
        assertEquals(source.length - 1, string.end)
    }

    @Test
    fun javascriptNestedTemplateLiteralsStayOneSpan() {
        val source = "const t = `x \${`y \${z}`} w`;"
        val tokens = lex(CodeLanguage.JAVASCRIPT, source)
        val string = assertToken(source, tokens, "`x \${`y \${z}`} w`", TokenType.STRING)
        assertEquals(source.length - 1, string.end)
    }

    @Test
    fun javascriptRegexLiteralsAreStringsButDivisionIsAnOperator() {
        val regex = "const re = /ab+c/gi;"
        val regexTokens = lex(CodeLanguage.JAVASCRIPT, regex)
        assertToken(regex, regexTokens, "/ab+c/gi", TokenType.STRING)

        val division = "const q = a / b;"
        val divisionTokens = lex(CodeLanguage.JAVASCRIPT, division)
        assertToken(division, divisionTokens, "/", TokenType.OPERATOR)
        assertTrue(divisionTokens.none { it.type == TokenType.STRING })
    }

    @Test
    fun javascriptSingleQuotedStringsAndComments() {
        val source = "const s = 'it\\'s'; // note\nlet n = 1;"
        val tokens = lex(CodeLanguage.JAVASCRIPT, source)
        assertToken(source, tokens, "'it\\'s'", TokenType.STRING)
        assertToken(source, tokens, "// note", TokenType.COMMENT)
        assertToken(source, tokens, "let", TokenType.KEYWORD)
    }

    // ------------------------------------------------------------------ TypeScript

    @Test
    fun typescriptTypeLevelKeywords() {
        val source = "interface User { readonly name: string }"
        val tokens = lex(CodeLanguage.TYPESCRIPT, source)
        assertToken(source, tokens, "interface", TokenType.KEYWORD)
        assertToken(source, tokens, "User", TokenType.IDENTIFIER)
        assertToken(source, tokens, "readonly", TokenType.KEYWORD)
        assertToken(source, tokens, "string", TokenType.TYPE)
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun typescriptTypeAliasAndUnionTypes() {
        val source = "type Id = number | string;"
        val tokens = lex(CodeLanguage.TYPESCRIPT, source)
        assertToken(source, tokens, "type", TokenType.KEYWORD)
        assertToken(source, tokens, "Id", TokenType.IDENTIFIER)
        assertToken(source, tokens, "number", TokenType.TYPE)
        assertToken(source, tokens, "string", TokenType.TYPE)
        assertToken(source, tokens, "|", TokenType.OPERATOR)
    }

    @Test
    fun typescriptGenericsAndAsCast() {
        val source = "function f<T>(x: T): T { return x as unknown as T }"
        val tokens = lex(CodeLanguage.TYPESCRIPT, source)
        assertToken(source, tokens, "function", TokenType.KEYWORD)
        assertToken(source, tokens, "unknown", TokenType.TYPE)
        assertToken(source, tokens, "as", TokenType.KEYWORD)
        assertEquals(4, tokensOf(source, tokens, TokenType.IDENTIFIER).count { it == "T" })
        assertTokenInvariants(source, tokens)
    }

    // ------------------------------------------------------------------ Python

    @Test
    fun pythonDefReturnAndPunctuation() {
        val source = "def greet(name):\n    return name"
        val tokens = lex(CodeLanguage.PYTHON, source)
        assertToken(source, tokens, "def", TokenType.KEYWORD)
        assertToken(source, tokens, "greet", TokenType.IDENTIFIER)
        assertToken(source, tokens, "name", TokenType.IDENTIFIER)
        assertToken(source, tokens, "return", TokenType.KEYWORD)
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun pythonDocstringsAreStrings() {
        val source = "def f():\n    \"\"\"Doc string.\"\"\"\n    pass"
        val tokens = lex(CodeLanguage.PYTHON, source)
        assertToken(source, tokens, "\"\"\"Doc string.\"\"\"", TokenType.STRING)
        assertToken(source, tokens, "pass", TokenType.KEYWORD)
    }

    @Test
    fun pythonFStringsSpanInterpolationsAndFormatSpecs() {
        val source = "msg = f\"value {x:>5}\""
        val tokens = lex(CodeLanguage.PYTHON, source)
        assertToken(source, tokens, "f\"value {x:>5}\"", TokenType.STRING)

        val escaped = "s = f\"{{literal}} {name}\""
        val escapedTokens = lex(CodeLanguage.PYTHON, escaped)
        assertToken(escaped, escapedTokens, "f\"{{literal}} {name}\"", TokenType.STRING)
    }

    @Test
    fun pythonDecoratorsAndComments() {
        val source = "@app.route(\"/\")\ndef f():\n    pass  # comment"
        val tokens = lex(CodeLanguage.PYTHON, source)
        assertToken(source, tokens, "@app.route", TokenType.ANNOTATION)
        assertToken(source, tokens, "\"/\"", TokenType.STRING)
        assertToken(source, tokens, "# comment", TokenType.COMMENT)
        assertToken(source, tokens, "def", TokenType.KEYWORD)
    }

    @Test
    fun pythonNumbersAndKeywordConstants() {
        val source = "x = 0xFF + 1_000 + 3.5j if None is not True else -2e3"
        val tokens = lex(CodeLanguage.PYTHON, source)
        assertToken(source, tokens, "0xFF", TokenType.NUMBER)
        assertToken(source, tokens, "1_000", TokenType.NUMBER)
        assertToken(source, tokens, "3.5j", TokenType.NUMBER)
        assertToken(source, tokens, "None", TokenType.KEYWORD)
        assertToken(source, tokens, "True", TokenType.KEYWORD)
        assertToken(source, tokens, "if", TokenType.KEYWORD)
        assertToken(source, tokens, "is", TokenType.KEYWORD)
        assertToken(source, tokens, "not", TokenType.KEYWORD)
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun pythonMatchStatementSoftKeywords() {
        val source = "match value:\n    case 1:\n        pass"
        val tokens = lex(CodeLanguage.PYTHON, source)
        assertToken(source, tokens, "match", TokenType.KEYWORD)
        assertToken(source, tokens, "case", TokenType.KEYWORD)
        assertToken(source, tokens, "1", TokenType.NUMBER)
    }

    @Test
    fun pythonUnterminatedTripleQuoteRunsToEnd() {
        val source = "s = \"\"\"abc"
        val tokens = lex(CodeLanguage.PYTHON, source)
        assertTokenInvariants(source, tokens)
        val string = assertToken(source, tokens, "\"\"\"abc", TokenType.STRING)
        assertEquals(source.length, string.end)
    }

    // ------------------------------------------------------------------ JSON

    @Test
    fun jsonObjectHasExactTokenSpans() {
        val source = "{\"a\": 1}"
        val tokens = lex(CodeLanguage.JSON, source)
        assertEquals(
            listOf(
                SyntaxToken(0, 1, TokenType.PUNCTUATION), // {
                SyntaxToken(1, 4, TokenType.PROPERTY), // "a"
                SyntaxToken(4, 5, TokenType.PUNCTUATION), // :
                SyntaxToken(6, 7, TokenType.NUMBER), // 1
                SyntaxToken(7, 8, TokenType.PUNCTUATION), // }
            ),
            tokens,
        )
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun jsonNestedDocumentValuesAndProperties() {
        val source = """{"a": [true, null, -1.5e3], "b": {"c": "d"}}"""
        val tokens = lex(CodeLanguage.JSON, source)
        assertEquals(listOf("\"a\"", "\"b\"", "\"c\""), tokensOf(source, tokens, TokenType.PROPERTY))
        assertEquals(listOf("true", "null"), tokensOf(source, tokens, TokenType.KEYWORD))
        assertToken(source, tokens, "-1.5e3", TokenType.NUMBER)
        assertEquals(listOf("\"d\""), tokensOf(source, tokens, TokenType.STRING))
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun jsonInvalidCharactersAreReportedAsErrors() {
        val source = "{\"a\": 'x'}"
        val tokens = lex(CodeLanguage.JSON, source)
        assertTrue("expected ERROR tokens", tokens.any { it.type == TokenType.ERROR })
        assertTokenInvariants(source, tokens)

        val minus = "{\"a\": -x}"
        val minusTokens = lex(CodeLanguage.JSON, minus)
        assertTrue("lone '-' must be ERROR", minusTokens.any { it.type == TokenType.ERROR })
        assertTokenInvariants(minus, minusTokens)
    }

    @Test
    fun jsonUnterminatedStringRunsToEnd() {
        val source = "{\"a\": \"unterminated"
        val tokens = lex(CodeLanguage.JSON, source)
        assertTokenInvariants(source, tokens)
        assertToken(source, tokens, "\"unterminated", TokenType.STRING)
    }

    // ------------------------------------------------------------------ XML

    @Test
    fun xmlTagsAttributesAndText() {
        val source = """<root id="1">text</root>"""
        val tokens = lex(CodeLanguage.XML, source)
        assertEquals(listOf("root", "root"), tokensOf(source, tokens, TokenType.TAG))
        assertToken(source, tokens, "id", TokenType.ATTRIBUTE)
        assertToken(source, tokens, "\"1\"", TokenType.STRING)
        assertToken(source, tokens, "=", TokenType.PUNCTUATION)
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun xmlCommentCdataAndProcessingInstruction() {
        val comment = "<!-- a comment with <tag> -->"
        val commentTokens = lex(CodeLanguage.XML, comment)
        assertEquals(listOf(comment), tokensOf(comment, commentTokens, TokenType.COMMENT))

        val cdata = "<a><![CDATA[x < y & z]]></a>"
        val cdataTokens = lex(CodeLanguage.XML, cdata)
        assertToken(cdata, cdataTokens, "<![CDATA[x < y & z]]>", TokenType.STRING)

        val instruction = "<?xml version=\"1.0\"?>"
        val instructionTokens = lex(CodeLanguage.XML, instruction)
        assertToken(instruction, instructionTokens, "<?", TokenType.PUNCTUATION)
        assertToken(instruction, instructionTokens, "xml", TokenType.TAG)
        assertToken(instruction, instructionTokens, "version", TokenType.ATTRIBUTE)
        assertToken(instruction, instructionTokens, "\"1.0\"", TokenType.STRING)
        assertToken(instruction, instructionTokens, "?>", TokenType.PUNCTUATION)
    }

    @Test
    fun xmlDoctypeWithInternalSubsetIsOneComment() {
        val source = """<!DOCTYPE note [ <!ENTITY x "y"> ]><note/>"""
        val tokens = lex(CodeLanguage.XML, source)
        assertEquals(
            listOf("<!DOCTYPE note [ <!ENTITY x \"y\"> ]>"),
            tokensOf(source, tokens, TokenType.COMMENT),
        )
        assertToken(source, tokens, "note", TokenType.TAG)
        assertToken(source, tokens, "/>", TokenType.PUNCTUATION)
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun xmlSelfClosingAndNamespacedTags() {
        val source = "<ns:tag attr='v'/><br/>"
        val tokens = lex(CodeLanguage.XML, source)
        assertToken(source, tokens, "ns:tag", TokenType.TAG)
        assertToken(source, tokens, "attr", TokenType.ATTRIBUTE)
        assertToken(source, tokens, "'v'", TokenType.STRING)
        assertEquals(2, tokensOf(source, tokens, TokenType.PUNCTUATION).count { it == "/>" })
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun xmlBareLessThanIsAnErrorNotAComment() {
        val source = "1 < 2"
        val tokens = lex(CodeLanguage.XML, source)
        assertToken(source, tokens, "<", TokenType.ERROR)
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun xmlUnterminatedTagValueRunsToEnd() {
        val source = "<div class=\"x"
        val tokens = lex(CodeLanguage.XML, source)
        assertTokenInvariants(source, tokens)
        val value = assertToken(source, tokens, "\"x", TokenType.STRING)
        assertEquals(source.length, value.end)
    }

    // ------------------------------------------------------------------ Shell

    @Test
    fun shellShebangAndKeywords() {
        val source = "#!/bin/bash\nif [ -f file ]; then\n  echo ok\nfi"
        val tokens = lex(CodeLanguage.SHELL, source)
        assertToken(source, tokens, "#!/bin/bash", TokenType.COMMENT)
        for (keyword in listOf("if", "then", "fi", "echo")) {
            assertToken(source, tokens, keyword, TokenType.KEYWORD)
        }
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun shellVariableExpansionsAreProperties() {
        val source = "echo \"hi \${USER} \$HOME \$? \$1\""
        val tokens = lex(CodeLanguage.SHELL, source)
        assertEquals(
            listOf("\${USER}", "\$HOME", "\$?", "\$1"),
            tokensOf(source, tokens, TokenType.PROPERTY),
        )

        val substitution = "echo \$(date) \$( (1 + 2) )"
        val substitutionTokens = lex(CodeLanguage.SHELL, substitution)
        assertEquals("$(date)", tokensOf(substitution, substitutionTokens, TokenType.PROPERTY).first())
    }

    @Test
    fun shellSingleQuotesSuppressExpansion() {
        val source = "echo 'no \$expand'"
        val tokens = lex(CodeLanguage.SHELL, source)
        assertToken(source, tokens, "'no \$expand'", TokenType.STRING)
        assertTrue(tokens.none { it.type == TokenType.PROPERTY && it.source(source) == "\$expand" })
    }

    @Test
    fun shellAssignmentsColourNameAsProperty() {
        val source = "FOO=bar PATH=\"/usr/bin:\$PATH\""
        val tokens = lex(CodeLanguage.SHELL, source)
        assertEquals(listOf("FOO", "PATH", "\$PATH"), tokensOf(source, tokens, TokenType.PROPERTY))
        assertToken(source, tokens, "=", TokenType.OPERATOR)
        assertToken(source, tokens, "bar", TokenType.IDENTIFIER)
    }

    @Test
    fun shellHeredocBodyIsAStringAndTerminatorAnIdentifier() {
        val source = "cat <<EOF\nhello\nworld\nEOF\n"
        val tokens = lex(CodeLanguage.SHELL, source)
        assertEquals(listOf("EOF", "EOF"), tokensOf(source, tokens, TokenType.IDENTIFIER).filter { it == "EOF" })
        assertToken(source, tokens, "hello\nworld\n", TokenType.STRING)
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun shellHeredocWithTabStrippingAcceptsIndentedTerminator() {
        val source = "cat <<-EOF\n\tline\n\tEOF\n"
        val tokens = lex(CodeLanguage.SHELL, source)
        assertToken(source, tokens, "\tline\n", TokenType.STRING)
        assertEquals(listOf("EOF", "EOF"), tokensOf(source, tokens, TokenType.IDENTIFIER).filter { it == "EOF" })
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun shellPipesRedirectionsAndNumbers() {
        val source = "ls -la | grep x > out.txt 2>&1"
        val tokens = lex(CodeLanguage.SHELL, source)
        assertEquals(listOf("|", ">", ">&"), tokensOf(source, tokens, TokenType.OPERATOR))
        assertEquals(listOf("2", "1"), tokensOf(source, tokens, TokenType.NUMBER))
        assertToken(source, tokens, "out.txt", TokenType.IDENTIFIER)
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun shellBackticksAndArithmeticExpansion() {
        val tokens = lex(CodeLanguage.SHELL, "echo `ls -l`")
        assertToken("echo `ls -l`", tokens, "`ls -l`", TokenType.PROPERTY)

        val arithmetic = "echo \$((1 + 2))"
        val arithmeticTokens = lex(CodeLanguage.SHELL, arithmetic)
        assertToken(arithmetic, arithmeticTokens, "\$((1 + 2))", TokenType.PROPERTY)
    }

    @Test
    fun shellHashInsideWordIsLiteralButStartsCommentAtBoundary() {
        val word = "echo foo#bar"
        val wordTokens = lex(CodeLanguage.SHELL, word)
        assertToken(word, wordTokens, "foo#bar", TokenType.IDENTIFIER)

        val boundary = "x=#value"
        val boundaryTokens = lex(CodeLanguage.SHELL, boundary)
        assertToken(boundary, boundaryTokens, "x", TokenType.PROPERTY)
        assertToken(boundary, boundaryTokens, "#value", TokenType.IDENTIFIER)

        val comment = "echo a #note"
        val commentTokens = lex(CodeLanguage.SHELL, comment)
        assertToken(comment, commentTokens, "#note", TokenType.COMMENT)
    }

    @Test
    fun shellUnterminatedConstructsDoNotThrow() {
        for (source in listOf("echo \"abc", "cat <<EOF\nbody line", "echo \${unclosed", "echo `open")) {
            val tokens = lex(CodeLanguage.SHELL, source)
            assertTokenInvariants(source, tokens)
        }
    }

    // ------------------------------------------------------------------ Markdown

    @Test
    fun markdownAtxHeadings() {
        val source = "# Title"
        assertEquals(listOf("# Title"), tokensOf(source, lex(CodeLanguage.MARKDOWN, source), TokenType.HEADING))

        val subtitle = "   ### Sub heading"
        assertEquals(
            listOf("### Sub heading"),
            tokensOf(subtitle, lex(CodeLanguage.MARKDOWN, subtitle), TokenType.HEADING),
        )

        val tooManyHashes = "####### seven"
        assertTrue(lex(CodeLanguage.MARKDOWN, tooManyHashes).isEmpty())
    }

    @Test
    fun markdownFencedCodeBlockIsOneSpan() {
        val source = "```kotlin\nfun f() { }\n```\n"
        val tokens = lex(CodeLanguage.MARKDOWN, source)
        assertEquals(1, tokens.size)
        assertEquals(TokenType.CODE_SPAN, tokens[0].type)
        assertEquals("```kotlin\nfun f() { }\n```", tokens[0].source(source))
        assertTokenInvariants(source, tokens)
    }

    @Test
    fun markdownTildeFenceAndUnterminatedFence() {
        val tilde = "~~~\ncode()\n~~~"
        val tildeTokens = lex(CodeLanguage.MARKDOWN, tilde)
        assertEquals(listOf(tilde), tokensOf(tilde, tildeTokens, TokenType.CODE_SPAN))

        val unterminated = "```\ncode line"
        val unterminatedTokens = lex(CodeLanguage.MARKDOWN, unterminated)
        assertEquals(1, unterminatedTokens.size)
        assertEquals(unterminated.length, unterminatedTokens[0].end)
    }

    @Test
    fun markdownHeadingInsideFenceStaysCode() {
        val source = "```\n# not a heading\n```"
        val tokens = lex(CodeLanguage.MARKDOWN, source)
        assertEquals(1, tokens.size)
        assertEquals(TokenType.CODE_SPAN, tokens[0].type)
        assertEquals(source, tokens[0].source(source))
    }

    @Test
    fun markdownInlineCodeSpans() {
        val source = "Use `val` here"
        val tokens = lex(CodeLanguage.MARKDOWN, source)
        assertEquals(listOf("`val`"), tokensOf(source, tokens, TokenType.CODE_SPAN))

        val double = "a ``b`c`` d"
        val doubleTokens = lex(CodeLanguage.MARKDOWN, double)
        assertEquals(listOf("``b`c``"), tokensOf(double, doubleTokens, TokenType.CODE_SPAN))

        val unmatched = "a ` b"
        assertTrue(lex(CodeLanguage.MARKDOWN, unmatched).isEmpty())
    }

    @Test
    fun markdownLinksAndImages() {
        val source = "See [docs](https://example.com) now"
        assertEquals(
            listOf("[docs](https://example.com)"),
            tokensOf(source, lex(CodeLanguage.MARKDOWN, source), TokenType.LINK),
        )

        val image = "![alt](img.png)"
        assertEquals(listOf(image), tokensOf(image, lex(CodeLanguage.MARKDOWN, image), TokenType.LINK))

        val reference = "[text][ref]"
        assertEquals(listOf(reference), tokensOf(reference, lex(CodeLanguage.MARKDOWN, reference), TokenType.LINK))

        val autolink = "<https://example.com>"
        // A line that starts like a link is fine; autolinks are matched inline.
        assertEquals(
            listOf(autolink),
            tokensOf("see $autolink here", lex(CodeLanguage.MARKDOWN, "see $autolink here"), TokenType.LINK),
        )
    }

    @Test
    fun markdownPlainProseHasNoTokens() {
        assertTrue(lex(CodeLanguage.MARKDOWN, "just some words, no markup").isEmpty())
        assertTrue(lex(CodeLanguage.MARKDOWN, "").isEmpty())
    }

    @Test
    fun markdownHeadingLineIsNotScannedForInlineCode() {
        val source = "# heading with `code`"
        val tokens = lex(CodeLanguage.MARKDOWN, source)
        assertEquals(1, tokens.size)
        assertEquals(TokenType.HEADING, tokens[0].type)
        assertEquals(source, tokens[0].source(source))
    }

    // ------------------------------------------------------------------ PLAIN and cross-cutting

    @Test
    fun plainLanguageReturnsNoTokens() {
        for (source in listOf("", "fun main() {}", "# not markdown", "\"unterminated")) {
            assertTrue(lex(CodeLanguage.PLAIN, source).isEmpty())
        }
    }

    @Test
    fun unicodeTextKeepsValidOffsets() {
        val source = "val s = \"héllo 😀\" // γειά"
        val tokens = lex(CodeLanguage.KOTLIN, source)
        assertTokenInvariants(source, tokens)
        assertToken(source, tokens, "\"héllo 😀\"", TokenType.STRING)
        assertToken(source, tokens, "// γειά", TokenType.COMMENT)
    }

    @Test
    fun crlfSourcesKeepValidOffsets() {
        val source = "fun a() {}\r\nfun b() {}\r\n"
        val tokens = lex(CodeLanguage.KOTLIN, source)
        assertTokenInvariants(source, tokens)
        assertEquals(2, tokensOf(source, tokens, TokenType.KEYWORD).count { it == "fun" })
    }

    @Test
    fun everyLanguageSurvivesATruncatedConstruct() {
        val fragments = listOf(
            "\"", "'", "\"\"\"", "'''", "`", "/*", "*/", "<!--", "-->", "<![CDATA[",
            "```", "~~~", "<<EOF\n", "\${", "\$(", "{", "}", "(", ")", "[", "]", "<tag",
            "\\", "#", "@", "0x", "1e", "f\"{", "<?", "<!", "|", "&&", "x=",
        )
        for (language in CodeLanguage.entries) {
            if (language == CodeLanguage.PLAIN) continue
            for (fragment in fragments) {
                val source = "prefix $fragment suffix"
                val tokens = lex(language, source)
                assertTokenInvariants(source, tokens)
                assertAllTokensWithin(source, tokens)
            }
        }
    }

    @Test
    fun kitchenSinkPerLanguageUpholdsInvariants() {
        val samples = mapOf(
            CodeLanguage.KOTLIN to "package a\n// c\n@Anno\nfun f(x: Int): String { val s = \"a \${x}\"; return \"\"\"raw\"\"\" }",
            CodeLanguage.JAVA to "public class A { /* c */ static int x = 1; String s = \"v\"; }",
            CodeLanguage.JAVASCRIPT to "const f = (x) => `t \${x}`; // c\nlet a = /re/g;",
            CodeLanguage.TYPESCRIPT to "interface I { a: string }\nconst x = 1 as unknown;",
            CodeLanguage.PYTHON to "@deco\ndef f(x: int) -> str:\n    \"\"\"doc\"\"\"\n    return f\"{x}\"  # c",
            CodeLanguage.JSON to "{\"a\": [1, -2.5e3, true, null, \"s\"]}",
            CodeLanguage.XML to "<?xml version=\"1.0\"?><a b=\"c\"><![CDATA[x]]></a>",
            CodeLanguage.MARKDOWN to "# H\n`code` [link](u)\n```\nblock\n```\n",
            CodeLanguage.SHELL to "#!/bin/sh\nfor f in *.txt; do echo \"\$f\"; done # c",
        )
        for ((language, source) in samples) {
            val tokens = lex(language, source)
            assertTrue("$language produced no tokens", tokens.isNotEmpty())
            assertTokenInvariants(source, tokens)
        }
    }

    @Test
    fun tokenizeIsRepeatableForSharedLexerInstances() {
        val lexer = CodeLexers.forLanguage(CodeLanguage.KOTLIN)
        val first = lexer.tokenize("fun a() {}")
        val second = lexer.tokenize("val b = 2")
        assertEquals(lexer.tokenize("fun a() {}"), first)
        assertEquals(lexer.tokenize("val b = 2"), second)
        assertNotNull(lexer.tokenize(""))
    }
}
