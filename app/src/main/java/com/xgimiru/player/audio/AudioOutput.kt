package com.xgimiru.player.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.xgimiru.player.settings.Passthrough

/**
 * The output media plays on right now, kept fresh by an AudioDeviceCallback. The audio sink asks [allows]
 * on the playback thread; [onChange] runs on the main thread with the old and the new route.
 */
@OptIn(UnstableApi::class)
class AudioOutput(ctx: Context, private val onChange: (old: AudioRoute.Device?, new: AudioRoute.Device?) -> Unit) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val media = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()

    @Volatile var route: AudioRoute.Device? = read(); private set

    /** The sink is configured with compressed input (passthrough), set by the sink on every configure. */
    @Volatile var passthrough = false

    private val watch = JumpWatch()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        Log.i(TAG, "route ${describe(route)}")
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun allows(format: Format, on: AudioRoute.Device? = route): Boolean {
        val mime = format.sampleMimeType
        if (!Passthrough.isCompressed(mime)) return true
        return AudioRoute.compressedAllowed(on, MimeTypes.getEncoding(mime!!, format.codecs), on?.type in broken)
    }

    /** A timestamp jump of the sink. True once passthrough counts as broken on this route: decode from now on. */
    fun jumped(nowMs: Long): Boolean {
        val r = route ?: return false
        if (!passthrough || r.type in broken || !watch.jump(nowMs)) return false
        broken = broken + r.type
        watch.reset()
        Log.w(TAG, "passthrough timestamps keep jumping on ${describe(r)}, decoding to PCM")
        return true
    }

    fun release() = am.unregisterAudioDeviceCallback(callback)

    private fun refresh() {
        val old = route
        val now = read()
        if (now == old) return
        route = now
        watch.reset()
        Log.i(TAG, "route ${describe(old)} -> ${describe(now)}")
        onChange(old, now)
    }

    private fun read(): AudioRoute.Device? {
        val routed = if (Build.VERSION.SDK_INT >= 33)
            runCatching { am.getAudioDevicesForAttributes(media).firstOrNull() }.getOrNull() else null
        return routed?.let(::device)
            ?: AudioRoute.guess(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(::device))
    }

    private fun device(d: AudioDeviceInfo) = AudioRoute.Device(d.type, d.encodings.toSet())

    private fun describe(d: AudioRoute.Device?) =
        d?.let { "type ${it.type}, encodings ${it.encodings.sorted()}, receiver ${AudioRoute.isReceiver(it)}" } ?: "none"

    companion object {
        private const val TAG = "LumenAudio"

        /** Route types where passthrough already looped in this run of the app. */
        @Volatile private var broken: Set<Int> = emptySet()
    }
}
