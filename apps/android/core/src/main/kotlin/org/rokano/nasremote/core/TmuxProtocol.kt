package org.rokano.nasremote.core

/** Only locally validated numeric identities enter shell commands. User text travels over stdin. */
object TmuxProtocol {
    const val LIST = "tmux list-panes -a -F '#{pane_id}\t#{pane_pid}\t#{pid}\t#{pane_dead}\t#{q:session_name}\t#{q:pane_current_command}\t#{q:pane_current_path}'"
    fun parsePanes(output: String): List<Pane> = output.lineSequence().filter { it.isNotBlank() }.take(128).map { line ->
        val p = line.split('\t')
        require(p.size == 7) { "tmux 返回了无法识别的会话数据" }
        require(p[3] == "0" || p[3] == "1")
        if (p[3] == "1") null else Pane(p[0], p[1].toLong(), p[2].toLong(), TerminalText.clean(p[4]), TerminalText.clean(p[5]), TerminalText.clean(p[6]))
    }.filterNotNull().toList()

    private fun guard(p: Pane, write: Boolean): String {
        val expected = "${p.pid}:${p.serverPid}:0" + if (write) ":codex" else ""
        val format = "#{pane_pid}:#{pid}:#{pane_dead}" + if (write) ":#{pane_current_command}" else ""
        return "[ \"\$(tmux display-message -p -t ${p.id} '$format')\" = '$expected' ]"
    }
    fun capture(p: Pane) = "${guard(p, false)} && tmux capture-pane -p -J -t ${p.id} -S -300"
    fun key(p: Pane, key: RemoteKey): String {
        require(p.canWrite) { "首版仅支持控制 Codex 窗格" }
        return "${guard(p, true)} && tmux send-keys -t ${p.id} ${key.tmuxName}"
    }
    fun validatePaste(text: String): ByteArray {
        require(text.isNotBlank()) { "请输入内容" }
        require(text.none { (it < ' ' && it != '\n' && it != '\t') || it == '\u007f' || it in '\u0080'..'\u009f' }) { "输入不能包含终端控制字符" }
        return text.toByteArray(Charsets.UTF_8).also { require(it.size <= 16_384) { "单次输入最多 16 KB" } }
    }
    fun paste(p: Pane, buffer: String): String {
        require(p.canWrite)
        require(Regex("nasremote-[a-f0-9]{32}").matches(buffer))
        // The text is read as data; -p requests bracketed paste when the target enables it.
        // -d removes our uniquely named buffer. A trap also cleans it up on target failure.
        return "trap 'tmux delete-buffer -b $buffer 2>/dev/null' EXIT; " +
            "${guard(p, true)} && tmux load-buffer -b $buffer - && ${guard(p, true)} && " +
            "tmux paste-buffer -p -d -b $buffer -t ${p.id}"
    }
}
