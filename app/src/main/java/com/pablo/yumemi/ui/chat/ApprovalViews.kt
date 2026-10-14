package com.pablo.yumemi.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pablo.yumemi.domain.ApprovalStatus
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.ui.theme.Yu

private fun approvalLabel(choice: String): String = when (choice) {
    "once" -> "Permitir una vez"
    "session" -> "Permitir en este chat"
    "always" -> "Permitir siempre"
    "deny" -> "Denegar"
    else -> choice.replaceFirstChar { it.uppercase() }
}

/** Hermes paused before a risky command: the question and its options as buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApprovalCard(part: MessagePart.Approval, onChoice: (String) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val active = part.status == ApprovalStatus.PENDING
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (active) Yu.GoldSoft else Yu.LilacSoft)
            .animateContentSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp), tint = Yu.PeriwinkleDeep)
            Spacer(Modifier.width(8.dp))
            Text(
                "¿Me dejas ejecutar esto?",
                style = MaterialTheme.typography.labelLarge,
                color = Yu.Indigo,
            )
        }
        if (part.description.isNotBlank()) {
            Text(part.description, style = MaterialTheme.typography.bodySmall, color = Yu.Muted)
        }
        if (part.command.isNotBlank()) {
            Text(
                part.command.take(1500),
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.85f))
                    .padding(8.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = Yu.Indigo,
            )
        }
        when (part.status) {
            ApprovalStatus.PENDING -> {
                if (part.answer != null) {
                    Text(
                        "No he podido enviar tu respuesta. Prueba otra vez.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Yu.Danger,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    part.choices.forEach { c ->
                        if (c == "deny") {
                            OutlinedButton(onClick = { onChoice(c) }) { Text(approvalLabel(c)) }
                        } else if (c == part.choices.first()) {
                            Button(
                                onClick = { onChoice(c) },
                                colors = ButtonDefaults.buttonColors(containerColor = Yu.Periwinkle),
                            ) { Text(approvalLabel(c)) }
                        } else {
                            FilledTonalButton(onClick = { onChoice(c) }) { Text(approvalLabel(c)) }
                        }
                    }
                }
            }
            ApprovalStatus.SENDING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Yu.Periwinkle)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Enviando: ${approvalLabel(part.answer.orEmpty())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Yu.Muted,
                )
            }
            ApprovalStatus.ANSWERED -> {
                val denied = part.answer == "deny"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (denied) Icons.Rounded.Cancel else Icons.Rounded.CheckCircle, null,
                        Modifier.size(16.dp),
                        tint = if (denied) Yu.Danger else Yu.Success,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Respondiste: ${approvalLabel(part.answer.orEmpty())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Yu.Muted,
                    )
                }
            }
            ApprovalStatus.EXPIRED -> Text(
                "Sin respuesta: Hermes dejó de esperar.",
                style = MaterialTheme.typography.bodySmall,
                color = Yu.Muted,
            )
        }
    }
}
