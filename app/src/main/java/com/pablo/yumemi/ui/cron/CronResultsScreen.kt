@file:OptIn(ExperimentalMaterial3Api::class)

package com.pablo.yumemi.ui.cron

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mikepenz.markdown.m3.Markdown
import com.pablo.yumemi.AppViewModelProvider
import com.pablo.yumemi.data.cron.CronResult
import com.pablo.yumemi.domain.YumemiMood
import com.pablo.yumemi.ui.components.SoftCard
import com.pablo.yumemi.ui.components.YumemiBackground
import com.pablo.yumemi.ui.components.formatRelativeTime
import com.pablo.yumemi.ui.sprite.YumemiSprite
import com.pablo.yumemi.ui.theme.Yu
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private const val AUTO_REFRESH_MS = 60_000L
private val ErrorText = Color(0xFF8A2B42)
private val es = Locale("es", "ES")

@Composable
fun CronResultsScreen() {
    val vm: CronResultsViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }

    // Load the jobs on open, then every minute while visible: that is when new messages are picked up.
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
                    title = { Text("Resultados", fontWeight = FontWeight.Bold, color = Yu.Indigo) },
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
                    if (state.loaded && state.results.isEmpty()) {
                        item(key = "empty") { EmptyState(hasError = state.error != null) }
                    }
                    items(state.results, key = { it.id }) { result ->
                        ResultCard(
                            result = result,
                            expanded = expandedId == result.id,
                            onToggle = { expandedId = if (expandedId == result.id) null else result.id },
                            onDelete = { vm.delete(result.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(hasError: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        YumemiSprite(
            if (hasError) YumemiMood.SLEEPING else YumemiMood.IDLE,
            Modifier.height(170.dp),
        )
        Text(
            "Aún no hay resultados",
            style = MaterialTheme.typography.titleMedium,
            color = Yu.Indigo,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Los mensajes que dejen tus cronjobs aparecerán aquí.",
            style = MaterialTheme.typography.bodyMedium,
            color = Yu.Muted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ResultCard(
    result: CronResult,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SoftCard(modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    result.jobName,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = Yu.Indigo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (expanded) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Borrar", tint = Yu.Muted)
                    }
                }
            }
            Text(
                "${formatRunTime(result.runAt)}  ·  ${formatRelativeTime(result.runAt)}",
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.labelLarge,
                color = Yu.Muted,
            )
            if (expanded) {
                Column(Modifier.padding(top = 10.dp)) { Markdown(content = result.text) }
            } else {
                Text(
                    previewOf(result.text),
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Yu.Indigo,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatRunTime(millis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", es).format(Date(millis))

/** Collapsed-card text: markdown markers stripped so the snippet reads as plain prose. */
internal fun previewOf(text: String): String =
    text.replace(Regex("[#*_`>]+"), "").replace(Regex("\\s+"), " ").trim()
