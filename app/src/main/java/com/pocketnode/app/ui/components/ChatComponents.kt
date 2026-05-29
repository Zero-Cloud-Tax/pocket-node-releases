package com.pocketnode.app.ui.components

import android.widget.TextView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketnode.app.data.model.ChatMessage
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.tables.TablePlugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ParsedMessage(val thought: String?, val text: String)

fun parseThinking(content: String): ParsedMessage {
    val thinkStart = "<think>"
    val thinkEnd = "</think>"
    var text = ""
    var thought = ""
    var currentIndex = 0
    while (currentIndex < content.length) {
        val startIndex = content.indexOf(thinkStart, currentIndex)
        if (startIndex == -1) {
            text += content.substring(currentIndex)
            break
        }
        text += content.substring(currentIndex, startIndex)
        val endIndex = content.indexOf(thinkEnd, startIndex + thinkStart.length)
        if (endIndex == -1) {
            thought += content.substring(startIndex + thinkStart.length)
            break
        }
        thought += content.substring(startIndex + thinkStart.length, endIndex) + "\n\n"
        currentIndex = endIndex + thinkEnd.length
    }
    return ParsedMessage(thought = if (thought.isNotBlank()) thought.trim() else null, text = text.trim())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(message: ChatMessage, renderMarkdown: Boolean = true) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val clipboardManager = LocalClipboardManager.current
    val parsed = if (!isUser) parseThinking(message.content) else ParsedMessage(null, message.content)
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val timeLabel = remember(message.timestamp) { timeFormat.format(Date(message.timestamp)) }
    var showThought by remember { mutableStateOf(false) }
    val bubbleColor = if (isUser) Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)))
    else Brush.linearGradient(listOf(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondaryContainer))
    val shape = if (isUser) RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)

    Column(horizontalAlignment = alignment) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (!isUser) {
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text("AI", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 8.sp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(horizontalAlignment = alignment) {
                if (parsed.thought != null) {
                    Surface(
                        modifier = Modifier.widthIn(max = 280.dp).padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp)).clickable { showThought = !showThought },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Description, contentDescription = "Thinking", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Model Notes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            AnimatedVisibility(visible = showThought) {
                                MarkdownText(markdown = parsed.thought, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }

                if (parsed.text.isNotBlank() || parsed.thought == null) {
                    Box(
                        modifier = Modifier.widthIn(max = 280.dp).clip(shape).background(bubbleColor)
                            .combinedClickable(onClick = {}, onLongClick = { clipboardManager.setText(AnnotatedString(parsed.text)) })
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        if (!isUser && renderMarkdown) {
                            MarkdownText(markdown = parsed.text, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        } else if (!isUser) {
                            Text(parsed.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        } else {
                            Text(parsed.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.padding(
                start = if (!isUser) 36.dp else 0.dp,
                top = 3.dp,
                bottom = 2.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            if (!isUser && parsed.text.isNotBlank()) {
                val ctx = LocalContext.current
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    modifier = Modifier
                        .size(14.dp)
                        .clickable {
                            clipboardManager.setText(AnnotatedString(parsed.text))
                            Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        }
    }
}

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val dot1Alpha by infiniteTransition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600, delayMillis = 0), RepeatMode.Reverse), "dot1")
    val dot2Alpha by infiniteTransition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600, delayMillis = 200), RepeatMode.Reverse), "dot2")
    val dot3Alpha by infiniteTransition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600, delayMillis = 400), RepeatMode.Reverse), "dot3")
    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text("AI", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 8.sp), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(
            modifier = Modifier.clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(dot1Alpha, dot2Alpha, dot3Alpha).forEach { alpha ->
                Box(modifier = Modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(MaterialTheme.colorScheme.onSecondaryContainer))
            }
        }
    }
}

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, color: Color) {
    val context = LocalContext.current
    val markwon = remember {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(JLatexMathPlugin.create(40f))
            .build()
    }
    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()))
                textSize = 16f
            }
        },
        update = { textView -> markwon.setMarkdown(textView, markdown) },
        modifier = modifier
    )
}
