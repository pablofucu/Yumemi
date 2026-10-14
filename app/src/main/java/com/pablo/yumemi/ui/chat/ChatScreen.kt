@file:OptIn(ExperimentalMaterial3Api::class)

package com.pablo.yumemi.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pablo.yumemi.AppViewModelProvider
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.Role
import com.pablo.yumemi.ui.sprite.YumemiSprite
import com.pablo.yumemi.ui.components.ConnectionDot
import com.pablo.yumemi.ui.components.SoftCard
import com.pablo.yumemi.ui.components.YumemiBackground
import com.pablo.yumemi.ui.components.connectionLabel
import com.pablo.yumemi.ui.components.softShadow
import com.pablo.yumemi.ui.theme.Yu
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import com.pablo.yumemi.domain.YumemiMood

@Composable
fun ChatScreen(onBack: () -> Unit) {
    val vm: ChatViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()

    var showDetails by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Reverse layout: index 0 is the newest message and sits at the bottom.
    val atBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 60 }
    }
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { atBottom }.collect { if (it) stick = true }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Start -> stick = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> stick = atBottom
                else -> Unit
            }
        }
    }
    val lastMsg = state.messages.lastOrNull()
    val talking = lastMsg != null && lastMsg.role == Role.ASSISTANT && lastMsg.isStreaming &&
        (lastMsg.parts.lastOrNull() as? MessagePart.Text)?.text?.isNotBlank() == true
    LaunchedEffect(state.messages.size, state.error) {
        if (stick && (state.messages.isNotEmpty() || state.error != null)) listState.animateScrollToItem(0)
    }

    val errorText = state.error ?: lastMsg?.error
    val reversed = remember(state.messages) { state.messages.asReversed() }

    val doSend = {
        val t = input.trim()
        if (t.isNotEmpty() && !state.isSending) {
            vm.send(t)
            input = ""
            stick = true
        }
    }

    YumemiBackground(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver", tint = Yu.Indigo)
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp)) {
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .clip(CircleShape)
                                        .background(Yu.SkySoft)
                                        .border(1.5.dp, Yu.Lilac.copy(alpha = 0.6f), CircleShape),
                                ) {
                                    YumemiSprite(
                                        state.mood,
                                        Modifier.fillMaxSize(),
                                        talking = talking,
                                        face = true,
                                    )
                                }
                                ConnectionDot(
                                    state.connection,
                                    Modifier.align(Alignment.BottomEnd),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    state.title.ifBlank { "Conversación" },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Yu.Indigo,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    connectionLabel(state.connection),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Yu.Muted,
                                )
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, "Más", tint = Yu.Indigo)
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                                shape = RoundedCornerShape(18.dp),
                                containerColor = Color.White,
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Mostrar detalles") },
                                    trailingIcon = {
                                        Switch(checked = showDetails, onCheckedChange = { showDetails = it })
                                    },
                                    onClick = { showDetails = !showDetails },
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
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .consumeWindowInsets(pad)
                    .imePadding(),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (state.messages.isEmpty() && errorText == null) {
                        EmptyChat(state.mood, Modifier.align(Alignment.Center))
                    }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                    ) {
                        if (errorText != null) {
                            item(key = "error") {
                                ErrorCard(
                                    errorText,
                                    onRetry = { vm.retryLast() },
                                    onDismiss = { vm.clearError() },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                        items(reversed, key = { it.id }) { msg ->
                            if (msg.role == Role.USER) {
                                UserBubble(msg, Modifier.animateItem())
                            } else {
                                AssistantMessage(
                                    msg,
                                    detailsExpanded = showDetails,
                                    showInlineError = msg.id != lastMsg?.id,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = !atBottom,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                stick = true
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                            containerColor = Color.White,
                            contentColor = Yu.PeriwinkleDeep,
                            shape = CircleShape,
                        ) { Icon(Icons.Rounded.KeyboardArrowDown, "Ir al final") }
                    }
                }
                InputBar(
                    value = input,
                    onValueChange = { input = it },
                    isSending = state.isSending,
                    onSend = { doSend() },
                    onStop = { vm.stop() },
                )
            }
        }
    }
}

@Composable
private fun EmptyChat(mood: YumemiMood, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        YumemiSprite(mood, Modifier.height(180.dp))
        Text(
            "Escribe algo para empezar",
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            color = Yu.Indigo,
            textAlign = TextAlign.Center,
        )
        Text(
            "Estoy lista para ayudarte.",
            style = MaterialTheme.typography.bodyMedium,
            color = Yu.Muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorCard(
    text: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SoftCard(modifier.fillMaxWidth(), color = Yu.DangerSoft, elevation = 3.dp) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF8A2B42),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
                    Text("Reintentar", color = Yu.Danger, fontWeight = FontWeight.Bold)
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, "Cerrar", tint = Yu.Danger)
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isSending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val canSend = value.isNotBlank()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SoftCard(Modifier.weight(1f), shape = RoundedCornerShape(26.dp), elevation = 8.dp) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Escribe a Yumemi…", color = Yu.Muted.copy(alpha = 0.7f)) },
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = Yu.Periwinkle,
                ),
            )
        }
        val active = isSending || canSend
        Box(
            Modifier
                .size(52.dp)
                .softShadow(if (active) 8.dp else 2.dp, CircleShape)
                .clip(CircleShape)
                .background(if (active) Yu.PrimaryBrush else androidx.compose.ui.graphics.SolidColor(Yu.Outline))
                .clickable(enabled = active) { if (isSending) onStop() else onSend() },
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(isSending, label = "sendStop") { sending ->
                Icon(
                    if (sending) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                    contentDescription = if (sending) "Detener" else "Enviar",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
