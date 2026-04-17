package com.kk.tvlauncher.data

import android.util.Base64
import android.util.Log
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * 通过 WebDAV PROPFIND 列出图片文件，再用 HTTPS GET 下载图片字节。
 * 支持自签名证书（路由器内网场景），使用 Basic Auth 认证。
 */
object WebDavImageFetcher {

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    // ── 信任所有证书的 SSL 上下文（仅用于局域网内网）──────────────────────
    private val trustAllSsl: SSLSocketFactory by lazy {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = Unit
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        SSLContext.getInstance("TLS").also { it.init(null, tm, SecureRandom()) }.socketFactory
    }

    private val trustAllHostname = HostnameVerifier { _, _ -> true }

    /**
     * WebDAV PROPFIND 列出目录下所有图片文件，返回完整 HTTPS URL。
     * @param baseUrl 形如 https://NAS_IP:6008/share/
     * @param user    用户名
     * @param pass    密码
     */
    fun listImages(baseUrl: String, user: String = "", pass: String = ""): List<String> {
        return try {
            val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

            val conn = (URL(normalized).openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = trustAllSsl
                hostnameVerifier = trustAllHostname
                // Android HttpURLConnection 不允许 PROPFIND，用反射强制设置
                setPropfindMethod(this)
                setRequestProperty("Depth", "1")
                setRequestProperty("Content-Type", "application/xml")
                // 不发送 Authorization 头，使用匿名访问（GL.iNet WebDAV 匿名模式）
                setRequestProperty("User-Agent", "Android/TVLauncher")
                connectTimeout = 8_000
                readTimeout   = 12_000
                doOutput = false
            }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                return emptyList()
            }

            val xml = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            // 从 XML 中提取 <D:href> 或 <href> 路径
            val hrefRegex = Regex("""<[Dd]:?href[^>]*>([^<]+)</[Dd]:?href>""")
            val baseUri = URL(normalized)

            val result = hrefRegex.findAll(xml).mapNotNull { m ->
                val href = m.groupValues[1].trim()
                val filename = href.substringAfterLast("/").lowercase()
                val ext = filename.substringAfterLast(".", "")
                if (ext in IMAGE_EXT) {
                    // href 可能是绝对路径或相对路径
                    if (href.startsWith("http", ignoreCase = true)) href
                    else "${baseUri.protocol}://${baseUri.authority}$href"
                } else null
            }.toList()

            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 下载 WebDAV 图片字节（带 Auth + 自签名证书支持）。
     * 供 MainActivity 在 Dispatchers.IO 中调用后交给 Glide。
     */
    fun loadBytes(imageUrl: String, user: String = "", pass: String = ""): ByteArray? {
        return try {
            val conn = (URL(imageUrl).openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = trustAllSsl
                hostnameVerifier = trustAllHostname
                // 匿名访问，不发送认证头
                connectTimeout = 8_000
                readTimeout   = 20_000
            }
            conn.inputStream.use { it.readBytes() }.also {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 判断是否为 WebDAV（https://）路径 */
    fun isWebDavUrl(path: String) = path.startsWith("https://", ignoreCase = true)

    private fun basicAuth(user: String, pass: String): String {
        val encoded = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    /**
     * Android HttpURLConnection 只允许标准 HTTP 方法，PROPFIND 会抛出 ProtocolException。
     * 通过反射直接设置 method 字段绕过这个限制。
     */
    private fun setPropfindMethod(conn: HttpsURLConnection) {
        try {
            // 方式1：直接设置 HttpURLConnection 的 method 字段
            val f = java.net.HttpURLConnection::class.java.getDeclaredField("method")
            f.isAccessible = true
            f.set(conn, "PROPFIND")
        } catch (e: Exception) {
            try {
                // 方式2：部分 Android 版本有 delegate 内部对象
                val delegateField = conn.javaClass.getDeclaredField("delegate")
                delegateField.isAccessible = true
                val delegate = delegateField.get(conn)
                val f = delegate!!.javaClass.getDeclaredField("method")
                f.isAccessible = true
                f.set(delegate, "PROPFIND")
            } catch (e2: Exception) {
            }
        }
    }
}
