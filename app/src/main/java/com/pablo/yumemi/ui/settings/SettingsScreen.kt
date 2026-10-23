@file:OptIn(ExperimentalMaterial3Api::class)

package com.pablo.yumemi.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pablo.yumemi.AppViewModelProvider
import com.pablo.yumemi.data.update.UpdateState
import com.pablo.yumemi.domain.ConnectionState
import com.pablo.yumemi.domain.YumemiMood
import com.pablo.yumemi.ui.sprite.YumemiSprite
import com.pablo.yumemi.ui.components.SoftCard
import com.pablo.yumemi.ui.components.YumemiBackground
import com.pablo.yumemi.ui.theme.Yu
import androidx.compose.ui.text.input.ImeAction

/**
 * @param canGoBack false on first launch (nothing to go back to).
 * @param onSaved called once after the user saves.
 */
@Composable
fun SettingsScreen(
    canGoBack: Boolean,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val useChat by vm.useChatCompletions.collectAsStateWithLifecycle()
    val feedsUrl by vm.feedsUrl.collectAsStateWithLifecycle()

    // Fire onSaved only for a false -> true transition seen while this screen is shown.
    var seenNotSaved by remember { mutableStateOf(!state.saved) }
    LaunchedEffect(state.saved) {
        if (!state.saved) seenNotSaved = true
        else if (seenNotSaved) onSaved()
    }

    var showKey by remember { mutableStateOf(false) }

    val mood = when (state.testResult) {
        ConnectionState.Connected -> YumemiMood.HAPPY
        ConnectionState.Checking -> YumemiMood.THINKING
        ConnectionState.Unauthorized -> YumemiMood.SURPRISED
        is ConnectionState.Unreachable -> YumemiMood.ERROR
        else -> YumemiMood.IDLE
    }

    YumemiBackground(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Conexión", fontWeight = FontWeight.Bold, color = Yu.Indigo) },
                    navigationIcon = {
                        if (canGoBack) {
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
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .consumeWindowInsets(pad)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    YumemiSprite(mood, Modifier.height(200.dp))
                    Text(
                        if (canGoBack) "Ajusta cómo hablo con Hermes" else "¡Hola! Vamos a conectar con Hermes",
                        style = MaterialTheme.typography.titleMedium,
                        color = Yu.Indigo,
                    )
                }

                SoftCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.baseUrl,
                            onValueChange = vm::onBaseUrlChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("URL de Hermes") },
                            placeholder = {
                                Text("https://hermes.tu-dominio.com o http://100.x.x.x:8642")
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next,
                            ),
                        )
                        OutlinedTextField(
                            value = state.apiKey,
                            onValueChange = vm::onApiKeyChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Clave de API") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            visualTransformation = if (showKey) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(
                                        if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = if (showKey) "Ocultar clave" else "Mostrar clave",
                                        tint = Yu.Muted,
                                    )
                                }
                            },
                        )

                        AnimatedVisibility(state.insecureWarning) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Yu.GoldSoft, RoundedCornerShape(14.dp))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Rounded.Warning, null, tint = Color(0xFFC79200))
                                Text(
                                    "Esta dirección usa http:// fuera de una red privada: tu clave viajaría sin cifrar. " +
                                        "Usa HTTPS (Cloudflare Tunnel) o una IP de Tailscale.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF7A5A00),
                                )
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth().animateContentSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            FilledTonalButton(
                                onClick = { vm.testConnection() },
                                enabled = state.baseUrl.isNotBlank() && state.testResult != ConnectionState.Checking,
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Yu.SkySoft,
                                    contentColor = Yu.Indigo,
                                ),
                            ) {
                                if (state.testResult == ConnectionState.Checking) {
                                    CircularProgressIndicator(
                                        Modifier.size(16.dp), strokeWidth = 2.dp, color = Yu.Periwinkle,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text("Probar conexión", fontWeight = FontWeight.SemiBold)
                            }
                            TestResultChip(state.testResult, Modifier.weight(1f, fill = false))
                        }
                    }
                }

                SoftCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = feedsUrl,
                            onValueChange = vm::onFeedsUrlChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("URL de informes (opcional)") },
                            placeholder = {
                                Text(vm.defaultFeedsUrl().ifBlank { "https://tu-vm.ts.net/feeds" })
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done,
                            ),
                        )
                        Text(
                            "Donde Hermes publica los feeds de sus automatizaciones. " +
                                "Vacío: junto a la API de Hermes, en /feeds, con la misma clave.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Yu.Muted,
                        )
                    }
                }

                SoftCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Mostrar razonamiento (modo Chat Completions)",
                                style = MaterialTheme.typography.titleSmall,
                                color = Yu.Indigo,
                            )
                            Text(
                                "Cambia el transporte para poder ver lo que Hermes piensa mientras responde.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Yu.Muted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = useChat, onCheckedChange = vm::onTransportChange)
                    }
                }

                UpdateCard(vm)

                Button(
                    onClick = { vm.save() },
                    enabled = state.baseUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Yu.Periwinkle),
                ) {
                    Text("Guardar", style = MaterialTheme.typography.titleMedium)
                }

                SoftCard(Modifier.fillMaxWidth(), color = Yu.LilacSoft, elevation = 0.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("¿Cómo llego a mi Hermes?", style = MaterialTheme.typography.titleSmall, color = Yu.PeriwinkleDeep)
                        Text(
                            "Si Hermes corre en tu casa o tu servidor, lo más fácil es Tailscale: instala la app en el móvil " +
                                "y en el servidor, y usa la IP 100.x.x.x que te da (http://100.x.x.x:8642). " +
                                "Otra opción es un Cloudflare Tunnel, que te da una dirección https:// pública y cifrada; " +
                                "en ese caso protege el acceso con tu clave de API.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Yu.Indigo,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TestResultChip(result: ConnectionState, modifier: Modifier = Modifier) {
    val (text, color) = when (result) {
        ConnectionState.Connected -> "Conectado" to Yu.Success
        ConnectionState.Unauthorized -> "Clave incorrecta" to Color(0xFFC79200)
        is ConnectionState.Unreachable -> result.message.ifBlank { "No se puede conectar" } to Yu.Danger
        ConnectionState.NotConfigured -> "Introduce la URL" to Yu.Muted
        else -> return
    }
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = CircleShape,
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (result == ConnectionState.Connected) Color(0xFF1F7A60) else color,
            maxLines = 2,
        )
    }
}

@Composable
private fun UpdateCard(vm: SettingsViewModel) {
    val update by vm.updateState.collectAsStateWithLifecycle()
    SoftCard(Modifier.fillMaxWidth(), color = Yu.LilacSoft, elevation = 0.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Actualizaciones", style = MaterialTheme.typography.titleSmall, color = Yu.PeriwinkleDeep)
            Text(
                when (val u = update) {
                    is UpdateState.Checking -> "Buscando…"
                    is UpdateState.UpToDate -> "Versión ${vm.appVersion}: estás al día."
                    is UpdateState.Available -> "Hay una versión nueva: ${u.info.version} (tienes ${vm.appVersion})."
                    is UpdateState.Downloading -> "Descargando ${u.info.version}…"
                    is UpdateState.Failed -> "Error: ${u.message}"
                    UpdateState.Idle -> "Versión ${vm.appVersion}."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Yu.Indigo,
            )
            val available = (update as? UpdateState.Available)?.info
            FilledTonalButton(
                onClick = { if (available != null) vm.installUpdate(available) else vm.checkUpdate() },
                enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading,
            ) {
                Text(if (available != null) "Descargar e instalar" else "Buscar actualizaciones")
            }
        }
    }
}
