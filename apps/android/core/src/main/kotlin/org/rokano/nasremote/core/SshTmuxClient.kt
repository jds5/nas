package org.rokano.nasremote.core

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class PinnedHostKeys(private val expected: String) : HostKeyRepository {
    @Volatile var rejected = false
        private set
    init { Fingerprints.decode(expected) }
    override fun check(host: String?, key: ByteArray?): Int {
        val accepted = key != null && Fingerprints.matches(expected, key)
        if (!accepted) rejected = true
        return if (accepted) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit // No trust-on-first-use writes.
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID() = "pinned-sha256"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

/** Blocking transport: call on an IO dispatcher. close() may be called to abort IO. */
class SshTmuxClient : AutoCloseable {
    @Volatile private var session: Session? = null
    @Volatile private var identity: JSch? = null

    fun connect(profile: ConnectionProfile, key: ByteArray, passphrase: ByteArray) {
        profile.validate()
        close()
        val pins = PinnedHostKeys(profile.fingerprint)
        val jsch = JSch()
        identity = jsch
        jsch.setHostKeyRepository(pins)
        try {
            jsch.addIdentity("phone", key, null, passphrase.takeIf { it.isNotEmpty() })
            val s = jsch.getSession(profile.user, profile.host, profile.port)
            session = s
            s.setConfig("StrictHostKeyChecking", "yes")
            s.setConfig("PreferredAuthentications", "publickey")
            s.setConfig("ClearAllForwardings", "yes")
            s.serverAliveInterval = 15_000
            s.serverAliveCountMax = 2
            s.timeout = 15_000
            s.connect(15_000)
        } catch (e: Exception) {
            close()
            throw IllegalStateException(if (pins.rejected) "主机指纹不匹配，已拒绝连接。请通过可信渠道核对。" else "SSH 连接失败，请检查网络、用户、私钥与口令。")
        }
    }

    fun panes(): List<Pane> = TmuxProtocol.parsePanes(exec(TmuxProtocol.LIST))
    fun capture(pane: Pane): String = TerminalText.clean(exec(TmuxProtocol.capture(pane)))
    fun paste(pane: Pane, text: String) {
        val bytes = TmuxProtocol.validatePaste(text)
        try { exec(TmuxProtocol.paste(pane, "nasremote-" + UUID.randomUUID().toString().replace("-", "")), bytes) }
        finally { bytes.fill(0) }
    }
    fun key(pane: Pane, key: RemoteKey) { exec(TmuxProtocol.key(pane, key)) }

    private fun exec(command: String, input: ByteArray = byteArrayOf()): String {
        val s = session?.takeIf { it.isConnected } ?: error("连接已断开")
        val channel = s.openChannel("exec") as ChannelExec
        try {
            channel.setCommand(command)
            channel.setInputStream(ByteArrayInputStream(input))
            val output = channel.inputStream
            val errors = channel.errStream
            channel.connect(10_000)
            val deadline = System.nanoTime() + 10_000_000_000L
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var received = 0
            while (true) {
                for (stream in listOf(output, errors)) {
                    while (stream.available() > 0) {
                        val n = stream.read(buffer, 0, minOf(buffer.size, stream.available()))
                        if (n < 0) break
                        received += n
                        check(received <= 262_144) { "远端输出过大，已停止读取" }
                        if (stream === output) result.write(buffer, 0, n)
                    }
                }
                if (channel.isClosed && output.available() == 0 && errors.available() == 0) break
                check(System.nanoTime() < deadline) { "远端操作超时" }
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                Thread.sleep(20)
            }
            check(channel.exitStatus == 0) { "目标已变化、tmux 不可用或远端操作失败，请重新选择会话" }
            return result.toString("UTF-8")
        } finally { channel.disconnect() }
    }
    override fun close() {
        session?.disconnect()
        session = null
        identity?.removeAllIdentity()
        identity = null
    }
}
