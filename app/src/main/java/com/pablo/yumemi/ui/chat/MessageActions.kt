package com.pablo.yumemi.ui.chat

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.pablo.yumemi.domain.ChatMessage
import com.pablo.yumemi.domain.MessagePart
import com.pablo.yumemi.ui.theme.Yu
import kotlinx.coroutines.delay

/** The text a message offers to copy or share, without Markdown marks ("" when it has none). */
internal fun ChatMessage.copyableText(): String =
    parts.filterIsInstance<MessagePart.Text>().joinToString("\n\n") { plainText(it.text) }.trim()

/** Drops the Markdown that reads badly once pasted: fences, headings, bold and inline code marks. */
internal fun plainText(markdown: String): String =
    markdown
        .replace(Regex("(?m)^\\s*```.*$\\n?"), "")
        .replace(Regex("(?m)^#{1,6}\\s+"), "")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .trim()

/**
 * Long-press menu of a chat bubble: copy, pick part of the text, or share. Nothing is shown
 * until the bubble is held down. Without [text] the bubble is shown as is.
 */
@Composable
fun MessageActions(
    text: String,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (text.isBlank()) {
        Box(modifier) { content() }
        return
    }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var copiedTick by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copiedTick) {
        if (copiedTick > 0) {
            copied = true
            delay(1_600)
            copied = false
        }
    }

    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Box(
            if (selecting) {
                Modifier
            } else {
                Modifier.pointerInput(Unit) {
                    detectTapGestures(onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    })
                }
            },
        ) {
            if (selecting) SelectionContainer { content() } else content()
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                shape = RoundedCornerShape(18.dp),
                containerColor = Yu.Indigo,
            ) {
                MenuItem("Copiar", Icons.Rounded.ContentCopy) {
                    menu = false
                    clipboard.setText(AnnotatedString(text))
                    copiedTick++
                }
                MenuItem("Elegir texto", Icons.Rounded.TextFields) {
                    menu = false
                    selecting = true
                }
                MenuItem("Compartir", Icons.Rounded.Share) {
                    menu = false
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }
            }
        }
        AnimatedVisibility(copied, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            Pill("¡Copiado!", Icons.Rounded.Check, Yu.SuccessSoft, Yu.Success)
        }
        AnimatedVisibility(selecting, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            Pill(
                "Mantén pulsado para elegir · Listo",
                Icons.Rounded.Check,
                Yu.LilacSoft,
                Yu.PeriwinkleDeep,
                Modifier.clickable { selecting = false },
            )
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = { Icon(icon, null, tint = Yu.SkySoft) },
        onClick = onClick,
    )
}

@Composable
private fun Pill(text: String, icon: ImageVector, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Row(
        Modifier
            .padding(top = 6.dp)
            .clip(CircleShape)
            .background(bg)
            .then(modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = fg)
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}
