package com.xgimiru.player.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** The HTTP server under the DLNA renderer and the browser send receiver: start, answer, stop. */
class HttpServerTest {
    private fun get(port: Int, path: String): String = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 3000
        s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        s.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    @Test fun answersWhileRunningAndClosesThePortOnStop() {
        val srv = HttpServer({ r -> HttpServer.Response(200, "hello ${r.path}".toByteArray(), "text/plain") }, "test")
        srv.start(0)
        val port = srv.port
        assertTrue(port > 0)
        val res = get(port, "/desc.xml")
        assertTrue(res, res.startsWith("HTTP/1.1 200"))
        assertTrue(res, res.endsWith("hello /desc.xml"))
        srv.stop()
        assertEquals(0, srv.port)
        Thread.sleep(50)
        val refused = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) } }
        assertTrue(refused.exceptionOrNull() is ConnectException)
    }

    @Test fun aBusyPreferredPortFallsBackToAFreeOne() {
        ServerSocket(0).use { busy ->
            val srv = HttpServer({ HttpServer.Response(204) }, "test")
            srv.start(busy.localPort)
            assertNotEquals(busy.localPort, srv.port)
            assertTrue(get(srv.port, "/").startsWith("HTTP/1.1 204"))
            srv.stop()
        }
    }

    @Test fun aFailingHandlerGivesAServerErrorNotACrash() {
        val srv = HttpServer({ throw IllegalStateException("boom") }, "test")
        srv.start(0)
        assertTrue(get(srv.port, "/x").startsWith("HTTP/1.1 500"))
        srv.stop()
    }

    @Test fun theDeviceDescriptionCarriesTheEscapedNameAndTheStableId() {
        val xml = Descriptions.device("1234-abcd", "Lumen Play on Kamal's <Z9X>", "2.0")
        assertTrue(xml.contains("uuid:1234-abcd"))
        assertTrue(xml, xml.contains("Lumen Play on Kamal&apos;s &lt;Z9X&gt;") || xml.contains("Lumen Play on Kamal's &lt;Z9X&gt;"))
        assertTrue(xml.contains("MediaRenderer"))
    }
}
