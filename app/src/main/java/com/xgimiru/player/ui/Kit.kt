package com.xgimiru.player.ui

import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout

/** A screen right of the left list (Continue, History, a folder, Settings). */
interface Pane {
    val view: View
    /** The pane became visible: refresh its data. */
    fun onShow() {}
    fun onHide() {}
    /** Give focus to the pane's first (or last used) element; false when it has nothing to focus. */
    fun focusFirst(): Boolean
    /** Back inside the pane (close a choice, go up a folder). true when handled. */
    fun onBack(): Boolean = false
}

/** What a pane may ask of the main screen. */
interface Nav {
    /** Open a place of the left list by its id. */
    fun open(place: String)
    /** Places changed (a folder added or removed). */
    fun refreshRail()
    fun focusRail()
}

/** Rounded clipping for pictures. */
fun View.round(radiusPx: Float) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radiusPx)
    }
    clipToOutline = true
}

/** Empty place: a title, one or two sentences, and at most one button. */
fun emptyState(ctx: Context, title: String, text: String, button: String?, onClick: (() -> Unit)?): LinearLayout =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.TOP
        addView(Ui.t(ctx, 44, 680, Ui.T1, spacing = -.8f).apply { this.text = title })
        addView(Ui.t(ctx, 25, 450, Ui.T2, 4).apply { this.text = text; setLineSpacing(0f, 1.35f) },
            Ui.linear(Ui.px(ctx, 900), -2).apply { topMargin = Ui.px(ctx, 24); bottomMargin = Ui.px(ctx, 36) })
        if (button != null && onClick != null) addView(Ui.pill(ctx, button, onClick), Ui.linear(-2, -2))
    }

fun View.onKey(code: Int, f: () -> Boolean) = setOnKeyListener { _, c, e -> if (e.action == KeyEvent.ACTION_DOWN && c == code) f() else false }
