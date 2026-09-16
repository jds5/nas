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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import org.rokano.nasremote.core.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NasRemoteApp(model: RemoteViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val screen = if (!state.connected) "connection" else if (state.selected == null) "panes" else "terminal"
    BackHandler(state.selected != null && state.connected) { model.back() }
    NasTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("接续", fontWeight = FontWeight.Bold)
                        Text("NAS REMOTE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }, navigationIcon = {
                    if (state.selected != null && state.connected) TextButton(onClick = model::back, enabled = !state.busy) { Text("返回") }
                }, actions = {
                    if (state.connected) TextButton(onClick = { model.disconnect() }) { Text("断开") }
                })
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                AnimatedVisibility(state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.message?.let { message ->
                    Surface(color = if (state.uncertain) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth()) {
                        Text(message, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                AnimatedContent(targetState = screen, label = "screen", modifier = Modifier.weight(1f), transitionSpec = {
                    (fadeIn(tween(220)) + slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 350f)) { it / 12 }) togetherWith
                        (fadeOut(tween(120)) + slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 400f)) { -it / 18 })
                }) { destination ->
                    when (destination) {
                        "connection" -> ConnectionScreen(state, model)
                        "panes" -> PanesScreen(state, model)
                        else -> TerminalScreen(state, model)
                    }
                }
            }
        }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(8.dp))
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("回到正在进行的事", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("在外也能连接家中的 NAS，继续原来的会话。", style = MaterialTheme.typography.bodyLarge)
                Text("SSH 加密  ·  主机指纹校验", style = MaterialTheme.typography.labelLarge)
            }
        }
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
        if (state.hasKey || state.message != null) TextButton(onClick = { forget = true }, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("清除本机连接资料") }
        Spacer(Modifier.height(20.dp))
    }
    if (forget) AlertDialog(onDismissRequest = { forget = false }, title = { Text("清除本机资料？") }, text = { Text("移除保存的连接与私钥。不会停止 NAS 上的任务；撤销登录权限仍需在 NAS 删除对应公钥。") }, confirmButton = { TextButton(onClick = { forget = false; model.forget() }) { Text("清除") } }, dismissButton = { TextButton(onClick = { forget = false }) { Text("取消") } })
}

@Composable
private fun PanesScreen(state: RemoteState, model: RemoteViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("正在进行", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text("${state.profile.user}@${state.profile.host}", Modifier.padding(top = 8.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.panes.isEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp)) {
                    Text("没有可连接的窗格", style = MaterialTheme.typography.titleMedium)
                    Text("请先在 NAS 上启动 tmux；这里不会替你创建或重启任务。", Modifier.padding(top = 8.dp))
                }
            }
        }
        items(state.panes, key = { it.identity }) { pane ->
            Card(onClick = { model.select(pane) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().animateItem(), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pane.session, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SuggestionChip(onClick = { model.select(pane) }, label = { Text(if (pane.canWrite) "Codex" else "仅查看") })
                    }
                    Text(pane.path, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${pane.id}  ·  ${pane.command}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item { Text("共享原会话，请避免与电脑同时输入。", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pane.session, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${pane.id} · 屏幕快照，每秒更新", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { model.key(RemoteKey.Up) }, enabled = writable) { Text("↑") }
                TextButton(onClick = { model.key(RemoteKey.Down) }, enabled = writable) { Text("↓") }
                TextButton(onClick = { model.key(RemoteKey.Tab) }, enabled = writable) { Text("Tab") }
                TextButton(onClick = { model.key(RemoteKey.Escape) }, enabled = writable) { Text("Esc") }
                TextButton(onClick = { interrupt = true }, enabled = writable) { Text("中断") }
            }
        } else Text("此窗格仅供查看。首版只向 Codex 发送输入。", style = MaterialTheme.typography.bodySmall)
    }
    if (interrupt) AlertDialog(onDismissRequest = { interrupt = false }, title = { Text("中断当前操作？") }, text = { Text("向 ${pane.session} / ${pane.id} 发送 Ctrl-C，可能取消正在运行的操作。") }, confirmButton = { TextButton(onClick = { interrupt = false; model.key(RemoteKey.Interrupt) }) { Text("发送 Ctrl-C") } }, dismissButton = { TextButton(onClick = { interrupt = false }) { Text("取消") } })
}
