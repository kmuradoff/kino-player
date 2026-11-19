package com.xgimiru.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

/** Start and stop of the built-in TorrServer process, with /bin/sh programs standing in for the engine. */
class EngineProcessTest {
    private lateinit var dir: File

    @Before fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        dir = Files.createTempDirectory("engine").toFile()
    }

    private fun sh(script: String) = listOf("/bin/sh", "-c", script)
    private fun alive(pid: String) = ProcessBuilder("kill", "-0", pid).start().waitFor() == 0
    private fun pidOf(f: File): String {
        repeat(50) { if (f.length() > 0) return f.readText().trim(); Thread.sleep(20) }
        fail("no pid"); return ""
    }

    @Test fun startWaitsForTheAnswerAndStopEndsTheProcess() {
        val pidFile = File(dir, "pid")
        var probes = 0
        val e = EngineProcess({ probes++ >= 2 }, timeoutMs = 5000, pollMs = 10)
        e.start(sh("echo $$ > ${pidFile.path}; exec sleep 30"), 8090, dir, File(dir, "log"), emptyMap(), 8090)
        assertTrue(e.isRunning)
        assertEquals(8090, e.port)
        assertTrue(probes >= 3)
        val pid = pidOf(pidFile)
        assertTrue(alive(pid))
        var asked = false
        e.stop { asked = true }
        assertTrue(asked)
        assertFalse(e.isRunning)
        Thread.sleep(100)
        assertFalse(alive(pid))
    }

    @Test fun stopWithoutAStartDoesNothing() {
        var asked = false
        EngineProcess({ true }).stop { asked = true }
        assertFalse(asked)
    }

    @Test fun anEngineThatCannotBindItsPortSaysSo() {
        val e = EngineProcess({ false }, timeoutMs = 5000, pollMs = 10)
        try {
            e.start(sh("echo 'cannot bind HTTP port 8090'; exit 1"), 8090, dir, File(dir, "log"), emptyMap(), 8090)
            fail("started")
        } catch (x: IOException) {
            assertTrue(x.message!!, x.message!!.startsWith("port 8090 is busy"))
            assertTrue(x.message!!.contains("another TorrServe"))
        }
        assertFalse(e.isRunning)
    }

    @Test fun anEngineThatDiesNamesItsLastLogLine() {
        val e = EngineProcess({ false }, timeoutMs = 5000, pollMs = 10)
        try {
            e.start(sh("echo 'open db: read-only file system'; exit 2"), 41234, dir, File(dir, "log"), emptyMap(), 8090)
            fail("started")
        } catch (x: IOException) {
            assertEquals("the engine stopped right after the start: open db: read-only file system", x.message)
        }
    }

    @Test fun anEngineThatNeverAnswersIsStoppedAfterTheTimeout() {
        val pidFile = File(dir, "pid")
        val e = EngineProcess({ false }, timeoutMs = 400, pollMs = 10)
        try {
            e.start(sh("echo $$ > ${pidFile.path}; exec sleep 30"), 41235, dir, File(dir, "log"), emptyMap(), 8090)
            fail("started")
        } catch (x: IOException) {
            assertTrue(x.message!!, x.message!!.contains("did not answer"))
        }
        assertFalse(e.isRunning)
        Thread.sleep(100)
        assertFalse(alive(pidOf(pidFile)))
    }

    @Test fun theEngineGetsItsEnvironment() {
        val out = File(dir, "env")
        val e = EngineProcess({ out.length() > 0 }, timeoutMs = 5000, pollMs = 10)
        e.start(sh("echo \"\$GOGC \$HOME\" > ${out.path}; exec sleep 30"), 1, dir, File(dir, "log"),
            mapOf("GOGC" to "50", "HOME" to dir.path), 8090)
        e.stop {}
        assertEquals("50 ${dir.path}", out.readText().trim())
    }

    @Test fun cacheFollowsTheDeviceMemory() {
        assertEquals(48, TorrSettings.autoCacheMb(1024))
        assertEquals(64, TorrSettings.autoCacheMb(1500))
        assertEquals(96, TorrSettings.autoCacheMb(2048))
        assertEquals(256, TorrSettings.autoCacheMb(3800))
        assertEquals(0, TorrSettings.CACHE_CHOICES.first())
    }
}
