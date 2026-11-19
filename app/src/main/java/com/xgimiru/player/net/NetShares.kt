package com.xgimiru.player.net

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Login and address parts of network links. Pure string code: history and the launcher never see a password. */
object ShareUri {
    private val RX = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/@?#]*@)?([^/?#]*)(.*)$")

    /** smb://user:pass@nas/films/a.mkv → smb://nas/films/a.mkv */
    fun strip(uri: String): String {
        val m = RX.find(uri) ?: return uri
        return m.groupValues[1] + "://" + m.groupValues[3] + m.groupValues[4]
    }

    /** smb://nas/x + user, pass → smb://user:pass@nas/x (an existing login stays). */
    fun withAuth(uri: String, user: String?, pass: String?): String {
        val m = RX.find(uri) ?: return uri
        if (user.isNullOrEmpty() || m.groupValues[2].isNotEmpty()) return uri
        val u = enc(user) + (if (!pass.isNullOrEmpty()) ":" + enc(pass) else "")
        return m.groupValues[1] + "://" + u + "@" + m.groupValues[3] + m.groupValues[4]
    }

    /** "smb://nas" for any link on that server, lower case. */
    fun server(uri: String): String? = RX.find(uri)?.let { (it.groupValues[1] + "://" + it.groupValues[3]).lowercase() }

    /** What the user typed ("nas/films", "\\\\nas\\films", "nfs://...") as a link; SMB when no scheme is given. */
    fun normalize(input: String): String? {
        var s = input.trim().replace('\\', '/')
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "smb://" + s.trimStart('/')
        if (!s.endsWith("/")) s += "/"
        return s
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** A network folder the user added, or a server he logged in to from the list. */
data class Share(val name: String, val uri: String, val user: String?, val pass: String?) {
    /** Never prints the password (logs, crash reports). */
    override fun toString() = "Share(name=$name, uri=$uri, user=$user, pass=${if (pass.isNullOrEmpty()) "none" else "set"})"
}

/**
 * Stored form of the share list: a JSON array of {n, u, user, p}, p = the password sealed by a [SecretBox].
 * Builds up to 1.3 wrote the password in plain text under "pass": such entries are read once and the list is
 * written again sealed ([Decoded.migrate]). Pure code, host tested.
 */
object ShareCodec {
    class Decoded(val shares: List<Share>, val migrate: Boolean)

    fun decode(raw: String?, box: SecretBox): Decoded {
        val a = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        var plain = false
        val list = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.has("u") }.map { o ->
            val sealed = o.optString("p")
            val legacy = o.optString("pass")
            val pass = when {
                sealed.isNotEmpty() -> box.open(sealed)     // null: the key is gone, the user types it again
                legacy.isNotEmpty() -> { plain = true; legacy }
                else -> null
            }
            Share(o.optString("n"), o.getString("u"), o.optString("user").ifEmpty { null }, pass)
        }
        return Decoded(list, plain)
    }

    /** Throws when the box cannot seal: the caller decides what to keep. */
    fun encode(list: List<Share>, box: SecretBox): String {
        val a = JSONArray()
        for (s in list) {
            val o = JSONObject().put("n", s.name).put("u", s.uri).put("user", s.user ?: "")
            if (!s.pass.isNullOrEmpty()) o.put("p", box.seal(s.pass))
            a.put(o)
        }
        return a.toString()
    }
}

/**
 * Saved network folders and their logins. Links everywhere else are stored without a login; the player
 * puts it back right before opening ([authorize]). Passwords are stored sealed with a key of the Android key
 * store ([KeystoreBox]).
 */
object NetShares {
    private const val PREFS = "shares"
    private val box: SecretBox by lazy { KeystoreBox() }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Share> {
        val d = ShareCodec.decode(prefs(ctx).getString("list", "[]"), box)
        // first start after an update from a build that kept plain passwords: seal them now. If the key store
        // fails, the old entries stay as they are and the next start tries again.
        if (d.migrate) runCatching { prefs(ctx).edit().putString("list", ShareCodec.encode(d.shares, box)).commit() }
        return d.shares
    }

    /** Seals the plain passwords of older builds (called once at the app start, off the main thread). */
    fun migrate(ctx: Context) {
        all(ctx)
    }

    fun saved(ctx: Context) = all(ctx).filter { it.name.isNotEmpty() }

    private fun write(ctx: Context, list: List<Share>) {
        // never a plain password on disk: without a working key store the login is kept without its password
        val raw = runCatching { ShareCodec.encode(list, box) }
            .getOrElse { ShareCodec.encode(list.map { it.copy(pass = null) }, box) }
        prefs(ctx).edit().putString("list", raw).apply()
    }

    fun add(ctx: Context, s: Share) = write(ctx, all(ctx).filter { it.uri != s.uri } + s)

    fun remove(ctx: Context, uri: String) = write(ctx, all(ctx).filter { it.uri != uri })

    /** Login typed for a server found in the list: kept without a name, used for every link on it. */
    fun rememberLogin(ctx: Context, uri: String, user: String, pass: String) {
        val server = ShareUri.server(uri) ?: return
        val rest = all(ctx).filter { !(it.name.isEmpty() && ShareUri.server(it.uri) == server) }
        write(ctx, rest + Share("", "$server/", user, pass))
    }

    fun login(ctx: Context, uri: String): Share? = login(all(ctx), uri)

    /** The longest saved prefix of [uri] with a login, else any login on that server. */
    fun login(list: List<Share>, uri: String): Share? {
        val server = ShareUri.server(uri) ?: return null
        val on = list.filter { ShareUri.server(it.uri) == server && it.user != null }
        return on.filter { uri.startsWith(it.uri, true) }.maxByOrNull { it.uri.length } ?: on.firstOrNull()
    }

    fun authorize(ctx: Context, uri: Uri): Uri {
        if (uri.scheme?.lowercase() !in setOf("smb", "ftp", "ftps", "sftp", "nfs")) return uri
        val s = login(ctx, uri.toString()) ?: return uri
        return Uri.parse(ShareUri.withAuth(uri.toString(), s.user, s.pass))
    }
}
