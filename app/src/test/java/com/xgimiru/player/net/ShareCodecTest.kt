package com.xgimiru.player.net

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class ShareCodecTest {
    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val box = GcmBox(key().let { k -> { k } })

    private val legacy = """[{"n":"Films","u":"smb://nas/films/","user":"kamal","pass":"s3cret pass"},""" +
        """{"n":"","u":"smb://pc/","user":"guest","pass":""},{"n":"Music","u":"nfs://nas/music/","user":"","pass":""}]"""

    @Test fun plainPasswordsOfOlderBuildsAreReadAndMarkedForMigration() {
        val d = ShareCodec.decode(legacy, box)
        assertTrue(d.migrate)
        assertEquals(listOf("s3cret pass", null, null), d.shares.map { it.pass })
        assertEquals(listOf("kamal", "guest", null), d.shares.map { it.user })
    }

    @Test fun migratedListHoldsNoPlainPasswordAndReadsBackTheSame() {
        val d = ShareCodec.decode(legacy, box)
        val raw = ShareCodec.encode(d.shares, box)
        assertFalse(raw.contains("s3cret"))
        assertFalse(raw.contains("\"pass\""))
        val o = JSONArray(raw).getJSONObject(0)
        assertTrue(GcmBox.isSealed(o.getString("p")))
        val again = ShareCodec.decode(raw, box)
        assertFalse(again.migrate)
        assertEquals(d.shares, again.shares)
    }

    @Test fun entriesWithoutPasswordNeedNoMigrationAndGetNoSealedField() {
        val raw = """[{"n":"Music","u":"nfs://nas/music/","user":"","pass":""}]"""
        val d = ShareCodec.decode(raw, box)
        assertFalse(d.migrate)
        assertFalse(JSONArray(ShareCodec.encode(d.shares, box)).getJSONObject(0).has("p"))
    }

    @Test fun aPasswordSealedWithAnotherKeyIsDroppedButTheLoginStays() {
        val raw = ShareCodec.encode(listOf(Share("Films", "smb://nas/films/", "kamal", "pw")), GcmBox(key().let { k -> { k } }))
        val d = ShareCodec.decode(raw, box)
        assertFalse(d.migrate)
        assertEquals("kamal", d.shares[0].user)
        assertNull(d.shares[0].pass)
    }

    @Test fun everySealUsesAFreshIvAndTamperingIsRefused() {
        val a = box.seal("pw")
        val b = box.seal("pw")
        assertNotEquals(a, b)
        assertEquals("pw", box.open(a))
        val bytes = java.util.Base64.getDecoder().decode(a.removePrefix(GcmBox.PREFIX))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull(box.open(GcmBox.PREFIX + java.util.Base64.getEncoder().encodeToString(bytes)))
        assertNull(box.open("pw"))
        assertNull(box.open(GcmBox.PREFIX + "%%%"))
    }

    @Test fun aBoxThatCannotSealMakesEncodeFailSoNothingPlainIsWritten() {
        val broken = object : SecretBox {
            override fun seal(plain: String): String = throw IllegalStateException("no key store")
            override fun open(sealed: String): String? = null
        }
        val list = listOf(Share("Films", "smb://nas/films/", "kamal", "pw"))
        assertTrue(runCatching { ShareCodec.encode(list, broken) }.isFailure)
        val fallback = ShareCodec.encode(list.map { it.copy(pass = null) }, broken)
        assertFalse(fallback.contains("pw\""))
        assertEquals("kamal", ShareCodec.decode(fallback, broken).shares[0].user)
    }

    @Test fun printingAShareNeverShowsThePassword() {
        val s = Share("Films", "smb://nas/films/", "kamal", "s3cret")
        assertFalse(s.toString().contains("s3cret"))
    }

    @Test fun theLongestSavedPrefixGivesTheLogin() {
        val list = listOf(Share("", "smb://nas/", "guest", "a"), Share("Films", "smb://nas/films/", "kamal", "b"),
            Share("Other", "smb://pc/", "x", "c"))
        assertEquals("kamal", NetShares.login(list, "smb://nas/films/a.mkv")?.user)
        assertEquals("guest", NetShares.login(list, "smb://nas/music/b.flac")?.user)
        assertNull(NetShares.login(list, "smb://tv/x.mkv"))
    }
}
