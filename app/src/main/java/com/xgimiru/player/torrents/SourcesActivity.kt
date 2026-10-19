package com.xgimiru.player.torrents

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xgimiru.player.ui.Backdrop
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.tr
import java.util.concurrent.Executors

/**
 * Источники поиска торрентов. Список: у каждого источника формат (Jackett API, Torznab или «авто»)
 * и итог последней проверки. Карточка источника: формат на выбор (по умолчанию «Автоматически»),
 * адрес (можно просто имя сайта), ключ API, название, кнопка «Проверить» с найденным форматом и
 * числом раздач. «Назад» из карточки сохраняет её и возвращает к списку.
 */
class SourcesActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val d = { v: Number -> Ui.dp(this, v) }
    private lateinit var content: FrameLayout
    private lateinit var heading: TextView
    private lateinit var lead: TextView

    // карточка источника
    private var editing: Source? = null
    private var draftType = Api.AUTO
    private var tested: Sources.Found? = null
    private var url: EditText? = null
    private var key: EditText? = null
    private var name: EditText? = null
    private var result: TextView? = null
    private var keyHint: TextView? = null
    private val typeRows = HashMap<Api, LinearLayout>()
    private var testing = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        val frame = FrameLayout(this)
        frame.addView(Backdrop(this), Ui.frame(-1, -1))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(56), d(40), d(56), d(16)) }
        heading = Ui.text(this, 34f, Ui.INK, Ui.serif())
        lead = Ui.text(this, 15f, Ui.INK2, Ui.sans(), 3).apply { setLineSpacing(0f, 1.3f) }
        col.addView(heading)
        col.addView(lead, Ui.linear(d(720), -2).apply { topMargin = d(10); bottomMargin = d(16) })
        content = FrameLayout(this)
        col.addView(content, Ui.linear(-1, 0, 1f))
        frame.addView(col, Ui.frame(-1, -1))
        setContentView(frame)
        showList(null)
    }

    override fun onDestroy() { io.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (editing != null) { saveDraft(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun scroll(inner: LinearLayout) = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false; clipToPadding = false
        addView(inner)
    }

    /** Строка-кнопка в стиле Lumen: светлая в фокусе. */
    private fun item(build: LinearLayout.() -> Unit, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(d(20), d(13), d(20), d(13))
        isFocusable = true; isClickable = true
        build()
        val texts = (0 until childCount).mapNotNull { getChildAt(it) as? TextView }
        val colors = texts.map { it.currentTextColor }
        setOnFocusChangeListener { _, f ->
            background = if (f) Ui.rounded(Ui.INK, d(12).toFloat()) else null
            texts.forEachIndexed { i, t -> t.setTextColor(if (f) Ui.GROUND else colors[i]) }
        }
        setOnClickListener { onClick() }
    }

    private fun typeName(t: Api?) = when (t) {
        Api.JSON -> tr("Jackett API (JSON)", "Jackett API (JSON)")
        Api.TORZNAB -> tr("Torznab (XML)", "Torznab (XML)")
        else -> tr("Автоматически", "Automatic")
    }

    private fun typeLine(s: Source): String = when {
        s.type == Api.AUTO && s.api != null -> tr("Авто: ", "Auto: ") + typeName(s.api)
        s.type == Api.AUTO -> tr("Авто: формат определится при проверке", "Auto: the format is found on the first check")
        else -> typeName(s.type)
    }

    // ── Список ──
    private fun showList(focusId: String?) {
        editing = null
        heading.text = tr("Источники поиска", "Search sources")
        lead.text = tr("Своих источников в Lumen Play нет. Добавьте свой Jackett, Prowlarr или совместимый сервис: поиск идёт по всем источникам сразу.",
            "Lumen Play has no sources of its own. Add your Jackett, Prowlarr or a compatible service: search uses all sources at once.")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var focus: View? = null
        for (s in Sources.list(this)) {
            val row = item({
                addView(Ui.text(this@SourcesActivity, 19f, Ui.INK, Ui.medium()).apply { text = s.label })
                addView(Ui.text(this@SourcesActivity, 14f, Ui.INK2).apply { text = typeLine(s) + "  ·  " + s.url },
                    Ui.linear(-1, -2).apply { topMargin = d(4) })
                addView(Ui.text(this@SourcesActivity, 14f, when (s.ok) { true -> Ui.ACCENT; false -> 0xFFE0866A.toInt(); else -> Ui.INK3 }, Ui.sans(), 2).apply {
                    text = when (s.ok) {
                        true -> tr("Работает", "Works") + (s.status?.let { ": " + it.substringAfter(": ", it) } ?: "")
                        false -> tr("Ошибка: ", "Error: ") + (s.status ?: "")
                        else -> tr("Ещё не проверен", "Not checked yet")
                    }
                }, Ui.linear(-1, -2).apply { topMargin = d(3) })
            }) { edit(s) }
            row.tag = s.id
            if (s.id == focusId) focus = row
            box.addView(row, Ui.linear(-1, -2).apply { bottomMargin = d(4) })
        }
        val add = Ui.pill(this, tr("+  Добавить источник", "+  Add a source")) { edit(null) }
        box.addView(add, Ui.linear(-2, -2).apply { topMargin = d(12); leftMargin = d(8) })
        content.removeAllViews()
        content.addView(scroll(box), Ui.frame(-1, -1))
        (focus ?: box.getChildAt(0)).let { v -> box.post { v?.requestFocus() } }
    }

    // ── Карточка источника ──
    private fun edit(s: Source?) {
        val src = s ?: Source()
        editing = src
        draftType = src.type
        tested = null
        heading.text = if (s == null) tr("Новый источник", "New source") else src.label
        lead.text = tr("Выберите формат или оставьте «Автоматически», введите адрес и нажмите «Проверить».",
            "Choose a format or keep Automatic, type the address and press Check.")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, d(24)) }

        box.addView(caption(tr("ФОРМАТ", "FORMAT")))
        typeRows.clear()
        for ((t, hint) in listOf(
            Api.AUTO to tr("Lumen Play сам найдёт JSON API или Torznab по адресу. Подходит почти всегда.",
                "Lumen Play finds the JSON API or Torznab at the address by itself. Fits almost always."),
            Api.JSON to tr("Поиск Jackett в формате JSON: свой Jackett или открытый совместимый сервис.",
                "Jackett search in JSON: your own Jackett or a public compatible service."),
            Api.TORZNAB to tr("Лента Torznab из Jackett («Copy Torznab Feed») или Prowlarr, обычно с ключом API.",
                "A Torznab feed from Jackett (Copy Torznab Feed) or Prowlarr, usually with an API key."))) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d(20), d(11), d(20), d(11))
                isFocusable = true; isClickable = true
                addView(Ui.text(this@SourcesActivity, 18f, Ui.INK, Ui.medium()))
                addView(Ui.text(this@SourcesActivity, 14f, Ui.INK3, Ui.sans(), 2).apply { text = hint }, Ui.linear(-1, -2).apply { topMargin = d(3); leftMargin = d(30) })
                setOnFocusChangeListener { _, _ -> paintTypes() }
                setOnClickListener { draftType = t; tested = null; paintTypes() }
            }
            typeRows[t] = row
            box.addView(row, Ui.linear(-1, -2).apply { bottomMargin = d(2) })
        }

        box.addView(caption(tr("АДРЕС", "ADDRESS")))
        url = field(src.url, tr("example.org или http://192.168.1.10:9117", "example.org or http://192.168.1.10:9117"), uri = true)
        box.addView(url, Ui.linear(d(640), -2))
        box.addView(caption(tr("КЛЮЧ API", "API KEY")))
        key = field(src.key ?: "", tr("Если сервер его просит", "If the server asks for one"))
        box.addView(key, Ui.linear(d(640), -2))
        keyHint = Ui.text(this, 13f, Ui.INK3, Ui.sans(), 2)
        box.addView(keyHint, Ui.linear(d(640), -2).apply { topMargin = d(6); leftMargin = d(8) })
        box.addView(caption(tr("НАЗВАНИЕ", "NAME")))
        name = field(src.name, tr("Необязательно, например «Дома»", "Optional, for example “Home”"))
        box.addView(name, Ui.linear(d(640), -2))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(Ui.pill(this, tr("Проверить", "Check")) { test() }, Ui.linear(-2, -2).apply { rightMargin = d(12) })
        buttons.addView(Ui.pill(this, tr("Сохранить", "Save")) { saveDraft() }, Ui.linear(-2, -2).apply { rightMargin = d(12) })
        if (s != null) buttons.addView(Ui.pill(this, tr("Удалить", "Delete")) { Sources.remove(this, src.id); showList(null) })
        box.addView(buttons, Ui.linear(-1, -2).apply { topMargin = d(22) })
        result = Ui.text(this, 16f, Ui.INK2, Ui.sans(), 4).apply { setLineSpacing(0f, 1.25f) }
        box.addView(result, Ui.linear(d(720), -2).apply { topMargin = d(14); leftMargin = d(8) })
        if (s?.ok != null) result?.text = (if (s.ok == true) tr("Последняя проверка: ", "Last check: ") else tr("Последняя проверка: ошибка, ", "Last check failed: ")) + (s.status ?: "")

        content.removeAllViews()
        content.addView(scroll(box), Ui.frame(-1, -1))
        paintTypes()
        box.post { (if (s == null) url else typeRows[draftType])?.requestFocus() }
    }

    private fun caption(t: String) = Ui.text(this, 12f, Ui.INK3, Ui.bold()).apply {
        letterSpacing = 0.16f; text = t
        setPadding(d(8), d(18), 0, d(8))
    }

    private fun field(value: String, hintText: String, uri: Boolean = false) = EditText(this).apply {
        setText(value)
        hint = hintText
        setHintTextColor(Ui.INK3); setTextColor(Ui.INK)
        textSize = 18f; typeface = Ui.sans()
        setSingleLine()
        inputType = if (uri) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setPadding(d(20), d(12), d(20), d(12))
        // вверх и вниз с пульта: сразу к соседнему полю, а не курсор в начало или конец строки
        setOnKeyListener { v, code, ev ->
            val dir = when (code) { KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP; KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN; else -> 0 }
            dir != 0 && ev.action == KeyEvent.ACTION_DOWN && v.focusSearch(dir)?.requestFocus() == true
        }
        background = Ui.rounded(0x1FF4EFE6, d(26).toFloat())
        setOnFocusChangeListener { _, f -> background = Ui.rounded(if (f) 0x33F4EFE6 else 0x1FF4EFE6, d(26).toFloat(), if (f) d(2) else 0, Ui.INK) }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { tested = null }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    /** Отметка выбранного формата (● / ○) и подсказка про ключ под него. */
    private fun paintTypes() {
        for ((t, row) in typeRows) {
            val f = row.isFocused
            row.background = if (f) Ui.rounded(Ui.INK, d(12).toFloat()) else null
            (row.getChildAt(0) as TextView).apply {
                text = (if (t == draftType) "●   " else "○   ") + typeName(t)
                setTextColor(if (f) Ui.GROUND else if (t == draftType) Ui.INK else Ui.INK2)
            }
            (row.getChildAt(1) as TextView).setTextColor(if (f) Ui.GROUND else Ui.INK3)
        }
        keyHint?.text = when (draftType) {
            Api.TORZNAB -> tr("Для Torznab ключ обычно нужен: он есть на главной странице Jackett и в настройках Prowlarr.",
                "Torznab usually needs the key: it is on the Jackett main page and in Prowlarr settings.")
            Api.JSON -> tr("У своего Jackett ключ обязателен; открытым сервисам он обычно не нужен.",
                "Your own Jackett needs the key; public services usually do not.")
            Api.AUTO -> tr("Оставьте пустым, если сервис открытый. Для своего Jackett или Prowlarr впишите ключ.",
                "Leave empty for a public service. For your own Jackett or Prowlarr type the key.")
        }
    }

    private fun draft(): Source? {
        val base = editing ?: return null
        val u = url?.text?.toString()?.trim().orEmpty()
        val k = key?.text?.toString()?.trim().orEmpty().ifEmpty { null } ?: Sources.keyFromUrl(u)
        // адрес из вставленной ссылки без хвоста ?apikey=…
        val clean = if (u.contains("apikey=", true)) u.substringBefore('?') else u
        val sameAddr = clean == base.url && k == base.key && draftType == base.type
        val t = tested
        return base.copy(name = name?.text?.toString()?.trim().orEmpty(), url = clean, key = k, type = draftType,
            api = t?.api ?: if (sameAddr) base.api else null,
            endpoint = t?.endpoint ?: if (sameAddr) base.endpoint else null,
            ok = if (t != null) true else if (sameAddr) base.ok else null,
            status = if (t != null) tr("работает: ${t.count} раздач по запросу «${Sources.SAMPLE}»", "works: ${t.count} releases for “${Sources.SAMPLE}”")
                else if (sameAddr) base.status else null)
    }

    private fun test() {
        val s = draft() ?: return
        if (s.url.isEmpty()) { result?.text = tr("Сначала введите адрес.", "Type the address first."); url?.requestFocus(); return }
        val my = ++testing
        result?.setTextColor(Ui.INK2)
        result?.text = tr("Проверяю ${s.url}…", "Checking ${s.url}…")
        io.execute {
            val r = runCatching { Sources.detect(s) }
            main.post {
                if (my != testing || editing == null) return@post
                r.onSuccess { f ->
                    tested = f
                    result?.setTextColor(Ui.ACCENT)
                    result?.text = (if (draftType == Api.AUTO) tr("Определено: ", "Detected: ") else tr("Работает: ", "Works: ")) + typeName(f.api) +
                        tr(". По запросу «${Sources.SAMPLE}» найдено раздач: ${f.count}.", ". Releases for “${Sources.SAMPLE}”: ${f.count}.") +
                        "\n" + f.endpoint
                }.onFailure { e ->
                    tested = null
                    result?.setTextColor(0xFFE0866A.toInt())
                    result?.text = tr("Не работает: ", "Does not work: ") + Sources.explain(e)
                }
                // итог ниже кнопок: прокрутить, чтобы его было видно
                result?.let { r -> r.post { r.requestRectangleOnScreen(android.graphics.Rect(0, 0, r.width, r.height)) } }
            }
        }
    }

    /** Сохранить карточку (пустой адрес: ничего не сохраняем) и вернуться к списку. Непроверенный: проверяем в фоне. */
    private fun saveDraft() {
        val s = draft()
        testing++
        if (s == null || s.url.isEmpty()) { showList(null); return }
        Sources.put(this, s)
        showList(s.id)
        if (s.ok == null) io.execute {
            Sources.test(this, s)
            // фокус остаётся на той строке, где он сейчас
            main.post { if (editing == null && !isDestroyed) showList(currentFocus?.tag as? String ?: s.id) }
        }
    }
}
