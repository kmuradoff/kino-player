package com.xgimiru.player.dlna

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.Locale

/** Общие мелочи UPnP: экранирование XML, время «Ч:ММ:СС», разбор SOAP и DIDL-Lite. */
object Upnp {

    const val DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CMS = "urn:schemas-upnp-org:service:ConnectionManager:1"

    /** Экранирование для текста и атрибутов; недопустимые в XML символы выбрасываются. */
    fun esc(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val b = StringBuilder(s.length + 16)
        for (c in s) when {
            c == '&' -> b.append("&amp;")
            c == '<' -> b.append("&lt;")
            c == '>' -> b.append("&gt;")
            c == '"' -> b.append("&quot;")
            c == '\'' -> b.append("&apos;")
            c < ' ' && c != '\t' && c != '\n' && c != '\r' -> {}
            c == '￾' || c == '￿' -> {}
            else -> b.append(c)
        }
        return b.toString()
    }

    /** 3725000 → «1:02:05». */
    fun time(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }

    /** «1:02:05», «01:02:05.500», «62:05», «3725»; null, если это не время. */
    fun parseTime(t: String?): Long? {
        val v = t?.trim()?.removePrefix("+") ?: return null
        if (v.isEmpty() || v.length > 24) return null
        val parts = v.split(':')
        if (parts.size > 3) return null
        var ms = 0.0
        for ((i, p) in parts.withIndex()) {
            val last = i == parts.size - 1
            val n = (if (last) p.toDoubleOrNull() else p.toLongOrNull()?.toDouble()) ?: return null
            if (n < 0 || n.isNaN() || n.isInfinite()) return null
            ms = ms * 60 + n
        }
        return (ms * 1000).toLong()
    }

    // Внутренние DTD у KXml разворачивают сущности: «миллиард смешков» отсекаем сразу.
    private fun safe(xml: String) = !xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)

    private fun parser(xml: String): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(StringReader(xml))
    }

    private fun local(name: String) = name.substringAfter(':')

    class Action(val name: String, val args: Map<String, String>)

    /** Тело SOAP-запроса: имя действия и его аргументы. null, если разобрать не вышло. */
    fun parseSoap(xml: String): Action? = runCatching {
        if (!safe(xml)) return null
        val p = parser(xml)
        var inBody = false
        var action: String? = null
        val args = HashMap<String, String>()
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> {
                    val n = local(p.name)
                    when {
                        !inBody && n == "Body" -> inBody = true
                        inBody && action == null -> action = n
                        action != null && args.size < 32 -> args[n] = p.nextText()
                    }
                }
                XmlPullParser.END_TAG -> if (action != null && local(p.name) == action) break
            }
        }
        action?.let { Action(it, args) }
    }.getOrNull()

    class Meta(val title: String?, val upnpClass: String?, val protocolInfo: String?, val durationMs: Long?)

    /** Название, класс и протокол из DIDL-Lite, который присылает телефон вместе со ссылкой. */
    fun parseDidl(xml: String?): Meta? {
        if (xml.isNullOrBlank() || !xml.contains("<")) return null
        return runCatching {
            if (!safe(xml)) return null
            val p = parser(xml)
            var title: String? = null
            var cls: String? = null
            var proto: String? = null
            var dur: Long? = null
            var gotRes = false
            while (true) {
                when (p.next()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.START_TAG -> when (local(p.name)) {
                        "title" -> if (title == null) title = p.nextText().trim().takeIf { it.isNotEmpty() }
                        "class" -> if (cls == null) cls = p.nextText().trim()
                        "res" -> if (!gotRes) {
                            gotRes = true
                            proto = p.getAttributeValue(null, "protocolInfo")
                            dur = parseTime(p.getAttributeValue(null, "duration"))
                        }
                    }
                }
            }
            Meta(title?.take(300), cls, proto, dur)
        }.getOrNull()
    }

    fun soapResponse(service: String, action: String, out: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body><u:").append(action).append("Response xmlns:u=\"").append(service).append("\">")
        for ((k, v) in out) append('<').append(k).append('>').append(esc(v)).append("</").append(k).append('>')
        append("</u:").append(action).append("Response></s:Body></s:Envelope>")
    }

    fun soapFault(code: Int, text: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
        "<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
        "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode>" +
        "<errorDescription>${esc(text)}</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"

    /** Событие GENA: каждая переменная своим свойством. */
    fun propertySet(vars: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">")
        for ((k, v) in vars) append("<e:property><").append(k).append('>').append(esc(v)).append("</").append(k).append("></e:property>")
        append("</e:propertyset>")
    }

    /** Значение LastChange: ns «AVT/» или «RCS/». */
    fun lastChange(ns: String, vars: List<Pair<String, String>>): String = buildString {
        append("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/").append(ns).append("\"><InstanceID val=\"0\">")
        for ((k, v) in vars) {
            append('<').append(k)
            if (k == "Volume" || k == "Mute") append(" channel=\"Master\"")
            append(" val=\"").append(esc(v)).append("\"/>")
        }
        append("</InstanceID></Event>")
    }
}
