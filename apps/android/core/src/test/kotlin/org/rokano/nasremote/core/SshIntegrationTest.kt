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
            Files.writeString(reply, """
                #!/bin/sh
                case "${'$'}SSH_ORIGINAL_COMMAND" in
                  "python3 -c "*) exec /bin/sh -c "${'$'}SSH_ORIGINAL_COMMAND" ;;
                  *) printf '%s\n' '%4	120	80	0	test	codex	/tmp' ;;
                esac
            """.trimIndent())
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
                    // Exercises the bundled resource and shell quoting over actual SSH.
                    // Invalid target is rejected before touching any tmux server.
                    val refused = client.bridge("""{"action":"snapshot","pane":{"id":"invalid;echo INJECTED"}}""")
                    assertTrue(refused.contains("\"ok\": false"))
                    assertTrue(refused.contains("窗格标识无效"))
                    assertFalse(refused.contains("INJECTED"))
                }
                SshTmuxClient().use { client ->
                    val failure = assertThrows(IllegalStateException::class.java) {
                        client.connect(profile.copy(fingerprint = "SHA256:" + "A".repeat(43)), key, password)
                    }
                    assertTrue(failure.message!!.contains("指纹不匹配"))
                }
                SshTmuxClient().use { client ->
                    val failure = assertThrows(IllegalStateException::class.java) { client.connect(profile, key, "wrong".toByteArray()) }
                    assertTrue(failure.message, failure.message!!.contains("[E_PRIVATE_KEY]"))
                }
                // Revoke only this disposable test server's key to exercise server-side auth rejection.
                Files.writeString(dir.resolve("authorized_keys"), "")
                SshTmuxClient().use { client ->
                    val failure = assertThrows(IllegalStateException::class.java) { client.connect(profile, key, password) }
                    assertTrue(failure.message, failure.message!!.contains("[E_AUTH]"))
                }
                val closedPort = ServerSocket(0).use { it.localPort }
                SshTmuxClient().use { client ->
                    val failure = assertThrows(IllegalStateException::class.java) {
                        client.connect(profile.copy(port = closedPort), key, password)
                    }
                    assertTrue(failure.message!!.contains("[E_TCP_REFUSED]"))
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
