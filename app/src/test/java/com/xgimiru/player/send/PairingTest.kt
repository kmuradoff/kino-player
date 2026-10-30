package com.xgimiru.player.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {

    private class MemStore : TokenStore {
        val devices = ArrayList<Device>()
        override fun list() = devices.toList()
        override fun add(d: Device) { devices += d }
        override fun remove(hash: String) { devices.removeAll { it.hash == hash } }
    }

    private var clock = 1_000_000L
    private val store = MemStore()
    private val pairing = Pairing(store, { clock })

    private fun code(client: String = "Chrome"): String = (pairing.start(client) as Pairing.Start.Code).code

    private fun wrongOf(c: String) = ((c.toInt() + 1) % 10_000).toString().padStart(4, '0')

    @Test fun pairAndAuthorize() {
        val c = code()
        assertEquals(4, c.length)
        assertEquals(c, pairing.shownCode)
        val ok = pairing.confirm(c) as Pairing.Confirm.Ok
        assertEquals(64, ok.token.length)
        assertEquals("Chrome", ok.client)
        assertTrue(pairing.authorized("Bearer ${ok.token}"))
        assertFalse(pairing.authorized("Bearer ${"0".repeat(64)}"))
        assertFalse(pairing.authorized(null))
        // хранится только хэш
        assertEquals(Pairing.hash(ok.token), store.devices.single().hash)
        assertFalse(store.devices.single().hash == ok.token)
        // код одноразовый
        assertTrue(pairing.confirm(c) is Pairing.Confirm.NoCode)
    }

    @Test fun removedTokenStopsWorking() {
        val ok = pairing.confirm(code()) as Pairing.Confirm.Ok
        store.remove(Pairing.hash(ok.token))
        assertFalse(pairing.authorized("Bearer ${ok.token}"))
    }

    @Test fun codeExpires() {
        val c = code()
        clock += Pairing.CODE_TTL_MS
        assertTrue(pairing.confirm(c) is Pairing.Confirm.NoCode)
        assertEquals(null, pairing.shownCode)
    }

    @Test fun repeatedStartKeepsCodeForAWhile() {
        val c = code()
        clock += Pairing.RETRY_MS - 1
        assertEquals(c, code("Firefox"))
        clock += 1
        assertNotNull(code())
        assertFalse(pairing.anyDevice())
    }

    @Test fun fiveWrongCodesLock() {
        val c = code()
        for (left in 4 downTo 1) assertEquals(left, (pairing.confirm(wrongOf(c)) as Pairing.Confirm.Wrong).attemptsLeft)
        val locked = pairing.confirm(wrongOf(c)) as Pairing.Confirm.Locked
        assertEquals(Pairing.LOCK_MS / 1000, locked.retryAfterSec)
        // даже верный код теперь не принимается, и новый не выдаётся
        assertTrue(pairing.confirm(c) is Pairing.Confirm.Locked)
        assertTrue(pairing.start("Chrome") is Pairing.Start.Locked)
        clock += Pairing.LOCK_MS - 1000
        assertTrue(pairing.start("Chrome") is Pairing.Start.Locked)
        clock += 1000
        val c2 = code()
        assertTrue(pairing.confirm(c2) is Pairing.Confirm.Ok)
    }

    @Test fun successResetsWrongCount() {
        val c = code()
        repeat(4) { pairing.confirm(wrongOf(c)) }
        assertTrue(pairing.confirm(c) is Pairing.Confirm.Ok)
        clock += Pairing.RETRY_MS
        val c2 = code()
        assertEquals(4, (pairing.confirm(wrongOf(c2)) as Pairing.Confirm.Wrong).attemptsLeft)
    }

    @Test fun atMostEightDevices() {
        val tokens = (1..10).map { i ->
            clock += Pairing.RETRY_MS
            (pairing.confirm(code("B$i")) as Pairing.Confirm.Ok).token
        }
        assertEquals(Pairing.MAX_DEVICES, store.devices.size)
        assertFalse(pairing.authorized("Bearer ${tokens[0]}"))
        assertFalse(pairing.authorized("Bearer ${tokens[1]}"))
        assertTrue(pairing.authorized("Bearer ${tokens[9]}"))
        assertEquals((3..10).map { "B$it" }, store.devices.map { it.client })
    }
}
