package com.pablo.yumemi.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.pablo.yumemi.domain.ChatMessage
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.domain.Role
import com.pablo.yumemi.ui.theme.Yu
import com.pablo.yumemi.ui.theme.YumemiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xxhdpi")
class MarkdownScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun message(id: String, role: Role, text: String) =
        ChatMessage(id, role, listOf(MessagePart.Text(text)), createdAt = 0L)

    @Composable
    private fun Chat(question: String, answer: String) {
        YumemiTheme {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Yu.BackgroundBrush)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                UserBubble(message("u", Role.USER, question))
                AssistantMessage(
                    message("a", Role.ASSISTANT, answer),
                    detailsExpanded = false,
                    showInlineError = false,
                )
            }
        }
    }

    @Test
    fun chatMarkdown() {
        composeTestRule.setContent { Chat("¿Me haces un resumen del servidor?", RICH_ANSWER) }
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/chat_markdown.png")
    }

    @Test
    fun chatMarkdownAncho() {
        composeTestRule.setContent { Chat("Enséñame la configuración completa.", WIDE_ANSWER) }
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/chat_markdown_ancho.png")
    }

    private companion object {
        val RICH_ANSWER = """
            ## Estado del servidor

            Todo funciona **bien**, aunque *conviene* revisar el `cron` de copias: el job de ayer fue ~~lento~~ más lento de lo normal. Más detalles en la [documentación](https://hermes-agent.nousresearch.com).

            - Servicios principales activos
              - La pasarela responde en menos de 50 ms
            - Base de datos con réplica al día
            - Certificados válidos hasta diciembre

            1. Revisar los logs de la noche
            2. Reiniciar el worker de colas
            3. Confirmar la copia de seguridad

            | Servicio | Estado | CPU | Notas |
            |:---|:---:|---:|---|
            | API | Activo | 12% | Sin incidencias |
            | Worker | Activo | 48% | Procesa la cola de correos y reintenta los envíos fallidos cada cinco minutos durante la noche |
            | Base de datos | Lento | 71% | Réplica al día |

            ```bash
            systemctl status hermes
            journalctl -u hermes --since "1 hour ago"
            df -h /var/lib/hermes
            ```

            ```kotlin
            fun main() {
                val servicios = listOf("api", "worker", "db")
                servicios.forEach { println("Comprobando ${'$'}it") }
            }
            ```

            > Recuerda que las copias se guardan 14 días.
            >
            > Después se eliminan automáticamente.

            ---

            ### Siguientes pasos

            - [ ] Ajustar el límite de memoria del worker
            - [ ] Programar la rotación de logs
            - [x] Actualizar los certificados
        """.trimIndent()

        val WIDE_ANSWER = """
            # Título grande

            Aquí tienes la tabla completa y un fragmento de configuración.

            | Entorno | Región | Instancias | CPU media | Memoria | Latencia p95 |
            |---|---|---:|---:|---:|---:|
            | Producción | eu-west-1 | 12 | 46% | 62% | 180 ms |
            | Preproducción | eu-west-1 | 4 | 21% | 35% | 140 ms |
            | Desarrollo | eu-south-2 | 2 | 8% | 18% | 95 ms |

            ### Configuración

            ```yaml
            hermes:
              endpoint: https://hermes.example.com/api/v1/runs?stream=true&timeout=120&retry=3&backoff=exponential&region=eu-west-1
              workers: 4
            ```
        """.trimIndent()
    }
}
