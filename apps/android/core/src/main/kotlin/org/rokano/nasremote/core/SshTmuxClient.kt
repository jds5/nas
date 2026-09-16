package org.rokano.nasremote.core

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.jcraft.jsch.SocketFactory
import java.net.Socket
import java.net.InetSocketAddress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class PinnedHostKeys(private val expected: String) : HostKeyRepository {
    @Volatile var rejected = false
        private set
    @Volatile var verified = false
        private set
    init { Fingerprints.decode(expected) }
    override fun check(host: String?, key: ByteArray?): Int {
        val accepted = key != null && Fingerprints.matches(expected, key)
        if (!accepted) rejected = true else verified = true
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
        val jsch = JSch()
        identity = jsch
        try { jsch.addIdentity("phone", key, null, passphrase.takeIf { it.isNotEmpty() }) }
        catch (e: Exception) { close(); throw IllegalStateException(ConnectionFailure.describe(e, ConnectionStage.KEY, false)) }
        try { open(profile, jsch) } catch (e: Exception) { close(); throw e }
    }

    /** Foreground-only recovery reuses the unlocked identity, never stores the passphrase. */
    fun reconnect(profile: ConnectionProfile) {
        profile.validate()
        val jsch = identity ?: error("请重新解锁私钥")
        session?.disconnect()
        open(profile, jsch)
    }
    private fun open(profile: ConnectionProfile, jsch: JSch) {
        val pins = PinnedHostKeys(profile.fingerprint)
        jsch.setHostKeyRepository(pins)
        var stage = ConnectionStage.TCP
        try {
            val s = jsch.getSession(profile.user, profile.host, profile.port)
            session = s
            s.setSocketFactory(object : SocketFactory {
                override fun createSocket(host: String, port: Int): Socket {
                    stage = ConnectionStage.TCP
                    val socket = Socket()
                    try {
                        socket.connect(InetSocketAddress(host, port), 15_000)
                        stage = ConnectionStage.SSH
                        return socket
                    } catch (e: Exception) { socket.close(); throw e }
                }
                override fun getInputStream(socket: Socket) = socket.getInputStream()
                override fun getOutputStream(socket: Socket) = socket.getOutputStream()
            })
            s.setConfig("StrictHostKeyChecking", "yes")
            s.setConfig("PreferredAuthentications", "publickey")
            s.setConfig("ClearAllForwardings", "yes")
            s.serverAliveInterval = 15_000
            s.serverAliveCountMax = 2
            s.timeout = 15_000
            s.connect(15_000)
        } catch (e: Exception) {
            session?.disconnect()
            session = null
            throw IllegalStateException(ConnectionFailure.describe(e, if (pins.verified) ConnectionStage.AUTH else stage, pins.rejected))
        }
    }

    /** Fixed bundled program; request data is carried only on stdin. No NAS installation. */
    fun bridge(request: String): String = exec(bridgeCommand, (request + "\n").toByteArray(Charsets.UTF_8))

    fun upload(request: String, file: java.io.File, progress: (Long) -> Unit): String {
        require(file.length() in 1..20L * 1024 * 1024)
        file.inputStream().use { source ->
            val counted = object : java.io.FilterInputStream(source) {
                var sent = 0L
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) { sent += n; progress(sent) }
                    return n
                }
            }
            java.io.SequenceInputStream(ByteArrayInputStream((request + "\n").toByteArray()), counted).use {
                return execStream(bridgeCommand, it, 125_000)
            }
        }
    }

    private val bridgeCommand: String by lazy {
        val script = requireNotNull(javaClass.getResourceAsStream("/nas_remote_bridge.py")).bufferedReader().use { it.readText() }
        "python3 -c '" + script.replace("'", "'\"'\"'") + "'"
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
        return execStream(command, ByteArrayInputStream(input), 10_000)
    }
    private fun execStream(command: String, input: java.io.InputStream, timeoutMs: Long): String {
        val s = session?.takeIf { it.isConnected } ?: error("连接已断开")
        val channel = s.openChannel("exec") as ChannelExec
        try {
            channel.setCommand(command)
            channel.setInputStream(input)
            val output = channel.inputStream
            val errors = channel.errStream
            channel.connect(10_000)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
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
    fun pause() { session?.disconnect(); session = null }

    override fun close() {
        session?.disconnect()
        session = null
        identity?.removeAllIdentity()
        identity = null
    }
}
