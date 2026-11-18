package com.xgimiru.player.net

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * smb:// for Media3: the file is read through [Smb] with a 1 MB read-ahead window, so a film on a NAS plays
 * through the same engine as a local file (Dolby Vision, passthrough, tunnelling). A server the reader cannot
 * talk to fails with an IO error, and the player falls back to libVLC for that file ([com.xgimiru.player.EngineRules]).
 */
@UnstableApi
class SmbDataSource : BaseDataSource(true) {
    private var reader: Smb.Reader? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var left = 0L
    private var opened = false
    private val window = ByteArray(WINDOW)
    private var winStart = 0L
    private var winLen = 0

    override fun open(spec: DataSpec): Long {
        uri = spec.uri
        transferInitializing(spec)
        val r = try { Smb.open(spec.uri.toString()) } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        reader = r
        if (spec.position > r.length) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        pos = spec.position
        left = if (spec.length != C.LENGTH_UNSET.toLong()) minOf(spec.length, r.length - pos) else r.length - pos
        winLen = 0
        opened = true
        transferStarted(spec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left <= 0) return C.RESULT_END_OF_INPUT
        if (pos < winStart || pos >= winStart + winLen) fill()
        if (winLen == 0) return C.RESULT_END_OF_INPUT
        val at = (pos - winStart).toInt()
        val n = minOf(length.toLong(), (winLen - at).toLong(), left).toInt()
        System.arraycopy(window, at, buffer, offset, n)
        pos += n; left -= n
        bytesTransferred(n)
        return n
    }

    private fun fill() {
        val r = reader ?: return
        val want = minOf(WINDOW.toLong(), left).toInt()
        var got = 0
        try {
            while (got < want) {
                val n = r.read(pos + got, window, got, want - got)
                if (n <= 0) break
                got += n
            }
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        winStart = pos
        winLen = got
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        reader?.close()
        reader = null
        uri = null
        winLen = 0
        if (opened) { opened = false; transferEnded() }
    }

    companion object {
        private const val WINDOW = 1 shl 20
    }
}

/** Picks the data source by the link: smb:// goes to [SmbDataSource], everything else to Media3's own. */
@UnstableApi
class RoutingDataSource(private val fallback: DataSource) : DataSource {
    private val smb = SmbDataSource()
    private var current: DataSource? = null

    override fun addTransferListener(l: TransferListener) { smb.addTransferListener(l); fallback.addTransferListener(l) }

    override fun open(spec: DataSpec): Long {
        val ds = if (spec.uri.scheme.equals("smb", true)) smb else fallback
        current = ds
        return ds.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int) = current!!.read(buffer, offset, length)
    override fun getUri(): Uri? = current?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
    override fun close() { try { current?.close() } finally { current = null } }

    class Factory(private val inner: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(inner.createDataSource())
    }
}
