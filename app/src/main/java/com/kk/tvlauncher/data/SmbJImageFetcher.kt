package com.kk.tvlauncher.data

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.util.concurrent.TimeUnit

/**
 * 使用 SMBJ 库访问 SMB2/3 共享，直接通过 TCP 445 端口连接。
 * 不依赖 NetBIOS 名称解析，兼容 GL.iNet 路由器的 SMB2 配置。
 */
object SmbJImageFetcher {

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    private val config: SmbConfig = SmbConfig.builder()
        .withTimeout(15, TimeUnit.SECONDS)
        .withReadTimeout(20, TimeUnit.SECONDS)
        .withDfsEnabled(false)
        .withDialects(SMB2Dialect.SMB_2_1)
        .withSigningRequired(false)
        .build()

    /** 单例 SMBClient，内含线程池，避免每次调用都重建 */
    private val client: SMBClient by lazy { SMBClient(config) }

    /**
     * 解析 smb://host/share/path 格式，返回 (host, share, path) 三元组
     */
    private fun parseSmbUrl(url: String): Triple<String, String, String>? {
        // 支持 smb://host/share/ 和 \\host\share 两种格式
        return try {
            val normalized = if (url.startsWith("smb://")) {
                url.removePrefix("smb://")
            } else {
                url.replace('\\', '/').trimStart('/')
            }
            val parts = normalized.trimEnd('/').split("/")
            if (parts.size < 2) return null
            val host  = parts[0]
            val share = parts[1]
            val path  = if (parts.size > 2) parts.drop(2).joinToString("\\") else ""
            Triple(host, share, path)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 原始 TCP 测试：连接 host:445 并读取前 4 字节，验证 smbd 是否响应。
     */
    fun testTcp(host: String): String {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(host, 445), 5000)
            socket.soTimeout = 5000
            val buf = ByteArray(4)
            val n = try { socket.getInputStream().read(buf) } catch (e: Exception) { -1 }
            socket.close()
            "connected, read=$n bytes=${buf.take(if(n>0) n else 0).toByteArray().joinToString(",")}"
        } catch (e: Exception) {
            "FAILED [${e.javaClass.simpleName}]: ${e.message}"
        }
    }

    /**
     * 列出 SMB 共享目录下所有图片文件，返回 smb:// URL 列表。
     */
    fun listImages(smbUrl: String, user: String = "", pass: String = ""): List<String> {
        val (host, share, dirPath) = parseSmbUrl(smbUrl) ?: run {
            return emptyList()
        }


        return try {
            client.connect(host).use { connection ->
                val auth = if (user.isNotBlank())
                    AuthenticationContext(user, pass.toCharArray(), "")
                else
                    AuthenticationContext.anonymous()

                val session = connection.authenticate(auth)
                val diskShare = session.connectShare(share) as DiskShare

                val files = diskShare.list(dirPath.ifEmpty { "" })

                val result = files.mapNotNull { info ->
                    val name = info.fileName
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in IMAGE_EXT && !name.startsWith(".")) {
                        val filePath = if (dirPath.isEmpty()) name else "$dirPath\\$name"
                        "smb://$host/$share/${filePath.replace('\\', '/')}"
                    } else null
                }

                diskShare.close()
                result
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 下载 SMB 图片为 ByteArray，供 Glide 加载。
     */
    fun loadBytes(smbUrl: String, user: String = "", pass: String = ""): ByteArray? {
        val (host, share, filePath) = parseSmbUrl(smbUrl) ?: return null

        return try {
            client.connect(host).use { connection ->
                val auth = if (user.isNotBlank())
                    AuthenticationContext(user, pass.toCharArray(), "")
                else
                    AuthenticationContext.anonymous()

                val session = connection.authenticate(auth)
                val diskShare = session.connectShare(share) as DiskShare

                val smbPath = filePath.replace('/', '\\')
                val file = diskShare.openFile(
                    smbPath,
                    setOf(AccessMask.GENERIC_READ),
                    null,
                    setOf(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
                val bytes = file.inputStream.readBytes()
                file.close()
                diskShare.close()
                bytes
            }
        } catch (e: Exception) {
            null
        }
    }
}
