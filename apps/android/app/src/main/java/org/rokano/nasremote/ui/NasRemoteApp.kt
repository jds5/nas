package org.rokano.nasremote.ui

import android.os.Build
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.rokano.nasremote.RemoteState
import org.rokano.nasremote.RemoteViewModel
import org.rokano.nasremote.CodeProject
import org.rokano.nasremote.core.*


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NasRemoteApp(model: RemoteViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val attachmentActions = rememberAttachmentActions(model)
    val screen = if (!state.connected) "connection" else if (state.lifecycleOpen) "lifecycle" else if (state.selected == null) "panes" else if (state.terminal) "terminal" else "chat"
    val lifecycleLeavable = !state.lifecycleBusy || state.lifecycleMachineStatus in listOf("cloning", "pulling")
    BackHandler(state.lifecycleOpen && lifecycleLeavable) { model.closeLifecycle() }
    BackHandler(state.selected != null && state.connected && !state.lifecycleOpen) { model.back() }
    NasTheme {
        Scaffold(
            topBar = {
                val pane = state.selected
                TopAppBar(title = {
                    // Contextual title + one-line subtitle (M3 small top app bar), instead of a fixed brand.
                    Column {
                        Text(when (screen) {
                            "connection" -> "接续"
                            "panes" -> "会话"
                            "lifecycle" -> when { state.lifecycleMode == "inspect" -> "启动结果"; state.lifecycleMode.startsWith("restore") -> "恢复历史"; else -> "新建会话" }
                            else -> pane?.session.orEmpty()
                        }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when (screen) {
                            "connection" -> "NAS Remote"
                            "panes" -> "${state.profile.user}@${state.profile.host}"
                            "lifecycle" -> Agents.label(state.agent)
                            "chat" -> chatStatus(state)
                            else -> "${pane?.id} · 屏幕快照"
                        }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }, navigationIcon = {
                    if (state.lifecycleOpen) IconAction(AppIcons.Back, "返回", enabled = lifecycleLeavable, onClick = model::closeLifecycle)
                    else if (pane != null && state.connected) IconAction(AppIcons.Back, "返回", enabled = !state.busy, onClick = model::back)
                }, actions = {
                    if (screen == "chat") IconAction(AppIcons.Tune, "回答与控制") { model.panel(true) }
                    if (pane?.canWrite == true && screen in listOf("chat", "terminal")) {
                        IconAction(if (state.terminal) AppIcons.Chat else AppIcons.Terminal, if (state.terminal) "聊天模式" else "终端模式",
                            enabled = !state.busy) { model.terminal(!state.terminal) }
                    }
                    if (state.connected) IconAction(AppIcons.Power, "断开连接") { model.disconnect() }
                })
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                AnimatedVisibility(state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                AnimatedVisibility(state.message != null) {
                    val message = state.message.orEmpty()
                    Surface(color = if (state.uncertain) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), style = MaterialTheme.typography.bodyMedium)
                            if (!state.uncertain) IconAction(AppIcons.Close, "关闭提示", onClick = model::dismissMessage)
                        }
                    }
                }
                AnimatedContent(targetState = screen, label = "screen", modifier = Modifier.weight(1f), transitionSpec = {
                    (fadeIn(tween(220)) + slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 350f)) { it / 12 }) togetherWith
                        (fadeOut(tween(120)) + slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 400f)) { -it / 18 })
                }) { destination ->
                    when (destination) {
                        "connection" -> ConnectionScreen(state, model)
                        "panes" -> PanesScreen(state, model)
                        "lifecycle" -> LifecycleScreen(state, model)
                        "chat" -> key(state.selected?.identity) { ChatScreen(state, model, attachmentActions) }
                        else -> TerminalScreen(state, model)
                    }
                }
            }
        }
    }
}

/** Icon button with an M3 plain tooltip; the label is also the accessibility description. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IconAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
    }
}

@Composable
private fun ConnectionScreen(state: RemoteState, model: RemoteViewModel) {
    var host by remember(state.profile) { mutableStateOf(state.profile.host) }
    var port by remember(state.profile) { mutableStateOf(state.profile.port.toString()) }
    var user by remember(state.profile) { mutableStateOf(state.profile.user) }
    var fingerprint by remember(state.profile) { mutableStateOf(state.profile.fingerprint) }
    // Credentials intentionally never enter savedInstanceState or persistent UI state.
    var passphrase by remember { mutableStateOf("") }
    var forget by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val localPermission = "android.permission.ACCESS_LOCAL_NETWORK"
    var networkAllowed by remember { mutableStateOf(Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(localPermission) == PackageManager.PERMISSION_GRANTED) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        networkAllowed = Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(localPermission) == PackageManager.PERMISSION_GRANTED
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { passphrase = "" }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { networkAllowed = it }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importKey) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(8.dp))
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("回到正在进行的事", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("在外也能连接家中的 NAS，继续原来的会话。", style = MaterialTheme.typography.bodyLarge)
                Text("SSH 加密  ·  主机指纹校验", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (state.attachments.isNotEmpty()) Text("已保留 ${state.attachments.size} 个待发送附件，接回原会话后继续发送。", color = MaterialTheme.colorScheme.primary)
        Text("连接到主机", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(host, { host = it.trim() }, label = { Text("公网主机 / DDNS 地址") }, placeholder = { Text("ssh.example.com") }, supportingText = { Text("填写 SSH 主机名或 IP，不含 https://；内网测试也可填写局域网 IP。") }, singleLine = true, enabled = !state.busy && state.ready, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(user, { user = it.trim() }, label = { Text("SSH 用户") }, singleLine = true, enabled = !state.busy, modifier = Modifier.weight(2f))
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("SSH 端口") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !state.busy, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(fingerprint, { fingerprint = it.trim() }, label = { Text("服务器 SHA256 指纹") }, supportingText = { Text("从已信任的电脑或 NAS 核对后填写，不接受未知指纹。") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { import.launch(arrayOf("*/*")) }, enabled = !state.busy && state.ready, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (state.hasKey) "已导入私钥 · 更换" else "导入手机专用 SSH 私钥")
        }
        OutlinedTextField(passphrase, { passphrase = it }, label = { Text("私钥口令（如有）") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        Text("私钥由 Android Keystore 加密保护；口令不保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!networkAllowed) {
            OutlinedButton(onClick = { permission.launch(localPermission) }, modifier = Modifier.fillMaxWidth()) { Text("授权局域网访问（可选）") }
            Text("公网连接无需此权限；连接局域网地址时，Android 17 需要授权。", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = {
            model.connect(ConnectionProfile(host, port.toIntOrNull() ?: 0, user, fingerprint), passphrase)
            passphrase = ""
        }, enabled = state.ready && state.hasKey && !state.busy, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("连接并查看会话", style = MaterialTheme.typography.titleMedium) }
        if (state.resumeTitle != null) FilledTonalButton(onClick = {
            model.connect(ConnectionProfile(host, port.toIntOrNull() ?: 0, user, fingerprint), passphrase, resume = true)
            passphrase = ""
        }, enabled = state.ready && state.hasKey && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("接回 ${state.resumeTitle}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ListItem(headlineContent = { Text("加密保存草稿") }, supportingContent = { Text("重启后恢复输入及回答草稿；口令和对话正文不保存") },
            trailingContent = { Switch(checked = state.saveDrafts, onCheckedChange = null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.selectable(state.saveDrafts, enabled = !state.busy && state.ready, role = Role.Switch) { model.saveDrafts(!state.saveDrafts) })
        if (state.hasKey || state.message != null) TextButton(onClick = { forget = true }, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterHorizontally),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清除本机连接资料") }
        Spacer(Modifier.height(20.dp))
    }
    if (forget) AlertDialog(onDismissRequest = { forget = false }, title = { Text("清除本机资料？") }, text = { Text("移除保存的连接、私钥、草稿与阅读位置。不会停止 NAS 上的任务；撤销登录权限仍需在 NAS 删除对应公钥。") }, confirmButton = { TextButton(onClick = { forget = false; model.forget() }) { Text("清除") } }, dismissButton = { TextButton(onClick = { forget = false }) { Text("取消") } })
}


@Composable
private fun PanesScreen(state: RemoteState, model: RemoteViewModel) {
    // Panes running the preferred agent first, then the other agent, then plain terminals.
    val ordered = remember(state.panes, state.agent) { state.panes.sortedBy { if (it.command == state.agent) 0 else if (it.canWrite) 1 else 2 } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "header") {
                Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AgentSwitch(state, model)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { model.openLifecycle("restore_new") }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                            Icon(AppIcons.History, null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text("恢复历史")
                        }
                        if (state.lifecycleOperation != null) TextButton(onClick = model::inspectLifecycle) { Text("上次启动结果") }
                    }
                }
            }
            if (state.panes.isEmpty()) item(key = "empty") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    ListItem(headlineContent = { Text("没有可连接的窗格") },
                        supportingContent = { Text("恢复历史对话，或点“新建会话”为项目启动 ${Agents.label(state.agent)}。") },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
            items(ordered, key = { it.identity }) { pane ->
                ElevatedCard(onClick = { model.select(pane) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().animateItem()) {
                    ListItem(
                        overlineContent = { Text(if (pane.canWrite) pane.agentName else "${pane.command} · 仅查看") },
                        headlineContent = { Text(pane.session, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${pane.path}  ·  ${pane.id}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { AgentAvatar(pane) },
                        trailingContent = {
                            if (pane.canWrite) IconAction(AppIcons.Terminal, "以终端模式打开", enabled = !state.busy) { model.select(pane, terminal = true) }
                            else if (pane.command == "bash") TextButton(onClick = { model.openLifecycle("new_pane", pane) }) { Text("启动") }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
            item(key = "footer") {
                Text("共享原会话，请避免与电脑同时输入。", Modifier.padding(top = 8.dp, start = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ExtendedFloatingActionButton(onClick = { model.openLifecycle("existing") }, icon = { Icon(AppIcons.Add, null) },
            text = { Text("新建会话") }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

@Composable
private fun AgentAvatar(pane: Pane) {
    Surface(shape = CircleShape, modifier = Modifier.size(40.dp),
        color = if (pane.canWrite) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
        Box(contentAlignment = Alignment.Center) {
            Text(when (pane.command) { "claude" -> "CC"; "codex" -> "Cx"; else -> ">_" },
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Preferred agent for new sessions and history; running panes are detected individually. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSwitch(state: RemoteState, model: RemoteViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("新建与恢复使用", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Agents.names.entries.forEachIndexed { index, (code, label) ->
                SegmentedButton(selected = state.agent == code, onClick = { model.chooseAgent(code) }, enabled = !state.lifecycleBusy,
                    shape = SegmentedButtonDefaults.itemShape(index, Agents.names.size)) { Text(label) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(options: List<Pair<String, String>>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(selected = selected == mode, onClick = { onSelect(mode) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, options.size)) { Text(label, maxLines = 1) }
        }
    }
}


@Composable
private fun LifecycleScreen(state: RemoteState, model: RemoteViewModel) {
    val mode = state.lifecycleMode
    var session by remember(mode) { mutableStateOf(if (mode in listOf("restore_pane", "new_pane")) state.lifecyclePane?.session.orEmpty() else "") }
    var path by remember(mode) { mutableStateOf("") }
    var thread by remember(mode) { mutableStateOf("") }
    var url by remember(mode) { mutableStateOf("") }
    var branch by remember(mode) { mutableStateOf("") }
    var pull by remember(mode) { mutableStateOf(false) }
    val agent = Agents.label(state.agent)
    var args by remember(mode, state.agent) { mutableStateOf(Agents.defaultArgs(state.agent)) }
    val restoring = mode.startsWith("restore")
    val editable = !state.lifecycleBusy
    val choices = if (mode == "restore_pane") state.lifecycleThreads.filter { it.path == state.lifecyclePane?.path } else state.lifecycleThreads
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (mode != "inspect") {
            AgentSwitch(state, model)
            if (mode in listOf("new_pane", "restore_pane")) ModeSwitch(listOf("new_pane" to "新建对话", "restore_pane" to "恢复历史"), mode, editable) {
                model.openLifecycle(it, state.lifecyclePane)
            }
            if (mode in listOf("existing", "empty", "clone")) ModeSwitch(listOf("existing" to "已有目录", "empty" to "空目录", "clone" to "Git 克隆"), mode, editable) {
                model.openLifecycle(it)
            }
            Text("项目位于 NAS 的 ~/code；确认参数后直接启动。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (restoring) {
            Text(if (mode == "restore_pane") "在 ${state.lifecyclePane?.session} 中恢复" else "选择历史对话", style = MaterialTheme.typography.titleMedium)
            OutlinedCard(Modifier.fillMaxWidth()) {
                if (choices.isEmpty()) ListItem(headlineContent = { Text("没有可用的历史会话记录") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                choices.take(30).forEach { item ->
                    ListItem(
                        headlineContent = { Text(item.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${item.created.take(16).replace('T', ' ')} · ${item.id.take(8)}", fontFamily = FontFamily.Monospace) },
                        leadingContent = { RadioButton(selected = thread == item.id, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.selectable(thread == item.id, enabled = editable, role = Role.RadioButton) { thread = item.id })
                }
            }
            OutlinedTextField(thread, { thread = it.trim().take(36) }, label = { Text("会话 UUID") },
                supportingText = { Text("也可手动填写精确 UUID") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
        }
        if (mode !in listOf("restore_pane", "new_pane", "inspect")) OutlinedTextField(session, { session = it.take(40) },
            label = { Text("tmux 名称") }, supportingText = { Text("留空使用项目名") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
        if (mode == "existing") ProjectPicker(state.lifecycleProjects, path, editable) { path = it }
        if (mode == "empty") OutlinedTextField(path, { path = it.take(500) },
            label = { Text("新目录名（相对 ~/code）") },
            placeholder = { Text("work_doc") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
        if (mode == "clone") {
            OutlinedTextField(url, { url = it.take(500) }, label = { Text("Git 仓库地址") },
                supportingText = { Text("HTTPS / SSH；克隆到 ~/code/仓库名，同名目录存在则停止") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(branch, { branch = it.take(128) }, label = { Text("分支") }, supportingText = { Text("留空使用远端默认分支") },
                singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
        }
        if (mode == "existing") ListItem(headlineContent = { Text("先拉取 Git 仓库") }, supportingContent = { Text("仅快速前进，工作树须干净") },
            trailingContent = { Switch(checked = pull, onCheckedChange = null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.selectable(pull, enabled = editable, role = Role.Switch) { pull = !pull })
        if (mode != "inspect") {
            OutlinedTextField(args, { args = it.take(500) }, label = { Text("$agent 启动参数") },
                supportingText = { Text("默认 ${Agents.defaultArgs(state.agent)}（跳过权限确认${if (state.agent == "codex") "与沙箱" else ""}）；可删改") },
                singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
            Button(onClick = { model.prepareLifecycle(session, path, thread, url, branch, pull, args) },
                enabled = editable && (!restoring || thread.isNotEmpty()) && (mode != "existing" || path.isNotEmpty()), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("启动 $agent") }
        }
        if (state.lifecycleBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.lifecycleStatus.isNotBlank()) Text(state.lifecycleStatus, style = MaterialTheme.typography.bodyMedium)
        if (mode == "inspect" && !state.lifecycleBusy && state.lifecycleOperation != null &&
            state.lifecycleMachineStatus !in listOf("cloning", "pulling", "starting", "cloned", "pulled"))
            TextButton(onClick = model::dismissLifecycleOperation) { Text("关闭此记录") }
        Text("结果不明确时请先检查目标窗格；不会自动重试启动。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Existing projects are picked from ~/code's first-level directories instead of typed by hand.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectPicker(projects: List<CodeProject>?, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val usable = enabled && !projects.isNullOrEmpty()
    ExposedDropdownMenuBox(expanded = expanded && usable, onExpandedChange = { if (usable) expanded = it }) {
        OutlinedTextField(selected, {}, readOnly = true, singleLine = true, enabled = enabled,
            label = { Text("项目（~/code）") },
            placeholder = { Text(when { projects == null -> "正在读取项目列表…"; projects.isEmpty() -> "~/code 下没有可用目录"; else -> "选择项目" }) },
            supportingText = { Text(projects?.firstOrNull { it.name == selected }?.let { if (it.git) "Git 仓库" else "普通目录" } ?: "只列出 ~/code 下的一级目录") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && usable) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, usable))
        ExposedDropdownMenu(expanded = expanded && usable, onDismissRequest = { expanded = false }) {
            projects.orEmpty().forEach { project ->
                DropdownMenuItem(text = { Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { if (project.git) Text("Git", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { onSelect(project.name); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding)
            }
        }
    }
}

@Composable
private fun TerminalScreen(state: RemoteState, model: RemoteViewModel) {
    val pane = state.selected ?: return
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    var follow by rememberSaveable(pane.identity) { mutableStateOf(true) }
    var interrupt by remember { mutableStateOf(false) }
    val writable = pane.canWrite && state.connected && !state.busy && !state.uncertain
    LaunchedEffect(state.output, follow) {
        if (follow && !list.isScrollInProgress && state.output.isNotEmpty()) list.scrollToItem(state.output.lastIndex)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("跟随输出") })
        }
        Surface(Modifier.fillMaxWidth().weight(1f), color = Color(0xFF121D19), contentColor = Color(0xFFE0EBE4), shape = RoundedCornerShape(22.dp)) {
            SelectionContainer {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    if (state.output.isEmpty()) item { Text("正在读取窗格…", fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                    items(state.output.size) { index -> Text(state.output[index].ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp) }
                }
            }
        }
        if (state.uncertain) {
            Text("先查看窗格，确认上次输入是否送达。", style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = model::acknowledge, enabled = state.output.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("已核对输出，恢复操作") }
        }
        if (pane.canWrite) {
            OutlinedTextField(state.draft, model::draft, modifier = Modifier.fillMaxWidth(), enabled = !state.busy,
                label = { Text("输入内容") }, placeholder = { Text("先粘贴，核对后再按回车") }, minLines = 2, maxLines = 4)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = model::paste, enabled = writable && state.draft.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("粘贴到窗格") }
                Button(onClick = { model.key(RemoteKey.Enter) }, enabled = writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("回车") }
            }
            if (pane.command == "codex") Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { model.key(RemoteKey.ShiftLeft) }, enabled = writable) { Text("打开 / 下一题 ⇧←") }
                TextButton(onClick = { model.key(RemoteKey.AltDown) }, enabled = writable) { Text("上一题 / 返回 Alt↓") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { model.key(RemoteKey.Up) }, enabled = writable) { Text("↑") }
                TextButton(onClick = { model.key(RemoteKey.Down) }, enabled = writable) { Text("↓") }
                TextButton(onClick = { model.key(RemoteKey.Tab) }, enabled = writable) { Text("Tab") }
                TextButton(onClick = { model.key(RemoteKey.Escape) }, enabled = writable) { Text("Esc") }
                TextButton(onClick = { interrupt = true }, enabled = writable) { Text("中断") }
            }
        } else Text("此窗格仅供查看。只向 Claude Code / Codex 发送输入。", style = MaterialTheme.typography.bodySmall)
    }
    if (interrupt) AlertDialog(onDismissRequest = { interrupt = false }, title = { Text("中断当前操作？") }, text = { Text("向 ${pane.session} / ${pane.id} 发送 Ctrl-C，可能取消正在运行的操作。") }, confirmButton = { TextButton(onClick = { interrupt = false; model.key(RemoteKey.Interrupt) }) { Text("发送 Ctrl-C") } }, dismissButton = { TextButton(onClick = { interrupt = false }) { Text("取消") } })
}

