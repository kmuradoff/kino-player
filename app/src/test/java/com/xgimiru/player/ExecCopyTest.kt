package com.xgimiru.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** The built-in engine runs from the app's own copy, with /bin/sh programs standing in for libtorrserver.so. */
class ExecCopyTest {
    private lateinit var dir: File
    private lateinit var copies: ExecCopy

    @Before fun setUp() {
        dir = Files.createTempDirectory("exec").toFile()
        copies = ExecCopy(File(dir, "no_backup/engine"))
    }

    /** The engine as a system image ships it: lib/arm64/libtorrserver.so at 0644 (Lumen OS 1.1.0-beta2). */
    private fun lib(body: String = "echo engine \"$@\"", mode: String = "rw-r--r--"): File {
        val f = File(dir, "system/app/LumenPlay/lib/arm64/libtorrserver.so")
        f.parentFile!!.mkdirs()
        f.writeText("#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(mode))
        return f
    }

    private fun run(f: File, vararg args: String): String {
        val p = ProcessBuilder(listOf(f.path) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
    }

    private fun mode(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))
    private fun inode(f: File) = Files.getAttribute(f.toPath(), "unix:ino")

    @Test fun aLibraryWithoutTheExecBitRunsFromItsCopy() {
        val src = lib()
        try {
            run(src)
            fail("ran straight from the lib folder")
        } catch (e: IOException) {
            assertTrue(e.message!!, ExecCopy.refusedExec(e))   // what Lumen Play 2.0 showed on the Z9X
        }
        val exe = copies.prepare(src, "100:5")
        assertEquals(File(dir, "no_backup/engine/libtorrserver.so"), exe)
        assertEquals("rwx------", mode(exe))
        assertEquals("engine -p 8090", run(exe, "-p", "8090"))
        assertEquals("rw-r--r--", mode(src))
    }

    @Test fun oneApkIsCopiedOnce() {
        val src = lib()
        val first = inode(copies.prepare(src, "100:5"))
        assertEquals(first, inode(copies.prepare(src, "100:5")))
    }

    @Test fun aNewApkCopiesAgainEvenWithTheSameLibrarySize() {
        val src = lib("echo aaa")
        src.setLastModified(1_600_000_000_000L)
        assertEquals("aaa", run(copies.prepare(src, "100:5")))
        lib("echo bbb")
        src.setLastModified(1_600_000_000_000L)
        assertEquals("bbb", run(copies.prepare(src, "200:5")))
    }

    @Test fun aChangedLibraryCopiesAgain() {
        val src = lib("echo old")
        assertEquals("old", run(copies.prepare(src, "100:5")))
        lib("echo the new engine")
        assertEquals("the new engine", run(copies.prepare(src, "100:5")))
    }

    @Test fun aBrokenCopyIsMadeAgain() {
        val src = lib()
        val exe = copies.prepare(src, "100:5")
        exe.setExecutable(false, false)
        assertEquals("engine", run(copies.prepare(src, "100:5")))
        exe.writeText("#!/bin/sh\n")
        exe.setExecutable(true, true)
        assertEquals("engine", run(copies.prepare(src, "100:5")))
        assertTrue(File(dir, "no_backup/engine").list()!!.sorted() == listOf("libtorrserver.so", "libtorrserver.so.stamp"))
    }

    @Test fun aRefusedProgramFallsBackToTheNextOne() {
        val refused = lib()
        val runnable = copies.prepare(refused, "100:5")
        val tried = mutableListOf<File>()
        assertEquals("engine", ExecCopy.startFirst(listOf(refused, runnable)) { tried += it; run(it) })
        assertEquals(listOf(refused, runnable), tried)
    }

    @Test fun anotherFailureIsTheAnswerAndNothingElseIsTried() {
        val tried = mutableListOf<File>()
        try {
            ExecCopy.startFirst(listOf(File("a"), File("b"))) { tried += it; throw IOException("port 8090 is busy") }
            fail("started")
        } catch (e: IOException) {
            assertEquals("port 8090 is busy", e.message)
        }
        assertEquals(listOf(File("a")), tried)
    }

    @Test fun whenEveryProgramIsRefusedTheLastReasonIsShown() {
        val src = lib()
        try {
            ExecCopy.startFirst(listOf(src)) { run(it) }
            fail("started")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains(src.path))
        }
        assertNotEquals(true, ExecCopy.refusedExec(IOException("the engine stopped right after the start: Permission denied")))
    }
}
