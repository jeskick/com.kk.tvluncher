@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kk.tvlauncher.ui.lan

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

/** 播放共享里的一个文件。视频里点按左右跳 5 秒，按住持续拖动；上下只唤出信息。 */
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
    private var playingUrl: String? = null
    private var progressTicks = 0
    private var playToken = 0
    private var imageToken = 0
    private var scrubDir = 0
    private var scrubbing = false
    private var scrubBase = 0L
    private var scrubPreview = 0L
    private var scrubSince = 0L
    private var lastSeekAt = 0L
    private val frames = LinkedHashMap<String, Frame>(4, 0.75f, true)
    private val hideInfo: Runnable = Runnable { hideOverlay() }
    private val armScrub = Runnable {
        val exo = player ?: return@Runnable
        scrubbing = true
        scrubBase = exo.currentPosition
        scrubPreview = scrubBase
        scrubSince = SystemClock.uptimeMillis()
        lastSeekAt = 0L
        handler.post(scrubTick)
    }
    private val scrubTick = object : Runnable {
        override fun run() {
            if (!scrubbing) return
            val exo = player ?: return
            val elapsed = SystemClock.uptimeMillis() - scrubSince
            val offset = scrubOffset(elapsed)
            val duration = exo.duration
            val target = scrubBase + scrubDir * offset
            scrubPreview = if (duration > 0) target.coerceIn(0L, duration) else max(0L, target)
            timeBar?.visibility = View.VISIBLE
            timeBar?.setTimes(scrubPreview, duration)
            val now = SystemClock.uptimeMillis()
            if (now - lastSeekAt >= 300) {
                lastSeekAt = now
                exo.seekTo(scrubPreview)
            }
            handler.postDelayed(this, 50)
        }
    }
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
        handler.removeCallbacks(armScrub)
        handler.removeCallbacks(scrubTick)
        playToken++
        synchronized(frames) { imageToken++ }
        dropImages()
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
        val video = current()?.kind == SmbMedia.Kind.VIDEO
        if (event.action == KeyEvent.ACTION_UP) {
            if (video && (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                onVideoSeekUp()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (video) {
                    if (event.repeatCount == 0) onVideoSeekDown(-5) else reveal()
                } else if (event.repeatCount == 0) {
                    step(-1)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (video) {
                    if (event.repeatCount == 0) onVideoSeekDown(5) else reveal()
                } else if (event.repeatCount == 0) {
                    step(1)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.repeatCount == 0 && video) toggle()
                reveal()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.repeatCount != 0) return true
                if (video) reveal() else step(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.repeatCount != 0) return true
                if (video) reveal() else step(1)
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
        val token = ++playToken
        val generation = synchronized(frames) { ++imageToken }
        releasePlayer()
        if (item.kind == SmbMedia.Kind.IMAGE) {
            surface?.visibility = View.GONE
            picture?.visibility = View.VISIBLE
            timeBar?.visibility = View.GONE
            showImage(item, generation)
            return
        }
        dropImages()
        picture?.visibility = View.GONE
        surface?.visibility = View.VISIBLE
        timeBar?.visibility = View.VISIBLE
        timeBar?.setTimes(0L, C.TIME_UNSET)
        audioDropped = false
        reveal()
        Thread {
            val prepared = runCatching { prepareSubtitles(item) }.getOrNull()
            runOnUiThread {
                if (token != playToken || isDestroyed || current()?.url != item.url) return@runOnUiThread
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
                if (token != playToken || isDestroyed || current()?.url != item.url) return@runOnUiThread
                fileBytes = size
                if (info?.visibility == View.VISIBLE) info?.text = describe(item)
            }
        }.start()
    }

    private fun showImage(item: SmbMedia.Entry, generation: Int) {
        val ready = synchronized(frames) { frames[item.url] }
        if (ready != null && !ready.bitmap.isRecycled) {
            bindImage(item, ready)
            prefetchAround(generation)
            return
        }
        Thread {
            val frame = decodeImage(item.url) ?: return@Thread
            val stored = synchronized(frames) {
                if (generation != imageToken) {
                    frame.bitmap.recycle()
                    null
                } else {
                    rememberFrame(item.url, frame)
                    frame
                }
            } ?: return@Thread
            runOnUiThread {
                if (generation != imageToken || isDestroyed || current()?.url != item.url) return@runOnUiThread
                bindImage(item, stored)
                prefetchAround(generation)
            }
        }.start()
    }

    private fun bindImage(item: SmbMedia.Entry, frame: Frame) {
        if (frame.bitmap.isRecycled) return
        picture?.setImageBitmap(frame.bitmap)
        fileBytes = frame.bytes
        val resolution = if (frame.width > 0 && frame.height > 0) "${frame.width}×${frame.height}" else null
        info?.text = line(item.name, resolution, null, LanLibraryActivity.formatSize(fileBytes))
        reveal()
    }

    private fun prefetchAround(generation: Int) {
        val index = LanPlayRequest.index
        for (next in intArrayOf(index - 1, index + 1)) {
            val item = LanPlayRequest.items.getOrNull(next) ?: continue
            if (item.kind != SmbMedia.Kind.IMAGE) continue
            if (synchronized(frames) { frames.containsKey(item.url) }) continue
            Thread {
                if (generation != imageToken) return@Thread
                val frame = decodeImage(item.url) ?: return@Thread
                synchronized(frames) {
                    if (generation != imageToken || frames.containsKey(item.url)) {
                        frame.bitmap.recycle()
                        return@Thread
                    }
                    rememberFrame(item.url, frame)
                }
            }.start()
        }
    }

    private fun decodeImage(url: String): Frame? {
        val bytes = SmbMedia.readAll(url, LanPlayRequest.user, LanPlayRequest.pass, 32 * 1024 * 1024) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val metrics = resources.displayMetrics
        val maxW = metrics.widthPixels.coerceIn(1280, 3840)
        val maxH = metrics.heightPixels.coerceIn(720, 2160)
        var sample = 1
        while (sample < 64 && (width / sample > maxW * 2 || height / sample > maxH * 2)) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return Frame(bitmap, width, height, bytes.size.toLong())
    }

    private fun rememberFrame(url: String, frame: Frame) {
        val previous = frames.put(url, frame)
        if (previous != null && previous.bitmap !== frame.bitmap && !previous.bitmap.isRecycled) {
            previous.bitmap.recycle()
        }
        while (frames.size > 3) {
            val eldest = frames.entries.first()
            if (eldest.key == url) break
            frames.remove(eldest.key)
            if (!eldest.value.bitmap.isRecycled) eldest.value.bitmap.recycle()
        }
    }

    private fun dropImages() {
        picture?.setImageDrawable(null)
        synchronized(frames) {
            frames.values.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            frames.clear()
        }
    }

    private fun startVideo(
        item: SmbMedia.Entry,
        subs: List<MediaItem.SubtitleConfiguration>,
        memory: Map<String, ByteArray>
    ) {
        if (isDestroyed || current()?.url != item.url) return
        playingUrl = item.url
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
        val resumeAt = savedPosition(item.url)
        if (resumeAt > 0L) exo.setMediaItem(builder.build(), resumeAt) else exo.setMediaItem(builder.build())
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

    private fun onVideoSeekDown(seconds: Int) {
        handler.removeCallbacks(armScrub)
        if (scrubbing) finishScrub()
        scrubDir = if (seconds < 0) -1 else 1
        seek(seconds)
        reveal()
        handler.postDelayed(armScrub, 450)
    }

    private fun onVideoSeekUp() {
        handler.removeCallbacks(armScrub)
        if (scrubbing) finishScrub() else reveal()
    }

    private fun finishScrub() {
        handler.removeCallbacks(scrubTick)
        scrubbing = false
        player?.seekTo(scrubPreview)
        refreshBar()
        reveal()
    }

    private fun scrubOffset(elapsedMs: Long): Long {
        val t = elapsedMs / 1000.0
        val seconds = if (t <= 1.0) t * 12 else 12 + (t - 1) * (16 + (t - 1) * 24)
        return (seconds * 1000).toLong()
    }

    private fun progressStore() = getSharedPreferences("lan_progress", MODE_PRIVATE)

    private fun savedPosition(url: String): Long = progressStore().getLong(url, 0L)

    private fun rememberPosition(url: String, position: Long, duration: Long, clearStart: Boolean = false) {
        if (position < 0L || duration <= 0L) return
        val editor = progressStore().edit()
        val remaining = duration - position
        when {
            remaining <= 8_000L && position > 0L -> editor.remove(url)
            position >= 5_000L -> {
                editor.putLong(url, position)
                if (progressStore().all.size > 300) {
                    progressStore().all.keys.firstOrNull { it != url }?.let { editor.remove(it) }
                }
            }
            clearStart -> editor.remove(url)
            else -> return
        }
        editor.apply()
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
        if (!scrubbing) timeBar?.setTimes(exo.currentPosition, exo.duration)
        val item = current() ?: return
        if (item.kind == SmbMedia.Kind.VIDEO && info?.visibility == View.VISIBLE) {
            info?.text = describe(item)
        }
        if (scrubbing || !exo.isPlaying) return
        if (++progressTicks < 10) return
        progressTicks = 0
        rememberPosition(item.url, exo.currentPosition, exo.duration)
    }

    private fun hideOverlay() {
        if (scrubbing) {
            handler.postDelayed(hideInfo, 4_000)
            return
        }
        info?.visibility = View.GONE
        if (current()?.kind == SmbMedia.Kind.VIDEO) timeBar?.visibility = View.GONE
    }

    private fun reveal() {
        val item = current() ?: return
        if (item.kind == SmbMedia.Kind.VIDEO) {
            info?.text = describe(item)
            timeBar?.visibility = View.VISIBLE
        }
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
        val exo = player
        val url = playingUrl
        if (exo != null && url != null) {
            val position = if (scrubbing) scrubPreview else exo.currentPosition
            val ready = exo.playbackState == Player.STATE_READY || exo.playbackState == Player.STATE_ENDED
            rememberPosition(url, position, exo.duration, clearStart = ready)
        }
        playingUrl = null
        scrubbing = false
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

    private class Frame(val bitmap: Bitmap, val width: Int, val height: Int, val bytes: Long)

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
