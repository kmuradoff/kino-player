package com.xgimiru.player.send

import com.xgimiru.player.StreamRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendProtocolTest {

    @Test fun httpUrls() {
        assertTrue(SendProtocol.isHttpUrl("https://cdn.example.com/v/master.m3u8?token=a|b{c}"))
        assertTrue(SendProtocol.isHttpUrl("http://192.168.1.5:8080/film.mp4"))
        assertTrue(SendProtocol.isHttpUrl("HTTPS://Example.com"))
        assertTrue(SendProtocol.isHttpUrl("http://[2001:db8::1]:443/a.mpd"))
        assertTrue(SendProtocol.isHttpUrl("https://user:pass@host/a"))
    }

    @Test fun badUrls() {
        assertFalse(SendProtocol.isHttpUrl(null))
        assertFalse(SendProtocol.isHttpUrl(""))
        assertFalse(SendProtocol.isHttpUrl("file:///sdcard/a.mp4"))
        assertFalse(SendProtocol.isHttpUrl("content://x/y"))
        assertFalse(SendProtocol.isHttpUrl("javascript:alert(1)"))
        assertFalse(SendProtocol.isHttpUrl("blob:https://site/1234"))
        assertFalse(SendProtocol.isHttpUrl("https:///path"))
        assertFalse(SendProtocol.isHttpUrl("https://host/a b"))
        assertFalse(SendProtocol.isHttpUrl("https://host/a\r\nX: y"))
        assertFalse(SendProtocol.isHttpUrl("https://host:99999/a"))
        assertFalse(SendProtocol.isHttpUrl("https://host:abc/a"))
        assertFalse(SendProtocol.isHttpUrl("https://h/" + "a".repeat(SendProtocol.MAX_URL)))
    }

    @Test fun playFull() {
        val p = SendProtocol.parsePlay("""
            {"url":"https://cdn.site/hls/master.m3u8","type":"HLS",
             "headers":{"Referer":"https://site/watch","Origin":"https://site","User-Agent":"Mozilla/5.0","Range":"bytes=0-","X-Bad":"a\nb","N":5},
             "title":"  Фильм\u0007 ","pageUrl":"https://site/watch","position":83.5,
             "subtitles":[{"url":"https://site/ru.vtt","lang":"ru","label":"Русские"},{"url":"blob:https://site/x"},"junk"]}
        """.trimIndent())
        assertEquals("https://cdn.site/hls/master.m3u8", p.url)
        assertEquals("hls", p.type)
        assertEquals(StreamRequest.MIME_HLS, p.mime)
        assertEquals(mapOf("Referer" to "https://site/watch", "Origin" to "https://site", "User-Agent" to "Mozilla/5.0"), p.headers)
        assertEquals("Фильм", p.title)
        assertEquals("https://site/watch", p.pageUrl)
        assertEquals(83_500L, p.positionMs)
        assertEquals(1, p.subtitles.size)
        assertEquals("ru", p.subtitles[0].lang)
        assertEquals("Русские", p.subtitles[0].label)
    }

    @Test fun playDefaults() {
        val p = SendProtocol.parsePlay("""{"url":"https://a.b/film.mp4","title":null,"position":-3,"pageUrl":"chrome://x"}""")
        assertEquals("auto", p.type)
        assertNull(p.mime)
        assertNull(p.title)
        assertNull(p.pageUrl)
        assertEquals(0L, p.positionMs)
        assertTrue(p.headers.isEmpty())
        assertEquals(StreamRequest.MIME_DASH, SendProtocol.parsePlay("""{"url":"https://a/m","type":"dash"}""").mime)
        assertNull(SendProtocol.parsePlay("""{"url":"https://a/m.m3u8","type":"progressive"}""").mime)
    }

    @Test fun playRejects() {
        bad("""{"url":"file:///a.mp4"}""")
        bad("""{"url":"https://a/b","type":"rtmp"}""")
        bad("""{"type":"hls"}""")
        bad("""{"url":5}""")
        bad("not json")
        bad("[1,2]")
        bad("")
        val many = (1..SendProtocol.MAX_HEADERS + 1).joinToString(",") { "\"H$it\":\"v\"" }
        bad("""{"url":"https://a/b","headers":{$many}}""")
    }

    @Test fun control() {
        val seek = SendProtocol.parseControl("""{"action":"seek","position":12.25}""")
        assertEquals("seek", seek.action)
        assertEquals(12_250L, seek.positionMs)
        assertEquals("pause", SendProtocol.parseControl("""{"action":"PAUSE"}""").action)
        assertNull(SendProtocol.parseControl("""{"action":"stop"}""").positionMs)
        badControl("""{"action":"seek"}""")
        badControl("""{"action":"seek","position":"10"}""")
        badControl("""{"action":"eject"}""")
    }

    @Test fun pairBodies() {
        assertEquals("Chrome на ноутбуке", SendProtocol.parsePairStart("""{"client":" Chrome на ноутбуке\n"}"""))
        assertEquals("Браузер", SendProtocol.parsePairStart(""))
        assertEquals("Браузер", SendProtocol.parsePairStart("""{"client":"  "}"""))
        assertEquals(SendProtocol.MAX_CLIENT, SendProtocol.parsePairStart("""{"client":"${"x".repeat(100)}"}""").length)
        assertEquals("0042", SendProtocol.parsePairConfirm("""{"code":"0042"}"""))
        for (b in listOf("""{"code":"42"}""", """{"code":1234}""", """{"code":"12a4"}""", """{"code":"12345"}""", "{}")) {
            try { SendProtocol.parsePairConfirm(b); throw AssertionError(b) } catch (_: SendProtocol.BadRequest) {}
        }
    }

    @Test fun origins() {
        assertTrue(SendProtocol.originAllowed(null))
        assertTrue(SendProtocol.originAllowed("chrome-extension://abcdefghijklmnop"))
        assertTrue(SendProtocol.originAllowed("moz-extension://1234-5678"))
        assertFalse(SendProtocol.originAllowed("https://evil.example"))
        assertFalse(SendProtocol.originAllowed("null"))
        assertFalse(SendProtocol.originAllowed("http://localhost:3000"))
    }

    @Test fun bearer() {
        val t = "ab".repeat(32)
        assertEquals(t, SendProtocol.bearer("Bearer $t"))
        assertEquals(t, SendProtocol.bearer("bearer  ${t.uppercase()} "))
        assertNull(SendProtocol.bearer(null))
        assertNull(SendProtocol.bearer(t))
        assertNull(SendProtocol.bearer("Basic $t"))
        assertNull(SendProtocol.bearer("Bearer ${t.dropLast(1)}"))
        assertNull(SendProtocol.bearer("Bearer ${t.dropLast(1)}g"))
    }

    private fun bad(body: String) {
        try { SendProtocol.parsePlay(body); throw AssertionError("accepted: $body") } catch (_: SendProtocol.BadRequest) {}
    }

    private fun badControl(body: String) {
        try { SendProtocol.parseControl(body); throw AssertionError("accepted: $body") } catch (_: SendProtocol.BadRequest) {}
    }
}
