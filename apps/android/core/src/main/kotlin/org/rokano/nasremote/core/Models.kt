package org.rokano.nasremote.core

import java.security.MessageDigest
import java.util.Base64

data class ConnectionProfile(val host: String, val port: Int, val user: String, val fingerprint: String) {
    fun validate() {
        require(host.length in 1..253 && host.none { it.isWhitespace() || it in "/\\@\u0000" }) { "请输入主机名或 IP，不包含协议或路径" }
        require(port in 1..65535) { "端口必须在 1–65535 之间" }
        require(Regex("[a-zA-Z_][a-zA-Z0-9_.-]{0,63}").matches(user)) { "SSH 用户名格式不正确" }
        Fingerprints.decode(fingerprint)
    }
}

object Fingerprints {
    fun decode(value: String): ByteArray {
        require(Regex("SHA256:[A-Za-z0-9+/]{43}").matches(value)) { "请填写核对过的 SSH SHA256 指纹" }
        return Base64.getDecoder().decode(value.removePrefix("SHA256:") + "=").also {
            require(it.size == 32 && "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(it) == value) { "指纹格式不正确" }
        }
    }
    fun matches(expected: String, key: ByteArray): Boolean =
        MessageDigest.isEqual(decode(expected), MessageDigest.getInstance("SHA-256").digest(key))
}

data class Pane(val id: String, val pid: Long, val serverPid: Long, val session: String, val command: String, val path: String) {
    init {
        require(Regex("%[0-9]+").matches(id))
        require(pid > 0 && serverPid > 0)
    }
    val identity: String get() = "$serverPid:$id:$pid"
    val canWrite: Boolean get() = command == "codex"
}

enum class RemoteKey(val tmuxName: String) { Enter("Enter"), Escape("Escape"), Up("Up"), Down("Down"), Tab("Tab"), Interrupt("C-c") }

object TerminalText {
    // Plain text only: no HTML, escape sequence interpretation or automatic URL handling.
    fun clean(value: String): String = value.filter {
        it == '\n' || it == '\t' || (it >= ' ' && it != '\u007f' && it !in '\u0080'..'\u009f' && it !in '\u202a'..'\u202e' && it !in '\u2066'..'\u2069')
    }
}
