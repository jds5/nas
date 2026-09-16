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

data class PendingAttachment(val id: String, val name: String, val size: Long, val sha256: String,
    val image: Boolean, val file: java.io.File, val binding: String) {
    fun json() = JSONObject().put("id", id).put("name", name).put("size", size).put("sha256", sha256).put("image", image)
}

data class ChatItem(val id: String, val role: String, val text: String, val phase: String = "", val offset: Long = 0)
data class SkillOption(val name: String, val description: String)
data class Question(val id: String, val title: String, val index: Int, val count: Int,
    val options: List<String>, val previous: Boolean, val next: Boolean, val freeText: Boolean)
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
    val question: Question? = null,
    val questionClosed: Boolean = false,
    val questionsOpen: Boolean = false,
    val questionChoice: Int? = null,
    val answerQuestion: String = "",
    val restoreAnchor: String = "",
    val restoreOffset: Int = 0,
    val seen: String = "",
    val catchingUp: Boolean = false,
    val reconnecting: Boolean = false,
    val attachments: List<PendingAttachment> = emptyList(),
    val attachmentProgress: String = "",
    val preparingAttachment: Boolean = false,
    val answered: String = "",


)

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = ProfileVault(application)
    private val sessionVault = SessionVault(application)
    private val storage = Mutex()
    private var persistJob: Job? = null
    private val memos = linkedMapOf<String, SessionMemo>()
    private val cached = linkedMapOf<String, List<ChatItem>>()
    private val cursors = mutableMapOf<String, Long>()
    private val questionDrafts = linkedMapOf<String, Pair<Int?, String>>()
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
    private var pickerActive = false
    private var pickerPaused = false
    private var pickerTimeout: Job? = null
    private val attachmentDir = java.io.File(application.cacheDir, "attachments")
    private val drafts = mutableMapOf<String, String>()

    init {
        // Temporary copies never survive an application process restart.
        attachmentDir.listFiles()?.forEach { it.delete() }
        attachmentDir.mkdirs()
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
    fun beginAttachmentPicker(): Boolean {
        val s = state.value
        if (!s.connected || s.busy || s.reconnecting || s.preparingAttachment || s.binding == null || s.attachments.size >= 4) return false
        pickerActive = true
        pickerTimeout?.cancel()
        pickerTimeout = viewModelScope.launch {
            delay(120_000)
            if (pickerActive) { pickerActive = false; disconnect("文件选择已超时，请重新连接。") }
        }
        return true
    }
    fun attachmentsSelected(uris: List<Uri>) {
        pickerActive = false
        pickerTimeout?.cancel()
        val binding = state.value.binding ?: return
        if (uris.isEmpty()) return
        val available = 4 - state.value.attachments.size
        if (uris.size > available) { mutable.update { it.copy(message = "一条消息最多选择 4 个附件") }; return }
        mutable.update { it.copy(preparingAttachment = true) }
        val token = epoch
        viewModelScope.launch {
            val prepared = mutableListOf<PendingAttachment>()
            try {
                withContext(Dispatchers.IO) {
                    var total = state.value.attachments.sumOf { it.size }
                    for (uri in uris) {
                        val resolver = getApplication<Application>().contentResolver
                        val rawName = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: "附件"
                        val name = rawName.filter { it.code >= 32 && it.code !in 127..159 && it.code !in 0x202A..0x202E && it.code !in 0x2066..0x2069 }.take(160).ifBlank { "附件" }
                        val id = java.util.UUID.randomUUID().toString().replace("-", "")
                        val file = java.io.File(attachmentDir, id)
                        try {
                            val digest = MessageDigest.getInstance("SHA-256")
                            var size = 0L
                            resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { output ->
                                val bytes = ByteArray(65536)
                                while (true) {
                                    val count = input.read(bytes)
                                    if (count < 0) break
                                    size += count
                                    require(size + total <= 20L * 1024 * 1024) { "每条消息附件总量不能超过 20 MiB" }
                                    check(token == epoch) { "连接已变化，请重新选择附件" }
                                    digest.update(bytes, 0, count); output.write(bytes, 0, count)
                                }
                            } }
                            require(size > 0) { "不能发送空文件" }
                            val header = ByteArray(12)
                            file.inputStream().use { it.read(header) }
                            val image = (header[0] == 0x89.toByte() && header.sliceArray(1..3).toString(Charsets.US_ASCII) == "PNG") ||
                                (header[0] == 0xff.toByte() && header[1] == 0xd8.toByte()) ||
                                (header.sliceArray(0..3).toString(Charsets.US_ASCII) == "RIFF" && header.sliceArray(8..11).toString(Charsets.US_ASCII) == "WEBP")
                            prepared += PendingAttachment(id, name, size, digest.digest().joinToString("") { "%02x".format(it) }, image, file, binding)
                            total += size
                        } catch (e: Exception) { file.delete(); throw e }
                    }
                }
                if (token == epoch && binding == state.value.binding) mutable.update { it.copy(attachments = it.attachments + prepared, message = null) }
                else prepared.forEach { it.file.delete() }
            } catch (e: Exception) {
                prepared.forEach { it.file.delete() }
                if (e is CancellationException) throw e
                mutable.update { it.copy(message = if (e is IllegalArgumentException || e is IllegalStateException) e.message else "无法读取附件，请重新选择本地文件。") }
            } finally { mutable.update { it.copy(preparingAttachment = false) } }
        }
    }
    fun removeAttachment(id: String) {
        if (state.value.busy || state.value.preparingAttachment) return
        state.value.attachments.find { it.id == id }?.file?.delete()
        mutable.update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) }
    }
    private fun clearAttachments() {
        state.value.attachments.forEach { it.file.delete() }
        mutable.update { it.copy(attachments = emptyList(), attachmentProgress = "") }
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
        if (state.value.busy || state.value.preparingAttachment || state.value.reconnecting || !state.value.connected) return
        rememberSession()
        clearAttachments()
        selection++
        loadingMemo = restored
        val prior = restored ?: memos.values.lastOrNull { it.profile == profileId() && it.pane == pane.identity }
        mutable.update { it.copy(selected = pane, output = emptyList(), message = null, draft = drafts[pane.identity].orEmpty(), terminal = terminal || !pane.canWrite, panel = false, binding = null, messages = emptyList(), skills = emptyList(), chatError = null, historyBefore = null, activity = "unknown", loadingHistory = false, delivery = "", answerDraft = "", screenToken = null, questionHint = false, questionClosed = false, question = null, questionsOpen = false, questionChoice = null, answerQuestion = "", answered = "", restoreAnchor = "", restoreOffset = 0, seen = "", catchingUp = false, uncertain = (prior?.uncertain ?: false) || recoveryLost) }
        startPolling()
    }
    fun back() {
        if (state.value.busy || state.value.preparingAttachment || state.value.reconnecting) return
        rememberSession()
        clearAttachments()
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
            draft = s.draft, answerDraft = s.answerDraft, answerQuestion = s.answerQuestion, uncertain = s.uncertain || s.busy,
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
    fun openQuestions(screenToken: String?) {
        mutable.update { it.copy(questionsOpen = true) }
        if (state.value.question == null && state.value.questionClosed) {
            control("key", key = RemoteKey.ShiftLeft, screenToken = screenToken, native = true)
        }
    }
    fun closeQuestions() { if (!state.value.busy) mutable.update { it.copy(questionsOpen = false) } }
    fun deferQuestions(screenToken: String?) {
        if (state.value.question == null) closeQuestions()
        else control("question_leave", screenToken = screenToken, native = true)
    }
    fun chooseAnswer(index: Int) { if (!state.value.busy) mutable.update { it.copy(questionChoice = index) } }
    fun questionPage(next: Boolean, screenToken: String?) {
        control("key", key = if (next) RemoteKey.ShiftLeft else RemoteKey.AltDown, screenToken = screenToken, native = true)
    }
    fun submitAnswer(question: Question, choice: Int, text: String, screenToken: String?) {
        val s = state.value
        val pane = s.selected ?: return
        val binding = s.binding ?: return
        if (screenToken == null || s.question?.id != question.id) return
        write(clearAnswer = true, successMessage = "回答已提交", rejectionPanel = false) { remote, _ ->
            val command = request(pane, "question_submit", binding).put("screenToken", screenToken)
                .put("questionId", question.id).put("choice", choice)
            if (choice == question.options.lastIndex) command.put("text", text)
            val result = JSONObject(remote.bridge(command.toString()))
            if (!result.optBoolean("ok")) {
                val reason = result.optString("error", "回答未提交")
                if (result.optBoolean("uncertain")) error(reason) else throw InputRejected(reason)
            }
        }
    }
    fun fillAnswer(screenToken: String?) {
        val s = state.value
        if (s.answerDraft.isBlank()) return
        control("answer", text = s.answerDraft, screenToken = screenToken)
    }
    private fun control(action: String, key: RemoteKey? = null, text: String? = null, screenToken: String?, native: Boolean = false) {
        val s = state.value
        val pane = s.selected ?: return
        val binding = s.binding ?: return
        val token = screenToken ?: return
        write(closeQuestions = action == "question_leave", clearAnswer = action == "answer", successMessage = if (native) null else "已发送终端操作，请核对原界面。", rejectionPanel = !native) { remote, _ ->
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
    fun panel(value: Boolean) { mutable.update { it.copy(panel = value, questionsOpen = if (value) false else it.questionsOpen) } }
    fun send() {
        val s = state.value
        val pane = s.selected ?: return
        val binding = s.binding ?: return
        val text = s.draft.ifBlank { if (s.attachments.isNotEmpty()) "请查看这些附件。" else "" }
        try { TmuxProtocol.validatePaste(text) }
        catch (e: IllegalArgumentException) { mutable.update { it.copy(message = e.message) }; return }
        if (s.chatError != null) return
        if (s.question != null) {
            mutable.update { it.copy(questionsOpen = true, message = "请提交当前回答，或点“稍后回答”返回聊天。") }
            return
        }
        if (s.preparingAttachment || s.reconnecting) return
        if (s.attachments.isNotEmpty() && (text.startsWith('/') || text.startsWith('!'))) {
            mutable.update { it.copy(message = "请将附件与 / 命令、! 命令分开发送。") }; return
        }
        val sendEpoch = epoch
        write(clearDraft = true) { remote, _ ->
            val attachments = org.json.JSONArray()
            for ((index, attachment) in s.attachments.withIndex()) {
                check(sendEpoch == epoch && foreground) { "连接已变化" }
                if (attachment.binding != binding) throw InputRejected("附件属于其他会话，请移除后重新选择")
                val uploaded = JSONObject(remote.upload(request(pane, "upload", binding).put("attachment", attachment.json()).toString(), attachment.file) { bytes ->
                    mutable.update { it.copy(attachmentProgress = "上传 ${index + 1}/${s.attachments.size} · ${bytes * 100 / attachment.size}%") }
                })
                if (!uploaded.optBoolean("ok")) throw InputRejected(uploaded.optString("error", "附件上传失败，消息未发送"))
                attachments.put(attachment.json())
            }
            mutable.update { it.copy(attachmentProgress = if (s.attachments.isEmpty()) "" else "附件已上传，正在提交消息…") }
            val command = request(pane, "send", binding).put("text", text)
            if (attachments.length() > 0) command.put("attachments", attachments)
            check(sendEpoch == epoch && foreground) { "连接已变化" }
            val result = JSONObject(remote.bridge(command.toString()))
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
    private fun write(clearDraft: Boolean = false, clearAnswer: Boolean = false, successMessage: String? = "已发送终端操作，请核对原界面。", rejectionPanel: Boolean = true, closeQuestions: Boolean = false, action: (SshTmuxClient, Pane) -> Unit) {
        val s = state.value
        val pane = s.selected ?: return
        val remote = client ?: return
        if (!s.connected || s.busy || s.reconnecting || s.preparingAttachment || s.uncertain || !pane.canWrite) return
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
                    if (clearDraft) { drafts.remove(pane.identity); clearAttachments() }
                    if (clearAnswer) {
                        questionDrafts.remove(memoKey(s.binding.orEmpty()) + ":" + s.answerQuestion)
                        mutable.update { it.copy(questionChoice = null, answered = "回答已提交到 Codex 输入端") }
                    }
                    mutable.update { it.copy(busy = false, questionsOpen = if (closeQuestions) false else it.questionsOpen, draft = if (clearDraft) "" else it.draft, message = if (clearDraft) null else successMessage, delivery = if (clearDraft) "submitted" else it.delivery, screenToken = null, answerDraft = if (clearAnswer) "" else it.answerDraft) }
                    rememberSession()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (token == epoch && e is InputRejected) {
                    mutable.update { it.copy(busy = false, message = e.message, panel = rejectionPanel, screenToken = null, delivery = if (clearDraft) "rejected" else it.delivery) }
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
                    val question = if (valid) chat.optJSONObject("question")?.let { q ->
                        val options = q.getJSONArray("options")
                        Question(q.getString("id"), q.getString("title"), q.getInt("index"), q.getInt("count"),
                            (0 until options.length()).map { options.getString(it) }, q.optBoolean("previous"), q.optBoolean("next"), q.optBoolean("freeText"))
                    } else null
                    val beforeQuestion = state.value
                    val changedQuestion = question != null && question.id != beforeQuestion.answerQuestion
                    if (changedQuestion && beforeQuestion.answerQuestion.isNotBlank()) {
                        questionDrafts[memoKey(binding!!) + ":" + beforeQuestion.answerQuestion] = beforeQuestion.questionChoice to beforeQuestion.answerDraft
                        while (questionDrafts.size > 32) questionDrafts.remove(questionDrafts.keys.first())
                    }
                    val questionDraft = question?.let { questionDrafts[memoKey(binding!!) + ":" + it.id] }
                    val restoredAnswer = if (initial) memo?.answerDraft.orEmpty() else beforeQuestion.answerDraft
                    val restoredAnswerId = if (initial) memo?.answerQuestion.orEmpty() else beforeQuestion.answerQuestion
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
                                question = question,
                                questionClosed = valid && chat.optBoolean("questionClosed"),
                                questionChoice = if (question == null) null else if (changedQuestion) questionDraft?.first else it.questionChoice,
                                answerQuestion = question?.id ?: restoredAnswerId,
                                activity = if (valid && chat.optString("status") != "unknown") chat.optString("status") else it.activity,
                                historyBefore = it.historyBefore ?: if (valid) chat.getLong("before") else null,
                                draft = if (initial && memo != null) memo.draft else it.draft,
                                answerDraft = if (question != null && question.id != restoredAnswerId) questionDraft?.second.orEmpty() else restoredAnswer,
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
                    if (token != epoch) break
                    if (!recoverConnection(remote, token)) break
                    cycle = 0
                }
            }
        }
    }
    private suspend fun recoverConnection(remote: SshTmuxClient, token: Int): Boolean {
        if (state.value.busy || state.value.uncertain || !foreground || token != epoch) {
            if (token == epoch) disconnect("连接中断，请重连核对原会话。")
            return false
        }
        mutable.update { it.copy(reconnecting = true, screenToken = null) }
        repeat(3) { attempt ->
            if (!foreground || token != epoch) return false
            mutable.update { it.copy(message = "网络中断，正在接回原会话（${attempt + 1}/3）…") }
            delay(1000L shl attempt)
            try {
                val snapshot = state.value
                withContext(Dispatchers.IO) { io.withLock {
                    check(foreground && token == epoch)
                    remote.reconnect(snapshot.profile)
                    val panes = remote.panes()
                    snapshot.selected?.let { pane ->
                        check(panes.any { it.identity == pane.identity }) { "原窗格已变化" }
                        snapshot.binding?.let { binding ->
                            val result = JSONObject(remote.bridge(request(pane, "snapshot", binding).toString()))
                            check(result.optBoolean("ok")) { "原会话已变化" }
                        }
                    }
                } }
                if (!foreground || token != epoch) { remote.close(); return false }
                mutable.update { it.copy(reconnecting = false, message = "已接回原会话，正在补读消息。") }
                return true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val retryable = e.message.orEmpty().let { it.startsWith("[E_TCP") || it.startsWith("[E_DNS]") || it.startsWith("[E_ROUTE]") || it.startsWith("[E_SSH_TIMEOUT]") }
                if (!retryable || attempt == 2) {
                    if (token == epoch) disconnect("接续失败，请重新连接。${e.message?.takeIf { it.startsWith("[") } ?: "原会话或连接需要核对。"}")
                    return false
                }
            }
        }
        return false
    }
    fun disconnect(message: String = "已断开，NAS 上的任务继续运行。") {
        ++epoch
        pickerPaused = false
        pickerActive = false
        pickerTimeout?.cancel()
        poll?.cancel()
        val old = client
        client = null
        rememberSession()
        val uncertain = state.value.uncertain || state.value.busy && state.value.connected
        mutable.update { it.copy(connected = false, busy = false, reconnecting = false, output = emptyList(), panes = emptyList(), selected = null, message = message, uncertain = uncertain, binding = null, messages = emptyList(), skills = emptyList(), panel = false) }
        viewModelScope.launch(Dispatchers.IO) { old?.close() }
    }
    fun foreground(value: Boolean) {
        foreground = value
        if ((pickerActive || pickerPaused) && client != null) {
            if (!value) {
                pickerPaused = true
                poll?.cancel()
                mutable.update { it.copy(reconnecting = true, screenToken = null) }
                client?.pause()
            } else if (pickerPaused) {
                pickerPaused = false
                val remote = client ?: return
                val token = epoch
                viewModelScope.launch { if (recoverConnection(remote, token)) startPolling() }
            }
            return
        }
        if (!value && (client != null)) disconnect("已暂停连接。返回后手动重连，原任务继续运行。")
    }
    fun forget() {
        if (state.value.busy) return
        disconnect()
        importedKey?.fill(0); importedKey = null
        drafts.clear()
        clearAttachments()
        persistJob?.cancel(); memos.clear(); cached.clear(); cursors.clear(); questionDrafts.clear()
        mutable.update { it.copy(busy = true, hasKey = false, draft = "") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { io.withLock { vault.delete(); storage.withLock { sessionVault.delete() } } }
                mutable.value = RemoteState(ready = true)
            } catch (_: Exception) { mutable.update { it.copy(busy = false, message = "清除失败，可在系统设置中清除应用数据。") } }
        }
    }
    override fun onCleared() { client?.close(); importedKey?.fill(0); attachmentDir.listFiles()?.forEach { it.delete() } }
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
