package com.xgimiru.player.send

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import com.xgimiru.player.R
import com.xgimiru.player.ui.tr
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Разрешённые браузеры в настройках приложения (prefs "send", поле devices: JSON-массив). */
class PrefsTokenStore(ctx: Context) : TokenStore {
    private val prefs = ctx.applicationContext.getSharedPreferences("send", Context.MODE_PRIVATE)

    @Synchronized
    override fun list(): List<Device> = runCatching {
        val arr = JSONArray(prefs.getString("devices", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val h = o.optString("hash").takeIf { it.length == 64 } ?: return@mapNotNull null
            Device(h, o.optString("client", "Браузер"), o.optLong("created"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    override fun add(d: Device) = save(list().filter { it.hash != d.hash } + d)

    @Synchronized
    override fun remove(hash: String) = save(list().filter { it.hash != hash })

    private fun save(all: List<Device>) {
        val arr = JSONArray()
        for (d in all) arr.put(JSONObject().put("hash", d.hash).put("client", d.client).put("created", d.created))
        prefs.edit().putString("devices", arr.toString()).apply()
    }
}

/** Раздел настроек «Устройства, которым разрешено отправлять видео». */
object SendSettings {

    /**
     * header, note и row: функции SettingsActivity (row: текст, подсказка, отмечен ли пункт,
     * действие), refresh: перерисовать экран после удаления.
     */
    fun section(
        a: Activity,
        header: (String) -> Unit,
        note: (String) -> Unit,
        row: (String, String?, Boolean?, () -> Unit) -> Unit,
        refresh: () -> Unit,
    ) {
        val store = PrefsTokenStore(a)
        val devices = store.list().sortedByDescending { it.created }
        header(tr("Устройства, которым разрешено отправлять видео", "Devices allowed to send videos"))
        if (devices.isEmpty()) {
            note(tr("Пока никого. Нажмите в браузере на компьютере кнопку расширения «Отправить на Lumen Play» " +
                "и введите код, который появится на экране.",
                "Nobody yet. Press the \"Send to Lumen Play\" extension button in the browser on your computer " +
                    "and enter the code shown on screen."))
            return
        }
        val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
        for (d in devices) {
            row(d.client, tr("подключено ${fmt.format(Date(d.created))}. Нажмите, чтобы убрать",
                "connected ${fmt.format(Date(d.created))}. Press to remove"), null) {
                AlertDialog.Builder(a, R.style.Lumen_Dialog)
                    .setTitle(tr("Убрать «${d.client}»?", "Remove \"${d.client}\"?"))
                    .setMessage(tr("Отправлять видео с этого браузера можно будет только после нового кода.",
                        "This browser will need a new code to send videos again."))
                    .setPositiveButton(tr("Убрать", "Remove")) { _, _ ->
                        store.remove(d.hash)
                        refresh()
                    }
                    .setNegativeButton(tr("Отмена", "Cancel"), null)
                    .show()
            }
        }
    }
}
