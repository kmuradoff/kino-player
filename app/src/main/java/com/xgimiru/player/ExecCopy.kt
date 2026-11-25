package com.xgimiru.player

import java.io.File
import java.io.IOException

/**
 * A program packed into the APK as lib/<abi>/lib*.so, run from the app's own copy. A system image may ship the
 * libraries of a system app without the exec bit (Lumen OS 1.1.0-beta2: /system/app/LumenPlay/lib/arm64 at 0644),
 * so the program is copied into [dir] on first use, made runnable for the app only (0700) and checked. A new APK
 * ([prepare]'s stamp), a changed source or a broken copy copies again. No Android in here, so the host tests run it.
 */
class ExecCopy(private val dir: File) {

    /** The runnable copy of [src] for the installed APK named by [stamp]. Throws IOException when it cannot be made. */
    fun prepare(src: File, stamp: String): File {
        val dst = File(dir, src.name)
        val mark = File(dir, src.name + ".stamp")
        val want = "$stamp:${src.length()}:${src.lastModified()}"
        if (fresh(dst, src) && runCatching { mark.readText() }.getOrNull() == want) return dst
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.path}")
        mark.delete()
        val tmp = File(dir, src.name + ".tmp")
        tmp.delete()
        src.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        if (!ownerOnly(tmp) || !fresh(tmp, src) || !tmp.renameTo(dst)) {
            tmp.delete()
            throw IOException("cannot make ${dst.path} runnable")
        }
        mark.writeText(want)
        return dst
    }

    private fun fresh(f: File, src: File) = f.isFile && f.length() == src.length() && f.canExecute()

    /** rwx for the owner, nothing for anyone else. */
    private fun ownerOnly(f: File) =
        f.setReadable(false, false) && f.setWritable(false, false) && f.setExecutable(false, false) &&
            f.setReadable(true, true) && f.setWritable(true, true) && f.setExecutable(true, true)

    companion object {
        /**
         * [start] with the first of [programs] the system lets run. Only a refused exec moves on to the next one:
         * a system that forbids running files of the app's own data (enforcing SELinux, targetSdk 29+) still runs
         * the APK's unpacked lib folder. Any other failure is the answer.
         */
        fun <T> startFirst(programs: List<File>, start: (File) -> T): T {
            var refused: IOException? = null
            for (p in programs) {
                try {
                    return start(p)
                } catch (e: IOException) {
                    if (!refusedExec(e)) throw e
                    refused = e
                }
            }
            throw refused ?: IOException("no program to run")
        }

        /** The exec itself was refused (no exec bit, a noexec mount, SELinux): "Cannot run program ...: Permission denied". */
        fun refusedExec(e: Throwable): Boolean {
            val m = e.message ?: return false
            return m.startsWith("Cannot run program") && ("error=13" in m || "Permission denied" in m)
        }
    }
}
