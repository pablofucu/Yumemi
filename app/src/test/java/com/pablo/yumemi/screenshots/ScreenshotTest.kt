package com.pablo.yumemi.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.pablo.yumemi.domain.ChatMessage
import com.pablo.yumemi.domain.ChatThread
import com.pablo.yumemi.domain.ChatUiState
import com.pablo.yumemi.domain.ConnectionState
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.Role
import com.pablo.yumemi.domain.SettingsUiState
import com.pablo.yumemi.domain.ThreadListUiState
import com.pablo.yumemi.domain.ToolStatus
import com.pablo.yumemi.domain.YumemiMood
import com.pablo.yumemi.ui.chat.ChatContent
import com.pablo.yumemi.ui.components.YumemiBackground
import com.pablo.yumemi.ui.settings.SettingsContent
import com.pablo.yumemi.ui.sprite.YumemiSprite
import com.pablo.yumemi.ui.theme.Yu
import com.pablo.yumemi.ui.theme.YumemiTheme
import com.pablo.yumemi.ui.threads.ThreadListContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders each screen with sample data and saves a PNG to app/build/outputs/roborazzi.
 * Run with: ./gradlew recordRoborazziDebug --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi", application = Application::class)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private val min = 60_000L

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { YumemiTheme { content() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    // ---------- sample data ----------

    private val threads = listOf(
        ChatThread("s1", "Plan de viaje a Kioto", now - 4 * min, "Te he preparado un itinerario de 5 días con templos…"),
        ChatThread("s2", "Revisar el servidor de casa", now - 52 * min, "El disco /dev/sdb está al 91 %, te propongo limpiar…"),
        ChatThread("s3", "Receta de curry japonés", now - 3 * 60 * min, "Para 4 personas: 2 patatas, 1 zanahoria…"),
        ChatThread("s4", "Ideas para el blog", now - 26 * 60 * min, "Tres temas que podrían funcionar este mes…"),
        ChatThread("s5", "Resumen de planetarian", now - 4 * 24 * 60 * min, "Una robot en un planetario abandonado espera…"),
    )

    private val conversation = listOf(
        ChatMessage(
            id = "u1", role = Role.USER, createdAt = now - 6 * min,
            parts = listOf(MessagePart.Text("¿Cuánto espacio libre queda en el servidor? Si hace falta, limpia lo que sobre.")),
        ),
        ChatMessage(
            id = "a1", role = Role.ASSISTANT, createdAt = now - 5 * min,
            parts = listOf(
                MessagePart.Reasoning(
                    "Primero miro el uso de disco con df. Si algún volumen pasa del 85 %, busco qué ocupa más " +
                        "con du y reviso si son logs o cachés que se puedan borrar sin riesgo.",
                ),
                MessagePart.ToolCall(
                    "t1", "terminal", "df -h /",
                    "Filesystem  Size  Used Avail Use%\n/dev/sdb    500G  455G   45G  91%",
                    ToolStatus.OK, 0.4,
                ),
                MessagePart.ToolCall(
                    "t2", "terminal", "du -sh /var/log/* | sort -h | tail -3",
                    "1.2G  /var/log/syslog.1\n8.4G  /var/log/journal\n31G   /var/log/nginx",
                    ToolStatus.OK, 2.1,
                ),
                MessagePart.ToolCall(
                    "t3", "terminal", "find /var/log/nginx -name '*.gz' -mtime +30 -delete",
                    "", ToolStatus.OK, 1.3,
                ),
                MessagePart.Text(
                    "He liberado **29 GB**.\n\n" +
                        "- El disco estaba al **91 %**; ahora está al **85 %**.\n" +
                        "- Lo que más ocupaba eran logs antiguos de nginx (más de 30 días).\n\n" +
                        "¿Quieres que programe una limpieza automática cada semana?",
                ),
            ),
        ),
    )

    private val chatState = ChatUiState(
        threadId = "s2",
        title = "Revisar el servidor de casa",
        messages = conversation,
        mood = YumemiMood.HAPPY,
        connection = ConnectionState.Connected,
    )

    private fun noop() = Unit

    @Composable
    private fun Chat(state: ChatUiState, details: Boolean = false, input: String = "") = ChatContent(
        state = state,
        onBack = ::noop,
        onSend = {},
        onStop = ::noop,
        onRetry = ::noop,
        onClearError = ::noop,
        initialShowDetails = details,
        initialInput = input,
    )

    @Composable
    private fun Threads(state: ThreadListUiState) = ThreadListContent(
        state = state,
        onOpenChat = {},
        onOpenSettings = ::noop,
        onRefresh = ::noop,
        onCreateThread = {},
        onRenameThread = { _, _ -> },
        onDeleteThread = {},
    )

    @Composable
    private fun Settings(state: SettingsUiState, canGoBack: Boolean = true) = SettingsContent(
        state = state,
        useChat = true,
        canGoBack = canGoBack,
        onBack = ::noop,
        onSaved = ::noop,
        onBaseUrlChange = {},
        onApiKeyChange = {},
        onTransportChange = {},
        onTestConnection = ::noop,
        onSave = ::noop,
    )

    // ---------- screens ----------

    @Test
    fun conversaciones() = shot("01_conversaciones") {
        Threads(ThreadListUiState(threads = threads, connection = ConnectionState.Connected))
    }

    @Test
    fun chatRespuesta() = shot("02_chat_respuesta") { Chat(chatState) }

    @Test
    fun chatDetalles() = shot("03_chat_detalles_razonamiento") { Chat(chatState, details = true) }

    @Test
    fun chatTrabajando() = shot("04_chat_hermes_trabajando") {
        Chat(
            ChatUiState(
                threadId = "s1",
                title = "Plan de viaje a Kioto",
                mood = YumemiMood.WORKING,
                isSending = true,
                connection = ConnectionState.Connected,
                messages = listOf(
                    ChatMessage(
                        id = "u1", role = Role.USER, createdAt = now - min,
                        parts = listOf(MessagePart.Text("Busca vuelos baratos a Osaka para la segunda semana de abril.")),
                    ),
                    ChatMessage(
                        id = "a1", role = Role.ASSISTANT, createdAt = now, isStreaming = true,
                        parts = listOf(
                            MessagePart.Reasoning("Busco en varias webs de vuelos y comparo precios con escala y sin escala."),
                            MessagePart.Commentary("Voy a mirar un par de buscadores a la vez."),
                            MessagePart.ToolCall("t1", "web_search", "vuelos Madrid Osaka abril", "12 resultados", ToolStatus.OK, 1.8),
                            MessagePart.Subagent("sa1", "Comparar precios en Skyscanner", ToolStatus.RUNNING, null),
                            MessagePart.ToolCall("t2", "browser_navigate", "https://www.google.com/travel/flights", null, ToolStatus.RUNNING, null),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun chatNuevo() = shot("05_chat_nuevo") {
        Chat(
            ChatUiState(threadId = "s9", title = "Conversación nueva", connection = ConnectionState.Connected),
            input = "Hola Yumemi, ¿qué tal?",
        )
    }

    @Test
    fun chatError() = shot("06_chat_error") {
        Chat(
            chatState.copy(
                mood = YumemiMood.ERROR,
                connection = ConnectionState.Unreachable("timeout"),
                messages = conversation.take(1),
                error = "No he podido hablar con Hermes: se agotó el tiempo de espera.",
            ),
        )
    }

    @Test
    fun ajustes() = shot("07_ajustes_conectado") {
        Settings(
            SettingsUiState(
                baseUrl = "http://100.101.102.103:8642",
                apiKey = "sk-hermes-0123456789",
                testResult = ConnectionState.Connected,
            ),
        )
    }

    @Test
    fun primeraVez() = shot("08_ajustes_primera_vez") {
        Settings(SettingsUiState(), canGoBack = false)
    }

    @Test
    fun sinConexion() = shot("09_conversaciones_sin_conexion") {
        Threads(
            ThreadListUiState(
                threads = threads.take(2),
                connection = ConnectionState.Unreachable("Connection refused"),
                mood = YumemiMood.SLEEPING,
            ),
        )
    }

    @Test
    fun estadosYumemi() = shot("10_estados_yumemi") {
        val moods = listOf(
            YumemiMood.IDLE to "En reposo",
            YumemiMood.HAPPY to "Contenta",
            YumemiMood.THINKING to "Pensando",
            YumemiMood.WORKING to "Trabajando",
            YumemiMood.SURPRISED to "Sorprendida",
            YumemiMood.ERROR to "Error",
            YumemiMood.SLEEPING to "Sin conexión",
        )
        YumemiBackground(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Estados de Yumemi",
                    Modifier.padding(start = 8.dp, top = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Yu.Indigo,
                )
                moods.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (mood, label) ->
                            Column(
                                Modifier
                                    .weight(1f)
                                    .background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                                    .padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                YumemiSprite(mood, Modifier.height(190.dp))
                                Text(label, style = MaterialTheme.typography.labelLarge, color = Yu.Indigo)
                            }
                        }
                        repeat(3 - row.size) { Column(Modifier.weight(1f)) {} }
                    }
                }
            }
        }
    }
}
