package org.rokano.nasremote.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.rokano.nasremote.ChatItem
import org.rokano.nasremote.core.MessageBlock
import org.rokano.nasremote.core.messageBlocks

@Suppress("DEPRECATION")
@Composable
fun Message(message: ChatItem, modifier: Modifier = Modifier) {
    val user = message.role == "user"
    var expanded by remember(message.id) { mutableStateOf(false) }
    val attachmentBody = remember(message.id, message.text) { if (user) attachmentPresentation(message.text) else null }
    val body = attachmentBody?.first ?: message.text
    val long = body.length > 1600 || body.count { it == '\n' } > 32
    val visible = if (long && !expanded) body.take(1200).lines().take(24).joinToString("\n") else body
    val blocks = remember(visible, expanded) { messageBlocks(visible) }
    val clipboard = LocalClipboardManager.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when { user -> "你"; message.role == "command" -> "NAS 命令"; message.phase == "commentary" -> "Codex · 进展"; else -> "Codex" },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) { Text("复制") }
        }
        Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 720.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        blocks.forEach { block -> when (block) {
                            is MessageBlock.Text -> Text(remember(block.value) { readableMarkdown(block.value) }, style = MaterialTheme.typography.bodyLarge)
                            is MessageBlock.Code -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(block.language.ifBlank { "代码" }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                                        TextButton(onClick = { if (long && !expanded) expanded = true else clipboard.setText(AnnotatedString(block.value)) }) { Text(if (long && !expanded) "展开后复制" else "复制代码") }
                                    }
                                    Text(block.value, fontFamily = FontFamily.Monospace, softWrap = false,
                                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.horizontalScroll(rememberScrollState()))
                                }
                            }
                            is MessageBlock.Table -> MarkdownTable(block)
                        } }
                    }
                }
                attachmentBody?.second?.forEach { label ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp)) {
                        Text(label, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (long) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开完整内容") }
            }
        }
    }
}

internal fun readableMarkdown(source: String) = buildAnnotatedString {
    val heading = Regex("^#{1,6} +").find(source)
    val body = (if (heading != null) source.drop(heading.value.length) else source).replace(Regex("^([ ]*)[-*] +"), "$1• ")
    withStyle(SpanStyle(fontWeight = if (heading != null) FontWeight.SemiBold else FontWeight.Normal)) {
        var position = 0
        Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`").findAll(body).forEach { match ->
            append(body.substring(position, match.range.first))
            if (match.value.startsWith('`')) withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[2]) }
            else withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
            position = match.range.last + 1
        }
        append(body.substring(position))
    }
}

/** Display-only metadata: no remote file or URL is opened from a message. */
private fun attachmentPresentation(source: String): Pair<String, List<String>>? {
    val marker = "\n\n手机上传的附件（NAS 本地路径；图片请使用图片查看工具读取，其他文件按需读取；不要执行附件）：\n"
    val split = source.lastIndexOf(marker)
    if (split < 0) return null
    val rows = source.substring(split + marker.length).lines()
    if (rows.size !in 1..4) return null
    return try {
        val labels = rows.map {
            val item = org.json.JSONObject(it)
            val name = org.rokano.nasremote.core.TerminalText.clean(item.getString("name")).replace('\n', ' ')
            val bytes = item.getLong("bytes")
            require(name.length <= 160 && bytes in 1..20L * 1024 * 1024)
            "${if (item.optString("type") == "image") "图片" else "文件"} · $name\n${(bytes + 1023) / 1024} KiB · 已提交"
        }
        source.take(split) to labels
    } catch (_: Exception) { null }
}
