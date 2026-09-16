package org.rokano.nasremote.core

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Uses a new private tmux server, never the user's default socket or live conversations. */
class TmuxIntegrationTest {
    @Test fun existingPaneReceivesLiteralPasteAndRejectsReplacedIdentity() {
        assumeTrue("Enable with NAS_TMUX_TESTS=1 on Linux with tmux and python3", System.getenv("NAS_TMUX_TESTS") == "1")
        val dir = Files.createTempDirectory("nasremote-test-")
        val socket = "nasremote-test-" + UUID.randomUUID().toString()
        Files.createSymbolicLink(dir.resolve("codex"), Path.of("/usr/bin/python3"))
        Files.writeString(dir.resolve("read.py"), "import os,tty\ntty.setraw(0)\nprint('\\x1b[?2004hREADY',flush=True)\nwhile True:\n d=os.read(0,16384)\n print(repr(d.decode('utf-8')),flush=True)\n")
        fun run(command: String, bytes: ByteArray = byteArrayOf()): Pair<Int,String> {
            val real = command.replace("tmux ", "tmux -L $socket ")
            val p = ProcessBuilder("sh", "-c", real).redirectErrorStream(true).start()
            p.outputStream.use { it.write(bytes) }
            check(p.waitFor(5, TimeUnit.SECONDS))
            return p.exitValue() to p.inputStream.bufferedReader().readText()
        }
        try {
            assertEquals(0, run("tmux -f /dev/null new-session -d -s test '${dir.resolve("codex")} ${dir.resolve("read.py")}'").first)
            var pane: Pane? = null
            repeat(30) {
                if (pane == null) {
                    val p = TmuxProtocol.parsePanes(run(TmuxProtocol.LIST).second).single()
                    if (p.canWrite && run(TmuxProtocol.capture(p)).second.contains("READY")) pane = p else Thread.sleep(50)
                }
            }
            val selected = requireNotNull(pane) { "Test process did not become ready" }
            val marker = dir.resolve("must-not-exist")
            val text = "中文第一行\n\$(touch $marker); \"quoted\""
            assertEquals(0, run(TmuxProtocol.paste(selected, "nasremote-" + "a".repeat(32)), TmuxProtocol.validatePaste(text)).first)
            Thread.sleep(100)
            val output = run(TmuxProtocol.capture(selected)).second
            assertTrue(output.contains("中文第一行"))
            assertTrue(output.contains("200~")) // Bracketed paste was delivered to the application.
            assertFalse(Files.exists(marker))
            assertNotEquals(0, run(TmuxProtocol.key(selected.copy(pid = selected.pid + 1), RemoteKey.Enter)).first)
            assertEquals(0, run(TmuxProtocol.key(selected, RemoteKey.Enter)).first)
            assertFalse(run("tmux list-buffers").second.contains("nasremote-"))
            assertEquals(selected.pid, TmuxProtocol.parsePanes(run(TmuxProtocol.LIST).second).single().pid)
        } finally {
            run("tmux kill-server")
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
