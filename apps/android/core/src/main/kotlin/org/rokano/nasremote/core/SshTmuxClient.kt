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
import java.security.MessageDigest
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

/**
 * A bundled Python program, cached on the NAS under ~/.cache/nas-remote by content hash.
 * Every run re-hashes the cached bytes and only executes them when they match the APK copy,
 * so each poll sends a ~1 KB loader instead of the whole script. No service is installed.
 */
internal class CachedScript(resource: String, private val name: String) {
    val bytes: ByteArray = requireNotNull(SshTmuxClient::class.java.getResourceAsStream(resource)).use { it.readBytes() }
    val sha: String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    init { require(Regex("[a-z_]{1,32}").matches(name) && bytes.size <= 262_144) }
    private fun python(code: String) = "python3 -c '" + code.replace("'", "'\"'\"'") + "' $name $sha"
    val run = python(VERIFY + "\nsys.argv=[p];exec(compile(d,p,'exec'),{'__name__':'__main__'})")
    val check = python(VERIFY)
    val install = python(INSTALLER)
    companion object {
        const val MISSING = 97
        // Exit 97 when the cached copy is missing, foreign, writable by others or not this exact hash.
        private const val VERIFY = """import hashlib,os,stat,sys
p=os.path.expanduser('~/.cache/nas-remote/%s-%s.py'%(sys.argv[1],sys.argv[2]))
try:
 fd=os.open(p,os.O_RDONLY|os.O_NOFOLLOW);st=os.fstat(fd)
 if not stat.S_ISREG(st.st_mode) or st.st_uid!=os.getuid() or st.st_mode&0o077 or st.st_size>262144:os.close(fd);sys.exit(97)
 with os.fdopen(fd,'rb') as f:d=f.read()
except OSError:sys.exit(97)
if hashlib.sha256(d).hexdigest()!=sys.argv[2]:sys.exit(97)"""
        private const val INSTALLER = """import hashlib,os,stat,sys,uuid
d=sys.stdin.buffer.read(262145)
if len(d)>262144 or hashlib.sha256(d).hexdigest()!=sys.argv[2]:sys.exit(3)
root=os.path.expanduser('~/.cache/nas-remote');os.makedirs(root,0o700,exist_ok=True)
st=os.lstat(root)
if not stat.S_ISDIR(st.st_mode) or st.st_uid!=os.getuid() or st.st_mode&0o077:sys.exit(4)
t=os.path.join(root,'.tmp-'+uuid.uuid4().hex);fd=os.open(t,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'wb') as f:f.write(d);f.flush();os.fsync(f.fileno())
os.replace(t,os.path.join(root,'%s-%s.py'%(sys.argv[1],sys.argv[2])))
for n in os.listdir(root):
 if n.startswith(sys.argv[1]+'-') and n!='%s-%s.py'%(sys.argv[1],sys.argv[2]):os.unlink(os.path.join(root,n))"""
    }
}

/** Blocking transport: call on an IO dispatcher. close() may be called to abort IO. */
class SshTmuxClient : AutoCloseable {
    @Volatile private var session: Session? = null
    @Volatile private var identity: JSch? = null
    private val installed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

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
        installed.clear() // A new SSH session re-verifies the NAS cache once.
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

    /** Fixed bundled program; request data is carried only on stdin. */
    fun bridge(request: String): String = runScript(bridgeScript, (request + "\n").toByteArray(Charsets.UTF_8))
    fun lifecycle(request: String): String = runScript(lifecycleScript, (request + "\n").toByteArray(Charsets.UTF_8))

    /** Installs the verified copy once per connection, then runs it through the small loader. */
    private fun prepare(script: CachedScript) {
        if (script.sha in installed) return
        synchronized(installed) {
            if (script.sha in installed) return
            val (status, _) = execStatus(script.check, ByteArrayInputStream(ByteArray(0)), 10_000)
            if (status != 0) check(execStatus(script.install, ByteArrayInputStream(script.bytes), 20_000).first == 0) { "无法在 NAS 缓存桥接程序，请检查 ~/.cache 权限" }
            installed += script.sha
        }
    }
    private fun runScript(script: CachedScript, input: ByteArray): String {
        prepare(script)
        val (status, output) = execStatus(script.run, ByteArrayInputStream(input), 10_000)
        if (status == CachedScript.MISSING) { installed -= script.sha; error("NAS 上的桥接缓存已变化，请重试") }
        check(status == 0) { "目标已变化、tmux 不可用或远端操作失败，请重新选择会话" }
        return output
    }

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
            prepare(bridgeScript)
            java.io.SequenceInputStream(ByteArrayInputStream((request + "\n").toByteArray()), counted).use {
                return execStream(bridgeScript.run, it, 125_000)
            }
        }
    }

    private val bridgeScript by lazy { CachedScript("/nas_remote_bridge.py", "bridge") }
    private val lifecycleScript by lazy { CachedScript("/nas_remote_lifecycle.py", "lifecycle") }

    fun panes(): List<Pane> {
        val output = exec("${TmuxProtocol.LIST} 2>&1 || true")
        if (output.contains("no server running") || output.contains("error connecting to")) return emptyList()
        return TmuxProtocol.parsePanes(output)
    }
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
        val (status, output) = execStatus(command, input, timeoutMs)
        check(status == 0) { "目标已变化、tmux 不可用或远端操作失败，请重新选择会话" }
        return output
    }
    private fun execStatus(command: String, input: java.io.InputStream, timeoutMs: Long): Pair<Int, String> {
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
            return channel.exitStatus to result.toString("UTF-8")
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
