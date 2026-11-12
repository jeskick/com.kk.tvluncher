package com.kk.tvlauncher.data

import android.util.Log
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** 局域网共享里的图片、视频和字幕。播放单独走这里，不改壁纸。 */
object SmbMedia {

    enum class Kind { DIRECTORY, IMAGE, VIDEO, SUBTITLE }

    data class Entry(val name: String, val url: String, val kind: Kind)

    private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "flv", "wmv", "mpg", "mpeg")
    private val SUBTITLE = setOf("srt", "ass", "ssa", "vtt")
    private val accountByHost = ConcurrentHashMap<String, Pair<String, String>>()
    private val sessions = ConcurrentHashMap<SmbRandomAccessFile, CIFSContext>()
    @PublishedApi
    internal val gate = Any()

    /** 同一时间只走一条共享连接，避免统计和播放抢连接把目录列空。 */
    inline fun <T> exclusive(block: () -> T): T = synchronized(gate) { block() }

    /** 列得出来返回列表，连接失败返回 null。空列表表示目录确实是空的。 */
    fun list(dirUrl: String, user: String, pass: String): List<Entry>? = exclusive {
        val url = if (dirUrl.endsWith("/")) dirUrl else "$dirUrl/"
        var blank: List<Entry>? = null
        var last: Exception? = null
        for ((account, password) in accounts(hostOf(url), user, pass)) {
            try {
                val found = listWith(url, account, password)
                if (found.isNotEmpty()) {
                    accountByHost[hostOf(url)] = account to password
                    return found
                }
                blank = found
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException || e is InterruptedException) throw e
                last = e
                Log.w(TAG, "list $url ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (blank == null && last != null) Log.w(TAG, "list failed $url ${last.javaClass.simpleName}: ${last.message}")
        blank
    }

    fun open(fileUrl: String, user: String, pass: String): SmbRandomAccessFile = exclusive {
        var last: Exception? = null
        for ((account, password) in accounts(hostOf(fileUrl), user, pass)) {
            val ctx = context(account, password)
            try {
                val file = SmbFile(fileUrl, ctx)
                val raf = SmbRandomAccessFile(file, "r")
                raf.length()
                sessions[raf] = ctx
                accountByHost[hostOf(fileUrl)] = account to password
                return raf
            } catch (e: Exception) {
                runCatching { ctx.close() }
                last = e
            }
        }
        throw IOException(last?.message ?: "打不开共享文件")
    }

    /** 关掉文件，并释放这条共享连接。统计和列目录用完必须关，否则电视上很快就进不了目录。 */
    fun close(file: SmbRandomAccessFile) {
        exclusive {
            runCatching { file.close() }
            sessions.remove(file)?.let { runCatching { it.close() } }
        }
    }

    fun parent(url: String): String {
        val cut = url.trimEnd('/').lastIndexOf('/')
        return if (cut <= "smb://".length) url else url.substring(0, cut + 1)
    }

    fun subtitlesFor(video: Entry, siblings: List<Entry>): List<Entry> {
        val subs = siblings.filter { it.kind == Kind.SUBTITLE }
        val stem = video.name.substringBeforeLast('.')
        if (stem.isNotBlank()) {
            val matched = subs.filter { entry ->
                val name = entry.name.substringBeforeLast('.')
                name.equals(stem, true) || name.startsWith(stem, true) || stem.startsWith(name, true)
            }.sortedBy { if (it.name.substringBeforeLast('.').equals(stem, true)) 0 else 1 }
            if (matched.isNotEmpty()) return matched
        }
        return if (subs.size == 1) subs else emptyList()
    }

    /** 把图片一次读完。走已经登录成功的账号，避免每次先用错误账号白等一轮。 */
    fun readAll(fileUrl: String, user: String, pass: String, maxBytes: Int = 64 * 1024 * 1024): ByteArray? = exclusive {
        val raf = try {
            open(fileUrl, user, pass)
        } catch (_: Exception) {
            return null
        }
        try {
            val size = raf.length()
            if (size <= 0L || size > maxBytes) return null
            val buf = ByteArray(size.toInt())
            var off = 0
            while (off < buf.size) {
                val n = raf.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            if (off == buf.size) buf else buf.copyOf(off)
        } catch (_: Exception) {
            null
        } finally {
            close(raf)
        }
    }

    /** 文件字节数。读不到时返回 -1。 */
    fun fileSize(fileUrl: String, user: String, pass: String): Long = exclusive {
        val url = fileUrl.trimEnd('/')
        for ((account, password) in accounts(hostOf(url), user, pass)) {
            val ctx = context(account, password)
            try {
                val size = SmbFile(url, ctx).length()
                if (size >= 0) return size
            } catch (_: Exception) {
            } finally {
                runCatching { ctx.close() }
            }
        }
        -1L
    }

    data class Totals(val videos: Int, val images: Int, val bytes: Long, val limited: Boolean)

    /** 只统计这一层里的视频和图片，不进子目录，避免把正在看的列表冲掉。 */
    fun totals(dirUrl: String, user: String, pass: String): Totals? {
        val children = list(dirUrl, user, pass) ?: return null
        var videos = 0
        var images = 0
        var bytes = 0L
        var limited = false
        for (child in children) {
            if (child.kind != Kind.VIDEO && child.kind != Kind.IMAGE) continue
            if (videos + images >= 200) {
                limited = true
                break
            }
            if (child.kind == Kind.VIDEO) videos++ else images++
            val size = fileSize(child.url, user, pass)
            if (size > 0) bytes += size
        }
        return Totals(videos, images, bytes, limited)
    }

    private fun listWith(url: String, user: String, pass: String): List<Entry> {
        val ctx = context(user, pass)
        try {
            val dir = SmbFile(url, ctx)
            val names = dir.list() ?: return emptyList()
            val server = isServerRoot(url)
            return names.mapNotNull { raw ->
                val name = raw.trim().trim('/')
                if (name.isBlank() || name.startsWith(".") || name.endsWith("$")) return@mapNotNull null
                val kind = when {
                    server -> Kind.DIRECTORY
                    ext(name) in IMAGE -> Kind.IMAGE
                    ext(name) in VIDEO -> Kind.VIDEO
                    ext(name) in SUBTITLE -> Kind.SUBTITLE
                    else -> if (isDirectory(ctx, url, name)) Kind.DIRECTORY else return@mapNotNull null
                }
                val full = url.trimEnd('/') + "/" + name + if (kind == Kind.DIRECTORY) "/" else ""
                Entry(name, full, kind)
            }.sortedWith(compareBy({ it.kind != Kind.DIRECTORY }, { it.name.lowercase() }))
        } finally {
            runCatching { ctx.close() }
        }
    }

    private fun isDirectory(ctx: CIFSContext, parent: String, name: String): Boolean {
        return try {
            SmbFile(parent + name, ctx).isDirectory
        } catch (_: Exception) {
            false
        }
    }

    private fun accounts(host: String, user: String, pass: String): List<Pair<String, String>> {
        val tries = mutableListOf<Pair<String, String>>()
        accountByHost[host]?.let { tries += it }
        if (user.isNotBlank()) tries += user to pass
        if (tries.none { it.first.equals("Guest", true) }) tries += "Guest" to ""
        if (user.isBlank()) tries += "" to ""
        return tries.distinct()
    }

    private fun hostOf(url: String): String =
        url.removePrefix("smb://").substringBefore('/').substringBefore(':')

    private fun isServerRoot(url: String): Boolean {
        val rest = url.removePrefix("smb://").trim('/')
        return rest.isNotEmpty() && !rest.contains('/')
    }

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    private fun context(user: String, pass: String) = BaseContext(PropertyConfiguration(Properties().apply {
        setProperty("jcifs.smb.client.minVersion", "SMB202")
        setProperty("jcifs.smb.client.maxVersion", "SMB311")
        setProperty("jcifs.resolveOrder", "DNS")
        setProperty("jcifs.smb.client.dfs.disabled", "true")
        setProperty("jcifs.smb.client.encryptData", "false")
        setProperty("jcifs.smb.client.responseTimeout", "20000")
        setProperty("jcifs.smb.client.soTimeout", "20000")
        setProperty("jcifs.smb.client.connTimeout", "8000")
        setProperty("jcifs.smb.client.signingEnforced", "false")
        setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
    })).let { base ->
        if (user.isNotBlank()) base.withCredentials(NtlmPasswordAuthenticator("", user, pass))
        else base.withCredentials(NtlmPasswordAuthenticator("", "", ""))
    }

    private const val TAG = "SmbMedia"
}
