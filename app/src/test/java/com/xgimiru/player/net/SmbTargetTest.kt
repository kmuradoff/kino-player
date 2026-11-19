package com.xgimiru.player.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbTargetTest {
    @Test fun splitsServerShareAndPath() {
        val t = SmbTarget.parse("smb://192.168.1.20/Video/Сериалы/Сезон%202/S02E22.mkv")!!
        assertEquals("192.168.1.20", t.host)
        assertEquals(445, t.port)
        assertEquals("Video", t.share)
        assertEquals("Сериалы\\Сезон 2\\S02E22.mkv", t.path)
        assertNull(t.user)
    }

    @Test fun readsLoginDomainAndPort() {
        val t = SmbTarget.parse("smb://HOME%3Bkamal:p%40ss%2Bword@nas:1445/films/")!!
        assertEquals("kamal", t.user)
        assertEquals("p@ss+word", t.pass)
        assertEquals("HOME", t.domain)
        assertEquals(1445, t.port)
        assertEquals("films", t.share)
        assertEquals("", t.path)
        assertEquals("nas:1445|HOME|kamal", t.sessionKey)
    }

    @Test fun plusStaysPlusAndBrokenEscapesStay() {
        assertEquals("a+b %zz 100%", SmbTarget.pct("a+b%20%zz%20100%"))
        assertEquals("Москва", SmbTarget.pct("%D0%9C%D0%BE%D1%81%D0%BA%D0%B2%D0%B0"))
    }

    @Test fun refusesWhatIsNotAShare() {
        assertNull(SmbTarget.parse("smb://nas"))
        assertNull(SmbTarget.parse("smb://nas/"))
        assertNull(SmbTarget.parse("nfs://nas/films/a.mkv"))
    }

    @Test fun printsNoPassword() {
        assertEquals(false, SmbTarget.parse("smb://u:secret@nas/f/a.mkv").toString().contains("secret"))
    }
}
