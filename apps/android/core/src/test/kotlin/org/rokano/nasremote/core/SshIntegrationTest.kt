package org.rokano.nasremote.core

import com.jcraft.jsch.JSch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A separate loopback-only OpenSSH server and disposable keys; no production SSH changes. */
class SshIntegrationTest {
    @Test fun encryptedEd25519LoginAndHostPinRejection() {
        assumeTrue(System.getenv("NAS_SSH_TESTS") == "1")
        assumeTrue(Files.isExecutable(Path.of("/usr/sbin/sshd")))
        // Exercise the BC signature path needed on Android, not the JDK 17 provider.
        val originalSignature = JSch.getConfig("ssh-ed25519")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        val dir = Files.createTempDirectory("nasremote-ssh-")
        var server: Process? = null
        fun command(vararg args: String) {
            val p = ProcessBuilder(*args).redirectErrorStream(true).start()
            check(p.waitFor(10, TimeUnit.SECONDS))
            check(p.exitValue() == 0) { "SSH test setup failed" }
        }
        try {
            command("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", "$dir/host")
            command("ssh-keygen", "-q", "-t", "ed25519", "-N", "test-only-password", "-f", "$dir/client")
            Files.copy(dir.resolve("client.pub"), dir.resolve("authorized_keys"))
            val reply = dir.resolve("reply")
            Files.writeString(reply, "#!/bin/sh\nprintf '%s\\n' '%4\t120\t80\t0\ttest\tcodex\t/tmp'\n")
            reply.toFile().setExecutable(true, true)
            val port = ServerSocket(0).use { it.localPort }
            Files.writeString(dir.resolve("sshd_config"), """
                ListenAddress 127.0.0.1
                Port $port
                HostKey $dir/host
                PidFile $dir/pid
                AuthorizedKeysFile $dir/authorized_keys
                StrictModes no
                PasswordAuthentication no
                KbdInteractiveAuthentication no
                UsePAM no
                AllowUsers ${System.getProperty("user.name")}
                ForceCommand $reply
                DisableForwarding yes
                LogLevel ERROR
            """.trimIndent())
            server = ProcessBuilder("/usr/sbin/sshd", "-D", "-e", "-f", "$dir/sshd_config")
                .redirectErrorStream(true).redirectOutput(dir.resolve("server.log").toFile()).start()
            var ready = false
            repeat(40) {
                if (!ready) try { Socket("127.0.0.1", port).use { ready = true } } catch (_: Exception) { Thread.sleep(50) }
            }
            check(ready) { "Private SSH server failed to start" }
            val hostKey = Base64.getDecoder().decode(Files.readString(dir.resolve("host.pub")).split(' ')[1])
            val pin = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(hostKey))
            val profile = ConnectionProfile("127.0.0.1", port, System.getProperty("user.name"), pin)
            val key = Files.readAllBytes(dir.resolve("client"))
            val password = "test-only-password".toByteArray()
            try {
                SshTmuxClient().use { client ->
                    client.connect(profile, key, password)
                    assertEquals("%4", client.panes().single().id)
                }
                SshTmuxClient().use { client ->
                    val failure = assertThrows(IllegalStateException::class.java) {
                        client.connect(profile.copy(fingerprint = "SHA256:" + "A".repeat(43)), key, password)
                    }
                    assertTrue(failure.message!!.contains("指纹不匹配"))
                }
                SshTmuxClient().use { client ->
                    assertThrows(IllegalStateException::class.java) { client.connect(profile, key, "wrong".toByteArray()) }
                }
            } finally { key.fill(0); password.fill(0) }
        } finally {
            JSch.setConfig("ssh-ed25519", originalSignature)
            server?.destroy()
            server?.waitFor(5, TimeUnit.SECONDS)
            if (server?.isAlive == true) server.destroyForcibly().waitFor()
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
