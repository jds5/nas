package org.rokano.nasremote

import android.app.Application
import android.net.Uri
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
    fun select(pane: Pane) {
        if (state.value.busy || !state.value.connected) return
        mutable.update { it.copy(selected = pane, output = emptyList(), message = null, draft = drafts[pane.identity].orEmpty()) }
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
    fun acknowledge() { mutable.update { it.copy(uncertain = false, message = null) } }
    fun paste() {
        val text = state.value.draft
        try { TmuxProtocol.validatePaste(text) }
        catch (e: IllegalArgumentException) { mutable.update { it.copy(message = e.message) }; return }
        write { remote, pane -> remote.paste(pane, text) }
    }
    fun key(key: RemoteKey) = write { remote, pane -> remote.key(pane, key) }
    private fun write(action: (SshTmuxClient, Pane) -> Unit) {
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
                if (token == epoch) mutable.update { it.copy(busy = false, message = "已发送终端输入；请以窗格显示为准。") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (token == epoch) {
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
                    val pane = state.value.selected
                    val result = withContext(Dispatchers.IO) { io.withLock {
                        val panes = if (cycle % 5 == 0) remote.panes() else null
                        val lines = pane?.let { remote.capture(it).lineSequence().takeLastBounded(400) }
                        panes to lines
                    } }
                    if (token != epoch) break
                    mutable.update {
                        it.copy(panes = result.first ?: it.panes, output = if (it.selected?.identity == pane?.identity) result.second ?: it.output else it.output)
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
        mutable.update { it.copy(connected = false, busy = false, output = emptyList(), panes = emptyList(), selected = null, message = message, uncertain = uncertain) }
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
