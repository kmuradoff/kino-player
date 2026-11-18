package com.xgimiru.player.send

import java.security.MessageDigest
import java.security.SecureRandom

/** Браузер, которому разрешено отправлять видео. Хранится хэш токена, сам токен знает только браузер. */
class Device(val hash: String, val client: String, val created: Long)

/** Где лежат разрешённые устройства: в приложении [PrefsTokenStore], в тестах список в памяти. */
interface TokenStore {
    fun list(): List<Device>
    fun add(d: Device)
    fun remove(hash: String)
}

/**
 * Подключение браузера по коду с экрана и проверка токена.
 *
 * Код из четырёх цифр живёт [CODE_TTL_MS]; новый запрос заменяет его, но не раньше чем через
 * [RETRY_MS] (до этого возвращается прежний код, иначе расширение могло бы листать коды подряд). После [MAX_WRONG] неверных кодов подряд
 * подключение закрыто на [LOCK_MS]. Разрешённых устройств не больше [MAX_DEVICES]: новое
 * вытесняет самое старое. Токен: 32 случайных байта в hex; в хранилище только SHA-256.
 */
class Pairing(
    private val store: TokenStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    companion object {
        const val CODE_TTL_MS = 60_000L
        const val RETRY_MS = 5_000L
        const val MAX_WRONG = 5
        const val LOCK_MS = 5 * 60_000L
        const val MAX_DEVICES = 8

        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII)).toHex()

        private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    sealed class Start {
        class Code(val code: String, val client: String) : Start()
        /** Подключение закрыто после неверных кодов; повторить через [retryAfterSec]. */
        class Locked(val retryAfterSec: Long) : Start()
    }

    sealed class Confirm {
        class Ok(val token: String, val client: String) : Confirm()
        class Wrong(val attemptsLeft: Int) : Confirm()
        class Locked(val retryAfterSec: Long) : Confirm()
        /** Кода нет или истёк: нужно начать заново. */
        object NoCode : Confirm()
    }

    private class Pending(val code: String, val client: String, val issued: Long)

    private var pending: Pending? = null
    private var wrong = 0
    private var lockedUntil = 0L

    /** Код, который сейчас на экране, или null. */
    @get:Synchronized
    val shownCode: String?
        get() = pending?.takeIf { now() - it.issued < CODE_TTL_MS }?.code

    @Synchronized
    fun start(client: String): Start {
        val t = now()
        if (t < lockedUntil) return Start.Locked(secondsUntil(lockedUntil, t))
        // повтор в первые секунды оставляет прежний код: расширение не может листать коды подряд
        pending?.let { if (t - it.issued < RETRY_MS) return Start.Code(it.code, client) }
        val code = random.nextInt(10_000).toString().padStart(4, '0')
        pending = Pending(code, client, t)
        return Start.Code(code, client)
    }

    @Synchronized
    fun confirm(code: String): Confirm {
        val t = now()
        if (t < lockedUntil) return Confirm.Locked(secondsUntil(lockedUntil, t))
        val p = pending?.takeIf { t - it.issued < CODE_TTL_MS }
        if (p == null) {
            pending = null
            return Confirm.NoCode
        }
        if (!MessageDigest.isEqual(p.code.toByteArray(), code.toByteArray())) {
            wrong++
            if (wrong >= MAX_WRONG) {
                wrong = 0
                pending = null
                lockedUntil = t + LOCK_MS
                return Confirm.Locked(secondsUntil(lockedUntil, t))
            }
            return Confirm.Wrong(MAX_WRONG - wrong)
        }
        wrong = 0
        pending = null
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val token = bytes.toHex()
        val all = store.list()
        if (all.size >= MAX_DEVICES) all.sortedBy { it.created }.take(all.size - MAX_DEVICES + 1).forEach { store.remove(it.hash) }
        store.add(Device(hash(token), p.client, t))
        return Confirm.Ok(token, p.client)
    }

    /** Код больше не нужен (подключение отменили или показ закончился). */
    @Synchronized
    fun cancel() { pending = null }

    /** Есть ли хоть одно разрешённое устройство (поле paired в /lumen/info). */
    fun anyDevice(): Boolean = store.list().isNotEmpty()

    /** Токен из заголовка Authorization разрешён? Сравнение хэшей за постоянное время. */
    fun authorized(authorization: String?): Boolean {
        val token = SendProtocol.bearer(authorization) ?: return false
        val h = hash(token).toByteArray()
        return store.list().any { MessageDigest.isEqual(it.hash.toByteArray(), h) }
    }

    private fun secondsUntil(at: Long, t: Long) = ((at - t + 999) / 1000).coerceAtLeast(1)
}
