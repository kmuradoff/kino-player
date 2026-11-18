package com.xgimiru.player.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.ByteArrayOutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * An smb:// link split into what SMB needs. Pure code, host tested.
 * smb://DOMAIN;user:pass@nas:445/Video/Films/a.mkv -> host nas, port 445, share Video, path Films\a.mkv.
 */
data class SmbTarget(val host: String, val port: Int, val share: String, val path: String,
                     val user: String?, val pass: String?, val domain: String?) {
    /** One SMB session per server and login, one tree per share. */
    val sessionKey get() = "${host.lowercase()}:$port|${domain.orEmpty()}|${user.orEmpty()}"

    override fun toString() = "SmbTarget($host:$port/$share/$path, user=$user)"

    companion object {
        private val RX = Regex("^smb://(?:([^@/]*)@)?([^/:?#]+)(?::(\\d+))?(/[^?#]*)?$", RegexOption.IGNORE_CASE)

        fun parse(uri: String): SmbTarget? {
            val m = RX.find(uri.trim()) ?: return null
            val parts = (m.groupValues[4]).split('/').filter { it.isNotEmpty() }.map(::pct)
            val share = parts.firstOrNull() ?: return null
            var user: String? = null; var pass: String? = null; var domain: String? = null
            m.groupValues[1].takeIf { it.isNotEmpty() }?.let { info ->
                val u = pct(info.substringBefore(':'))
                pass = if (':' in info) pct(info.substringAfter(':')) else null
                val sep = u.indexOfAny(charArrayOf(';', '\\'))
                if (sep > 0) { domain = u.substring(0, sep); user = u.substring(sep + 1) } else user = u
            }
            return SmbTarget(m.groupValues[2], m.groupValues[3].toIntOrNull() ?: 445, share,
                parts.drop(1).joinToString("\\"), user?.ifEmpty { null }, pass, domain)
        }

        /** Percent decoding of URL parts. Unlike URLDecoder a '+' stays a '+'. */
        fun pct(s: String): String {
            if ('%' !in s) return s
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length && s.substring(i + 1, i + 3).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    out.write(s.substring(i + 1, i + 3).toInt(16)); i += 3
                } else { out.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
            }
            return out.toString("UTF-8")
        }
    }
}

/**
 * SMB 2/3 reader (smbj) for the player and the folder list. Connections and sessions are kept and reused:
 * Media3 opens the file again on every seek. Logins come from the link or from [NetShares].
 */
object Smb {
    class Entry(val name: String, val dir: Boolean, val size: Long)

    private val client by lazy {
        SMBClient(SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
            .build())
    }
    private val sessions = HashMap<String, Session>()
    private val shares = HashMap<String, DiskShare>()

    /** A file opened for reading: random access by position. */
    class Reader internal constructor(private val f: com.hierynomus.smbj.share.File) : AutoCloseable {
        val length: Long = f.fileInformation.standardInformation.endOfFile
        fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int = f.read(buf, pos, off, len)
        override fun close() { runCatching { f.close() } }
    }

    @Synchronized
    private fun share(t: SmbTarget): DiskShare {
        val key = t.sessionKey + "|" + t.share.lowercase()
        shares[key]?.takeIf { it.isConnected }?.let { return it }
        val s = session(t)
        val share = runCatching { s.connectShare(t.share) as DiskShare }.getOrElse {
            // the session died (server restarted, Wi-Fi changed): log in again once
            sessions.remove(t.sessionKey)?.let { old -> runCatching { old.close() } }
            session(t).connectShare(t.share) as DiskShare
        }
        shares[key] = share
        return share
    }

    private fun session(t: SmbTarget): Session {
        sessions[t.sessionKey]?.takeIf { it.connection.isConnected }?.let { return it }
        val conn = client.connect(t.host, t.port)
        val auth = when {
            t.user != null -> listOf(AuthenticationContext(t.user, (t.pass ?: "").toCharArray(), t.domain))
            else -> listOf(AuthenticationContext.guest(), AuthenticationContext.anonymous())
        }
        var last: Exception? = null
        for (a in auth) {
            try { return conn.authenticate(a).also { sessions[t.sessionKey] = it } } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("no login")
    }

    fun open(uri: String): Reader {
        val t = SmbTarget.parse(uri) ?: throw IllegalArgumentException("not an smb link")
        val f = share(t).openFile(t.path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null)
        return Reader(f)
    }

    /** Folder contents with sizes (dot entries and hidden files left out). */
    fun list(uri: String): List<Entry> {
        val t = SmbTarget.parse(uri) ?: return emptyList()
        return share(t).list(t.path).filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith(".") }.map {
            val dir = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            Entry(it.fileName, dir, it.endOfFile)
        }
    }

    /** A small file (subtitles, a poster) read whole, null if bigger than [max]. */
    fun readAll(uri: String, max: Int): ByteArray? = open(uri).use { r ->
        if (r.length > max) return null
        val out = ByteArray(r.length.toInt())
        var pos = 0
        while (pos < out.size) { val n = r.read(pos.toLong(), out, pos, out.size - pos); if (n <= 0) break; pos += n }
        out
    }
}
