@file:OptIn(ExperimentalMaterial3Api::class)

package com.pablo.yumemi.ui.feeds

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pablo.yumemi.AppViewModelProvider
import com.pablo.yumemi.data.cron.CronJob
import com.pablo.yumemi.data.cron.CronStatus
import com.pablo.yumemi.data.feeds.FeedBlock
import com.pablo.yumemi.data.feeds.FeedEnvelope
import com.pablo.yumemi.data.feeds.FeedInfo
import com.pablo.yumemi.data.feeds.FeedStatus
import com.pablo.yumemi.domain.YumemiMood
import com.pablo.yumemi.ui.components.SoftCard
import com.pablo.yumemi.ui.components.YumemiBackground
import com.pablo.yumemi.ui.sprite.YumemiSprite
import com.pablo.yumemi.ui.theme.Yu
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay

private const val AUTO_REFRESH_MS = 5 * 60_000L
private val ErrorText = Color(0xFF8A2B42)
private val es = Locale("es", "ES")

/** Refreshes on open and every few minutes while visible: reports change a few times a day. */
@Composable
private fun AutoRefresh(refresh: (manual: Boolean) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            refresh(false)
            while (true) {
                delay(AUTO_REFRESH_MS)
                refresh(false)
            }
        }
    }
}

@Composable
private fun FeedScaffold(
    title: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onBack: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    YumemiBackground(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(title, fontWeight = FontWeight.Bold, color = Yu.Indigo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver", tint = Yu.Indigo)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                )
            },
        ) { pad ->
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.padding(top = pad.calculateTopPadding()).fillMaxSize(),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp,
                        bottom = pad.calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

// ---------- overview ----------

/** The Informes tab: one card per automation; tapping one opens its full report. */
@Composable
fun FeedsScreen(onOpenFeed: (String) -> Unit) {
    val vm: FeedsViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    AutoRefresh { vm.refresh(manual = it) }

    FeedScaffold("Informes", state.isRefreshing, onRefresh = { vm.refresh(manual = true) }) {
        state.error?.let { err ->
            item(key = "error") { ErrorBanner(err, onDismiss = vm::dismissError) }
        }
        if (state.loaded && state.cards.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = "Aún no hay informes",
                    text = "Cuando una automatización de Hermes publique su feed, aparecerá aquí.",
                    hasError = state.error != null,
                )
            }
        }
        items(state.cards, key = { it.feed.tag }) { card ->
            FeedCard(card, onClick = { onOpenFeed(card.feed.tag) }, modifier = Modifier.animateItem())
        }
    }
}

@Composable
internal fun FeedCard(card: FeedCardState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val latest = card.latest
    val trend = latest?.view?.firstNotNullOfOrNull { it as? FeedBlock.Series }?.points?.map { it.second }
    val headline = latest?.view?.firstNotNullOfOrNull { it as? FeedBlock.Stat }
    SoftCard(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FeedBadge(card.feed)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        card.feed.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Yu.Indigo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val timing = timingLine(latest, card.job, System.currentTimeMillis(), withNext = false)
                    if (timing.isNotEmpty()) {
                        Text(timing, style = MaterialTheme.typography.labelMedium, color = Yu.Muted, maxLines = 1)
                    }
                }
                if (latest != null && latest.status != FeedStatus.OK) StatusChip(latest.status)
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Yu.Lilac)
            }
            Row(
                Modifier.padding(top = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    if (headline != null) {
                        Text(
                            headline.value,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Yu.PeriwinkleDeep,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        when {
                            latest == null -> "Sin informe todavía"
                            latest.status == FeedStatus.ERROR ->
                                "La última ejecución falló" + latest.error?.let { ": $it" }.orEmpty()
                            else -> latest.summary.ifBlank { "Sin resumen" }
                        },
                        Modifier.padding(top = if (headline != null) 2.dp else 0.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            latest == null -> Yu.Muted
                            latest.status == FeedStatus.ERROR -> ErrorText
                            else -> Yu.Indigo
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (trend != null && trend.size > 1) {
                    LineChart(trend, Modifier.padding(start = 12.dp).width(84.dp).height(40.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun FeedBadge(feed: FeedInfo) {
    Box(
        Modifier.size(42.dp).background(Yu.LilacSoft, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (feed.emoji.isNotBlank()) {
            Text(feed.emoji, fontSize = 22.sp)
        } else {
            Icon(Icons.Rounded.Insights, contentDescription = null, tint = Yu.PeriwinkleDeep)
        }
    }
}

// ---------- detail ----------

@Composable
fun FeedDetailScreen(onBack: () -> Unit) {
    val vm: FeedDetailViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    AutoRefresh { vm.refresh(manual = it) }

    FeedScaffold(
        title = state.feed?.title ?: "Informe",
        isRefreshing = state.isRefreshing,
        onRefresh = { vm.refresh(manual = true) },
        onBack = onBack,
    ) {
        state.error?.let { err ->
            item(key = "error") { ErrorBanner(err, onDismiss = vm::dismissError) }
        }
        val feed = state.feed
        val latest = state.latest
        when {
            !state.loaded -> Unit
            feed == null -> item(key = "empty") {
                EmptyState(
                    title = "Este informe ya no existe",
                    text = "Puede que la automatización se haya borrado en Hermes.",
                    hasError = state.error != null,
                )
            }
            latest == null -> {
                item(key = "header") { FeedHeader(feed, null, state.job) }
                item(key = "empty") {
                    EmptyState(
                        title = "Sin informe todavía",
                        text = "Aparecerá tras la próxima ejecución de «${feed.title}».",
                        hasError = state.error != null,
                    )
                }
            }
            else -> {
                item(key = "header") { FeedHeader(feed, latest, state.job) }
                if (latest.status == FeedStatus.ERROR) {
                    item(key = "run_error") {
                        ErrorBanner("La última ejecución falló" + latest.error?.let { ": $it" }.orEmpty())
                    }
                }
                itemsIndexed(groupBlocks(latest.view), key = { i, _ -> "b$i" }) { _, group -> BlockRow(group) }
                if (state.history.isNotEmpty()) {
                    item(key = "history_title") {
                        Text(
                            "Ejecuciones anteriores",
                            Modifier.padding(top = 12.dp, start = 4.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = Yu.Muted,
                        )
                    }
                    items(state.history, key = { "h${it.runAt}" }) { HistoryRow(it) }
                }
            }
        }
    }
}

/** "Última: hoy 09:07 · Próxima: mañana 09:00", plus paused/running notes from the cron job. */
private fun timingLine(latest: FeedEnvelope?, job: CronJob?, now: Long, withNext: Boolean = true): String {
    val lastRun = latest?.runAt?.takeIf { it > 0 } ?: job?.lastRunAt ?: 0L
    return buildList {
        if (lastRun > 0) add(if (withNext) "Última: ${formatDayTime(lastRun, now)}" else formatDayTime(lastRun, now))
        if (withNext && job != null && job.nextRunAt > 0 && job.status != CronStatus.PAUSED) {
            add("Próxima: ${formatDayTime(job.nextRunAt, now)}")
        }
        if (job?.status == CronStatus.PAUSED) add("En pausa")
        if (job?.status == CronStatus.RUNNING) add("Ejecutándose…")
    }.joinToString("  ·  ")
}

@Composable
internal fun FeedHeader(feed: FeedInfo, latest: FeedEnvelope?, job: CronJob?) {
    val timing = timingLine(latest, job, System.currentTimeMillis())
    SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf(feed.emoji, feed.title).filter { it.isNotBlank() }.joinToString(" "),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Yu.Indigo,
                )
                latest?.let { StatusChip(it.status) }
            }
            latest?.summary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Yu.Indigo,
                )
            }
            if (timing.isNotEmpty()) {
                Text(
                    timing,
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = Yu.Muted,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: FeedStatus) {
    val (text, color) = when (status) {
        FeedStatus.OK -> "OK" to Yu.Success
        FeedStatus.ERROR -> "Fallo" to Yu.Danger
        FeedStatus.EMPTY -> "Sin datos" to Yu.Muted
    }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.15f)) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
private fun HistoryRow(env: FeedEnvelope) {
    var expanded by rememberSaveable(env.runAt) { mutableStateOf(false) }
    SoftCard(
        Modifier.fillMaxWidth().clickable { expanded = !expanded },
        elevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDayTime(env.runAt, System.currentTimeMillis()),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = Yu.Muted,
                )
                if (env.status != FeedStatus.OK) StatusChip(env.status)
            }
            val text = env.summary.ifBlank { env.error.orEmpty() }
            if (text.isNotBlank()) {
                Text(
                    text,
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Yu.Indigo,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    groupBlocks(env.view).forEach { BlockRow(it) }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(text: String, onDismiss: (() -> Unit)? = null) {
    SoftCard(Modifier.fillMaxWidth(), color = Yu.DangerSoft, elevation = 2.dp) {
        Row(
            Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                Modifier.weight(1f).padding(vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = ErrorText,
            )
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cerrar", tint = ErrorText)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, text: String, hasError: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        YumemiSprite(if (hasError) YumemiMood.SLEEPING else YumemiMood.IDLE, Modifier.height(170.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = Yu.Indigo,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = Yu.Muted,
            modifier = Modifier.padding(top = 4.dp, start = 16.dp, end = 16.dp),
        )
    }
}

/** "hoy 09:07", "mañana 09:00", "ayer 21:25", else "15 feb, 09:00". */
internal fun formatDayTime(millis: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = SimpleDateFormat("HH:mm", es).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(millis))
    val days = ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate(),
    )
    return when (days) {
        0L -> "hoy $time"
        1L -> "mañana $time"
        -1L -> "ayer $time"
        else -> SimpleDateFormat("d MMM, HH:mm", es).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(millis))
    }
}
