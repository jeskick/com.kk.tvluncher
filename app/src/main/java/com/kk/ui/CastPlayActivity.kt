package com.kk.tvlauncher.ui

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.kk.tvlauncher.service.CastServer
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 全屏播放电脑推过来的照片或视频。上下键换片，左右键和确定键控制视频。 */
class CastPlayActivity : Activity() {
    private var player: ExoPlayer? = null
    private var picture: SurfaceView? = null
    private var videoUrl = ""
    private var prepared = false
    private var recovering = false
    private var recoveries = 0
    private var info: TextView? = null
    private var rate: TextView? = null
    private var hint: TextView? = null
    private var stepUrl = ""
    private var stepping = false
    private var lastStep = 0L
    private var pausedByUser = false
    private var screenLock: PowerManager.WakeLock? = null
    private val hideHint = Runnable { hint?.visibility = View.GONE }

    private val playback = object : CastServer.Playback {
        override fun pause() { runOnUiThread { if (player?.isPlaying == true) togglePlay() } }
        override fun resume() { runOnUiThread { if (player?.isPlaying != true) togglePlay() } }
        override fun stop() { runOnUiThread { finish() } }
        override fun seekTo(positionMs: Int) {
            runOnUiThread {
                val mp = player ?: return@runOnUiThread
                mp.seekTo(positionMs.coerceAtLeast(0).toLong())
                hint?.removeCallbacks(resumeRunnable)
                if (!pausedByUser) hint?.postDelayed(resumeRunnable, 500)
            }
        }
        override fun positionMs(): Int = onPlayer { safePosition() }
        override fun durationMs(): Int = onPlayer {
            val duration = player?.duration ?: 0L
            if (duration < 0L) 0 else duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        CastServer.playback = playback
        readIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        holdScreen()
    }

    override fun onStop() {
        releaseScreen()
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        if (CastServer.playback === playback) CastServer.playback = null
        releaseScreen()
        releasePlayer()
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    step("prev")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    step("next")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    seek(-10)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    seek(10)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    togglePlay()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                    finish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun readIntent(source: android.content.Intent?) {
        if (source?.getBooleanExtra("stop", false) == true) {
            finish()
            return
        }
        stepUrl = source?.getStringExtra("step").orEmpty()
        val url = source?.getStringExtra("url").orEmpty()
        show(url, source?.getStringExtra("kind").orEmpty(), null)
        val title = source?.getStringExtra("title").orEmpty()
        if (title.isNotBlank() && info?.text.isNullOrBlank()) info?.text = title
        loadMeta(metaUrl(url))
    }

    private fun onPlayer(read: () -> Int): Int {
        if (Looper.myLooper() == Looper.getMainLooper()) return read()
        val box = IntArray(1)
        val latch = CountDownLatch(1)
        runOnUiThread {
            box[0] = read()
            latch.countDown()
        }
        latch.await(200, TimeUnit.MILLISECONDS)
        return box[0]
    }

    private fun step(dir: String) {
        val endpoint = stepUrl
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) return
        val now = android.os.SystemClock.uptimeMillis()
        if (stepping || now - lastStep < 280) return
        lastStep = now
        stepping = true
        Thread {
            try {
                val json = getJson("$endpoint?dir=$dir")
                val next = json.optString("url")
                val kind = json.optString("kind")
                val edge = json.optBoolean("edge")
                val meta = json.optJSONObject("meta")
                runOnUiThread {
                    stepping = false
                    if (edge) flash(if (dir == "prev") "已经是第一个" else "已经是最后一个")
                    else if (next.startsWith("http://") || next.startsWith("https://")) show(next, kind, meta)
                }
            } catch (_: Exception) {
                runOnUiThread { stepping = false }
            }
        }.start()
    }

    private val resumeRunnable = Runnable {
        if (pausedByUser) return@Runnable
        player?.play()
    }

    private fun seek(seconds: Int) {
        val mp = player ?: return
        val duration = mp.duration
        if (duration <= 0) return
        val position = safePosition()
        val next = (position + seconds * 1000).coerceIn(0, (duration - 800).toInt().coerceAtLeast(0))
        if (mp.isPlaying) mp.pause()
        mp.seekTo(next.toLong())
        hint?.removeCallbacks(resumeRunnable)
        if (!pausedByUser) hint?.postDelayed(resumeRunnable, 500)
        flash(if (seconds < 0) "${seconds}秒" else "+${seconds}秒")
    }

    private fun togglePlay() {
        val mp = player ?: return
        if (mp.isPlaying) {
            pausedByUser = true
            hint?.removeCallbacks(resumeRunnable)
            mp.pause()
            flash("暂停")
        } else {
            pausedByUser = false
            mp.play()
            flash("播放")
        }
    }

    private fun safePosition(): Int {
        return player?.currentPosition?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
    }

    private fun openPlayer(url: String, view: SurfaceView, startAt: Int) {
        releasePlayer()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Lavf/60.3.100")
            .setAllowCrossProtocolRedirects(true)
        val mp = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        player = mp
        mp.setWakeMode(C.WAKE_MODE_LOCAL)
        mp.setVideoSurfaceView(view)
        mp.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                fitVideo(videoSize.width, videoSize.height)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) showStatus("正在打开视频")
                if (state != Player.STATE_READY) return
                prepared = true
                recovering = false
                recoveries = 0
                hideStatus()
                if (startAt > 0) mp.seekTo(startAt.toLong())
            }
            override fun onRenderedFirstFrame() {
                hideStatus()
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e("CastPlay", "播放失败 ${error.errorCodeName} ${error.message}", error)
                if (!recovering && recoveries < 1) {
                    recoveries += 1
                    replayFrom(safePosition())
                } else {
                    showStatus("这个视频打不开")
                }
            }
        })
        try {
            mp.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            mp.prepare()
            if (!pausedByUser) mp.play()
        } catch (error: Exception) {
            Log.e("CastPlay", "打不开这个地址", error)
            showStatus("这个清晰度打不开，按返回键退出")
            releasePlayer()
        }
    }

    private fun replayFrom(position: Int) {
        val view = picture ?: return
        val url = videoUrl
        if (url.isBlank() || recovering) return
        recovering = true
        prepared = false
        openPlayer(url, view, position.coerceAtLeast(0))
    }

    private fun fitVideo(width: Int, height: Int) {
        val view = picture ?: return
        val parent = view.parent as? View ?: return
        if (width <= 0 || height <= 0 || parent.width <= 0 || parent.height <= 0) return
        val videoRatio = width.toFloat() / height
        val viewRatio = parent.width.toFloat() / parent.height
        val params = view.layoutParams as FrameLayout.LayoutParams
        if (videoRatio > viewRatio) {
            params.width = parent.width
            params.height = (parent.width / videoRatio).toInt()
        } else {
            params.height = parent.height
            params.width = (parent.height * videoRatio).toInt()
        }
        params.gravity = Gravity.CENTER
        view.layoutParams = params
    }

    private fun holdScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (screenLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "kk:cast-screen"
            ).apply { setReferenceCounted(false) }
        }
        if (screenLock?.isHeld != true) screenLock?.acquire()
    }

    private fun releaseScreen() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lock = screenLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (_: Exception) {
        }
    }

    private fun releasePlayer() {
        hint?.removeCallbacks(resumeRunnable)
        val mp = player
        player = null
        prepared = false
        if (mp == null) return
        try { mp.release() } catch (_: Exception) {}
    }

    private fun show(url: String, kind: String, meta: JSONObject?) {
        releasePlayer()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            finish()
            return
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        if (kind == "video") {
            videoUrl = url
            pausedByUser = false
            prepared = false
            val view = SurfaceView(this)
            picture = view
            root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addChrome(root)
            setContentView(root)
            showStatus("正在打开视频")
            openPlayer(url, view, 0)
            if (meta != null) applyMeta(meta)
            return
        }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addChrome(root)
        setContentView(root)
        if (meta != null) applyMeta(meta)
        Glide.with(this).load(url).into(image)
    }

    private fun addChrome(root: FrameLayout) {
        val mark = label("KK", 14f, Gravity.START)
        val markParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
        markParams.leftMargin = 36
        markParams.bottomMargin = 28
        root.addView(mark, markParams)

        val line = label("", 16f, Gravity.CENTER)
        info = line
        val lineParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lineParams.bottomMargin = 72
        lineParams.leftMargin = 48
        lineParams.rightMargin = 48
        root.addView(line, lineParams)

        val speed = label("", 15f, Gravity.END)
        rate = speed
        val speedParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        speedParams.topMargin = 28
        speedParams.rightMargin = 36
        root.addView(speed, speedParams)

        val note = label("", 22f, Gravity.CENTER)
        hint = note
        note.visibility = View.GONE
        root.addView(note, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun label(text: String, size: Float, align: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.WHITE)
            gravity = align
            setShadowLayer(8f, 0f, 1f, Color.BLACK)
        }
    }

    private fun applyMeta(meta: JSONObject) {
        val parts = listOf("name", "when", "size", "resolution").map { meta.optString(it) }.filter { it.isNotBlank() }
        info?.text = parts.joinToString("    ")
        val bitrate = meta.optString("bitrate")
        rate?.text = bitrate
        rate?.visibility = if (bitrate.isBlank()) View.GONE else View.VISIBLE
    }

    private fun loadMeta(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        Thread {
            try {
                val json = getJson(url)
                runOnUiThread { applyMeta(json) }
            } catch (_: Exception) { }
        }.start()
    }

    private fun metaUrl(fileUrl: String): String {
        val marker = "/api/cast/file/"
        val at = fileUrl.indexOf(marker)
        if (at < 0) return ""
        val token = fileUrl.substring(at + marker.length).substringBefore('.')
        if (token.isBlank()) return ""
        return fileUrl.substring(0, at) + "/api/cast/meta/" + token
    }

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 15000
        }
        val text = connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        return JSONObject(text)
    }

    private fun showStatus(text: String) {
        hint?.text = text
        hint?.visibility = View.VISIBLE
        hint?.removeCallbacks(hideHint)
    }

    private fun hideStatus() {
        hint?.visibility = View.GONE
    }

    private fun flash(text: String) {
        hint?.text = text
        hint?.visibility = View.VISIBLE
        hint?.removeCallbacks(hideHint)
        hint?.postDelayed(hideHint, 800)
    }
}
