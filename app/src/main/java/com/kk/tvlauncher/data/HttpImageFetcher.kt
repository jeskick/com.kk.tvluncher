package com.kk.tvlauncher.data

import android.util.Log
import java.net.URL

/**
 * 通过 HTTP 目录列表获取图片文件 URL 列表。
 * 路由器用 busybox httpd 把共享目录暴露为 HTTP 服务，
 * Android 直接 HTTP 下载图片，完全绕开 SMB 协议问题。
 */
object HttpImageFetcher {

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    /**
     * 解析 busybox httpd / nginx / Apache 目录列表，提取所有图片链接。
     * @param baseUrl 形如 http://NAS_IP:8080/
     * @return 图片完整 URL 列表
     */
    fun listImages(baseUrl: String): List<String> {
        return try {
            val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

            val html = URL(normalizedBase).openConnection().apply {
                connectTimeout = 8_000
                readTimeout   = 10_000
            }.getInputStream().bufferedReader().readText()

            // 解析 href="filename.jpg" / href='filename.jpg' 格式
            val regex = Regex("""href=['"]([^'"?#]+\.(jpg|jpeg|png|webp))['"]""",
                RegexOption.IGNORE_CASE)
            val result = regex.findAll(html).mapNotNull { m ->
                val href = m.groupValues[1]
                if (href.startsWith("http", ignoreCase = true)) href
                else normalizedBase + href.trimStart('/')
            }.toList()

            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 判断是否为 HTTP/HTTPS 路径 */
    fun isHttpUrl(path: String) =
        path.startsWith("http://", ignoreCase = true) ||
        path.startsWith("https://", ignoreCase = true)
}
