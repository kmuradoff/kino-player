package com.xgimiru.player.dlna

import android.os.Handler
import android.os.Looper

/**
 * Связь DLNA-рендерера с открытым экраном просмотра (плеер или фото) внутри процесса.
 * Экран регистрируется через [attach], раз в полсекунды сообщает, что играет ([report]),
 * и выполняет команды с телефона ([command]). Регистрация и команды живут в главном потоке,
 * снимок состояния ([snapshot]) читается из любого.
 */
object PlayerBridge {

    interface Controls {
        fun remotePlay()
        fun remotePause()
        fun remoteSeek(ms: Long)
        fun remoteStop()
    }

    enum class State { LOADING, PLAYING, PAUSED, STOPPED }

    /** Что показывает экран сейчас. uri: адрес, с которым экран открыли. */
    class Snapshot(val uri: String?, val state: State, val position: Long, val duration: Long)

    private val main = Handler(Looper.getMainLooper())
    private var controls: Controls? = null

    @Volatile
    var snapshot = Snapshot(null, State.STOPPED, 0, 0)
        private set

    /** Состояние поменялось (вызывается в главном потоке). */
    @Volatile
    var onChange: (() -> Unit)? = null

    /** Файл досмотрен до конца и экран закрылся сам (вызывается в главном потоке). */
    @Volatile
    var onEnded: ((uri: String?) -> Unit)? = null

    /** Новый экран вытесняет прежний: рендерер играет что-то одно. */
    fun attach(c: Controls) {
        val old = controls
        controls = c
        if (old != null && old !== c) runCatching { old.remoteStop() }
    }

    fun detach(c: Controls, ended: Boolean) {
        if (controls !== c) return
        controls = null
        val s = snapshot
        snapshot = Snapshot(s.uri, State.STOPPED, s.position, s.duration)
        onChange?.invoke()
        if (ended) onEnded?.invoke(s.uri)
    }

    fun report(c: Controls, uri: String?, state: State, position: Long, duration: Long) {
        if (controls !== c) return
        val old = snapshot
        snapshot = Snapshot(uri, state, position.coerceAtLeast(0), duration.coerceAtLeast(0))
        if (old.state != state || old.uri != uri || (old.duration > 0) != (duration > 0)) onChange?.invoke()
    }

    /** Команда открытому экрану. false: экрана нет. */
    fun command(block: (Controls) -> Unit): Boolean {
        if (snapshot.state == State.STOPPED) return false
        main.post { controls?.let { runCatching { block(it) } } }
        return true
    }
}
