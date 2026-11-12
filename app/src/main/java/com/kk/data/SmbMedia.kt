package com.kk.tvlauncher.data

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
    @PublishedApi
    internal val gate = Any()

    /** 同一时间只走一条共享连接，避免统计和播放抢连接把目录列空。 */
    inline fun <T> exclusive(block: () -> T): T = synchronized(gate) { block() }

    /** 列得出来返回列表，连接失败返回 null。空列表表示目录确实是空的。 */
    fun list(dirUrl: String, user: String, pass: String): List<Entry>? = exclusive {
        val url = if (dirUrl.endsWith("/")) dirUrl else "$dirUrl/"
        var blank: List<Entry>? = null
        for ((account, password) in accounts(hostOf(url), user, pass)) {
            val found = runCatching { listWith(url, account, password) }.getOrNull() ?: continue
            if (found.isNotEmpty()) {
                accountByHost[hostOf(url)] = account to password
                return found
            }
            blank = found
        }
        blank
    }

    fun open(fileUrl: String, user: String, pass: String): SmbRandomAccessFile = exclusive {
        var last: Exception? = null
        for ((account, password) in accounts(hostOf(fileUrl), user, pass)) {
            try {
                val file = SmbFile(fileUrl, context(account, password))
                val raf = SmbRandomAccessFile(file, "r")
                raf.length()
                accountByHost[hostOf(fileUrl)] = account to password
                return raf
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException(last?.message ?: "打不开共享文件")
    }

    fun parent(url: String): String {
        val cut = url.trimEnd('/').lastIndexOf('/')
        return if (cut <= "smb://".length) url else url.substring(0, cut + 1)
    }

    fun subtitlesFor(video: Entry, siblings: List<Entry>): List<Entry> {
        val stem = video.name.substringBeforeLast('.')
        if (stem.isBlank()) return emptyList()
        return siblings.filter { entry ->
            entry.kind == Kind.SUBTITLE && entry.name.substringBeforeLast('.').startsWith(stem)
        }.sortedBy { if (it.name.substringBeforeLast('.') == stem) 0 else 1 }
    }

    /** 文件字节数。读不到时返回 -1。 */
    fun fileSize(fileUrl: String, user: String, pass: String): Long = exclusive {
        val url = fileUrl.trimEnd('/')
        for ((account, password) in accounts(hostOf(url), user, pass)) {
            val size = runCatching { SmbFile(url, context(account, password)).length() }.getOrDefault(-1L)
            if (size >= 0) return size
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
        val dir = SmbFile(url, context(user, pass))
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
                else -> if (isDirectory(url, name, user, pass)) Kind.DIRECTORY else return@mapNotNull null
            }
            val full = url.trimEnd('/') + "/" + name + if (kind == Kind.DIRECTORY) "/" else ""
            Entry(name, full, kind)
        }.sortedWith(compareBy({ it.kind != Kind.DIRECTORY }, { it.name.lowercase() }))
    }

    private fun isDirectory(parent: String, name: String, user: String, pass: String): Boolean {
        return try {
            SmbFile(parent + name, context(user, pass)).isDirectory
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
        setProperty("jcifs.smb.client.tcpNoDelay", "true")
        setProperty("jcifs.smb.client.rcv_buf_size", "1048576")
        setProperty("jcifs.smb.client.snd_buf_size", "1048576")
        setProperty("jcifs.smb.client.signingEnforced", "false")
        setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
    })).let { base ->
        if (user.isNotBlank()) base.withCredentials(NtlmPasswordAuthenticator("", user, pass))
        else base.withCredentials(NtlmPasswordAuthenticator("", "", ""))
    }
}
