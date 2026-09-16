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
import org.rokano.nasremote.security.*
import java.security.MessageDigest

data class ChatItem(val id: String, val role: String, val text: String, val phase: String = "", val offset: Long = 0)
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
    val saveDrafts: Boolean = false,
    val resumeTitle: String? = null,
    val delivery: String = "",
    val answerDraft: String = "",
    val screenToken: String? = null,
    val questionHint: Boolean = false,
    val restoreAnchor: String = "",
    val restoreOffset: Int = 0,
    val seen: String = "",
    val catchingUp: Boolean = false,

)

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = ProfileVault(application)
    private val sessionVault = SessionVault(application)
    private val storage = Mutex()
    private var persistJob: Job? = null
    private val memos = linkedMapOf<String, SessionMemo>()
    private val cached = linkedMapOf<String, List<ChatItem>>()
    private val cursors = mutableMapOf<String, Long>()
    private var selection = 0
    private var interaction = 0
    private var recoveryLost = false
    private var loadingMemo: SessionMemo? = null
    private fun profileId(profile: ConnectionProfile = state.value.profile): String = MessageDigest.getInstance("SHA-256")
        .digest("${profile.host}:${profile.port}:${profile.user}:${profile.fingerprint}".toByteArray()).joinToString("") { "%02x".format(it) }
    private fun memoKey(binding: String) = profileId() + ":" + binding
    private fun lastMemo() = memos.values.lastOrNull { it.profile == profileId() && !it.binding.startsWith("terminal:") }

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
                val savedSessions = try { withContext(Dispatchers.IO) { sessionVault.load() } }
                    catch (_: Exception) { recoveryLost = true; SavedSessions() }
                savedSessions.entries.forEach { memos[it.profile + ":" + it.binding] = it }
                importedKey = saved?.key
                mutable.update { it.copy(ready = true, hasKey = saved != null, profile = saved?.profile ?: it.profile, saveDrafts = savedSessions.saveDrafts) }
                mutable.update { it.copy(resumeTitle = lastMemo()?.title, message = if (recoveryLost) "恢复记录无法解锁。连接后请先核对原窗格，避免重复发送。" else it.message) }
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
    fun connect(profile: ConnectionProfile, password: String, resume: Boolean = false) {
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
                val previous = if (resume) lastMemo() else null
                val original = previous?.let { m -> panes.singleOrNull { it.identity == m.pane && it.canWrite } }
                if (original != null) select(original, restored = previous)
                else {
                    if (resume) mutable.update { it.copy(message = "原窗格已变化，请手动选择；没有创建或恢复其他执行器。") }
                    startPolling()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { remote.close() }
                if (token == epoch) mutable.update { it.copy(busy = false, connected = false, message = if (e is IllegalStateException) e.message else "连接未完成，请核对网络、tmux 和本机凭据存储。") }
            } finally { key.fill(0); pass.fill(0) }
        }
    }
    fun select(pane: Pane, terminal: Boolean = false, restored: SessionMemo? = null) {
        if (state.value.busy || !state.value.connected) return
        rememberSession()
        selection++
        loadingMemo = restored
        val prior = restored ?: memos.values.lastOrNull { it.profile == profileId() && it.pane == pane.identity }
        mutable.update { it.copy(selected = pane, output = emptyList(), message = null, draft = drafts[pane.identity].orEmpty(), terminal = terminal || !pane.canWrite, panel = false, binding = null, messages = emptyList(), skills = emptyList(), chatError = null, historyBefore = null, activity = "unknown", loadingHistory = false, delivery = "", answerDraft = "", screenToken = null, questionHint = false, restoreAnchor = "", restoreOffset = 0, seen = "", catchingUp = false, uncertain = (prior?.uncertain ?: false) || recoveryLost) }
        startPolling()
    }
    fun back() {
        if (state.value.busy) return
        rememberSession()
        selection++
        mutable.update { it.copy(selected = null, output = emptyList()) }
    }
    fun draft(text: String) {
        if (state.value.busy) return
        val value = text.take(16_384)
        state.value.selected?.let { drafts[it.identity] = value }
        mutable.update { it.copy(draft = value) }
        rememberSession()
    }
    fun saveDrafts(value: Boolean) {
        mutable.update { it.copy(saveDrafts = value) }
        rememberSession()
        persist()
    }
    fun answerDraft(value: String) {
        if (state.value.busy) return
        mutable.update { it.copy(answerDraft = value.take(16384)) }
        rememberSession()
    }
    fun reading(anchor: String, offset: Int, atBottom: Boolean) {
        val s = state.value
        val binding = s.binding ?: return
        val key = memoKey(binding)
        val note = memos[key] ?: return
        val item = s.messages.firstOrNull { it.id == anchor } ?: return
        val seen = if (atBottom) s.messages.lastOrNull()?.id.orEmpty() else s.seen
        memos[key] = note.copy(anchor = anchor, anchorOffset = offset, anchorByte = item.offset, seen = seen)
        mutable.update { it.copy(seen = seen, restoreAnchor = anchor, restoreOffset = offset) }
        persist()
    }
    private fun rememberSession() {
        val s = state.value
        val pane = s.selected ?: return
        val binding = s.binding ?: if (s.terminal) "terminal:${pane.identity}" else return
        val key = memoKey(binding)
        val prior = memos[key]
        val note = (prior ?: SessionMemo(profileId(), pane.identity, binding, pane.session)).copy(
            draft = s.draft, answerDraft = s.answerDraft, uncertain = s.uncertain || s.busy,
            delivery = if (s.busy) "uncertain" else s.delivery)
        memos.remove(key)
        memos[key] = note
        cached[key] = s.messages
        while (memos.size > 8) {
            val oldest = memos.keys.first(); memos.remove(oldest); cached.remove(oldest); cursors.remove(oldest)
        }
        mutable.update { it.copy(resumeTitle = lastMemo()?.title) }
        persist()
    }
    private fun savedSnapshot() = SavedSessions(state.value.saveDrafts, memos.values.toList())
    private fun persist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(300)
            val snapshot = savedSnapshot()
            try { withContext(Dispatchers.IO) { storage.withLock { sessionVault.save(snapshot) } } }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(message = "无法保存会话恢复信息，当前草稿仅保留在内存中。") }
            }
        }
    }
    fun openQuestions() { panel(true) }
    fun fillAnswer(screenToken: String?) {
        val s = state.value
        if (s.answerDraft.isBlank()) return
        control("answer", text = s.answerDraft, screenToken = screenToken)
    }
    private fun control(action: String, key: RemoteKey? = null, text: String? = null, screenToken: String?) {
        val s = state.value
        val pane = s.selected ?: return
        val binding = s.binding ?: return
        val token = screenToken ?: return
        write(clearAnswer = action == "answer") { remote, _ ->
            val command = request(pane, action, binding).put("screenToken", token)
            key?.let { command.put("key", it.tmuxName) }
            text?.let { command.put("text", it) }
            val result = JSONObject(remote.bridge(command.toString()))
            if (!result.optBoolean("ok")) {
                val reason = result.optString("error", "操作未完成")
                if (result.optBoolean("uncertain")) error(reason) else throw InputRejected(reason)
            }
        }
    }
    fun terminal(value: Boolean) {
        if (!state.value.busy) mutable.update { it.copy(terminal = value, panel = false) }
    }
    fun panel(value: Boolean) { mutable.update { it.copy(panel = value) } }
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
            ChatItem(it.getString("id"), it.getString("role"), TerminalText.clean(it.getString("text")), it.optString("phase"), it.optLong("offset"))
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
        val selectedToken = selection
        mutable.update { it.copy(loadingHistory = true) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { io.withLock {
                    JSONObject(remote.bridge(request(pane, "snapshot", s.binding).put("before", before).toString()))
                } }
                if (token == epoch && selectedToken == selection && state.value.selected?.identity == pane.identity && state.value.binding == s.binding) mutable.update {
                    if (result.optBoolean("ok")) it.copy(messages = mergeChat(it.messages, result, older = true), historyBefore = result.getLong("before"), loadingHistory = false)
                    else it.copy(loadingHistory = false, message = result.optString("error"))
                }
            } catch (_: Exception) { if (token == epoch && selectedToken == selection) mutable.update { it.copy(loadingHistory = false, message = "历史读取失败，可稍后重试") } }
        }
    }
    fun acknowledge() {
        if (state.value.connected && state.value.output.isNotEmpty()) {
            recoveryLost = false
            mutable.update { it.copy(uncertain = false, delivery = "reviewed", message = "已核对上次操作；需要重发时请先确认不会重复。") }
            rememberSession()
        }
    }
    fun paste() {
        val text = state.value.draft
        try { TmuxProtocol.validatePaste(text) }
        catch (e: IllegalArgumentException) { mutable.update { it.copy(message = e.message) }; return }
        write { remote, pane -> remote.paste(pane, text) }
    }
    fun key(key: RemoteKey, screenToken: String? = state.value.screenToken) {
        if (!state.value.terminal && state.value.binding != null) control("key", key, screenToken = screenToken)
        else write { remote, pane -> remote.key(pane, key) }
    }
    private fun write(clearDraft: Boolean = false, clearAnswer: Boolean = false, action: (SshTmuxClient, Pane) -> Unit) {
        val s = state.value
        val pane = s.selected ?: return
        val remote = client ?: return
        if (!s.connected || s.busy || s.uncertain || !pane.canWrite) return
        val token = epoch
        interaction++
        mutable.update { it.copy(busy = true, screenToken = null, message = null, delivery = if (clearDraft) "sending" else it.delivery) }
        rememberSession()
        persistJob?.cancel()
        val journal = savedSnapshot()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { io.withLock {
                    storage.withLock { sessionVault.save(journal) }
                    check(token == epoch && foreground) { "连接已变化" }
                    action(remote, pane)
                } }
                if (token == epoch) {
                    if (clearDraft) drafts.remove(pane.identity)
                    mutable.update { it.copy(busy = false, draft = if (clearDraft) "" else it.draft, message = if (clearDraft) null else "已发送终端操作，请核对原界面。", delivery = if (clearDraft) "submitted" else it.delivery, screenToken = null, answerDraft = if (clearAnswer) "" else it.answerDraft) }
                    rememberSession()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (token == epoch && e is InputRejected) {
                    mutable.update { it.copy(busy = false, message = e.message, panel = true, screenToken = null, delivery = if (clearDraft) "rejected" else it.delivery) }
                    rememberSession()
                } else if (token == epoch) {
                    disconnect("发送结果待确认。请重新连接并查看原窗格，确认后再操作。")
                    mutable.update { it.copy(uncertain = true, delivery = "uncertain") }
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
                    val selectedToken = selection
                    val operation = interaction
                    val pane = snapshot.selected
                    val restore = loadingMemo
                    val expected = snapshot.binding ?: restore?.binding
                    val cursor = expected?.let { cursors[memoKey(it)] ?: restore?.anchorByte?.takeIf { n -> n > 0 } }
                    val result = withContext(Dispatchers.IO) { io.withLock {
                        val panes = if (cycle % 5 == 0) remote.panes() else null
                        val lines = pane?.takeIf { snapshot.terminal }?.let { remote.capture(it).lineSequence().takeLastBounded(400) }
                        val chat = pane?.takeIf { !snapshot.terminal && it.canWrite }?.let {
                            JSONObject(remote.bridge(request(it, "snapshot", expected)
                                .put("screen", true).put("includeSkills", snapshot.binding == null)
                                .apply { if (cursor != null) put("after", cursor) }.toString()))
                        }
                        Triple(panes, lines, chat)
                    } }
                    if (token != epoch) break
                    if (selectedToken != selection) continue
                    val chat = result.third
                    val valid = chat?.optBoolean("ok") == true
                    val binding = if (valid) chat.getString("binding") else null
                    val initial = snapshot.binding == null && binding != null
                    val memo = if (initial) memos[memoKey(binding)] else null
                    // A stale persisted binding never silently reconnects to a replacement executor.
                    if (valid) cursors[memoKey(binding!!)] = chat.getLong("next")
                    mutable.update {
                        if (it.selected?.identity != pane?.identity) it else {
                            val skillArray = chat?.optJSONArray("skills")
                            val base = if (initial && cursor != null) cached[memoKey(binding)].orEmpty() else it.messages
                            it.copy(panes = result.first ?: it.panes,
                                output = if (valid) TerminalText.clean(chat.optString("screen")).lines() else result.second ?: it.output,
                                messages = if (valid) mergeChat(base, chat) else it.messages,
                                binding = binding ?: it.binding,
                                chatError = if (chat != null && !valid) chat.optString("error") else null,
                                screenToken = if (valid && !it.busy && operation == interaction) chat.optString("screenToken") else null,
                                questionHint = valid && chat.optBoolean("questionHint"),
                                activity = if (valid && chat.optString("status") != "unknown") chat.optString("status") else it.activity,
                                historyBefore = it.historyBefore ?: if (valid) chat.getLong("before") else null,
                                draft = if (initial && memo != null) memo.draft else it.draft,
                                answerDraft = if (initial && memo != null) memo.answerDraft else it.answerDraft,
                                uncertain = if (initial) (memo?.uncertain ?: false) || it.uncertain || recoveryLost else it.uncertain,
                                delivery = if (initial) memo?.delivery.orEmpty() else it.delivery,
                                restoreAnchor = if (initial) memo?.anchor.orEmpty() else it.restoreAnchor,
                                restoreOffset = if (initial) memo?.anchorOffset ?: 0 else it.restoreOffset,
                                seen = if (initial) memo?.seen.orEmpty() else it.seen,
                                catchingUp = valid && chat.optBoolean("more"),
                                skills = if (skillArray != null) (0 until skillArray.length()).map { i -> skillArray.getJSONObject(i).let { v -> SkillOption(v.getString("name"), v.optString("description")) } } else it.skills)
                        }
                    }
                    if (valid) {
                        loadingMemo = null
                        if (initial || state.value.messages != snapshot.messages) rememberSession()
                    }
                    cycle++
                    delay(if (state.value.catchingUp) 150 else 1000)
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
        rememberSession()
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
        persistJob?.cancel(); memos.clear(); cached.clear(); cursors.clear()
        mutable.update { it.copy(busy = true, hasKey = false, draft = "") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { io.withLock { vault.delete(); storage.withLock { sessionVault.delete() } } }
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
