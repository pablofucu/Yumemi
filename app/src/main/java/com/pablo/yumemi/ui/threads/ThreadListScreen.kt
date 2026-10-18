@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.pablo.yumemi.ui.threads

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pablo.yumemi.AppViewModelProvider
import com.pablo.yumemi.domain.ActivityKind
import com.pablo.yumemi.domain.ChatThread
import com.pablo.yumemi.domain.ConnectionState
import com.pablo.yumemi.domain.YumemiMood
import com.pablo.yumemi.ui.components.ConnectionChip
import com.pablo.yumemi.ui.components.SoftCard
import com.pablo.yumemi.ui.components.SpeechBubble
import com.pablo.yumemi.ui.components.YumemiBackground
import com.pablo.yumemi.ui.components.softShadow
import com.pablo.yumemi.ui.sprite.YumemiSprite
import com.pablo.yumemi.ui.theme.Yu
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class Filter(val label: String) { ALL("Todas"), ACTIVE("Activas"), PINNED("Favs") }

private enum class Section(val label: String, val icon: ImageVector) {
    NOW("Ahora mismo", Icons.Rounded.AutoAwesome),
    PINNED("Favoritas", Icons.Rounded.Favorite),
    TODAY("Hoy", Icons.Rounded.WbSunny),
    YESTERDAY("Ayer", Icons.Rounded.Cloud),
    WEEK("Esta semana", Icons.Rounded.NightsStay),
    MONTH("Este mes", Icons.Rounded.CalendarMonth),
    OLDER("Antes", Icons.Rounded.History),
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

private fun startOfToday(now: Long): Long = Calendar.getInstance().run {
    timeInMillis = now
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}

private fun sectionOf(t: ChatThread, today: Long): Section = when {
    t.activity != null -> Section.NOW
    t.pinned -> Section.PINNED
    t.updatedAt >= today -> Section.TODAY
    t.updatedAt >= today - DAY_MS -> Section.YESTERDAY
    t.updatedAt >= today - 6 * DAY_MS -> Section.WEEK
    t.updatedAt >= today - 30 * DAY_MS -> Section.MONTH
    else -> Section.OLDER
}

private val es = Locale("es", "ES")

/** Short time for a row: the hour today and yesterday, the weekday this week, else the date. */
private fun rowTime(t: ChatThread, today: Long): String {
    if (t.activity != null) return "ahora"
    if (t.updatedAt <= 0) return ""
    val pattern = when {
        t.updatedAt >= today - DAY_MS -> "HH:mm"
        t.updatedAt >= today - 6 * DAY_MS -> "EEE"
        else -> "d MMM"
    }
    return SimpleDateFormat(pattern, es).format(Date(t.updatedAt))
}

@Composable
fun ThreadListScreen(
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: ThreadListViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    // Watches turns other clients run while the list is on screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.watchActivity() }
    }

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ChatThread?>(null) }
    var deleting by remember { mutableStateOf<ChatThread?>(null) }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }

    val busy = state.threads.filter { it.activity != null }
    val mood = when {
        state.mood != YumemiMood.IDLE -> state.mood
        busy.any { it.activity?.kind == ActivityKind.TERMINAL } -> YumemiMood.WORKING
        busy.isNotEmpty() -> YumemiMood.THINKING
        else -> YumemiMood.IDLE
    }
    val greeting = when {
        state.mood == YumemiMood.SLEEPING || state.connection is ConnectionState.Unreachable ->
            "No consigo conectar… ¿está encendido Hermes?"
        state.connection == ConnectionState.Unauthorized -> "Mmm, la clave no parece correcta…"
        state.connection == ConnectionState.NotConfigured -> "Configura la conexión y empezamos."
        state.mood == YumemiMood.ERROR -> "¡Ay! Algo ha salido mal."
        busy.size == 1 -> "Estoy con una cosita en marcha ✨"
        busy.size > 1 -> "¡Tengo ${busy.size} cositas en marcha! ✨"
        else -> "¡Hola! ¿De qué hablamos hoy?"
    }

    val today = startOfToday(System.currentTimeMillis())
    val shown = when (filter) {
        Filter.ALL -> state.threads
        Filter.ACTIVE -> busy
        Filter.PINNED -> state.threads.filter { it.pinned }
    }
    val sections = shown.groupBy { sectionOf(it, today) }.toSortedMap()

    YumemiBackground(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text("Conversaciones", fontWeight = FontWeight.Bold, color = Yu.Indigo)
                    },
                    actions = {
                        ConnectionChip(state.connection, onClick = { vm.refresh() })
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Ajustes", tint = Yu.PeriwinkleDeep)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (!creating) {
                            creating = true
                            vm.createThread { id ->
                                creating = false
                                onOpenChat(id)
                            }
                        }
                    },
                    modifier = Modifier.border(3.dp, Color.White, CircleShape),
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Charlemos", fontWeight = FontWeight.SemiBold) },
                    containerColor = Yu.Periwinkle,
                    contentColor = Color.White,
                    shape = CircleShape,
                )
            },
        ) { pad ->
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.padding(top = pad.calculateTopPadding()).fillMaxSize(),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 0.dp,
                        bottom = pad.calculateBottomPadding() + 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "header") {
                        Header(mood, greeting, Modifier.fillMaxWidth())
                    }
                    item(key = "filters") {
                        FilterRow(filter, busy.size, onSelect = { filter = it })
                    }
                    state.error?.let { err ->
                        item(key = "error") {
                            SoftCard(Modifier.fillMaxWidth(), color = Yu.DangerSoft, elevation = 2.dp) {
                                Text(
                                    err,
                                    Modifier.padding(14.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF8A2B42),
                                )
                            }
                        }
                    }
                    if (shown.isEmpty() && !state.isRefreshing) {
                        item(key = "empty-$filter") { EmptyState(filter) }
                    }
                    sections.forEach { (section, threads) ->
                        item(key = "section-${section.name}") {
                            SectionHeader(section, Modifier.animateItem())
                        }
                        items(threads, key = { it.id }) { thread ->
                            ThreadRow(
                                thread = thread,
                                time = rowTime(thread, today),
                                onClick = { onOpenChat(thread.id) },
                                onTogglePin = { vm.setPinned(thread.id, !thread.pinned) },
                                onRename = { renaming = thread },
                                onDelete = { deleting = thread },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }

    renaming?.let { t ->
        RenameDialog(
            initial = t.title,
            onDismiss = { renaming = null },
            onConfirm = { vm.renameThread(t.id, it); renaming = null },
        )
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            shape = RoundedCornerShape(26.dp),
            containerColor = Color.White,
            title = { Text("¿Eliminar conversación?", fontWeight = FontWeight.Bold) },
            text = { Text("«${t.title.ifBlank { "Sin título" }}» se borrará de forma permanente.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteThread(t.id); deleting = null }) {
                    Text("Eliminar", color = Yu.Danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

/** Yumemi's head bobbing next to a speech bubble. */
@Composable
private fun Header(mood: YumemiMood, greeting: String, modifier: Modifier = Modifier) {
    val bob by rememberInfiniteTransition(label = "header").animateFloat(
        -1f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob",
    )
    Row(modifier.padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Box(
            Modifier
                .graphicsLayer { translationY = bob * 3.dp.toPx() }
                .size(84.dp)
                .softShadow(6.dp, CircleShape)
                .border(3.dp, Color.White, CircleShape)
                .clip(CircleShape)
                .background(Yu.LilacSoft),
        ) {
            YumemiSprite(mood, Modifier.fillMaxSize(), face = true, headOnly = true)
        }
        AnimatedContent(
            greeting,
            Modifier.weight(1f).padding(start = 10.dp, bottom = 30.dp),
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
            label = "greeting",
        ) { text ->
            SpeechBubble(text)
        }
    }
}

@Composable
private fun FilterRow(selected: Filter, busyCount: Int, onSelect: (Filter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Filter.entries.forEach { f ->
            val on = f == selected
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (on) Yu.Periwinkle else Color.White)
                    .clickable { onSelect(f) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (f) {
                    Filter.ACTIVE -> if (busyCount > 0 && !on) BouncingDots() else Unit
                    Filter.PINNED -> Icon(
                        Icons.Rounded.Favorite, null, Modifier.size(14.dp),
                        tint = if (on) Color.White else Color(0xFFF4A7C6),
                    )
                    Filter.ALL -> Unit
                }
                Text(
                    if (f == Filter.ACTIVE && busyCount > 0) "${f.label} · $busyCount" else f.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) Color.White else Yu.Muted,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(section: Section, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(start = 8.dp, top = 10.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(section.icon, null, Modifier.size(15.dp), tint = Yu.Lilac)
        Text(section.label, style = MaterialTheme.typography.labelLarge, color = Color(0xFF8A7FD6))
    }
}

@Composable
private fun EmptyState(filter: Filter) {
    val (title, body) = when (filter) {
        Filter.ALL -> "Todavía no hay conversaciones" to "Pulsa «Charlemos» y empezamos."
        Filter.ACTIVE -> "Ahora mismo estoy libre" to "Aquí verás lo que tenga en marcha, aunque lo hayas empezado desde fuera."
        Filter.PINNED -> "Sin favoritas todavía" to "Mantén pulsada una conversación para marcarla con un corazón."
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Yu.Indigo)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = Yu.Muted,
            modifier = Modifier.padding(top = 4.dp),
            textAlign = TextAlign.Center,
        )
    }
}

private val RowShape = RoundedCornerShape(22.dp)

@Composable
private fun ThreadRow(
    thread: ChatThread,
    time: String,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            false // snap back; the dialog decides
        },
    )
    val activity = thread.activity
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Yu.DangerSoft, RowShape)
                    .padding(end = 22.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = "Eliminar", tint = Yu.Danger)
            }
        },
    ) {
        Box {
            Surface(
                Modifier
                    .fillMaxWidth()
                    .softShadow(3.dp, RowShape)
                    .border(
                        2.dp,
                        if (activity != null) activityColor(activity.kind).copy(alpha = 0.45f) else Color.White,
                        RowShape,
                    )
                    .clip(RowShape)
                    .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
                shape = RowShape,
                color = Color.White,
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ThreadAvatar(thread)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    thread.title.ifBlank { "Sin título" },
                                    Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Yu.Indigo,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (thread.pinned) {
                                    Icon(
                                        Icons.Rounded.Favorite, null,
                                        Modifier.padding(start = 4.dp).size(13.dp),
                                        tint = Color(0xFFF4A7C6),
                                    )
                                }
                            }
                            sourceLabel(thread.source)?.let { SourceTag(it, Modifier.padding(start = 6.dp)) }
                            Text(
                                time,
                                Modifier.padding(start = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (activity != null) activityColor(activity.kind) else Yu.Muted,
                            )
                        }
                        AnimatedContent(
                            activity,
                            Modifier.padding(top = 2.dp),
                            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                            contentKey = { it?.kind },
                            label = "subtitle",
                        ) { a ->
                            if (a != null) ActivityLine(a) else LastMessageLine(thread)
                        }
                    }
                }
            }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                shape = RoundedCornerShape(18.dp),
                containerColor = Color.White,
            ) {
                DropdownMenuItem(
                    text = { Text(if (thread.pinned) "Quitar de favoritas" else "Marcar como favorita") },
                    leadingIcon = {
                        Icon(
                            if (thread.pinned) Icons.Rounded.FavoriteBorder else Icons.Rounded.Favorite,
                            null,
                            tint = Color(0xFFF4A7C6),
                        )
                    },
                    onClick = { menu = false; onTogglePin() },
                )
                DropdownMenuItem(
                    text = { Text("Renombrar") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { menu = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Eliminar", color = Yu.Danger) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Yu.Danger) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

/** "Tú · …" / "Yumemi · …" for the latest message, or the server's snippet when it is unknown. */
@Composable
private fun LastMessageLine(thread: ChatThread) {
    val text = if (thread.lastMessage.isNotBlank()) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = if (thread.lastFromUser) Yu.Periwinkle else Yu.Lilac, fontWeight = FontWeight.Medium)) {
                append(if (thread.lastFromUser) "Tú · " else "Yumemi · ")
            }
            append(thread.lastMessage)
        }
    } else {
        buildAnnotatedString { append(thread.preview.ifBlank { "Sin mensajes todavía" }) }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Yu.Muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        containerColor = Color.White,
        title = { Text("Renombrar conversación", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                label = { Text("Título") },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) {
                Text("Guardar", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
