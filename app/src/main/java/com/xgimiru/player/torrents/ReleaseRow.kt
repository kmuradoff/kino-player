package com.xgimiru.player.torrents

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Строка раздачи для ListView: название, значки (качество, HDR, тип рипа, озвучки), размер, трекер,
 * дата и сиды. Значки: постоянный набор TextView, при прокрутке только меняется текст: без
 * создания вью на каждый bind, это важно на слабом проекторе.
 */
class ReleaseRow(ctx: Context) : LinearLayout(ctx) {
    private val d = { v: Number -> Ui.dp(ctx, v) }
    private val eyebrow = Ui.text(ctx, 12f, Ui.ACCENT, Ui.bold()).apply { letterSpacing = 0.14f }
    private val title = Ui.text(ctx, 17f, Ui.INK, Ui.medium(), 2)
    private val badges = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val pool = ArrayList<TextView>()
    private val meta = Ui.text(ctx, 14f, Ui.INK3)
    private val seeds = Ui.text(ctx, 18f, Ui.ACCENT, Ui.bold()).apply { gravity = Gravity.END }
    private val peers = Ui.text(ctx, 13f, Ui.INK3).apply { gravity = Gravity.END }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(d(20), d(12), d(22), d(12))
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        col.addView(eyebrow, Ui.linear(-1, -2).apply { bottomMargin = d(5) })
        col.addView(title)
        col.addView(badges, Ui.linear(-1, -2).apply { topMargin = d(7) })
        col.addView(meta, Ui.linear(-1, -2).apply { topMargin = d(6) })
        addView(col, Ui.linear(0, -2, 1f))
        val right = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.END }
        right.addView(seeds, Ui.linear(-1, -2))
        right.addView(peers, Ui.linear(-1, -2).apply { topMargin = d(4) })
        addView(right, Ui.linear(d(96), -2))
        repeat(9) {
            val t = Ui.text(ctx, 12f, Ui.GROUND, Ui.bold()).apply { setPadding(d(8), d(3), d(8), d(3)) }
            pool += t
            badges.addView(t, Ui.linear(-2, -2).apply { rightMargin = d(7) })
        }
    }

    /** pick: раздача, которую уже выбирали для этого фильма. */
    fun bind(r: Release, pick: Boolean = false) {
        eyebrow.visibility = if (pick) VISIBLE else GONE
        if (pick) eyebrow.text = tr("ПРОДОЛЖИТЬ С ЭТОЙ РАЗДАЧЕЙ", "CONTINUE WITH THIS RELEASE")
        title.text = r.title
        title.maxLines = 2
        val g = r.tags
        var i = 0
        fun badge(text: String, fill: Int, ink: Int, outline: Boolean = false) {
            if (i >= pool.size) return
            val t = pool[i++]
            t.text = text
            t.setTextColor(ink)
            t.background = if (outline) Ui.rounded(0, d(6).toFloat(), d(1), Ui.INK3) else Ui.rounded(fill, d(6).toFloat())
            t.visibility = VISIBLE
        }
        for (b in g.badges) when (b) {
            "4K", "DV", "HDR", "HDR10+" -> badge(b, Ui.ACCENT, Ui.GROUND)
            "CAMRip" -> badge(b, 0xFFD9694A.toInt(), Ui.INK)
            "1080p", "720p", "SD" -> badge(b, Ui.INK, Ui.GROUND)
            else -> badge(b, Ui.INK2, Ui.GROUND)
        }
        if (g.seasons.isNotEmpty()) badge(seasonsLabel(g.seasons), Ui.CARD_HI, Ui.INK)
        for (v in g.voices.take(4)) badge(v, 0, Ui.INK2, outline = true)
        while (i < pool.size) pool[i++].visibility = GONE
        badges.visibility = if (pool[0].visibility == VISIBLE) VISIBLE else GONE
        meta.visibility = VISIBLE
        meta.text = listOfNotNull(humanSize(r.size).ifEmpty { null }, r.tracker?.let { trackers(it) },
            r.time.takeIf { it > 0 }?.let { SimpleDateFormat("d.MM.yyyy", Locale.US).format(Date(it)) }).joinToString("  ·  ")
        seeds.text = "▲ ${r.seeders}"
        peers.text = if (r.peers > 0) "▼ ${r.peers}" else ""
        seeds.visibility = VISIBLE
        peers.visibility = VISIBLE
    }

    /** Простая строка (без значков): недавний запрос и т. п. */
    fun bindText(text: String, sub: String? = null) {
        eyebrow.visibility = GONE
        title.text = text
        title.maxLines = 1
        badges.visibility = GONE
        meta.text = sub ?: ""
        meta.visibility = if (sub == null) GONE else VISIBLE
        seeds.visibility = GONE
        peers.visibility = GONE
    }

    companion object {
        /** «Сезон 2», «Сезоны 1-5», «Сезоны 1, 3». */
        fun seasonsLabel(s: Set<Int>): String {
            val l = s.sorted()
            if (l.size == 1) return tr("Сезон ${l[0]}", "Season ${l[0]}")
            val span = if (l.last() - l.first() == l.size - 1) "${l.first()}-${l.last()}" else l.take(4).joinToString(", ")
            return tr("Сезоны $span", "Seasons $span")
        }

        /** Трекеры раздачи: не больше трёх, остальные числом. */
        fun trackers(t: String): String {
            val l = t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return if (l.size <= 3) l.joinToString(", ") else l.take(3).joinToString(", ") + " +" + (l.size - 3)
        }
    }
}
