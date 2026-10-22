package com.xgimiru.player

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xgimiru.player.ui.tr

/** Настройки «Кино»: откуда брать торренты, память под кэш, движок для Лампы. */
class SettingsActivity : Activity() {

    private lateinit var list: LinearLayout
    private var state: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(80), dp(56), dp(80), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "Настройки"
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setPadding(dp(16), 0, 0, dp(12))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(list)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        build(null)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private val tick = object : Runnable {
        override fun run() {
            state?.text = stateText()
            handler.postDelayed(this, 1000)
        }
    }

    private fun stateText(): String = when {
        TorrEngine.isRunning -> "Встроенный движок работает: 127.0.0.1:${TorrEngine.port}"
        TorrEngine.lastError != null -> "Встроенный движок не запустился: ${TorrEngine.lastError}"
        else -> "Встроенный движок остановлен. Он запустится сам, когда откроете торрент."
    }

    private fun build(focus: Int?) {
        list.removeAllViews()
        state = null
        val available = TorrEngine.isAvailable(this)
        val builtin = TorrSettings.builtin(this)
        val ext = TorrSettings.external(this)

        header("Торренты")
        note("Magnet-ссылки и .torrent-файлы «Кино» открывает само. Видео из Лампы играет как раньше, " +
            "через тот TorrServe, что указан в Лампе.")
        if (available) {
            row("Встроенный движок", "TorrServer ${BuildConfig.TORRSERVER} внутри «Кино», отдельный TorrServe не нужен", builtin) {
                TorrSettings.setBuiltin(this, true)
            }
        } else {
            note("Встроенного движка нет в этой сборке «Кино».")
        }
        row("Внешний TorrServe", ext ?: "адрес не задан", !builtin) {
            if (ext == null) askAddress() else TorrSettings.setBuiltin(this, false)
        }
        row("Адрес внешнего TorrServe…", null, null) { askAddress() }
        // источники поиска (Jackett, Prowlarr, совместимые сервисы): свой экран
        row(com.xgimiru.player.ui.tr("Источники поиска торрентов…", "Torrent search sources…"),
            com.xgimiru.player.ui.tr("Jackett, Prowlarr или совместимый сервис", "Jackett, Prowlarr or a compatible service"), null) {
            startActivity(android.content.Intent(this, com.xgimiru.player.torrents.SourcesActivity::class.java))
        }

        if (available) {
            header("Память под кэш встроенного движка")
            val cur = TorrSettings.cacheMb(this)
            val ram = TorrSettings.totalRamMb(this)
            row("Авто: ${TorrSettings.autoCacheMb(this)} МБ", "по памяти устройства, %.1f ГБ".format(ram / 1024.0), cur == 0) {
                setCache(0)
            }
            for (mb in listOf(48, 64, 96, 128, 192, 256, 384, 512)) row("$mb МБ", null, cur == mb) { setCache(mb) }

            header("Для Лампы и других приложений")
            val shared = TorrSettings.shared(this)
            row("Выключено", "движок работает, только пока «Кино» показывает торрент", !shared) {
                TorrSettings.setShared(this, false)
                TorrService.release(this, TorrService.SHARED)
            }
            row("Включено: 127.0.0.1:8090", "движок работает всё время и запускается при включении. " +
                "В Лампе укажите адрес TorrServe 127.0.0.1:8090; отдельный TorrServe на этом порту выключите.", shared) {
                TorrSettings.setShared(this, true)
                TorrService.hold(this, TorrService.SHARED)
            }

            header("Состояние")
            state = note(stateText())
            note("TorrServer: GPL-3.0, github.com/YouROK/TorrServer. Журнал движка: " +
                "Android/data/$packageName/files/torrserver.log")
        }
        hdrSection()
        com.xgimiru.player.dlna.DlnaSettings.section(this, { header(it) }) { t, h, s, c -> row(t, h, s, c) }
        val target = focus?.let { list.getChildAt(it) }?.takeIf { it.isFocusable }
            ?: (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable && it.tag == "selected" }
        // после разметки: иначе фокус достаётся самому списку
        list.post { target?.requestFocus() }
    }

    /**
     * HDR, как в Kodi. Пункты видны, только если устройство это умеет: Dolby Vision — когда экран
     * и декодер знают DV (на Z9X нет: XGIMI выключила DV), HDR10+ — когда экран его принимает.
     */
    private fun hdrSection() {
        val caps = com.xgimiru.player.dovi.HdrPolicy.caps(this)
        val p = com.xgimiru.player.dovi.HdrPolicy.prefs(this)
        fun set(key: String, v: Boolean) = com.xgimiru.player.dovi.HdrPolicy.set(this, key, v)
        if (!caps.dvHardware && !caps.hdr10PlusDisplay) return
        header(tr("Видео HDR", "HDR video"))
        if (caps.dvHardware) {
            row(tr("Dolby Vision через проектор", "Dolby Vision on the projector"),
                if (p.dvHardware) tr("Dolby Vision обрабатывает сам проектор", "the projector processes Dolby Vision itself")
                else tr("выключено: Dolby Vision играет как HDR10", "off: Dolby Vision plays as HDR10"), p.dvHardware) {
                set("dvHardware", !p.dvHardware)
            }
            if (p.dvHardware && 8 in caps.dvProfiles && 7 !in caps.dvProfiles) {
                row(tr("Профиль 7 → 8.1", "Profile 7 → 8.1"),
                    tr("двухслойные раздачи (BD-remux) тоже идут через Dolby Vision, слой улучшения отбрасывается",
                        "dual-layer releases (BD remux) also go through Dolby Vision, the enhancement layer is dropped"),
                    p.p7to81) { set("dvP7to81", !p.p7to81) }
            }
            if (p.dvHardware) {
                row(tr("Убрать поля кадра (L5)", "Remove frame borders (L5)"),
                    tr("если проектор обрезает или сдвигает картинку в Dolby Vision", "if the projector crops or shifts Dolby Vision video"),
                    p.zeroL5) { set("dvZeroL5", !p.zeroL5) }
            }
        }
        if (caps.hdr10PlusDisplay) {
            row("HDR10+",
                if (p.hdr10Plus) tr("яркость подстраивается под каждую сцену", "brightness follows each scene")
                else tr("выключено: такие фильмы идут как HDR10", "off: these films play as HDR10"), p.hdr10Plus) {
                set("hdr10Plus", !p.hdr10Plus)
            }
        }
    }

    private fun setCache(mb: Int) {
        TorrSettings.setCacheMb(this, mb)
        if (TorrEngine.isRunning) Thread { runCatching { TorrEngine.applySettings(this) } }.start()
    }

    private fun askAddress() {
        val input = EditText(this).apply {
            setText(TorrSettings.external(this@SettingsActivity) ?: "127.0.0.1:8090")
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            selectAll()
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this, R.style.Lumen_Dialog)
            .setTitle("Адрес TorrServe")
            .setMessage("Например 127.0.0.1:8090 или 192.168.1.10:8090")
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val addr = TorrSettings.normalize(input.text.toString())
                TorrSettings.setExternal(this, addr)
                TorrSettings.setBuiltin(this, addr == null)
                build(null)
            }
            .setNeutralButton("Убрать адрес") { _, _ ->
                TorrSettings.setExternal(this, null)
                TorrSettings.setBuiltin(this, true)
                build(null)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun header(text: String) {
        list.addView(TextView(this).apply {
            this.text = text.uppercase()
            textSize = 14f
            letterSpacing = 0.14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(getColor(R.color.accent_warm))
            setPadding(dp(16), dp(if (list.childCount == 0) 8 else 30), dp(16), dp(8))
        })
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setLineSpacing(0f, 1.15f)
        setTextColor(getColor(R.color.text_faint))
        setPadding(dp(16), dp(6), dp(16), dp(8))
    }.also { list.addView(it) }

    /** selected == null: пункт-действие без отметки. */
    private fun row(text: String, hint: String?, selected: Boolean?, onClick: () -> Unit) {
        val on = selected == true
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            tag = if (on) "selected" else null
            background = getDrawable(R.drawable.item_bg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isFocusable = true
            isClickable = true
            addView(TextView(this@SettingsActivity).apply {
                this.text = when (selected) { null -> ""; true -> "●   "; false -> "      " } + text
                textSize = 20f
                setTextColor(if (on || selected == null) Color.WHITE else getColor(R.color.text_dim))
                typeface = if (on) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            })
            if (hint != null) addView(TextView(this@SettingsActivity).apply {
                this.text = hint
                textSize = 15f
                setTextColor(getColor(R.color.text_faint))
                setPadding(dp(if (selected == null) 0 else 34), dp(2), 0, 0)
            })
            setOnClickListener { v ->
                onClick()
                val idx = list.indexOfChild(v)
                handler.post { build(idx) }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

}
