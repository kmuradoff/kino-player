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
data class Share(val name: String, val uri: String, val user: String?, val pass: String?)

/**
 * Saved network folders and their logins. Links everywhere else are stored without a login; the player
 * puts it back right before opening ([authorize]).
 */
object NetShares {
    private const val PREFS = "shares"

    fun all(ctx: Context): List<Share> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "[]")
        val a = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Share(it.optString("n"), it.getString("u"), it.optString("user").ifEmpty { null }, it.optString("pass").ifEmpty { null })
        }
    }

    fun saved(ctx: Context) = all(ctx).filter { it.name.isNotEmpty() }

    private fun write(ctx: Context, list: List<Share>) {
        val a = JSONArray()
        for (s in list) a.put(JSONObject().put("n", s.name).put("u", s.uri).put("user", s.user ?: "").put("pass", s.pass ?: ""))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", a.toString()).apply()
    }

    fun add(ctx: Context, s: Share) = write(ctx, all(ctx).filter { it.uri != s.uri } + s)

    fun remove(ctx: Context, uri: String) = write(ctx, all(ctx).filter { it.uri != uri })

    /** Login typed for a server found in the list: kept without a name, used for every link on it. */
    fun rememberLogin(ctx: Context, uri: String, user: String, pass: String) {
        val server = ShareUri.server(uri) ?: return
        val rest = all(ctx).filter { !(it.name.isEmpty() && ShareUri.server(it.uri) == server) }
        write(ctx, rest + Share("", "$server/", user, pass))
    }

    fun login(ctx: Context, uri: String): Share? {
        val server = ShareUri.server(uri) ?: return null
        val list = all(ctx).filter { ShareUri.server(it.uri) == server && it.user != null }
        return list.filter { uri.startsWith(it.uri, true) }.maxByOrNull { it.uri.length } ?: list.firstOrNull()
    }

    fun authorize(ctx: Context, uri: Uri): Uri {
        if (uri.scheme?.lowercase() !in setOf("smb", "ftp", "ftps", "sftp", "nfs")) return uri
        val s = login(ctx, uri.toString()) ?: return uri
        return Uri.parse(ShareUri.withAuth(uri.toString(), s.user, s.pass))
    }
}
