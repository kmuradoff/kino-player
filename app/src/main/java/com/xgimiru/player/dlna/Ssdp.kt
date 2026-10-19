package com.xgimiru.player.dlna

import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.Locale
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SSDP: объявляет устройство в группе 239.255.255.250:1900 (NOTIFY раз в 30 секунд) и отвечает
 * на поиск (M-SEARCH). Все отправки идут через [scheduler] (один поток), приём блокирующий в
 * своём потоке, так что без трафика ничего не крутится. При смене сети сокет пересоздаётся
 * на новых интерфейсах ([refresh]).
 */
class Ssdp(
    private val uuid: String,
    private val httpPort: () -> Int,
    private val scheduler: ScheduledExecutorService,
) {

    /** Интерфейс с IPv4-адресом и длиной префикса, чтобы отвечать адресом из сети спрашивающего. */
    private data class Iface(val ni: NetworkInterface, val addr: Inet4Address, val prefix: Int)

    private val group: InetAddress = InetAddress.getByName("239.255.255.250")
    private val types = listOf("upnp:rootdevice", "uuid:$uuid", Upnp.DEVICE, Upnp.AVT, Upnp.RCS, Upnp.CMS)
    private var socket: MulticastSocket? = null
    private var ifaces: List<Iface> = emptyList()
    private var scanned: List<Iface> = emptyList()
    private val pending = AtomicInteger()
    @Volatile private var stopped = false

    /** Адрес для уведомления пользователя: первый подходящий интерфейс. */
    val address: String? get() = ifaces.firstOrNull()?.addr?.hostAddress

    /** Вызывать на потоке [scheduler]. */
    fun start() {
        open()
        scheduler.scheduleWithFixedDelay({ runCatching { tick() } }, 30, 30, TimeUnit.SECONDS)
    }

    /** Раз в 30 секунд: сменилась сеть, пересоздать; иначе просто объявиться. */
    private fun tick() {
        if (stopped) return
        if (scan() != scanned) open() else announce(true)
    }

    /** Сеть поменялась (Wi-Fi переподключился, другой адрес): проверить и, если надо, пересоздать сокет. */
    fun refresh() {
        if (stopped) return
        if (scan() != scanned || socket == null) open()
        else announce(true)
    }

    fun stop() {
        stopped = true
        runCatching { announce(false) }
        runCatching { socket?.close() }
        socket = null
    }

    private fun open() {
        runCatching { socket?.close() }
        socket = null
        scanned = scan()
        ifaces = scanned
        if (ifaces.isEmpty()) return
        val s = MulticastSocket(null as SocketAddress?)
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(1900))
            s.timeToLive = 2
            val joined = ifaces.filter { runCatching { s.joinGroup(InetSocketAddress(group, 1900), it.ni) }.isSuccess }
            if (joined.isEmpty()) { s.close(); return }
            ifaces = joined
        } catch (e: Exception) {
            runCatching { s.close() }
            return
        }
        socket = s
        Thread({ receive(s) }, "lumen-dlna-ssdp").apply { isDaemon = true }.start()
        // старые записи в кэшах контроллеров (другой порт или адрес) сбросить, потом объявиться
        announce(false)
        announce(true)
        scheduler.schedule({ if (socket === s) runCatching { announce(true) } }, 1500, TimeUnit.MILLISECONDS)
    }

    private fun scan(): List<Iface> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { ni ->
                val n = ni.name.lowercase(Locale.US)
                ni.isUp && !ni.isLoopback && !ni.isPointToPoint && ni.supportsMulticast() &&
                    listOf("rmnet", "tun", "dummy", "p2p", "ccmni", "ppp").none { n.startsWith(it) }
            }
            .flatMap { ni ->
                ni.interfaceAddresses.mapNotNull { ia ->
                    (ia.address as? Inet4Address)?.takeIf { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        ?.let { Iface(ni, it, ia.networkPrefixLength.toInt()) }
                }.take(1)
            }
            .sortedBy { if (it.ni.name.startsWith("wlan")) 0 else if (it.ni.name.startsWith("eth")) 1 else 2 }
    }.getOrDefault(emptyList())

    private fun location(a: Inet4Address) = "http://${a.hostAddress}:${httpPort()}/description.xml"

    private fun usn(nt: String) = if (nt.startsWith("uuid:")) nt else "uuid:$uuid::$nt"

    /** NOTIFY ssdp:alive или ssdp:byebye на всех интерфейсах. */
    private fun announce(alive: Boolean) {
        val s = socket ?: return
        for (i in ifaces) {
            runCatching { s.networkInterface = i.ni }
            for (nt in types) {
                val msg = buildString {
                    append("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n")
                    if (alive) {
                        append("CACHE-CONTROL: max-age=1800\r\n")
                        append("LOCATION: ").append(location(i.addr)).append("\r\n")
                        append("SERVER: ").append(HttpServer.SERVER).append("\r\n")
                    }
                    append("NT: ").append(nt).append("\r\n")
                    append("NTS: ").append(if (alive) "ssdp:alive" else "ssdp:byebye").append("\r\n")
                    append("USN: ").append(usn(nt)).append("\r\n\r\n")
                }
                val b = msg.toByteArray(Charsets.UTF_8)
                runCatching { s.send(DatagramPacket(b, b.size, group, 1900)) }
            }
        }
    }

    private fun receive(s: MulticastSocket) {
        val buf = ByteArray(2048)
        while (!s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (e: Exception) { if (s.isClosed) return else continue }
            if (p.length >= buf.size) continue
            val text = String(p.data, 0, p.length, Charsets.UTF_8)
            if (!text.startsWith("M-SEARCH", true)) continue
            runCatching { search(text, p.address, p.port) }
        }
    }

    private fun search(text: String, from: InetAddress, port: Int) {
        val h = HashMap<String, String>()
        for (l in text.split("\r\n", "\n").drop(1)) {
            val c = l.indexOf(':')
            if (c > 0) h[l.substring(0, c).trim().lowercase(Locale.US)] = l.substring(c + 1).trim()
        }
        if (h["man"]?.trim('"') != "ssdp:discover") return
        val st = h["st"] ?: return
        val match = when {
            st == "ssdp:all" -> types
            st in types -> listOf(st)
            else -> return
        }
        // MX: ответ разносится случайно в пределах MX (не больше секунды), чтобы не душить сеть
        val mx = h["mx"]?.toIntOrNull()?.coerceIn(0, 5) ?: 0
        val delay = if (mx == 0) 0L else (Math.random() * minOf(mx, 1) * 900).toLong()
        if (pending.incrementAndGet() > 20) { pending.decrementAndGet(); return }
        scheduler.schedule({
            pending.decrementAndGet()
            runCatching { reply(from, port, match) }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun reply(to: InetAddress, port: Int, sts: List<String>) {
        val s = socket ?: return
        val local = pick(to) ?: return
        for (st in sts) {
            val msg = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nDATE: ${HttpServer.httpDate()}\r\nEXT:\r\n" +
                "LOCATION: ${location(local)}\r\nSERVER: ${HttpServer.SERVER}\r\nST: $st\r\nUSN: ${usn(st)}\r\n\r\n"
            val b = msg.toByteArray(Charsets.UTF_8)
            s.send(DatagramPacket(b, b.size, to, port))
        }
    }

    /** Свой адрес в той же подсети, что и спрашивающий; иначе основной. */
    private fun pick(to: InetAddress): Inet4Address? {
        val t = (to as? Inet4Address)?.address ?: return ifaces.firstOrNull()?.addr
        fun same(i: Iface): Boolean {
            val a = i.addr.address
            var bits = i.prefix
            for (k in 0 until 4) {
                if (bits <= 0) return true
                val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
                if ((a[k].toInt() and mask) != (t[k].toInt() and mask)) return false
                bits -= 8
            }
            return true
        }
        return (ifaces.firstOrNull { same(it) } ?: ifaces.firstOrNull())?.addr
    }
}
