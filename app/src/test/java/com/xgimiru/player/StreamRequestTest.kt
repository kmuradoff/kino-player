package com.xgimiru.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamRequestTest {

    @Test fun mimeFromIntentType() {
        assertEquals(StreamRequest.MIME_HLS, StreamRequest.streamMime("application/x-mpegURL", "/v/play"))
        assertEquals(StreamRequest.MIME_HLS, StreamRequest.streamMime("application/vnd.apple.mpegurl", null))
        assertEquals(StreamRequest.MIME_DASH, StreamRequest.streamMime("application/dash+xml", "/a"))
    }

    @Test fun mimeFromExtension() {
        assertEquals(StreamRequest.MIME_HLS, StreamRequest.streamMime("video/mp4", "/hls/Master.M3U8"))
        assertEquals(StreamRequest.MIME_DASH, StreamRequest.streamMime(null, "/dash/manifest.mpd"))
        assertEquals(StreamRequest.MIME_HLS, StreamRequest.streamMime(null, "/x/master.m3u8/index", null))
        assertEquals(StreamRequest.MIME_HLS, StreamRequest.streamMime(null, "/stream", "id=4&format=m3u8"))
        assertNull(StreamRequest.streamMime("video/mp4", "/film.mp4"))
        assertNull(StreamRequest.streamMime(null, "/film.mkv", "t=1"))
        assertNull(StreamRequest.streamMime(null, null))
    }

    @Test fun headersFromBothForms() {
        val h = StreamRequest.headers(
            arrayOf("Referer", "https://site/page", "Cookie", "a=1; b=2", "User-Agent", "Mozilla/5.0", "Range", "bytes=0-"),
            mapOf("referer" to "https://other/", "Origin" to "https://site"),
        )
        assertEquals(mapOf("Origin" to "https://site", "Referer" to "https://site/page", "Cookie" to "a=1; b=2",
            "User-Agent" to "Mozilla/5.0"), h)
        assertEquals("Mozilla/5.0", StreamRequest.userAgent(h))
        assertEquals("https://site/page", StreamRequest.referer(h))
        assertEquals(setOf("Origin", "Referer", "Cookie"), StreamRequest.withoutUserAgent(h).keys)
    }

    @Test fun badHeadersDropped() {
        val h = StreamRequest.headers(arrayOf("Bad Name", "x", "X-Ok", "v\r\nInjected: 1", "Odd"), null)
        assertEquals(emptyMap<String, String>(), h)
        assertEquals(StreamRequest.DEFAULT_USER_AGENT, StreamRequest.userAgent(emptyMap()))
    }
}
