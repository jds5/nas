package org.rokano.nasremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.rokano.nasremote.ChatItem
import org.rokano.nasremote.RemoteState
import org.rokano.nasremote.RemoteViewModel
import org.rokano.nasremote.core.RemoteKey

private val commands = listOf(
    "skills" to "浏览此主机的技能", "status" to "查看 Codex 会话状态",
    "model" to "选择模型", "permissions" to "调整权限", "review" to "代码审查",
    "compact" to "压缩会话上下文", "diff" to "查看改动", "help" to "Codex 帮助",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: RemoteState, model: RemoteViewModel) {
    val pane = state.selected ?: return
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var skillsOpen by remember(pane.identity) { mutableStateOf(false) }
    var skillQuery by remember { mutableStateOf("") }
    var interrupt by remember { mutableStateOf(false) }
    val writable = state.connected && !state.busy && !state.uncertain
    val canSend = writable && state.binding != null && state.chatError == null && state.draft.isNotBlank()
    val nearBottom by remember { derivedStateOf { !list.canScrollForward ||
        (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 2 } }
    var previousLast by remember(pane.identity) { mutableStateOf<String?>(null) }
    var previousSize by remember(pane.identity) { mutableIntStateOf(0) }
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        val firstLoad = previousLast == null
        val wasNearBottom = (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= previousSize - 1
        previousSize = state.messages.size
        previousLast = state.messages.lastOrNull()?.id
        if ((firstLoad || wasNearBottom) && state.messages.isNotEmpty()) list.animateScrollToItem(state.messages.size)
    }
    val prefix = state.draft.takeIf { it.startsWith('/') && !it.contains(' ') && !it.contains('\n') }?.drop(1)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pane.session, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when {
                    state.chatError != null -> "会话读取需要处理"
                    state.binding == null -> "正在关联原会话…"
                    state.activity == "working" -> "Codex 正在处理"
                    state.activity == "idle" -> "本轮已结束"
                    state.activity == "interrupted" -> "本轮已中断"
                    else -> "已连接原会话"
                }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { model.panel(true) }) { Text("控制面板") }
        }
        state.chatError?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { model.terminal(true) }) { Text("使用终端模式") }
                }
            }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "history") {
                if ((state.historyBefore ?: 0) > 0) TextButton(onClick = model::loadHistory, enabled = !state.loadingHistory && state.messages.size < 300) {
                    Text(if (state.messages.size >= 300) "已显示 300 条，更多内容请用终端查看" else if (state.loadingHistory) "正在读取…" else "查看更早的消息")
                }
                if (state.messages.isEmpty() && state.binding != null) Text("暂未读到公开消息，可继续向 Codex 提问。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.messages, key = { it.id }) { message ->
                Message(message, Modifier.animateItem())
            }
        }
        AnimatedVisibility(!nearBottom && state.messages.isNotEmpty()) {
            TextButton(onClick = { scope.launch { list.animateScrollToItem(state.messages.size) } }, modifier = Modifier.fillMaxWidth()) { Text("↓ 最新消息") }
        }
        if (state.uncertain) FilledTonalButton(onClick = { model.panel(true) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text("核对上次发送结果") }
        Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (prefix != null) {
                    LazyColumn(Modifier.heightIn(max = 168.dp)) {
                        items(commands.filter { it.first.startsWith(prefix) }) { (command, description) ->
                            TextButton(onClick = {
                                if (command == "skills") { skillsOpen = true; model.draft("") }
                                else model.draft("/$command ")
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("/$command", Modifier.width(108.dp), fontWeight = FontWeight.SemiBold)
                                Text(description, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(state.draft, model::draft, enabled = !state.busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp),
                        placeholder = { Text("向 Codex 发送消息…") }, minLines = 1, maxLines = 6)
                    Button(onClick = {
                        if (state.draft.trim() == "/skills") { skillsOpen = true; model.draft("") }
                        else model.send()
                    }, enabled = canSend, modifier = Modifier.heightIn(min = 52.dp)) { Text("发送") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { skillsOpen = true }) { Text("/ 技能") }
                    Text(if (state.draft.startsWith('!')) "在 NAS 执行命令 · 使用 Codex 当前权限" else "输入 / 使用命令，! 执行 NAS 命令", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    if (skillsOpen) ModalBottomSheet(onDismissRequest = { skillsOpen = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("技能", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(skillQuery, { skillQuery = it }, label = { Text("搜索技能") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("来自 NAS 的 SKILL.md；选择后以 Codex 的 \$技能名发送。实际可用性由当前会话决定。", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (state.skills.isEmpty()) item { Text("未发现技能。可在控制面板使用 Codex 的 /skills 菜单。") }
                items(state.skills.filter { it.name.contains(skillQuery, true) || it.description.contains(skillQuery, true) }, key = { it.name }) { skill ->
                    TextButton(onClick = { model.draft("\$${skill.name} " + state.draft); skillsOpen = false }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(skill.name, fontWeight = FontWeight.SemiBold)
                            Text(skill.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            TextButton(onClick = { model.draft("/skills"); model.send(); skillsOpen = false }, enabled = writable && state.binding != null && state.chatError == null) { Text("打开 Codex 原菜单") }
        }
    }
    val panelWritable = writable && state.output.isNotEmpty()
    if (state.panel) ModalBottomSheet(onDismissRequest = { model.panel(false) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Codex 控制面板", style = MaterialTheme.typography.titleLarge)
            Text("菜单、权限确认与异常输入在这里处理。共享电脑端光标，请核对后操作。", style = MaterialTheme.typography.bodySmall)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(16.dp)) {
                val scroll = rememberScrollState()
                LaunchedEffect(state.output) { if (!scroll.isScrollInProgress) scroll.scrollTo(scroll.maxValue) }
                SelectionContainer {
                    Text(state.output.takeLast(80).joinToString("\n").ifBlank { "正在读取…" }, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(scroll).padding(12.dp))
                }
            }
            if (state.uncertain) Button(onClick = model::acknowledge, enabled = state.output.isNotEmpty()) { Text("已核对结果，恢复操作") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { model.key(RemoteKey.Up) }, enabled = panelWritable) { Text("↑") }
                TextButton(onClick = { model.key(RemoteKey.Down) }, enabled = panelWritable) { Text("↓") }
                TextButton(onClick = { model.key(RemoteKey.Tab) }, enabled = panelWritable) { Text("Tab") }
                TextButton(onClick = { model.key(RemoteKey.Escape) }, enabled = panelWritable) { Text("Esc") }
                Button(onClick = { model.key(RemoteKey.Enter) }, enabled = panelWritable) { Text("确认") }
            }
            Row {
                TextButton(onClick = { interrupt = true }, enabled = panelWritable) { Text("中断…") }
                TextButton(onClick = { model.terminal(true) }, enabled = !state.busy) { Text("切换终端模式") }
            }
        }
    }
    if (interrupt) AlertDialog(onDismissRequest = { interrupt = false }, title = { Text("中断当前操作？") }, text = { Text("向 ${pane.session} 发送 Ctrl-C，可能取消正在执行的操作。") },
        confirmButton = { TextButton(onClick = { interrupt = false; model.key(RemoteKey.Interrupt) }) { Text("发送 Ctrl-C") } },
        dismissButton = { TextButton(onClick = { interrupt = false }) { Text("取消") } })
}

@Composable
private fun Message(message: ChatItem, modifier: Modifier = Modifier) {
    val user = message.role == "user"
    Column(modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Text(when { user -> "你"; message.role == "command" -> "NAS 命令"; message.phase == "commentary" -> "Codex · 进展"; else -> "Codex" },
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 720.dp)) {
            SelectionContainer {
                // Native text only: no WebView, remote image fetch, HTML or executable links.
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val parts = remember(message.text) { message.text.split("```") }
                    parts.forEachIndexed { index, part ->
                        if (part.isNotBlank()) {
                            if (index % 2 == 1) Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp)) {
                                Text(part.trim(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                            } else Text(remember(part) { readableMarkdown(part.trim()) }, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

/** Small, non-interactive Markdown subset. Unknown syntax stays visible as text. */
private fun readableMarkdown(source: String) = buildAnnotatedString {
    val token = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
    source.lines().forEachIndexed { index, line ->
        if (index > 0) append("\n")
        val heading = Regex("^#{1,6} +").find(line)
        val body = if (heading != null) line.drop(heading.value.length) else line
        withStyle(SpanStyle(fontWeight = if (heading != null) FontWeight.SemiBold else FontWeight.Normal)) {
            var position = 0
            token.findAll(body).forEach { match ->
                append(body.substring(position, match.range.first))
                if (match.value.startsWith('`')) withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[2]) }
                else withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
                position = match.range.last + 1
            }
            append(body.substring(position))
        }
    }
}
