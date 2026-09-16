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
    val long = message.text.length > 1600 || message.text.count { it == '\n' } > 32
    val visible = if (long && !expanded) message.text.take(1200).lines().take(24).joinToString("\n") else message.text
    val blocks = remember(visible, expanded) { if (long && !expanded) listOf(MessageBlock.Text(visible + "…")) else messageBlocks(visible) }
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
                                        TextButton(onClick = { clipboard.setText(AnnotatedString(block.value)) }) { Text("复制代码") }
                                    }
                                    Text(block.value, fontFamily = FontFamily.Monospace, softWrap = false,
                                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.horizontalScroll(rememberScrollState()))
                                }
                            }
                            is MessageBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                                block.rows.forEachIndexed { rowIndex, row ->
                                    Row {
                                        repeat(block.rows.maxOf { it.size }) { column ->
                                            Text(readableMarkdown(row.getOrElse(column) { "" }), Modifier.width(168.dp).padding(8.dp),
                                                fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                                                style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        } }
                    }
                }
                if (long) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开完整内容") }
            }
        }
    }
}

private fun readableMarkdown(source: String) = buildAnnotatedString {
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
