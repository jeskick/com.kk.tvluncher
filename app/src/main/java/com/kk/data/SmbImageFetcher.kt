package com.kk.tvlauncher.data

import android.util.Log
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.InputStream
import java.util.Properties

object SmbImageFetcher {

    /**
     * 构建 CIFS 上下文，同时支持 SMB1 和 SMB2/3。
     * 路由器一般只支持 SMB1，电视在内网直连时无 NetBIOS 解析问题。
     */
    private fun buildContext(user: String = "", pass: String = "", forList: Boolean = false): CIFSContext {
        val props = Properties().apply {
            // 路由器 server min protocol = SMB2
            setProperty("jcifs.smb.client.minVersion",          "SMB202")
            setProperty("jcifs.smb.client.maxVersion",          "SMB311")
            // 直接用 DNS 解析 IP，避免 NetBIOS 解析问题
            setProperty("jcifs.resolveOrder",                   "DNS")
            // 禁用 DFS
            setProperty("jcifs.smb.client.dfs.disabled",        "true")
            // smb.conf: smb encrypt = desired（非 required），客户端不强制加密
            setProperty("jcifs.smb.client.encryptData",         "false")
            setProperty("jcifs.smb.client.responseTimeout",     if (forList) "6000" else "30000")
            setProperty("jcifs.smb.client.soTimeout",           if (forList) "7000" else "35000")
            setProperty("jcifs.smb.client.connTimeout",         if (forList) "4000" else "20000")
            // 不强制签名
            setProperty("jcifs.smb.client.signingEnforced",     "false")
            setProperty("jcifs.smb.client.ipcSigningEnforced",  "false")
        }
        val base = BaseContext(PropertyConfiguration(props))
        return if (user.isNotBlank()) {
            base.withCredentials(NtlmPasswordAuthenticator("", user, pass))
        } else {
            base.withCredentials(NtlmPasswordAuthenticator("", "", ""))
        }
    }

    /**
     * 标准化路径为 smb://host/share/
     * \\192.168.8.1\kk  →  smb://192.168.8.1/kk/
     */
    fun normalizePath(raw: String, user: String = "", pass: String = ""): String {
        return if (raw.startsWith("smb://")) {
            raw.trimEnd('/') + "/"
        } else {
            val cleaned = raw.replace('\\', '/').trimStart('/')
            "smb://$cleaned/"
        }
    }

    /** 列出目录下所有图片文件，返回完整 smb:// URL 列表 */
    fun listImages(dirUrl: String, user: String = "", pass: String = ""): List<String> {
        for ((account, password) in accounts(user, pass)) {
            val found = listImagesWith(dirUrl, account, password)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun listImagesWith(dirUrl: String, user: String, pass: String): List<String> {
        return try {
            val ctx = buildContext(user, pass)
            val url = if (dirUrl.endsWith("/")) dirUrl else "$dirUrl/"
            val dir = SmbFile(url, ctx)

            // 列出所有条目，过滤图片文件
            val allEntries = dir.list() ?: return emptyList()
            val result = allEntries.mapNotNull { raw ->
                val name = raw.trim().trim('/')
                if (!isImageName(name)) return@mapNotNull null
                url.trimEnd('/') + "/" + name
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class SmbEntry(val name: String, val url: String, val directory: Boolean)

    /** 列出共享或目录。目录在前，隐藏系统共享（IPC$ 等）。 */
    fun listEntries(dirUrl: String, user: String = "", pass: String = ""): List<SmbEntry> {
        return try {
            val ctx = buildContext(user, pass, forList = true)
            val url = if (dirUrl.endsWith("/")) dirUrl else "$dirUrl/"
            val dir = SmbFile(url, ctx)
            if (isServerRoot(url)) {
                val names = dir.list() ?: return emptyList()
                return names.mapNotNull { raw ->
                    val name = raw.trim().trimEnd('/')
                    if (name.isBlank() || name.startsWith(".") || name.endsWith("$")) return@mapNotNull null
                    SmbEntry(name, url + name + "/", true)
                }.sortedBy { it.name.lowercase() }
            }
            val names = dir.list() ?: return emptyList()
            return names.mapNotNull { raw ->
                val name = raw.trim().trim('/')
                if (name.isBlank() || name.startsWith(".") || name.endsWith("$")) return@mapNotNull null
                val image = isImageName(name)
                val isDir = if (image) {
                    false
                } else {
                    try {
                        SmbFile(url + name, ctx).isDirectory
                    } catch (_: Exception) {
                        false
                    }
                }
                if (!image && !isDir) return@mapNotNull null
                val full = url.trimEnd('/') + "/" + name + if (isDir) "/" else ""
                SmbEntry(name, full, isDir)
            }.sortedWith(compareBy({ !it.directory }, { it.name.lowercase() }))
        } catch (e: Exception) {
            Log.w("SmbImageFetcher", "listEntries $dirUrl: ${e.message}")
            emptyList()
        }
    }

    private fun isServerRoot(url: String): Boolean {
        val rest = url.removePrefix("smb://").trim('/')
        return rest.isNotEmpty() && !rest.contains('/')
    }

    fun isImageName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp" || ext == "gif" || ext == "bmp"
    }

    /** 打开输入流（调用方负责关闭） */
    fun openStream(fileUrl: String, user: String = "", pass: String = ""): InputStream? {
        return try {
            val ctx = buildContext(user, pass)
            SmbFile(fileUrl, ctx).inputStream
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 在 IO 线程将 SMB 图片完整读入内存，返回 ByteArray。
     * 比 openStream 更安全：调用方不需要管理流的生命周期。
     */
    fun loadBytes(fileUrl: String, user: String = "", pass: String = ""): ByteArray? {
        for ((account, password) in accounts(user, pass)) {
            val bytes = loadBytesWith(fileUrl, account, password)
            if (bytes != null) return bytes
        }
        return null
    }

    private fun loadBytesWith(fileUrl: String, user: String, pass: String): ByteArray? {
        return try {
            val ctx = buildContext(user, pass)
            SmbFile(fileUrl, ctx).inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun accounts(user: String, pass: String): List<Pair<String, String>> {
        val tries = mutableListOf<Pair<String, String>>()
        if (user.isNotBlank()) tries += user to pass
        if (tries.none { it.first.equals("Guest", true) }) tries += "Guest" to ""
        if (user.isBlank()) tries += "" to ""
        return tries
    }
}
