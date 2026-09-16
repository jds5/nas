package org.rokano.nasremote

import android.app.Application
import android.net.Uri
import org.json.JSONObject
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.rokano.nasremote.core.*
import org.rokano.nasremote.security.ProfileVault

data class ChatItem(val id: String, val role: String, val text: String, val phase: String = "")
data class SkillOption(val name: String, val description: String)
private class InputRejected(message: String) : Exception(message)

data class RemoteState(
    val profile: ConnectionProfile = ConnectionProfile("", 22, "", ""),
    val ready: Boolean = false,
    val hasKey: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val panes: List<Pane> = emptyList(),
    val selected: Pane? = null,
    val output: List<String> = emptyList(),
    val message: String? = null,
    val uncertain: Boolean = false,
    val draft: String = "",
    val terminal: Boolean = false,
    val panel: Boolean = false,
    val binding: String? = null,
    val messages: List<ChatItem> = emptyList(),
    val skills: List<SkillOption> = emptyList(),
    val chatError: String? = null,
    val activity: String = "unknown",
    val historyBefore: Long? = null,
    val loadingHistory: Boolean = false,
)

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = ProfileVault(application)
    private val mutable = MutableStateFlow(RemoteState())
    val state = mutable.asStateFlow()
    private val io = Mutex()
    private var client: SshTmuxClient? = null
    private var importedKey: ByteArray? = null
    private var poll: Job? = null
    @Volatile private var epoch = 0
    @Volatile private var foreground = true
    private val drafts = mutableMapOf<String, String>()

    init {
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { vault.load() }
                importedKey = saved?.key
                mutable.update { it.copy(ready = true, hasKey = saved != null, profile = saved?.profile ?: it.profile) }
            } catch (_: Exception) {
                mutable.update { it.copy(ready = true, message = "无法解锁保存的连接。可清除后重新配置。") }
            }
        }
    }
    fun importKey(uri: Uri) {
        if (state.value.busy || state.value.connected) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { stream ->
                        val bytes = stream.readBytesLimited(65_536)
                        require(bytes.size in 32..65_536)
                        require(bytes.toString(Charsets.US_ASCII).contains("PRIVATE KEY"))
                        bytes
                    }
                }
                importedKey?.fill(0)
                importedKey = data
                mutable.update { it.copy(hasKey = true, message = "私钥已导入，连接成功后加密保存。") }
            } catch (_: Exception) { mutable.update { it.copy(message = "私钥导入失败，请选择小于 64 KB 的 OpenSSH / PEM 私钥文件。") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun connect(profile: ConnectionProfile, password: String) {
        if (!state.value.ready || state.value.busy || state.value.connected || !foreground) return
        try { profile.validate(); require(importedKey != null) { "请先导入手机专用私钥" } }
        catch (e: IllegalArgumentException) { mutable.update { it.copy(message = e.message) }; return }
        if (profile != state.value.profile) drafts.clear()
        val token = ++epoch
        val remote = SshTmuxClient()
        client = remote
        val key = importedKey!!.copyOf()
        val pass = password.toByteArray()
        mutable.update { it.copy(busy = true, profile = profile, message = null) }
        viewModelScope.launch {
            try {
                val panes = withContext(Dispatchers.IO) { io.withLock {
                    remote.connect(profile, key, pass)
                    val result = remote.panes()
                    vault.save(profile, key)
                    result
                } }
                if (token != epoch) { withContext(Dispatchers.IO) { remote.close() }; return@launch }
                mutable.update { it.copy(connected = true, busy = false, panes = panes, selected = null, output = emptyList(), message = null) }
                startPolling()
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { remote.close() }
                if (token == epoch) mutable.update { it.copy(busy = false, connected = false, message = if (e is IllegalStateException) e.message else "连接未完成，请核对网络、tmux 和本机凭据存储。") }
            } finally { key.fill(0); pass.fill(0) }
        }
    }
    fun select(pane: Pane, terminal: Boolean = false) {
        if (state.value.busy || !state.value.connected) return
        mutable.update { it.copy(selected = pane, output = emptyList(), message = null, draft = drafts[pane.identity].orEmpty(), terminal = terminal || !pane.canWrite, panel = false, binding = null, messages = emptyList(), skills = emptyList(), chatError = null, historyBefore = null, activity = "unknown", loadingHistory = false) }
        startPolling()
    }
    fun back() {
        if (state.value.busy) return
        mutable.update { it.copy(selected = null, output = emptyList()) }
    }
    fun draft(text: String) {
        if (state.value.busy) return
        val value = text.take(16_384)
        state.value.selected?.let { drafts[it.identity] = value }
        mutable.update { it.copy(draft = value) }
    }
    fun terminal(value: Boolean) {
        if (!state.value.busy) mutable.update { it.copy(terminal = value, panel = false) }
    }
    fun panel(value: Boolean) { mutable.update { it.copy(panel = value, output = if (value) emptyList() else it.output) } }
    fun send() {
        val s = state.value
        val pane = s.selected ?: return
        val binding = s.binding ?: return
        val text = s.draft
        try { TmuxProtocol.validatePaste(text) }
        catch (e: IllegalArgumentException) { mutable.update { it.copy(message = e.message) }; return }
        if (s.chatError != null) return
        write(clearDraft = true) { remote, _ ->
            val result = JSONObject(remote.bridge(request(pane, "send", binding).put("text", text).toString()))
            if (!result.optBoolean("ok")) {
                val reason = result.optString("error", "发送未完成")
                if (result.optBoolean("uncertain")) error(reason) else throw InputRejected(reason)
            }
        }
        if (text.startsWith("/")) panel(true)
    }
    private fun request(pane: Pane, action: String, binding: String? = null): JSONObject = JSONObject()
        .put("action", action).put("pane", JSONObject().put("id", pane.id).put("pid", pane.pid).put("serverPid", pane.serverPid))
        .apply { if (binding != null) put("binding", binding) }

    private fun mergeChat(old: List<ChatItem>, result: JSONObject, older: Boolean = false): List<ChatItem> {
        val array = result.optJSONArray("messages") ?: return old
        val incoming = (0 until array.length()).map { i -> array.getJSONObject(i).let {
            ChatItem(it.getString("id"), it.getString("role"), TerminalText.clean(it.getString("text")), it.optString("phase"))
        } }
        val combined = if (older) incoming.filter { v -> old.none { it.id == v.id } }.takeLast((300 - old.size).coerceAtLeast(0)) + old else old + incoming
        return combined.distinctBy { it.id }.let { if (older) it.take(300) else it.takeLast(300) }
    }
    fun loadHistory() {
        val s = state.value
        val pane = s.selected ?: return
        val before = s.historyBefore?.takeIf { it > 0 } ?: return
        val remote = client ?: return
        if (s.loadingHistory || s.busy || s.binding == null) return
        val token = epoch
        mutable.update { it.copy(loadingHistory = true) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { io.withLock {
                    JSONObject(remote.bridge(request(pane, "snapshot", s.binding).put("before", before).toString()))
                } }
                if (token == epoch && state.value.selected?.identity == pane.identity && state.value.binding == s.binding) mutable.update {
                    if (result.optBoolean("ok")) it.copy(messages = mergeChat(it.messages, result, older = true), historyBefore = result.getLong("before"), loadingHistory = false)
                    else it.copy(loadingHistory = false, message = result.optString("error"))
                }
            } catch (_: Exception) { if (token == epoch) mutable.update { it.copy(loadingHistory = false, message = "历史读取失败，可稍后重试") } }
        }
    }
    fun acknowledge() { if (state.value.connected && state.value.output.isNotEmpty()) mutable.update { it.copy(uncertain = false, message = null) } }
    fun paste() {
        val text = state.value.draft
        try { TmuxProtocol.validatePaste(text) }
        catch (e: IllegalArgumentException) { mutable.update { it.copy(message = e.message) }; return }
        write { remote, pane -> remote.paste(pane, text) }
    }
    fun key(key: RemoteKey) = write { remote, pane -> remote.key(pane, key) }
    private fun write(clearDraft: Boolean = false, action: (SshTmuxClient, Pane) -> Unit) {
        val s = state.value
        val pane = s.selected ?: return
        val remote = client ?: return
        if (!s.connected || s.busy || s.uncertain || !pane.canWrite) return
        val token = epoch
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { io.withLock {
                    check(token == epoch && foreground) { "连接已变化" }
                    action(remote, pane)
                } }
                if (token == epoch) {
                    if (clearDraft) drafts.remove(pane.identity)
                    mutable.update { it.copy(busy = false, draft = if (clearDraft) "" else it.draft, message = if (clearDraft) null else "已发送终端操作。") }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (token == epoch && e is InputRejected) {
                    mutable.update { it.copy(busy = false, message = e.message, panel = true, output = emptyList()) }
                } else if (token == epoch) {
                    disconnect("发送结果待确认。请重新连接并查看原窗格，确认后再操作。")
                    mutable.update { it.copy(uncertain = true) }
                }
            }
        }
    }
    private fun startPolling() {
        poll?.cancel()
        val remote = client ?: return
        val token = epoch
        poll = viewModelScope.launch {
            var cycle = 0
            while (foreground && token == epoch && state.value.connected) {
                try {
                    val snapshot = state.value
                    val pane = snapshot.selected
                    val result = withContext(Dispatchers.IO) { io.withLock {
                        val panes = if (cycle % 5 == 0) remote.panes() else null
                        val lines = pane?.takeIf { snapshot.terminal || snapshot.panel || snapshot.uncertain }
                            ?.let { remote.capture(it).lineSequence().takeLastBounded(400) }
                        val chat = pane?.takeIf { !snapshot.terminal && it.canWrite }?.let {
                            try { JSONObject(remote.bridge(request(it, "snapshot", snapshot.binding)
                                .put("includeSkills", snapshot.binding == null).toString())) }
                            catch (_: Exception) { JSONObject().put("ok", false).put("error", "无法读取会话消息，可切换终端模式检查 Python / Codex 兼容性") }
                        }
                        Triple(panes, lines, chat)
                    } }
                    if (token != epoch) break
                    mutable.update {
                        if (it.selected?.identity != pane?.identity) it else {
                            val chat = result.third
                            val valid = chat?.optBoolean("ok") == true
                            val skillArray = chat?.optJSONArray("skills")
                            it.copy(panes = result.first ?: it.panes, output = result.second ?: it.output,
                                messages = if (valid) mergeChat(it.messages, chat) else it.messages,
                                binding = if (valid) chat.getString("binding") else it.binding,
                                chatError = if (chat != null && !valid) chat.optString("error") else null,
                                activity = if (valid && chat.optString("status") != "unknown") chat.optString("status") else it.activity,
                                historyBefore = it.historyBefore ?: if (valid) chat.getLong("before") else null,
                                skills = if (skillArray != null) (0 until skillArray.length()).map { i -> skillArray.getJSONObject(i).let { v -> SkillOption(v.getString("name"), v.optString("description")) } } else it.skills)
                        }
                    }
                    cycle++
                    delay(1000)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (token == epoch) disconnect("连接或目标已变化，请重新连接并选择会话。")
                    break
                }
            }
        }
    }
    fun disconnect(message: String = "已断开，NAS 上的任务继续运行。") {
        ++epoch
        poll?.cancel()
        val old = client
        client = null
        val uncertain = state.value.uncertain || state.value.busy && state.value.connected
        mutable.update { it.copy(connected = false, busy = false, output = emptyList(), panes = emptyList(), selected = null, message = message, uncertain = uncertain, binding = null, messages = emptyList(), skills = emptyList(), panel = false) }
        viewModelScope.launch(Dispatchers.IO) { old?.close() }
    }
    fun foreground(value: Boolean) {
        foreground = value
        if (!value && (client != null)) disconnect("已暂停连接。返回后手动重连，原任务继续运行。")
    }
    fun forget() {
        if (state.value.busy) return
        disconnect()
        importedKey?.fill(0); importedKey = null
        drafts.clear()
        mutable.update { it.copy(busy = true, hasKey = false, draft = "") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { io.withLock { vault.delete() } }
                mutable.value = RemoteState(ready = true)
            } catch (_: Exception) { mutable.update { it.copy(busy = false, message = "清除失败，可在系统设置中清除应用数据。") } }
        }
    }
    override fun onCleared() { client?.close(); importedKey?.fill(0) }
}

private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(result.size() + count <= max)
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}
private fun Sequence<String>.takeLastBounded(count: Int): List<String> {
    val queue = java.util.ArrayDeque<String>()
    for (line in this) { if (queue.size == count) queue.removeFirst(); queue.addLast(line) }
    return queue.toList()
}
