package com.xgimiru.player

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** «Кино»: продолжить просмотр. Сам плеер открывается из Лампы, TorrServe и т. п. */
class MainActivity : Activity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(80), dp(56), dp(80), dp(40))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@MainActivity).apply {
                background = getDrawable(R.drawable.ic_app)
            }, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = "Кино"
                    textSize = 34f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Color.WHITE)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "плеер для проектора"
                    textSize = 16f
                    setTextColor(getColor(R.color.text_dim))
                })
            })
        })
        root.addView(TextView(this).apply {
            text = "ПРОДОЛЖИТЬ ПРОСМОТР"
            textSize = 14f
            letterSpacing = 0.14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(getColor(R.color.accent_warm))
            setPadding(dp(4), dp(44), 0, dp(14))
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
        fill()
    }

    private fun fill() {
        list.removeAllViews()
        val items = History.all(this).filter { !it.finished }
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Здесь появятся фильмы и серии, которые вы начали смотреть.\n\n" +
                    "Запускайте видео из Лампы — «Кино» откроется само, если выбрать его плеером."
                textSize = 18f
                setLineSpacing(0f, 1.25f)
                setTextColor(getColor(R.color.text_dim))
                setPadding(dp(4), dp(8), 0, 0)
            })
            return
        }
        for (e in items) list.addView(card(e), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        list.getChildAt(0)?.requestFocus()
    }

    private fun card(e: History.Entry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.history_card)
            setPadding(dp(26), dp(18), dp(26), dp(18))
            isFocusable = true
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PlayerActivity::class.java)
                    .setData(Uri.parse(e.uri))
                    .putExtra("title", e.title))
            }
            setOnKeyListener { _, code, ev ->
                // долгое нажатие OK или «меню» — убрать из списка
                if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_MENU) {
                    History.remove(this@MainActivity, e.key); fill(); true
                } else false
            }
            setOnFocusChangeListener { v, has -> v.animate().scaleX(if (has) 1.015f else 1f).scaleY(if (has) 1.015f else 1f).setDuration(150).start() }
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = e.title
                textSize = 22f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "осталось ${Time.human(e.duration - e.position)}"
                textSize = 15f
                setTextColor(getColor(R.color.text_dim))
            })
        })
        card.addView(FrameLayout(this).apply {
            addView(ProgressView(this@MainActivity).apply {
                update(e.duration, e.position, 0, null)
            }, FrameLayout.LayoutParams(-1, dp(24)))
        }, LinearLayout.LayoutParams(-1, dp(24)).apply { topMargin = dp(6) })
        return card
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
