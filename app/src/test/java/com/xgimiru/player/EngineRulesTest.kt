package com.xgimiru.player

import androidx.media3.common.PlaybackException
import com.xgimiru.player.EngineRules.Kind
import com.xgimiru.player.EngineRules.Plan
import com.xgimiru.player.EngineRules.Problem
import com.xgimiru.player.EngineRules.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRulesTest {
    private val media3 = Plan(Kind.MEDIA3, Why.NONE)

    @Test fun media3PlaysEveryLocalAndStreamedFile() {
        for (s in listOf("file", "content", "http", "https", "smb", "SMB", null)) assertEquals(s.toString(), media3, EngineRules.first(s))
    }

    @Test fun oldContainersStillStartOnMedia3() {
        // the file name never picks libVLC: an AVI or WMV goes to libVLC only after Media3 refused it
        assertEquals(media3, EngineRules.first(schemeOf("file:///mnt/usb/Old.Film.1999.avi")))
    }

    @Test fun onlyFoldersMedia3CannotReadStartOnLibVlc() {
        for (s in listOf("nfs", "ftp", "ftps", "sftp", "NFS")) assertEquals(Plan(Kind.VLC, Why.SHARE), EngineRules.first(s))
    }

    @Test fun unsupportedFormatFallsBackOnce() {
        for (code in listOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
            val p = EngineRules.problem(code)
            assertEquals(Problem.FORMAT, p)
            assertEquals(Plan(Kind.VLC, Why.FORMAT), EngineRules.afterError("file", p, Kind.MEDIA3, alreadyFellBack = false))
            assertNull(EngineRules.afterError("file", p, Kind.MEDIA3, alreadyFellBack = true))
        }
    }

    @Test fun networkAndDrmErrorsNeverSwitchEngines() {
        for (code in listOf(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)) {
            assertNull(EngineRules.afterError("https", EngineRules.problem(code), Kind.MEDIA3, false))
        }
        // a dropped HTTP connection is a read problem, but only an SMB server is worth a second engine
        val read = EngineRules.problem(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(Problem.READ, read)
        assertNull(EngineRules.afterError("http", read, Kind.MEDIA3, false))
        assertNull(EngineRules.afterError("file", read, Kind.MEDIA3, false))
    }

    @Test fun anSmbServerOurReaderCannotTalkToGoesToLibVlc() {
        val read = EngineRules.problem(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(Plan(Kind.VLC, Why.SMB_READ), EngineRules.afterError("smb", read, Kind.MEDIA3, false))
        assertNull(EngineRules.afterError("smb", read, Kind.MEDIA3, true))
    }

    @Test fun libVlcErrorsAreFinal() {
        assertNull(EngineRules.afterError("nfs", Problem.FORMAT, Kind.VLC, false))
        assertNull(EngineRules.afterError("file", Problem.FORMAT, Kind.VLC, false))
    }

    @Test fun missingDecoderForPictureOrSoundCountsAsUnsupported() {
        assertTrue(EngineRules.tracksUnsupported(hasVideo = true, videoOk = false, hasAudio = true, audioOk = true))
        assertTrue(EngineRules.tracksUnsupported(hasVideo = true, videoOk = true, hasAudio = true, audioOk = false))
        assertFalse(EngineRules.tracksUnsupported(hasVideo = true, videoOk = true, hasAudio = true, audioOk = true))
        // music without a picture, or a silent clip, is fine
        assertFalse(EngineRules.tracksUnsupported(hasVideo = false, videoOk = false, hasAudio = true, audioOk = true))
        assertFalse(EngineRules.tracksUnsupported(hasVideo = true, videoOk = true, hasAudio = false, audioOk = false))
    }

    private fun schemeOf(uri: String) = uri.substringBefore("://")
}
