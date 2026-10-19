package com.xgimiru.player.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xgimiru.player.ui.Img
import com.xgimiru.player.ui.Ui
import com.xgimiru.player.ui.tr
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Прямой эфир. Один libVLC на всё время, при переключении меняется только Media: каналы листаются быстро.
 *  - вверх / вниз, CH+ / CH-: соседний канал в текущем списке (группа или избранное);
 *  - цифры: номер канала; OK: список каналов поверх эфира; назад: закрыть список, иначе выйти;
 *  - при каждом переключении на 4 секунды плашка: номер, логотип, имя, что идёт и что дальше.
 */
class LiveActivity : Activity() {

    companion object {
        private const val EXTRA_KEY = "channel"
        private const val EXTRA_LIST = "list"   // "fav", "all" или имя группы

        fun open(ctx: Context, c: Channel, list: String) {
            ctx.startActivity(Intent(ctx, LiveActivity::class.java).putExtra(EXTRA_KEY, c.key).putExtra(EXTRA_LIST, list))
        }

        fun listOf(ctx: Context, list: String): List<Channel> = when (list) {
            "fav" -> Iptv.favoriteChannels(ctx)
            "all" -> Iptv.channels(ctx)
            else -> Iptv.channels(ctx).filter { it.group == list }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var vlc: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var channels: List<Channel>
    private var index = 0
    private var listName = "all"

    private lateinit var banner: LinearLayout
    private lateinit var bLogo: ImageView
    private lateinit var bNum: TextView
    private lateinit var bName: TextView
    private lateinit var bNow: TextView
    private lateinit var bNext: TextView
    private lateinit var bBar: View
    private lateinit var bBarFill: View
    private lateinit var status: TextView
    private lateinit var picker: ChannelList
    private var digits = ""
    private val hideBanner = Runnable { banner.animate().alpha(0f).setDuration(300).start() }
    private val applyDigits = Runnable { jumpToNumber() }
    private var errors = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        listName = intent.getStringExtra(EXTRA_LIST) ?: "all"
        channels = listOf(this, listName).ifEmpty { Iptv.channels(this) }
        if (channels.isEmpty()) { finish(); return }
        index = channels.indexOfFirst { it.key == intent.getStringExtra(EXTRA_KEY) }.coerceAtLeast(0)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        videoLayout = VLCVideoLayout(this)
        root.addView(videoLayout, Ui.frame(-1, -1))
        status = Ui.text(this, 20f, Ui.INK2, Ui.medium()).apply { gravity = Gravity.CENTER }
        root.addView(status, Ui.frame(-2, -2, Gravity.CENTER))
        root.addView(buildBanner(), Ui.frame(-1, -2, Gravity.BOTTOM))
        picker = ChannelList(this) { c -> picker.hide(); index = channels.indexOf(c).coerceAtLeast(0); tune() }
        root.addView(picker, Ui.frame(-1, -1))
        setContentView(root)

        vlc = LibVLC(this, arrayListOf("--no-stats", "--audio-time-stretch"))
        player = MediaPlayer(vlc)
        player.attachViews(videoLayout, null, false, false)
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> { errors = 0; status.text = "" }
                MediaPlayer.Event.Buffering -> if (e.buffering < 100f && player.isPlaying.not()) status.text = tr("Загрузка", "Loading")
                MediaPlayer.Event.EncounteredError, MediaPlayer.Event.EndReached -> onStreamError()
            }
        }
        tune()
    }

    private fun buildBanner(): View {
        val d = { v: Number -> Ui.dp(this, v) }
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(48), d(28), d(48), d(36))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF00A0908.toInt(), 0xB00A0908.toInt(), 0x000A0908))
            alpha = 0f
        }
        bNum = Ui.text(this, 34f, Ui.ACCENT, Ui.serif())
        banner.addView(bNum, Ui.linear(-2, -2).apply { rightMargin = d(24) })
        bLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        banner.addView(bLogo, Ui.linear(d(96), d(64)).apply { rightMargin = d(24) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bName = Ui.text(this, 30f, Ui.INK, Ui.serif())
        bNow = Ui.text(this, 19f, Ui.INK, Ui.medium())
        bNext = Ui.text(this, 16f, Ui.INK3)
        col.addView(bName)
        col.addView(bNow, Ui.linear(-1, -2).apply { topMargin = d(8) })
        val bar = FrameLayout(this).apply { background = Ui.rounded(0x33F4EFE6, d(2).toFloat()) }
        bBarFill = View(this).apply { background = Ui.rounded(Ui.ACCENT, d(2).toFloat()) }
        bar.addView(bBarFill, Ui.frame(0, -1))
        bBar = bar
        col.addView(bar, Ui.linear(d(420), d(4)).apply { topMargin = d(10) })
        col.addView(bNext, Ui.linear(-1, -2).apply { topMargin = d(10) })
        banner.addView(col, Ui.linear(0, -2, 1f))
        return banner
    }

    private fun current() = channels[index]

    private fun tune() {
        val c = current()
        Iptv.setLast(this, c.key)
        status.text = tr("Загрузка", "Loading")
        val m = Media(vlc, Uri.parse(c.url))
        m.setHWDecoderEnabled(true, false)
        m.addOption(":network-caching=1500")
        m.addOption(":http-user-agent=" + (c.userAgent ?: Net.UA))
        c.referrer?.let { m.addOption(":http-referrer=$it") }
        player.media = m
        m.release()
        player.play()
        showBanner()
    }

    private fun onStreamError() {
        // поток оборвался: одна-две попытки переподключиться, потом честное сообщение
        if (++errors <= 2) handler.postDelayed({ if (!isFinishing) tune() }, 1500)
        else status.text = tr("Канал не отвечает. Попробуйте другой.", "The channel does not respond. Try another one.")
    }

    private fun showBanner() {
        val c = current()
        bNum.text = c.number.toString()
        bName.text = c.name
        Img.load(bLogo, c.logo, 192)
        bLogo.visibility = if (c.logo != null) View.VISIBLE else View.GONE
        val now = System.currentTimeMillis()
        val p = Epg.now(this, c, now)
        val n = Epg.next(this, c, now)
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
        bNow.text = p?.let { "${hm.format(Date(it.start))}  ${it.title}" } ?: tr("Нет программы передач", "No programme guide")
        bNext.text = n?.let { tr("Дальше", "Next") + "  ${hm.format(Date(n.start))}  ${n.title}" } ?: ""
        bBar.visibility = if (p != null) View.VISIBLE else View.GONE
        bBar.post {
            bBarFill.layoutParams = (bBarFill.layoutParams as FrameLayout.LayoutParams).apply { width = ((bBar.width) * (p?.progress(now) ?: 0f)).toInt() }
            bBarFill.requestLayout()
        }
        banner.animate().cancel()
        banner.alpha = 1f
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, 4000)
    }

    private fun zap(step: Int) {
        if (channels.size < 2) return
        index = (index + step + channels.size) % channels.size
        errors = 0
        tune()
    }

    private fun jumpToNumber() {
        val n = digits.toIntOrNull(); digits = ""
        val i = channels.indexOfFirst { it.number == n }
        if (i >= 0) { index = i; tune() } else showBanner()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (picker.isOpen()) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { picker.show(channels, index, listName); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> { showBanner(); return true }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                digits += (keyCode - KeyEvent.KEYCODE_0).toString()
                bNum.text = digits; banner.alpha = 1f
                handler.removeCallbacks(applyDigits); handler.postDelayed(applyDigits, 1500)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (picker.isOpen()) picker.hide() else super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()   // эфир в фоне не держим
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) {
            player.setEventListener(null)
            player.stop()
            player.detachViews()
            player.release()
            vlc.release()
        }
        io.shutdownNow()
        super.onDestroy()
    }
}
