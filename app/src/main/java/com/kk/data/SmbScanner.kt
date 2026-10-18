package com.kk.tvlauncher.data

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object SmbScanner {

    /** 扫描局域网中开放 SMB 445 端口的主机，返回 IP 列表 */
    suspend fun scan(
        context: Context,
        timeoutMs: Int = 600,
        onFound: (String) -> Unit = {}
    ): List<String> = withContext(Dispatchers.IO) {
        val base = getSubnetBase(context) ?: return@withContext emptyList()
        val found = mutableListOf<String>()
        // 一次拨太多连接时，后面的主机会连不上，Windows 电脑就扫丢了。
        for (chunk in (1..254).chunked(32)) {
            found += chunk.map { i ->
                async {
                    val host = "$base.$i"
                    runCatching {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(host, 445), timeoutMs)
                            onFound(host)
                            host
                        }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        found.sorted()
    }

    /** 获取本机所在子网前缀，如 192.168.1 */
    private fun getSubnetBase(context: Context): String? {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ip = wm?.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        val a = ip and 0xFF
        val b = (ip shr 8) and 0xFF
        val c = (ip shr 16) and 0xFF
        return "$a.$b.$c"
    }
}
