@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kk.tvlauncher.ui.lan

import android.app.Activity
import android.graphics.BitmapFactory
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
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.kk.tvlauncher.data.PlaybackSource
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
    private var info: TextView? = null
    private var cues: TextView? = null
    private var timeBar: TimeBar? = null
    private var audioDropped = false
    private var wantedSubtitle: String? = null
    private var subtitlePicked = false
    private var fileBytes = -1L
    private val images = LinkedHashMap<String, ByteArray>()
    private val hideInfo = Runnable { info?.visibility = View.GONE }
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
        info = label(Gravity.TOP or Gravity.START, 16f).apply {
            setPadding(dp(28), dp(24), dp(28), 0)
            visibility = View.GONE
        }
        cues = label(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 22f).apply {
            setPadding(dp(48), 0, dp(48), dp(96))
            visibility = View.GONE
        }
        timeBar = TimeBar(this)
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(picture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(info, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(cues, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
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
        handler.removeCallbacks(hideInfo)
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
                reveal()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (video && event.repeatCount == 0) seek(10) else if (!video && event.repeatCount == 0) step(1)
                reveal()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.repeatCount == 0 && video) toggle()
                reveal()
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
        fileBytes = -1L
        wantedSubtitle = null
        subtitlePicked = false
        info?.text = ""
        info?.visibility = View.GONE
        cues?.text = ""
        cues?.visibility = View.GONE
        releasePlayer()
        if (item.kind == SmbMedia.Kind.IMAGE) {
            surface?.visibility = View.GONE
            picture?.visibility = View.VISIBLE
            timeBar?.visibility = View.GONE
            showImage(item)
            return
        }
        picture?.visibility = View.GONE
        surface?.visibility = View.VISIBLE
        timeBar?.visibility = View.VISIBLE
        timeBar?.setTimes(0L, C.TIME_UNSET)
        audioDropped = false
        reveal()
        Thread {
            val prepared = runCatching { prepareSubtitles(item) }.getOrNull()
            runOnUiThread {
                if (current()?.url != item.url) return@runOnUiThread
                val subs = prepared?.first ?: emptyList()
                val memory = prepared?.second ?: emptyMap()
                startVideo(item, subs, memory)
            }
        }.start()
        Thread {
            val size = runCatching {
                SmbMedia.fileSize(item.url, LanPlayRequest.user, LanPlayRequest.pass)
            }.getOrDefault(-1L)
            runOnUiThread {
                if (current()?.url != item.url) return@runOnUiThread
                fileBytes = size
                if (info?.visibility == View.VISIBLE) info?.text = describe(item)
            }
        }.start()
    }

    private fun showImage(item: SmbMedia.Entry) {
        val ready = synchronized(images) { images[item.url] }
        if (ready != null) {
            bindImage(item, ready)
            prefetchAround()
            return
        }
        Thread {
            val bytes = SmbMedia.readAll(item.url, LanPlayRequest.user, LanPlayRequest.pass) ?: return@Thread
            synchronized(images) {
                images[item.url] = bytes
                while (images.size > 4) images.remove(images.keys.first())
            }
            runOnUiThread {
                if (current()?.url != item.url) return@runOnUiThread
                bindImage(item, bytes)
                prefetchAround()
            }
        }.start()
    }

    private fun bindImage(item: SmbMedia.Entry, bytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        picture?.setImageBitmap(bitmap)
        fileBytes = bytes.size.toLong()
        val resolution = if (bitmap.width > 0 && bitmap.height > 0) "${bitmap.width}×${bitmap.height}" else null
        info?.text = line(item.name, resolution, null, LanLibraryActivity.formatSize(fileBytes))
        reveal()
    }

    private fun prefetchAround() {
        val index = LanPlayRequest.index
        for (next in intArrayOf(index - 1, index + 1)) {
            val item = LanPlayRequest.items.getOrNull(next) ?: continue
            if (item.kind != SmbMedia.Kind.IMAGE) continue
            if (synchronized(images) { images.containsKey(item.url) }) continue
            Thread {
                val bytes = SmbMedia.readAll(item.url, LanPlayRequest.user, LanPlayRequest.pass) ?: return@Thread
                synchronized(images) {
                    if (images.containsKey(item.url)) return@Thread
                    images[item.url] = bytes
                    while (images.size > 4) images.remove(images.keys.first())
                }
            }.start()
        }
    }

    private fun startVideo(
        item: SmbMedia.Entry,
        subs: List<MediaItem.SubtitleConfiguration>,
        memory: Map<String, ByteArray>
    ) {
        if (current()?.url != item.url) return
        wantedSubtitle = subs.firstOrNull()?.label
        val builder = MediaItem.Builder()
            .setUri(item.url)
            .setSubtitleConfigurations(subs)
        mimeOf(item.name)?.let { builder.setMimeType(it) }
        val dataSource = PlaybackSource.Factory(LanPlayRequest.user, LanPlayRequest.pass, memory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 8_000, 12_000)
            .setTargetBufferBytes(48 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setRenderersFactory(LanRenderersFactory(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, DefaultExtractorsFactory()))
            .build()
        exo.setVideoSurfaceView(surface)
        exo.setWakeMode(C.WAKE_MODE_LOCAL)
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage("zh")
            .setSelectUndeterminedTextLanguage(true)
            .build()
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

            override fun onTracksChanged(tracks: Tracks) {
                if (subtitlePicked) return
                val label = wantedSubtitle ?: return
                for (group in tracks.groups) {
                    if (group.type != C.TRACK_TYPE_TEXT) continue
                    for (index in 0 until group.length) {
                        if (group.getTrackFormat(index).label != label) continue
                        subtitlePicked = true
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                            .build()
                        return
                    }
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                val text = cueGroup.cues.mapNotNull { it.text }.joinToString("\n")
                cues?.text = text
                cues?.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
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

    private fun mimeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv", "webm" -> MimeTypes.VIDEO_MATROSKA
        "mp4", "m4v", "mov" -> MimeTypes.VIDEO_MP4
        "mp3" -> MimeTypes.AUDIO_MPEG
        "ts" -> MimeTypes.VIDEO_MP2T
        else -> null
    }

    private fun prepareSubtitles(
        video: SmbMedia.Entry
    ): Pair<List<MediaItem.SubtitleConfiguration>, Map<String, ByteArray>> {
        var siblings = LanPlayRequest.folder
        if (SmbMedia.subtitlesFor(video, siblings).isEmpty()) {
            val fresh = runCatching {
                SmbMedia.list(SmbMedia.parent(video.url), LanPlayRequest.user, LanPlayRequest.pass).orEmpty()
            }.getOrDefault(emptyList())
            if (fresh.isNotEmpty()) siblings = fresh
        }
        val configs = ArrayList<MediaItem.SubtitleConfiguration>()
        val memory = HashMap<String, ByteArray>()
        SmbMedia.subtitlesFor(video, siblings).forEachIndexed { index, sub ->
            val raw = SmbMedia.readAll(sub.url, LanPlayRequest.user, LanPlayRequest.pass, 8 * 1024 * 1024)
                ?: return@forEachIndexed
            val ext = sub.name.substringAfterLast('.', "").lowercase()
            val text = if (ext == "ass" || ext == "ssa") assToSrt(String(raw, Charsets.UTF_8)) else null
            val payload = text?.toByteArray(Charsets.UTF_8) ?: raw
            if (payload.isEmpty()) return@forEachIndexed
            val mime = if (text == null && ext == "vtt") MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            val uri = Uri.parse("mem://subtitle/$index")
            memory[uri.toString()] = payload
            configs += MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(mime)
                .setLabel(sub.name)
                .setLanguage("zh")
                .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        return configs to memory
    }

    private fun assToSrt(source: String): String {
        val out = StringBuilder()
        var index = 1
        for (raw in source.split('\n', '\r')) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (!line.startsWith("Dialogue:", true)) continue
            val parts = line.substringAfter(':').trim().split(',', limit = 10)
            if (parts.size < 10) continue
            val start = srtTime(parts[1].trim()) ?: continue
            val end = srtTime(parts[2].trim()) ?: continue
            val cue = stripAssTags(parts[9])
                .replace("\\N", "\n", true)
                .replace("\\n", "\n", true)
                .replace("\\h", " ", true)
                .trim()
            if (cue.isBlank()) continue
            out.append(index++).append('\n')
            out.append(start).append(" --> ").append(end).append('\n')
            out.append(cue).append("\n\n")
        }
        return out.toString()
    }

    private fun stripAssTags(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index] == '{') {
                val end = text.indexOf('}', index + 1)
                if (end >= 0) {
                    index = end + 1
                    continue
                }
            }
            out.append(text[index])
            index++
        }
        return out.toString()
    }

    private fun srtTime(raw: String): String? {
        val match = Regex("""(\d+):(\d{2}):(\d{2})[.](\d{1,3})""").find(raw) ?: return null
        val hour = match.groupValues[1].toInt()
        val fraction = match.groupValues[4].padEnd(3, '0').take(3)
        return "%02d:%s:%s,%s".format(hour, match.groupValues[2], match.groupValues[3], fraction)
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
        val item = current() ?: return
        if (item.kind == SmbMedia.Kind.VIDEO && info?.visibility == View.VISIBLE) {
            info?.text = describe(item)
        }
    }

    private fun reveal() {
        val item = current() ?: return
        if (item.kind == SmbMedia.Kind.VIDEO) info?.text = describe(item)
        info?.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 4_000)
    }

    private fun describe(item: SmbMedia.Entry): String {
        val format = player?.videoFormat
        val resolution = format?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" }
        val bps = format?.bitrate ?: Format.NO_VALUE
        val duration = player?.duration ?: C.TIME_UNSET
        val rate = when {
            bps > 0 -> formatRate(bps)
            fileBytes > 0 && duration > 0 -> formatRate((fileBytes * 8_000L / duration).toInt())
            else -> null
        }
        val size = if (fileBytes > 0) LanLibraryActivity.formatSize(fileBytes) else null
        return line(item.name, resolution, rate, size)
    }

    private fun line(name: String, resolution: String?, rate: String?, size: String?): String {
        val meta = listOfNotNull(resolution, rate, size).joinToString("   ·   ")
        return if (meta.isBlank()) name else "$name\n$meta"
    }

    private fun formatRate(bps: Int): String {
        val mbps = bps / 1_000_000.0
        return if (mbps >= 1) String.format("%.1f Mb/s", mbps) else "${bps / 1000} kb/s"
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

}

object LanPlayRequest {
    var user: String = ""
    var pass: String = ""
    var items: List<SmbMedia.Entry> = emptyList()
    var folder: List<SmbMedia.Entry> = emptyList()
    var index: Int = 0
}
