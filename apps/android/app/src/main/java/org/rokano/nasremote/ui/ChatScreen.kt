package org.rokano.nasremote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
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
fun ChatScreen(state: RemoteState, model: RemoteViewModel, attachmentActions: AttachmentActions) {
    val pane = state.selected ?: return
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var skillsOpen by remember(pane.identity) { mutableStateOf(false) }
    var skillQuery by remember { mutableStateOf("") }
    var interrupt by remember { mutableStateOf(false) }
    var skip by remember { mutableStateOf(false) }
    var confirmationToken by remember { mutableStateOf<String?>(null) }
    val writable = state.connected && !state.busy && !state.uncertain && !state.reconnecting && !state.preparingAttachment
    val canSend = writable && state.binding != null && state.chatError == null && (state.draft.isNotBlank() || state.attachments.isNotEmpty())
    val nearBottom by remember { derivedStateOf { !list.canScrollForward } }
    var restored by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(true) }
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(state.binding, state.messages.lastOrNull()?.id) {
        if (state.binding == null || state.messages.isEmpty()) return@LaunchedEffect
        if (!restored) {
            val anchor = state.messages.indexOfFirst { it.id == state.restoreAnchor }
            if (anchor >= 0) list.scrollToItem(anchor + 1, state.restoreOffset)
            else list.showLatest(state.messages.size, animate = false)
            following = !list.canScrollForward
            restored = true
        } else if (following && !list.isScrollInProgress) list.showLatest(state.messages.size)
        if (!list.canScrollForward) state.messages.getOrNull((list.firstVisibleItemIndex - 1).coerceAtLeast(0))?.let {
            model.reading(it.id, list.firstVisibleItemScrollOffset, true)
        }
    }
    LaunchedEffect(restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.canScrollForward) }
            .distinctUntilChanged().collect { (index, offset, forward) ->
                if (list.isScrollInProgress) following = !forward
                val messages = currentState.messages
                val item = messages.getOrNull((index - 1).coerceAtLeast(0))
                if (item != null) model.reading(item.id, offset, !forward)
            }
    }
    val unread = if (state.seen.isBlank()) 0 else state.messages.indexOfFirst { it.id == state.seen }.let {
        if (it >= 0) state.messages.size - it - 1 else state.messages.size
    }
    val prefix = state.draft.takeIf { it.startsWith('/') && !it.contains(' ') && !it.contains('\n') }?.drop(1)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pane.session, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when {
                    state.reconnecting -> "正在接回原会话…"
                    state.chatError != null -> "会话读取需要处理"
                    state.catchingUp -> "正在补读断线期间的消息…"
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
        if (state.questionHint || state.question != null) Surface(
            color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Codex 需要你的回答", style = MaterialTheme.typography.titleSmall)
                    Text(state.question?.title ?: "点击查看问题并选择回答", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
                FilledTonalButton(onClick = { model.openQuestions(state.screenToken) }, enabled = !state.busy) { Text("回答") }
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
                Column {
                    if (state.seen.isNotBlank() && state.messages.getOrNull(state.messages.indexOf(message) - 1)?.id == state.seen) {
                        Text("以下为未读消息", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                    Message(message)
                }
            }
        }
        AnimatedVisibility(!nearBottom && state.messages.isNotEmpty()) {
            TextButton(onClick = { scope.launch { following = true; list.showLatest(state.messages.size) } }, modifier = Modifier.fillMaxWidth()) { Text(if (unread > 0) "↓ $unread 条未读 · 最新消息" else "↓ 最新消息") }
        }
        if (state.uncertain) FilledTonalButton(onClick = { model.panel(true) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text("核对上次发送结果") }
        Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AttachmentComposer(state, model, attachmentActions)
                if (state.answered.isNotBlank()) Text(state.answered, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (state.delivery.isNotBlank()) Text(when (state.delivery) {
                    "sending" -> "正在发送…"
                    "submitted" -> "已提交到 Codex 输入端"
                    "uncertain" -> "发送结果待核对，未自动重发"
                    "rejected" -> "未发送，草稿已保留"
                    "reviewed" -> "已人工核对上次操作"
                    else -> ""
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    OutlinedTextField(state.draft, model::draft, enabled = !state.busy && state.binding != null, modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp),
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
    QuestionSheet(state, model)
    val panelWritable = writable && state.screenToken != null && state.chatError == null
    if (state.panel) ModalBottomSheet(onDismissRequest = { if (!state.busy) model.panel(false) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("回答与控制", style = MaterialTheme.typography.titleLarge)
            Text("先核对原界面，再选择选项或填入回答；与电脑共享光标。", style = MaterialTheme.typography.bodySmall)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(16.dp)) {
                val scroll = rememberScrollState()
                LaunchedEffect(Unit) { delay(100); scroll.scrollTo(scroll.maxValue) }
                SelectionContainer {
                    Text(state.output.joinToString("\n").ifBlank { "正在读取…" }, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, softWrap = false,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(scroll).horizontalScroll(rememberScrollState()).padding(12.dp))
                }
            }
            if (state.uncertain) Button(onClick = model::acknowledge, enabled = state.screenToken != null) { Text("已核对结果，恢复操作") }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                FilledTonalButton(onClick = { model.key(RemoteKey.ShiftLeft, state.screenToken) }, enabled = panelWritable) { Text("打开 / 下一题 ⇧←") }
                TextButton(onClick = { model.key(RemoteKey.AltDown, state.screenToken) }, enabled = panelWritable) { Text("上一题 / 返回 Alt↓") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { model.key(RemoteKey.Up, state.screenToken) }, enabled = panelWritable) { Text("↑") }
                TextButton(onClick = { model.key(RemoteKey.Down, state.screenToken) }, enabled = panelWritable) { Text("↓") }
                TextButton(onClick = { model.key(RemoteKey.Left, state.screenToken) }, enabled = panelWritable) { Text("←") }
                TextButton(onClick = { model.key(RemoteKey.Right, state.screenToken) }, enabled = panelWritable) { Text("→") }
                Button(onClick = { model.key(RemoteKey.Enter, state.screenToken) }, enabled = panelWritable) { Text("确认 ↵") }
            }
            OutlinedTextField(state.answerDraft, model::answerDraft, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4,
                label = { Text("自由回答 / 菜单输入") }, supportingText = { Text("需要时先选择 Other。填入后核对上方画面，再点确认。") })
            FilledTonalButton(onClick = { model.fillAnswer(state.screenToken) }, enabled = panelWritable && state.answerDraft.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("填入回答（不自动回车）") }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                listOf("Tab" to RemoteKey.Tab, "⇧Tab" to RemoteKey.BackTab, "空格" to RemoteKey.Space,
                    "退格" to RemoteKey.Backspace, "清行" to RemoteKey.ClearLine, "Esc" to RemoteKey.Escape).forEach { (label, key) ->
                    TextButton(onClick = { model.key(key, state.screenToken) }, enabled = panelWritable) { Text(label) }
                }
            }
            Row {
                TextButton(onClick = { confirmationToken = state.screenToken; skip = true }, enabled = panelWritable) { Text("跳过此题…") }
                TextButton(onClick = { confirmationToken = state.screenToken; interrupt = true }, enabled = panelWritable) { Text("中断…") }
                TextButton(onClick = { model.terminal(true) }, enabled = !state.busy) { Text("切换终端模式") }
            }
        }
    }
    if (skip) AlertDialog(onDismissRequest = { skip = false }, title = { Text("跳过当前问题？") },
        text = { Text("向当前界面发送 Ctrl+]。请确认画面中确实是要跳过的问题。") },
        confirmButton = { TextButton(onClick = { skip = false; model.key(RemoteKey.SkipQuestion, confirmationToken) }) { Text("跳过") } },
        dismissButton = { TextButton(onClick = { skip = false }) { Text("取消") } })
    if (interrupt) AlertDialog(onDismissRequest = { interrupt = false }, title = { Text("中断当前操作？") }, text = { Text("向 ${pane.session} 发送 Ctrl-C，可能取消正在执行的操作。") },
        confirmButton = { TextButton(onClick = { interrupt = false; model.key(RemoteKey.Interrupt, confirmationToken) }) { Text("发送 Ctrl-C") } },
        dismissButton = { TextButton(onClick = { interrupt = false }) { Text("取消") } })
}

/** Align the end of a tall last message too, not just its first line. */
private suspend fun LazyListState.showLatest(index: Int, animate: Boolean = true) {
    if (animate) animateScrollToItem(index) else scrollToItem(index)
    val last = layoutInfo.visibleItemsInfo.lastOrNull { it.index == index } ?: return
    val remainder = (last.offset + last.size - layoutInfo.viewportEndOffset + layoutInfo.afterContentPadding).coerceAtLeast(0).toFloat()
    if (animate) animateScrollBy(remainder) else scrollBy(remainder)
}
