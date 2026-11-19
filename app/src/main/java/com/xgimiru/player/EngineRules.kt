package com.xgimiru.player

import androidx.media3.common.PlaybackException

/**
 * Which engine plays a link. Media3 always plays first, libVLC only does what Media3 cannot:
 *  - network folders Media3 has no reader for (NFS, FTP, SFTP),
 *  - an SMB server our SMB reader cannot talk to (SMB1 only, refused login),
 *  - a file whose container or codec Media3 reports as unsupported (old AVI, RMVB, WMV ...).
 * A fallback happens once per file and the player says so in one line. Pure code, host tested.
 */
object EngineRules {
    enum class Kind { MEDIA3, VLC }

    /** Why libVLC plays: [NONE] for Media3. Each reason has its own one-line notice. */
    enum class Why { NONE, SHARE, FORMAT, SMB_READ }

    data class Plan(val kind: Kind, val why: Why)

    /** What went wrong in Media3, as far as the engine choice cares. */
    enum class Problem { FORMAT, READ, OTHER }

    /** Network folders that only libVLC can read. SMB goes through Media3 ([com.xgimiru.player.net.SmbDataSource]). */
    val VLC_ONLY = setOf("nfs", "ftp", "ftps", "sftp")

    private val FORMAT_CODES = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    )

    private val READ_CODES = setOf(
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    )

    fun first(scheme: String?): Plan =
        if (scheme?.lowercase() in VLC_ONLY) Plan(Kind.VLC, Why.SHARE) else Plan(Kind.MEDIA3, Why.NONE)

    fun problem(errorCode: Int): Problem = when (errorCode) {
        in FORMAT_CODES -> Problem.FORMAT
        in READ_CODES -> Problem.READ
        else -> Problem.OTHER
    }

    /**
     * Media3 failed on a file with [scheme]. Returns the plan for one more try, or null: show the error.
     * Network and DRM errors never switch engines (libVLC would fail the same way and hide the cause).
     */
    fun afterError(scheme: String?, problem: Problem, current: Kind, alreadyFellBack: Boolean): Plan? = when {
        current != Kind.MEDIA3 || alreadyFellBack -> null
        problem == Problem.FORMAT -> Plan(Kind.VLC, Why.FORMAT)
        problem == Problem.READ && scheme?.lowercase() == "smb" -> Plan(Kind.VLC, Why.SMB_READ)
        else -> null
    }

    /**
     * Media3 opened the file but has no decoder for its picture or for any of its sound tracks: it would
     * play a black screen or silence instead of failing, so this counts as an unsupported format.
     */
    fun tracksUnsupported(hasVideo: Boolean, videoOk: Boolean, hasAudio: Boolean, audioOk: Boolean) =
        (hasVideo && !videoOk) || (hasAudio && !audioOk)
}
