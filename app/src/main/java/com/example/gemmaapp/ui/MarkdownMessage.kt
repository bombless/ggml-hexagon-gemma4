package com.example.gemmaapp.ui

import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownMessage(content: String, textColor: Color) {
    val fenceMark = 96.toChar().toString().repeat(3)
    val fence = Regex("(?s)" + fenceMark + "([\\w+#.-]*)[ \\t]*\\r?\\n(.*?)" + fenceMark)
    val matches = fence.findAll(content).toList()
    Log.d("CodeHighlight", "MarkdownMessage chars=${content.length}, fencedBlocks=${matches.size}")
    var cursor = 0
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        matches.forEach { match ->
            if (match.range.first > cursor) MarkdownTextBlock(content.substring(cursor, match.range.first), textColor)
            val language = match.groupValues[1].ifBlank { "code" }
            val isJs = language.lowercase() in setOf("js", "javascript", "jsx", "ts", "typescript", "tsx")
            Log.d("CodeHighlight", "Markdown block language='$language', chars=${match.groupValues[2].length}, jsHighlighter=$isJs")
            Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(9.dp)) {
                Column {
                    Text(language.uppercase(), modifier = Modifier.padding(start = 10.dp, top = 7.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    SelectionContainer {
                        val code = match.groupValues[2].trimEnd()
                        val highlighted = if (language.lowercase() in setOf("js", "javascript", "jsx", "ts", "typescript", "tsx")) {
                            highlightJavaScript(code)
                        } else {
                            buildAnnotatedString { append(code) }
                        }
                        Text(highlighted, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < content.length) MarkdownTextBlock(content.substring(cursor), textColor)
    }
}

@Composable
private fun MarkdownTextBlock(block: String, color: Color) {
    block.lines().forEach { raw ->
        val line = raw.trimEnd()
        if (line.isBlank()) return@forEach
        val heading = Regex("^(#{1,3})\\s+(.+)$").matchEntire(line)
        val bullet = Regex("^\\s*[-*+]\\s+(.+)$").matchEntire(line)
        val ordered = Regex("^\\s*\\d+[.)]\\s+(.+)$").matchEntire(line)
        when {
            heading != null -> {
                val size = when (heading.groupValues[1].length) { 1 -> 19.sp; 2 -> 17.sp; else -> 15.sp }
                Text(inlineMarkdown(heading.groupValues[2], color), color = color, fontSize = size, fontWeight = FontWeight.Bold)
            }
            bullet != null -> Row(verticalAlignment = Alignment.Top) {
                Text("•  ", color = color)
                Text(inlineMarkdown(bullet.groupValues[1], color), color = color, fontSize = 14.sp, lineHeight = 20.sp)
            }
            ordered != null -> Text(inlineMarkdown(line.trimStart(), color), color = color, fontSize = 14.sp, lineHeight = 20.sp)
            line.trim() == "---" -> HorizontalDivider()
            else -> Text(inlineMarkdown(line, color), color = color, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

private fun inlineMarkdown(source: String, color: Color) = buildAnnotatedString {
    val tick = 96.toChar()
    val regex = Regex("(\\*\\*[^*]+\\*\\*|(?<!\\*)\\*[^*]+\\*(?!\\*)|~~[^~]+~~|" + tick + "[^" + tick + "]+" + tick + ")")
    var index = 0
    regex.findAll(source).forEach { match ->
        append(source.substring(index, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.substring(2, token.length - 2)) }
            token.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(token.substring(2, token.length - 2)) }
            token.first() == tick -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = color.copy(alpha = 0.12f))) { append(token.substring(1, token.length - 1)) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.substring(1, token.length - 1)) }
        }
        index = match.range.last + 1
    }
    append(source.substring(index))
}

/** Applies lightweight JavaScript/TypeScript syntax highlighting without changing code text. */
@Composable
internal fun highlightJavaScript(source: String) = buildAnnotatedString {
    val keywordColor = Color(0xFFCF8EFA)
    val stringColor = Color(0xFF9BCB8B)
    val numberColor = Color(0xFFE5AE72)
    val commentColor = Color(0xFF84919E)
    val propertyColor = Color(0xFF78C8C1)
    val literalColor = Color(0xFFE6A0A0)
    val keywords = setOf("const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "try", "catch", "finally", "throw", "new", "class", "extends", "super", "this", "import", "export", "from", "as", "default", "async", "await", "yield", "typeof", "instanceof", "in", "of", "delete", "void", "with", "debugger", "interface", "type", "implements", "public", "private", "static", "readonly", "enum")
    val tokenPattern = Regex("""(?s)//[^\\r\\n]*|/\\*.*?\\*/|"(?:\\\\.|[^"\\\\])*"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`|\\b(?:const|let|var|function|return|if|else|for|while|do|switch|case|break|continue|try|catch|finally|throw|new|class|extends|super|this|import|export|from|as|default|async|await|yield|typeof|instanceof|in|of|delete|void|with|debugger|interface|type|implements|public|private|static|readonly|enum)\\b|\\b(?:true|false|null|undefined|NaN|Infinity)\\b|\\b(?:0[xX][\\da-fA-F]+|0[bB][01]+|0[oO][0-7]+|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)\\b|\\b[A-Za-z_$][\\w$]*\\b""")
    var cursor = 0
    var highlightedTokens = 0
    tokenPattern.findAll(source).forEach { match ->
        append(source.substring(cursor, match.range.first))
        val token = match.value
        val previous = source.substring(0, match.range.first).trimEnd().lastOrNull()
        val color = when {
            token.startsWith("//") || token.startsWith("/*") -> commentColor
            token.startsWith('"') || token.startsWith('\'') || token.startsWith('`') -> stringColor
            token.firstOrNull()?.isDigit() == true -> numberColor
            token in setOf("true", "false", "null", "undefined", "NaN", "Infinity") -> literalColor
            token in keywords -> keywordColor
            previous == '.' -> propertyColor
            else -> null
        }
        if (color == null) append(token) else {
            withStyle(SpanStyle(color = color)) { append(token) }
            highlightedTokens++
        }
        cursor = match.range.last + 1
    }
    append(source.substring(cursor))
    Log.d("CodeHighlight", "JavaScript lexer chars=${source.length}, coloredTokens=$highlightedTokens")
}
