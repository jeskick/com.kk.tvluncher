@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kk.tvlauncher.ui.lan

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
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
    private var timeBar: TimeBar? = null
    private var audioDropped = false
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
        timeBar = TimeBar(this)
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(picture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(title, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(
            timeBar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(72), Gravity.BOTTOM)
        )
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
            timeBar?.visibility = View.GONE
            Thread {
                val bytes = com.kk.tvlauncher.data.SmbImageFetcher.loadBytes(item.url, LanPlayRequest.user, LanPlayRequest.pass)
                runOnUiThread {
                    if (current()?.url != item.url) return@runOnUiThread
                    if (bytes != null) picture?.let { Glide.with(this).load(bytes).into(it) }
                }
            }.start()
            return
        }
        picture?.visibility = View.GONE
        surface?.visibility = View.VISIBLE
        timeBar?.visibility = View.VISIBLE
        timeBar?.setTimes(0L, C.TIME_UNSET)
        audioDropped = false
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
        val builder = MediaItem.Builder()
            .setUri(item.url)
            .setSubtitleConfigurations(subs)
        mimeOf(item.name)?.let { builder.setMimeType(it) }
        val dataSource = SmbDataSource.Factory(LanPlayRequest.user, LanPlayRequest.pass)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 8_000, 12_000)
            .setTargetBufferBytes(48 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setRenderersFactory(LanRenderersFactory(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, mkvExtractors()))
            .build()
        exo.setVideoSurfaceView(surface)
        exo.setWakeMode(C.WAKE_MODE_LOCAL)
        exo.setMediaItem(builder.build())
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e("LanPlay", "code=${error.errorCodeName}", error)
                if (audioDropped || !isAudioError(error)) return
                audioDropped = true
                val position = exo.currentPosition
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                exo.prepare()
                exo.seekTo(position)
                exo.play()
            }
        })
        exo.prepare()
        exo.play()
        player = exo
    }

    private fun isAudioError(error: PlaybackException): Boolean {
        val renderer = (error as? ExoPlaybackException)?.rendererName
        if (renderer?.contains("Audio", true) == true) return true
        var cause = error.cause
        while (cause != null) {
            if (cause.javaClass.name.contains("Audio")) return true
            cause = cause.cause
        }
        return false
    }

    private fun mkvExtractors(): ExtractorsFactory = ExtractorsFactory {
        DefaultExtractorsFactory().createExtractors().map { extractor ->
            if (extractor is MatroskaExtractor) {
                extractor.release()
                MatroskaExtractor(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
            } else {
                extractor
            }
        }.toTypedArray()
    }

    private fun mimeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv", "webm" -> MimeTypes.VIDEO_MATROSKA
        "mp4", "m4v", "mov" -> MimeTypes.VIDEO_MP4
        "mp3" -> MimeTypes.AUDIO_MPEG
        "ts" -> MimeTypes.VIDEO_MP2T
        else -> null
    }

    private fun loadSubtitles(video: SmbMedia.Entry): List<MediaItem.SubtitleConfiguration> {
        val siblings = runCatching {
            SmbMedia.list(SmbMedia.parent(video.url), LanPlayRequest.user, LanPlayRequest.pass).orEmpty()
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
        if (exo.isPlaying) exo.pause() else exo.play()
        refreshBar()
    }

    private fun seek(seconds: Int) {
        val exo = player ?: return
        val duration = exo.duration
        val target = exo.currentPosition + seconds * 1000L
        val next = if (duration > 0) target.coerceIn(0L, duration) else max(0L, target)
        exo.seekTo(next)
        refreshBar()
    }

    private fun step(delta: Int) {
        val items = LanPlayRequest.items
        if (items.isEmpty()) return
        val next = LanPlayRequest.index + delta
        if (next !in items.indices) return
        LanPlayRequest.index = next
        showCurrent()
    }

    private fun refreshBar() {
        val exo = player ?: return
        timeBar?.setTimes(exo.currentPosition, exo.duration)
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

    private class TimeBar(context: android.content.Context) : View(context) {
        private var positionMs = 0L
        private var durationMs = 0L
        private val density = resources.displayMetrics.density
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f * resources.displayMetrics.scaledDensity
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
        private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        fun setTimes(position: Long, duration: Long) {
            positionMs = position
            durationMs = duration
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val pad = 28f * density
            val line = 3f * density
            val bottom = height - 18f * density
            val top = bottom - line
            val rightEdge = width - pad
            canvas.drawRoundRect(pad, top, rightEdge, bottom, line, line, track)
            val span = (rightEdge - pad).coerceAtLeast(0f)
            val frac = if (durationMs > 0) positionMs.coerceIn(0L, durationMs).toFloat() / durationMs else 0f
            canvas.drawRoundRect(pad, top, pad + span * frac, bottom, line, line, played)
            val baseline = top - 12f * density
            canvas.drawText(clock(positionMs), pad, baseline, text)
            val end = clock(durationMs)
            canvas.drawText(end, rightEdge - text.measureText(end), baseline, text)
        }

        private fun clock(ms: Long): String {
            if (ms < 0) return "--:--"
            val total = ms / 1000
            val hour = total / 3600
            val minute = (total % 3600) / 60
            val second = total % 60
            return if (hour > 0) "%d:%02d:%02d".format(hour, minute, second)
            else "%02d:%02d".format(minute, second)
        }
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
