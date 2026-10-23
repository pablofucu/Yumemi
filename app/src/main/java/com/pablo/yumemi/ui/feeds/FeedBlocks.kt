package com.pablo.yumemi.ui.feeds

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pablo.yumemi.data.feeds.FeedBlock
import com.pablo.yumemi.ui.components.SoftCard
import com.pablo.yumemi.ui.theme.Yu
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Consecutive stats share a row (two per row); every other block is a full-width card. */
internal fun groupBlocks(blocks: List<FeedBlock>): List<List<FeedBlock>> {
    val out = mutableListOf<List<FeedBlock>>()
    var stats = mutableListOf<FeedBlock>()
    fun flush() {
        stats.chunked(2).forEach { out += it }
        stats = mutableListOf()
    }
    for (b in blocks) {
        if (b is FeedBlock.Stat) stats += b else {
            flush()
            out += listOf(b)
        }
    }
    flush()
    return out
}

@Composable
internal fun BlockRow(group: List<FeedBlock>, modifier: Modifier = Modifier) {
    val first = group.first()
    if (first is FeedBlock.Stat) {
        Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            group.forEach { StatCard(it as FeedBlock.Stat, Modifier.weight(1f)) }
            if (group.size == 1) Spacer(Modifier.weight(1f))
        }
    } else {
        when (first) {
            is FeedBlock.KeyValue -> KeyValueCard(first, modifier)
            is FeedBlock.Table -> TableCard(first, modifier)
            is FeedBlock.Series -> SeriesCard(first, modifier)
            is FeedBlock.Text -> TextCard(first, modifier)
            is FeedBlock.Link -> LinkRow(first, modifier)
            is FeedBlock.Stat -> Unit
        }
    }
}

@Composable
private fun StatCard(stat: FeedBlock.Stat, modifier: Modifier = Modifier) {
    SoftCard(modifier.fillMaxHeight()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                stat.label,
                style = MaterialTheme.typography.labelMedium,
                color = Yu.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stat.value,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Yu.PeriwinkleDeep,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            stat.delta?.takeIf { it.isNotBlank() }?.let { delta ->
                Surface(Modifier.padding(top = 6.dp), shape = CircleShape, color = Yu.LilacSoft) {
                    Text(
                        delta,
                        Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Yu.Indigo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyValueCard(kv: FeedBlock.KeyValue, modifier: Modifier = Modifier) {
    SoftCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            kv.items.forEachIndexed { i, (k, v) ->
                if (i > 0) HorizontalDivider(color = Yu.Outline.copy(alpha = 0.6f))
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, style = MaterialTheme.typography.bodyMedium, color = Yu.Muted)
                    Text(
                        v,
                        Modifier.weight(1f).padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Yu.Indigo,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

/** Column-major layout so every column takes the width of its widest cell; scrolls sideways when wide. */
@Composable
private fun TableCard(table: FeedBlock.Table, modifier: Modifier = Modifier) {
    val width = maxOf(table.columns.size, table.rows.maxOfOrNull { it.size } ?: 0)
    SoftCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            for (c in 0 until width) {
                Column(Modifier.padding(horizontal = 6.dp)) {
                    val header = table.columns.getOrNull(c)
                    if (table.columns.isNotEmpty()) {
                        Text(
                            header.orEmpty(),
                            Modifier.padding(vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Yu.PeriwinkleDeep,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    table.rows.forEach { row ->
                        Text(
                            row.getOrNull(c).orEmpty(),
                            Modifier.padding(vertical = 6.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Yu.Indigo,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

private val esSymbols = DecimalFormatSymbols(Locale("es", "ES"))

/** Small magnitudes (€/kWh…) keep three decimals so 0,085 does not read as 0,09. */
private fun formatValue(v: Double, unit: String): String {
    val pattern = if (kotlin.math.abs(v) < 1) "#,##0.###" else "#,##0.##"
    return DecimalFormat(pattern, esSymbols).format(v) + if (unit.isBlank()) "" else " $unit"
}

@Composable
private fun SeriesCard(series: FeedBlock.Series, modifier: Modifier = Modifier) {
    val values = series.points.map { it.second }
    val min = values.min()
    val max = values.max()
    SoftCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    series.label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = Yu.Indigo,
                )
                Text(
                    formatValue(values.last(), series.unit),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Yu.PeriwinkleDeep,
                )
            }
            Text(
                "mín. ${formatValue(min, series.unit)} · máx. ${formatValue(max, series.unit)}",
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Yu.Muted,
            )
            LineChart(values, Modifier.fillMaxWidth().height(120.dp).padding(top = 12.dp))
            if (series.points.size > 1) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(series.points.first().first, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Yu.Muted)
                    Text(series.points.last().first, style = MaterialTheme.typography.labelSmall, color = Yu.Muted)
                }
            }
        }
    }
}

@Composable
internal fun LineChart(values: List<Double>, modifier: Modifier = Modifier, strokeWidth: Dp = 3.dp) {
    val line = Yu.Periwinkle
    val fillTop = Yu.Lilac.copy(alpha = 0.35f)
    val fillBottom = Yu.Lilac.copy(alpha = 0f)
    Canvas(modifier) {
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        val pad = 6.dp.toPx()
        val h = size.height - pad * 2
        fun x(i: Int) = if (values.size == 1) size.width / 2 else size.width * i / (values.size - 1)
        fun y(v: Double) = if (max == min) pad + h / 2 else pad + (h * (1 - (v - min) / span)).toFloat()
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        val area = Path().apply {
            addPath(path)
            lineTo(x(values.lastIndex), size.height)
            lineTo(x(0), size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(fillTop, fillBottom)))
        drawPath(path, line, style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = Offset(x(values.lastIndex), y(values.last()))
        drawCircle(Yu.Gold, radius = strokeWidth.toPx() * 1.7f, center = last)
        drawCircle(line, radius = strokeWidth.toPx() * 1.7f, center = last, style = Stroke(width = strokeWidth.toPx() * 0.66f))
    }
}

@Composable
private fun TextCard(text: FeedBlock.Text, modifier: Modifier = Modifier) {
    Text(
        text.text,
        modifier
            .fillMaxWidth()
            .background(Yu.LilacSoft.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = Yu.Indigo,
    )
}

@Composable
private fun LinkRow(link: FeedBlock.Link, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    TextButton(onClick = { runCatching { uri.openUri(link.url) } }, modifier = modifier) {
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = Yu.PeriwinkleDeep)
        Text(
            link.label,
            Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Yu.PeriwinkleDeep,
        )
    }
}
