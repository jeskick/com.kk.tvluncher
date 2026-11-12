@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kk.tvlauncher.ui.lan

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.bumptech.glide.Glide
import com.kk.tvlauncher.data.SmbDataSource
import com.kk.tvlauncher.data.SmbMedia
import com.kk.tvlauncher.ui.lock.KeyDecision
import com.kk.tvlauncher.ui.lock.LockInputGuard
import kotlin.math.max

/** 播放共享里的一个文件。左右快退快进，确定暂停或继续。 */
class LanPlayActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val lockGuard by lazy { LockInputGuard(this, armWhenUnlocked = false) }
    private var player: ExoPlayer? = null
    private var picture: ImageView? = null
    private var surface: SurfaceView? = null
    private var title: TextView? = null
    private var bar: TextView? = null
    private var flash: TextView? = null
    private val hideFlash = Runnable { flash?.visibility = View.GONE }
    private val tick = object : Runnable {
        override fun run() {
            refreshBar()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        picture = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        title = label(Gravity.TOP or Gravity.START, 18f).apply {
            setPadding(dp(28), dp(24), dp(28), 0)
        }
        flash = label(Gravity.CENTER, 28f).apply { visibility = View.GONE }
        bar = label(Gravity.BOTTOM or Gravity.START, 18f).apply {
            setPadding(dp(28), 0, dp(28), dp(24))
        }
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(picture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(title, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(flash, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        showCurrent()
    }

    override fun onStart() {
        super.onStart()
        lockGuard.start()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        lockGuard.stop()
        releasePlayer()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (lockGuard.decide(event)) {
            KeyDecision.CONSUME -> return true
            KeyDecision.SYSTEM -> return super.dispatchKeyEvent(event)
            KeyDecision.UI -> Unit
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val video = current()?.kind == SmbMedia.Kind.VIDEO
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (video && event.repeatCount == 0) seek(-10) else if (!video && event.repeatCount == 0) step(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (video && event.repeatCount == 0) seek(10) else if (!video && event.repeatCount == 0) step(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.repeatCount == 0 && video) toggle()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.repeatCount == 0) step(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.repeatCount == 0) step(1)
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                finish()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showCurrent() {
        val item = current() ?: run {
            finish()
            return
        }
        title?.text = item.name
        releasePlayer()
        if (item.kind == SmbMedia.Kind.IMAGE) {
            surface?.visibility = View.GONE
            picture?.visibility = View.VISIBLE
            bar?.text = "左右或上下换一张，返回退出"
            Thread {
                val bytes = com.kk.tvlauncher.data.SmbImageFetcher.loadBytes(item.url, LanPlayRequest.user, LanPlayRequest.pass)
                runOnUiThread {
                    if (current()?.url != item.url) return@runOnUiThread
                    if (bytes == null) flash("打不开这张图片") else picture?.let { Glide.with(this).load(bytes).into(it) }
                }
            }.start()
            return
        }
        picture?.visibility = View.GONE
        surface?.visibility = View.VISIBLE
        bar?.text = "正在打开"
        Thread {
            val subs = loadSubtitles(item)
            runOnUiThread {
                if (current()?.url != item.url) return@runOnUiThread
                startVideo(item, subs)
            }
        }.start()
    }

    private fun startVideo(item: SmbMedia.Entry, subs: List<MediaItem.SubtitleConfiguration>) {
        if (current()?.url != item.url) return
        val media = MediaItem.Builder()
            .setUri(item.url)
            .setSubtitleConfigurations(subs)
            .build()
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSource.Factory(LanPlayRequest.user, LanPlayRequest.pass)))
            .build()
        exo.setVideoSurfaceView(surface)
        exo.setWakeMode(C.WAKE_MODE_LOCAL)
        exo.setMediaItem(media)
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                flash("这个视频打不开")
            }
        })
        exo.prepare()
        exo.play()
        player = exo
        if (subs.isNotEmpty()) flash("已加载字幕")
    }

    private fun loadSubtitles(video: SmbMedia.Entry): List<MediaItem.SubtitleConfiguration> {
        val siblings = runCatching {
            SmbMedia.list(SmbMedia.parent(video.url), LanPlayRequest.user, LanPlayRequest.pass)
        }.getOrDefault(emptyList())
        return SmbMedia.subtitlesFor(video, siblings).mapIndexed { index, sub ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                .setMimeType(subtitleMime(sub.name))
                .setLabel(sub.name)
                .setLanguage("zh")
                .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
    }

    private fun toggle() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
            flash("暂停")
        } else {
            exo.play()
            flash("播放")
        }
        refreshBar()
    }

    private fun seek(seconds: Int) {
        val exo = player ?: return
        val duration = exo.duration
        val target = exo.currentPosition + seconds * 1000L
        val next = if (duration > 0) target.coerceIn(0L, duration) else max(0L, target)
        exo.seekTo(next)
        flash(if (seconds < 0) "快退 10 秒" else "快进 10 秒")
        refreshBar()
    }

    private fun step(delta: Int) {
        val items = LanPlayRequest.items
        if (items.isEmpty()) return
        val next = LanPlayRequest.index + delta
        if (next !in items.indices) {
            flash(if (delta < 0) "已经是第一个" else "已经是最后一个")
            return
        }
        LanPlayRequest.index = next
        showCurrent()
    }

    private fun refreshBar() {
        val exo = player
        if (exo == null) return
        val state = if (exo.isPlaying) "播放" else "暂停"
        bar?.text = "$state    ${clock(exo.currentPosition)} / ${clock(exo.duration)}    左快退  右快进  确定暂停"
    }

    private fun flash(text: String) {
        flash?.text = text
        flash?.visibility = View.VISIBLE
        flash?.removeCallbacks(hideFlash)
        flash?.postDelayed(hideFlash, 1200)
    }

    private fun current(): SmbMedia.Entry? = LanPlayRequest.items.getOrNull(LanPlayRequest.index)

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    private fun label(gravity: Int, size: Float) = TextView(this).apply {
        this.gravity = gravity
        textSize = size
        setTextColor(Color.WHITE)
        setShadowLayer(8f, 0f, 2f, Color.BLACK)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun clock(ms: Long): String {
        if (ms < 0) return "--:--"
        val total = ms / 1000
        val hour = total / 3600
        val minute = (total % 3600) / 60
        val second = total % 60
        return if (hour > 0) "%d:%02d:%02d".format(hour, minute, second)
        else "%02d:%02d".format(minute, second)
    }

    private fun subtitleMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

object LanPlayRequest {
    var user: String = ""
    var pass: String = ""
    var items: List<SmbMedia.Entry> = emptyList()
    var index: Int = 0
}
