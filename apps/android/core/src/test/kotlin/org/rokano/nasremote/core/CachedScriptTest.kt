package org.rokano.nasremote.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Runs the real loader/installer commands with a temporary HOME; never touches ~/.cache. */
class CachedScriptTest {
    private fun sh(home: java.nio.file.Path, command: String, input: ByteArray = ByteArray(0)): Pair<Int, String> {
        val process = ProcessBuilder("/bin/sh", "-c", command).apply { environment()["HOME"] = home.toString() }.start()
        process.outputStream.use { it.write(input) }
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(20, TimeUnit.SECONDS))
        return process.exitValue() to output
    }

    @Test fun installsOnceThenRunsOnlyTheExactPrivateCopy() {
        val home = Files.createTempDirectory("nas-cache-test-")
        try {
            val script = CachedScript("/nas_remote_bridge.py", "bridge")
            assertTrue(script.run.length < 1_500)
            assertEquals(CachedScript.MISSING, sh(home, script.check).first)
            assertEquals(3, sh(home, script.install, "tampered".toByteArray()).first)
            assertEquals(0, sh(home, script.install, script.bytes).first)
            assertEquals(0, sh(home, script.check).first)
            val (status, output) = sh(home, script.run, "{\"action\":\"snapshot\",\"pane\":{\"id\":\"bad\"}}\n".toByteArray())
            assertEquals(0, status); assertTrue(output, output.contains("窗格标识无效"))
            val cached = home.resolve(".cache/nas-remote/bridge-${script.sha}.py")
            assertEquals("rwx------", Files.getPosixFilePermissions(cached.parent).let { java.nio.file.attribute.PosixFilePermissions.toString(it) })
            Files.setPosixFilePermissions(cached, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
            assertEquals(CachedScript.MISSING, sh(home, script.run).first)
            Files.setPosixFilePermissions(cached, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            Files.write(cached, "print('injected')\n".toByteArray(), java.nio.file.StandardOpenOption.APPEND)
            val tampered = sh(home, script.run)
            assertEquals(CachedScript.MISSING, tampered.first); assertFalse(tampered.second.contains("injected"))
        } finally { home.toFile().deleteRecursively() }
    }
}
