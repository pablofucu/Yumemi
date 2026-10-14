@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.pablo.yumemi.ui.threads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pablo.yumemi.AppViewModelProvider
import com.pablo.yumemi.domain.ChatThread
import com.pablo.yumemi.domain.ConnectionState
import com.pablo.yumemi.domain.ThreadListUiState
import com.pablo.yumemi.domain.YumemiMood
import com.pablo.yumemi.ui.sprite.YumemiSprite
import com.pablo.yumemi.ui.components.ConnectionChip
import com.pablo.yumemi.ui.components.SoftCard
import com.pablo.yumemi.ui.components.SpeechBubble
import com.pablo.yumemi.ui.components.YumemiBackground
import com.pablo.yumemi.ui.components.formatRelativeTime
import com.pablo.yumemi.ui.theme.Yu

@Composable
fun ThreadListScreen(
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: ThreadListViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    ThreadListContent(
        state = state,
        onOpenChat = onOpenChat,
        onOpenSettings = onOpenSettings,
        onRefresh = vm::refresh,
        onCreateThread = vm::createThread,
        onRenameThread = vm::renameThread,
        onDeleteThread = vm::deleteThread,
    )
}

/** Stateless body of [ThreadListScreen], also used by the screenshot tests. */
@Composable
fun ThreadListContent(
    state: ThreadListUiState,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onCreateThread: (onCreated: (String) -> Unit) -> Unit,
    onRenameThread: (id: String, title: String) -> Unit,
    onDeleteThread: (id: String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ChatThread?>(null) }
    var deleting by remember { mutableStateOf<ChatThread?>(null) }

    val greeting = when {
        state.mood == YumemiMood.SLEEPING || state.connection is ConnectionState.Unreachable ->
            "No consigo conectar… ¿está encendido Hermes?"
        state.connection == ConnectionState.Unauthorized -> "Mmm, la clave no parece correcta…"
        state.connection == ConnectionState.NotConfigured -> "Configura la conexión y empezamos."
        state.mood == YumemiMood.ERROR -> "¡Ay! Algo ha salido mal."
        state.mood == YumemiMood.THINKING || state.mood == YumemiMood.WORKING -> "Un momento, estoy en ello…"
        else -> "¡Hola! ¿De qué hablamos hoy?"
    }

    YumemiBackground(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text("Conversaciones", fontWeight = FontWeight.Bold, color = Yu.Indigo)
                    },
                    actions = {
                        ConnectionChip(state.connection, onClick = { onRefresh() })
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
                            onCreateThread { id ->
                                creating = false
                                onOpenChat(id)
                            }
                        }
                    },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Nueva conversación", fontWeight = FontWeight.SemiBold) },
                    containerColor = Yu.Periwinkle,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(20.dp),
                )
            },
        ) { pad ->
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { onRefresh() },
                modifier = Modifier.padding(top = pad.calculateTopPadding()).fillMaxSize(),
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp,
                        bottom = pad.calculateBottomPadding() + 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "header") {
                        Header(state.mood, greeting, Modifier.fillMaxWidth())
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
                    if (state.threads.isEmpty() && !state.isRefreshing) {
                        item(key = "empty") {
                            Column(
                                Modifier.fillMaxWidth().padding(top = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "Todavía no hay conversaciones",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Yu.Indigo,
                                )
                                Text(
                                    "Pulsa «Nueva conversación» y charlamos.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Yu.Muted,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    items(state.threads, key = { it.id }) { thread ->
                        ThreadRow(
                            thread = thread,
                            onClick = { onOpenChat(thread.id) },
                            onRename = { renaming = thread },
                            onDelete = { deleting = thread },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    renaming?.let { t ->
        RenameDialog(
            initial = t.title,
            onDismiss = { renaming = null },
            onConfirm = { onRenameThread(t.id, it); renaming = null },
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
                TextButton(onClick = { onDeleteThread(t.id); deleting = null }) {
                    Text("Eliminar", color = Yu.Danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Header(mood: YumemiMood, greeting: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        YumemiSprite(mood, Modifier.height(170.dp))
        SpeechBubble(
            greeting,
            Modifier.weight(1f).padding(start = 4.dp, bottom = 70.dp),
        )
    }
}

@Composable
private fun ThreadRow(
    thread: ChatThread,
    onClick: () -> Unit,
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
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Yu.DangerSoft, RoundedCornerShape(22.dp))
                    .padding(end = 22.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = "Eliminar", tint = Yu.Danger)
            }
        },
    ) {
        Box {
            SoftCard(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            thread.title.ifBlank { "Sin título" },
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = Yu.Indigo,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatRelativeTime(thread.updatedAt),
                            Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = Yu.Muted,
                        )
                    }
                    if (thread.preview.isNotBlank()) {
                        Text(
                            thread.preview,
                            Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Yu.Muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
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
