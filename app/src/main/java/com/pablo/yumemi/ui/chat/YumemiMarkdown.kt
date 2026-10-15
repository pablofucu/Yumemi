package com.pablo.yumemi.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LocalMarkdownAnnotator
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.utils.buildMarkdownAnnotatedString
import com.mikepenz.markdown.utils.codeSpanStyle
import com.mikepenz.markdown.utils.linkTextSpanStyle
import com.pablo.yumemi.ui.theme.Yu
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.delay
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import kotlin.math.max

private val CodeBackground = Color(0xFFF6F7FF)
private val ZebraStripe = Color(0xFFF8F9FF)
private val MinColumnWidth = 64.dp
private val MaxColumnWidth = 240.dp
private val QuotePrefix = Regex("^ {0,3}> ?")

/** Markdown for chat bubbles: compact headings, wrapping aligned tables, copyable highlighted code. */
@Composable
fun YumemiMarkdown(content: String, modifier: Modifier = Modifier) {
    StyledMarkdown(content, Yu.Indigo, modifier)
}

@Composable
private fun StyledMarkdown(content: String, textColor: Color, modifier: Modifier = Modifier) {
    val body = MaterialTheme.typography.bodyLarge
    val heading = MaterialTheme.typography.titleLarge
    Markdown(
        content = content,
        colors = markdownColor(
            text = textColor,
            codeText = Yu.Indigo,
            inlineCodeText = Yu.PeriwinkleDeep,
            linkText = Yu.PeriwinkleDeep,
            // The code card paints its own background; only inline code uses this one.
            codeBackground = Color.Transparent,
            inlineCodeBackground = Yu.LilacSoft,
            dividerColor = Yu.Outline,
            tableText = textColor,
            tableBackground = Color.Transparent,
        ),
        typography = markdownTypography(
            h1 = heading.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
            h2 = heading.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
            h3 = heading.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            h4 = heading.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
            h5 = heading.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            h6 = heading.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            text = body,
            code = body.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp),
            inlineCode = body.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            quote = body,
            paragraph = body,
            ordered = body,
            bullet = body,
            list = body,
            link = body.copy(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline),
        ),
        modifier = modifier.fillMaxWidth(),
        padding = markdownPadding(
            block = 6.dp,
            list = 2.dp,
            listItemBottom = 4.dp,
            indentList = 10.dp,
            codeBlock = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ),
        dimens = markdownDimens(
            dividerThickness = 1.dp,
            codeBackgroundCornerSize = 12.dp,
            tableCornerSize = 12.dp,
        ),
        components = YumemiComponents,
    )
}

private fun heading(
    top: Dp,
    select: (MarkdownTypography) -> TextStyle,
    contentType: IElementType = MarkdownTokenTypes.ATX_CONTENT,
): MarkdownComponent = {
    Box(Modifier.padding(top = top)) {
        MarkdownHeader(it.content, it.node, select(it.typography), contentType)
    }
}

/** Small copy-to-clipboard button that flips to a check mark for a moment. */
@Composable
internal fun CopyButton(text: String, modifier: Modifier = Modifier, iconSize: Dp = 16.dp) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Box(
        modifier
            .size(iconSize + 16.dp)
            .clip(CircleShape)
            .clickable {
                clipboard.setText(AnnotatedString(text))
                copied = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = if (copied) "Copiado" else "Copiar",
            modifier = Modifier.size(iconSize),
            tint = if (copied) Yu.Success else Yu.Muted,
        )
    }
}

@Composable
private fun CodeCard(code: String, language: String?) {
    val shape = RoundedCornerShape(12.dp)
    val highlights = remember { Highlights.Builder().theme(SyntaxThemes.atom(darkMode = false)) }
    val text = code.trimEnd('\n')
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(CodeBackground)
            .border(1.dp, Yu.Outline, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language?.takeIf { it.isNotBlank() } ?: "código",
                style = MaterialTheme.typography.labelSmall,
                color = Yu.Muted,
            )
            CopyButton(text, iconSize = 14.dp)
        }
        MarkdownHighlightedCode(text, language, highlights)
    }
}

@Composable
private fun QuoteBlock(content: String, node: ASTNode) {
    val inner = remember(content, node) {
        node.getTextInNode(content).lines().joinToString("\n") { it.replaceFirst(QuotePrefix, "") }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .drawBehind {
                drawRoundRect(
                    color = Yu.Lilac,
                    size = Size(3.dp.toPx(), size.height),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                )
            },
    ) {
        StyledMarkdown(inner, Yu.Muted, Modifier.padding(start = 12.dp))
    }
}

private class TableData(val rows: List<List<AnnotatedString>>, val aligns: List<TextAlign>)

private fun AnnotatedString.trimmed(): AnnotatedString {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return AnnotatedString("")
    return subSequence(start, text.indexOfLast { !it.isWhitespace() } + 1)
}

@Composable
private fun TableBlock(content: String, node: ASTNode, baseStyle: TextStyle) {
    val typography = LocalMarkdownTypography.current
    val linkStyle = typography.linkTextSpanStyle
    val codeStyle = typography.codeSpanStyle
    val annotator = LocalMarkdownAnnotator.current
    val style = remember(baseStyle) { baseStyle.copy(fontSize = 14.sp, lineHeight = 20.sp) }
    val table = remember(content, node, style, linkStyle, codeStyle, annotator) {
        val headerStyle = style.copy(fontWeight = FontWeight.Bold)
        fun cells(row: ASTNode, cellStyle: TextStyle) = row.children
            .filter { it.type == GFMTokenTypes.CELL }
            .map { content.buildMarkdownAnnotatedString(it, cellStyle, linkStyle, codeStyle, annotator).trimmed() }

        val header = node.children.firstOrNull { it.type == GFMElementTypes.HEADER }
        val headerCells = header?.let { cells(it, headerStyle) }.orEmpty()
        val columns = headerCells.size
        val body = node.children.filter { it.type == GFMElementTypes.ROW }.map { row ->
            val parsed = cells(row, style)
            List(columns) { parsed.getOrElse(it) { AnnotatedString("") } }
        }
        val spec = node.children.firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }
            ?.getTextInNode(content)?.toString().orEmpty()
            .split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val aligns = List(columns) { i ->
            val s = spec.getOrElse(i) { "" }
            when {
                s.length > 1 && s.startsWith(':') && s.endsWith(':') -> TextAlign.Center
                s.endsWith(':') -> TextAlign.End
                else -> TextAlign.Start
            }
        }
        TableData(listOf(headerCells) + body, aligns)
    }
    if (table.aligns.isNotEmpty()) AlignedTable(table, style)
}

/** Columns share one width across rows (capped, text wraps), and the table scrolls sideways if it overflows. */
@Composable
private fun AlignedTable(table: TableData, style: TextStyle) {
    val columns = table.aligns.size
    val rowCount = table.rows.size
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)) {
        Layout(
            content = {
                table.rows.forEachIndexed { r, row ->
                    Box(
                        Modifier
                            .background(if (r == 0) Yu.LilacSoft else if (r % 2 == 0) ZebraStripe else Color.White)
                            .then(if (r == 0) Modifier.drawBehind { drawHeaderRule() } else Modifier),
                    )
                    row.forEachIndexed { c, cell ->
                        MarkdownText(
                            content = cell,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            style = style.copy(textAlign = table.aligns[c]),
                        )
                    }
                }
            },
            modifier = Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth),
        ) { measurables, constraints ->
            val stride = columns + 1
            val minColumn = MinColumnWidth.roundToPx()
            val maxColumn = MaxColumnWidth.roundToPx()
            val widths = IntArray(columns) { c ->
                var widest = 0
                for (r in 0 until rowCount) {
                    widest = max(widest, measurables[r * stride + 1 + c].maxIntrinsicWidth(0))
                }
                widest.coerceIn(minColumn, maxColumn)
            }
            val natural = widths.sum()
            if (constraints.minWidth > natural) {
                val extra = constraints.minWidth - natural
                for (c in 0 until columns) widths[c] += (extra.toLong() * widths[c] / natural).toInt()
                widths[columns - 1] += (constraints.minWidth - widths.sum()).coerceAtLeast(0)
            }
            val total = widths.sum()
            val cells = Array(rowCount) { r ->
                Array(columns) { c -> measurables[r * stride + 1 + c].measure(Constraints.fixedWidth(widths[c])) }
            }
            val heights = IntArray(rowCount) { r -> cells[r].maxOf { it.height } }
            val backgrounds = Array(rowCount) { r ->
                measurables[r * stride].measure(Constraints.fixed(total, heights[r]))
            }
            layout(total, heights.sum()) {
                var y = 0
                for (r in 0 until rowCount) {
                    backgrounds[r].placeRelative(0, y)
                    var x = 0
                    for (c in 0 until columns) {
                        cells[r][c].placeRelative(x, y)
                        x += widths[c]
                    }
                    y += heights[r]
                }
            }
        }
        Box(Modifier.matchParentSize().border(1.dp, Yu.Outline, shape))
    }
}

private fun DrawScope.drawHeaderRule() {
    val thickness = 1.dp.toPx()
    drawRect(Yu.Outline, Offset(0f, size.height - thickness), Size(size.width, thickness))
}

private val YumemiCodeFence: MarkdownComponent = {
    MarkdownCodeFence(it.content, it.node) { code, language -> CodeCard(code, language) }
}

private val YumemiCodeBlock: MarkdownComponent = {
    MarkdownCodeBlock(it.content, it.node) { code, language -> CodeCard(code, language) }
}

private val YumemiBlockQuote: MarkdownComponent = { QuoteBlock(it.content, it.node) }

private val YumemiRule: MarkdownComponent = {
    HorizontalDivider(Modifier.padding(vertical = 6.dp), thickness = 1.dp, color = Yu.Outline)
}

private val YumemiTable: MarkdownComponent = { TableBlock(it.content, it.node, it.typography.text) }

private val YumemiComponents = markdownComponents(
    codeFence = YumemiCodeFence,
    codeBlock = YumemiCodeBlock,
    heading1 = heading(8.dp, { it.h1 }),
    heading2 = heading(8.dp, { it.h2 }),
    heading3 = heading(6.dp, { it.h3 }),
    heading4 = heading(4.dp, { it.h4 }),
    heading5 = heading(4.dp, { it.h5 }),
    heading6 = heading(4.dp, { it.h6 }),
    setextHeading1 = heading(8.dp, { it.h1 }, MarkdownTokenTypes.SETEXT_CONTENT),
    setextHeading2 = heading(8.dp, { it.h2 }, MarkdownTokenTypes.SETEXT_CONTENT),
    blockQuote = YumemiBlockQuote,
    horizontalRule = YumemiRule,
    table = YumemiTable,
)
