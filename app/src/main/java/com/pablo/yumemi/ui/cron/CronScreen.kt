@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.pablo.yumemi.ui.cron

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pablo.yumemi.AppViewModelProvider
import com.pablo.yumemi.data.cron.CronJob
import com.pablo.yumemi.data.cron.CronStatus
import com.pablo.yumemi.domain.YumemiMood
import com.pablo.yumemi.ui.components.SoftCard
import com.pablo.yumemi.ui.components.YumemiBackground
import com.pablo.yumemi.ui.components.formatRelativeTime
import com.pablo.yumemi.ui.sprite.YumemiSprite
import com.pablo.yumemi.ui.theme.Yu
import kotlinx.coroutines.delay

private const val AUTO_REFRESH_MS = 60_000L
private val ErrorText = Color(0xFF8A2B42)

@Composable
fun CronScreen() {
    val vm: CronViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }

    // Refresh on open, then every minute while the screen is visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.refresh(manual = false)
            while (true) {
                delay(AUTO_REFRESH_MS)
                vm.refresh(manual = false)
            }
        }
    }

    YumemiBackground(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Cronjobs", fontWeight = FontWeight.Bold, color = Yu.Indigo) },
                    actions = {
                        if (state.hiddenCount > 0) {
                            TextButton(onClick = { vm.toggleShowHidden() }) {
                                Icon(
                                    if (state.showHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = null,
                                    tint = Yu.PeriwinkleDeep,
                                )
                                Text(
                                    if (state.showHidden) "Ocultar de nuevo"
                                    else "Mostrar ocultos (${state.hiddenCount})",
                                    Modifier.padding(start = 6.dp),
                                    color = Yu.PeriwinkleDeep,
                                    style = MaterialTheme.typography.labelLarge,
                                )
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
                isRefreshing = state.isRefreshing,
                onRefresh = { vm.refresh(manual = true) },
                modifier = Modifier.padding(top = pad.calculateTopPadding()).fillMaxSize(),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp,
                        bottom = pad.calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.error?.let { err ->
                        item(key = "error") {
                            SoftCard(Modifier.fillMaxWidth(), color = Yu.DangerSoft, elevation = 2.dp) {
                                Row(
                                    Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        err,
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ErrorText,
                                    )
                                    IconButton(onClick = { vm.dismissError() }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Cerrar", tint = ErrorText)
                                    }
                                }
                            }
                        }
                    }
                    if (state.loaded && state.jobs.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(allHidden = state.totalCount > 0, hasError = state.error != null)
                        }
                    }
                    items(state.jobs, key = { it.job.id }) { item ->
                        CronCard(
                            job = item.job,
                            hidden = item.hidden,
                            expanded = expandedId == item.job.id,
                            busy = item.job.id in state.busyIds,
                            onToggleExpanded = {
                                expandedId = if (expandedId == item.job.id) null else item.job.id
                            },
                            onTogglePause = { vm.togglePause(item.job) },
                            onRunNow = { vm.runNow(item.job) },
                            onToggleHidden = { vm.setHidden(item.job.id, !item.hidden) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(allHidden: Boolean, hasError: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        YumemiSprite(
            if (hasError) YumemiMood.SLEEPING else YumemiMood.IDLE,
            Modifier.height(170.dp),
        )
        Text(
            if (allHidden) "Todos tus cronjobs están ocultos" else "Hermes no tiene cronjobs",
            style = MaterialTheme.typography.titleMedium,
            color = Yu.Indigo,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            if (allHidden) "Pulsa «Mostrar ocultos» para verlos." else "Cuando crees alguno, aparecerá aquí.",
            style = MaterialTheme.typography.bodyMedium,
            color = Yu.Muted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun CronCard(
    job: CronJob,
    hidden: Boolean,
    expanded: Boolean,
    busy: Boolean,
    onToggleExpanded: () -> Unit,
    onTogglePause: () -> Unit,
    onRunNow: () -> Unit,
    onToggleHidden: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = job.status
    val now = System.currentTimeMillis()
    SoftCard(
        modifier
            .fillMaxWidth()
            .alpha(if (hidden) 0.6f else 1f)
            .clickable(onClick = onToggleExpanded),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    job.displayName,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = Yu.Indigo,
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusChip(status, Modifier.padding(start = 8.dp))
            }
            if (job.scheduleDisplay.isNotBlank() || hidden) {
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (job.scheduleDisplay.isNotBlank()) {
                        Text(
                            job.scheduleDisplay,
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Yu.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (hidden) {
                        Text(
                            "Oculto",
                            Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = Yu.PeriwinkleDeep,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            val times = buildList {
                if (job.nextRunAt > 0 && status != CronStatus.COMPLETED && status != CronStatus.PAUSED) {
                    add("Próxima: ${formatFutureTime(job.nextRunAt, now)}")
                }
                if (job.lastRunAt > 0) add("Última: ${formatRelativeTime(job.lastRunAt, now)}")
            }
            if (times.isNotEmpty()) {
                Text(
                    times.joinToString("  ·  "),
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = Yu.Muted,
                )
            }
            job.errorText?.let { err ->
                Text(
                    err,
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorText,
                    maxLines = if (expanded) 8 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(top = 12.dp)) {
                    if (job.prompt.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Yu.LilacSoft,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SelectionContainer {
                                Text(
                                    job.prompt,
                                    Modifier
                                        .heightIn(max = 240.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(12.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Yu.Indigo,
                                    maxLines = 12,
                                )
                            }
                        }
                    }
                    FlowRow(
                        Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val paused = status == CronStatus.PAUSED
                        if (status != CronStatus.COMPLETED) {
                            ActionButton(
                                label = if (paused) "Reanudar" else "Pausar",
                                icon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                enabled = !busy,
                                onClick = onTogglePause,
                            )
                        }
                        ActionButton(
                            label = "Ejecutar ahora",
                            icon = Icons.Rounded.Bolt,
                            enabled = !busy && status != CronStatus.RUNNING,
                            onClick = onRunNow,
                        )
                        ActionButton(
                            label = if (hidden) "Mostrar" else "Ocultar",
                            icon = if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                            enabled = true,
                            onClick = onToggleHidden,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = Yu.SkySoft,
            contentColor = Yu.PeriwinkleDeep,
        ),
    ) {
        Icon(icon, contentDescription = null, Modifier.height(18.dp))
        Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatusChip(status: CronStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        CronStatus.ACTIVE -> "Activo" to Yu.Success
        CronStatus.PAUSED -> "En pausa" to Yu.Gold
        CronStatus.RUNNING -> "Ejecutándose" to Yu.Periwinkle
        CronStatus.FAILED -> "Falló" to Yu.Danger
        CronStatus.COMPLETED -> "Terminado" to Yu.Muted
    }
    Surface(modifier = modifier, shape = CircleShape, color = color.copy(alpha = 0.16f)) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Yu.Indigo,
        )
    }
}

/** "en menos de 1 min", "en 5 min", "en 2 h", "mañana", "en 3 días"; falls back to "ahora" for past times. */
private fun formatFutureTime(millis: Long, now: Long): String {
    val diff = millis - now
    if (diff <= 0L) return "ahora"
    val min = diff / 60_000L
    val hours = min / 60
    val days = hours / 24
    return when {
        min < 1 -> "en menos de 1 min"
        min < 60 -> "en $min min"
        hours < 24 -> "en $hours h"
        days == 1L -> "mañana"
        else -> "en $days días"
    }
}
