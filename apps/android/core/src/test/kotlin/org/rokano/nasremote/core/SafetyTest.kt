package org.rokano.nasremote.core

import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class SafetyTest {
    private val pane = Pane("%4", 120, 80, "nas", "codex", "/work")
    private val key = "host public key bytes".toByteArray()
    private val pin = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
    @Test fun onlyPinnedHostCanAuthenticate() {
        val repository = PinnedHostKeys(pin)
        assertEquals(HostKeyRepository.OK, repository.check("server", key))
        assertEquals(HostKeyRepository.CHANGED, repository.check("server", "attacker".toByteArray()))
        assertTrue(repository.rejected)
        assertEquals(HostKeyRepository.CHANGED, repository.check("server", null))
    }
    @Test fun malformedOrNonCanonicalPinsAreRejected() {
        for (p in listOf("", "MD5:abc", "SHA256:" + "A".repeat(42), "SHA256:" + "A".repeat(42) + "B"))
            assertThrows(IllegalArgumentException::class.java) { Fingerprints.decode(p) }
    }
    @Test fun userTextNeverBecomesAShellCommand() {
        val attack = "中文\n\$(touch /tmp/should-not-exist); ' \" `id`"
        assertArrayEquals(attack.toByteArray(), TmuxProtocol.validatePaste(attack))
        val command = TmuxProtocol.paste(pane, "nasremote-" + "a".repeat(32))
        assertFalse(command.contains(attack))
        assertTrue(command.contains("load-buffer"))
        assertTrue(command.contains("120:80:0:codex"))
    }
    @Test fun controlSequencesAndLargePastesAreRejected() {
        for (text in listOf("\u001b[201~\nid", "hello\u0000world", "\u0003", "a".repeat(16385), " "))
            assertThrows(IllegalArgumentException::class.java) { TmuxProtocol.validatePaste(text) }
        assertThrows(IllegalArgumentException::class.java) { TmuxProtocol.validatePaste("中".repeat(6000)) }
    }
    @Test fun unsafeTargetsAndBuffersNeverReachShell() {
        assertThrows(IllegalArgumentException::class.java) { Pane("%1;id", 1, 2, "", "codex", "") }
        assertThrows(IllegalArgumentException::class.java) { TmuxProtocol.paste(pane, "x;id") }
        assertThrows(IllegalArgumentException::class.java) { TmuxProtocol.key(pane.copy(command = "bash"), RemoteKey.Enter) }
    }
    @Test fun parserSkipsDeadPanesAndRejectsMalformedRecords() {
        val panes = TmuxProtocol.parsePanes("%4\t120\t80\t0\tnas\tcodex\t/work\n%5\t121\t80\t1\told\tbash\t/tmp\n")
        assertEquals(listOf(pane), panes)
        assertThrows(IllegalArgumentException::class.java) { TmuxProtocol.parsePanes("%4\t120\t80\t0\tnas\tcodex\t/work\tinjected") }
    }
    @Test fun remoteControlCharactersAreNotRendered() {
        assertEquals("abc[31m\n中文", TerminalText.clean("abc\u001b[31m\n中文\u202e"))
    }
}
