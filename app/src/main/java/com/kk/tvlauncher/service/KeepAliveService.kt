package com.kk.tvlauncher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * 前台服务：
 *  1. 持有 WiFi 高性能锁 → 熄屏后 WiFi 不断连，ADB 可访问
 *  2. 持有 CPU 部分唤醒锁 → 防止 CPU 完全休眠导致 ADB 超时
 *  3. 每 30 秒向网关发一次心跳，维持 NAT 会话与网络活跃状态
 */
class KeepAliveService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // ── WiFi 高性能锁（防止 WiFi 进入低功耗/断开）────────────────────────
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG)
        wifiLock?.acquire()

        // ── CPU 部分唤醒锁（保持网络栈活跃，让 ADB 不超时）──────────────────
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::cpu")
        wakeLock?.acquire(10 * 60 * 1000L)  // 最长持锁 10 分钟，防止意外死锁

        // ── 升为前台服务（Android 8+ 必须有通知）────────────────────────────
        startForeground(NOTIF_ID, buildNotification())

        // ── 心跳协程：每 30 秒 ping 网关，每 9 分钟续期 WakeLock ───────────
        scope.launch {
            val gateway = getDefaultGateway()
            var tick = 0
            while (isActive) {
                delay(HEARTBEAT_MS)
                try {
                    java.net.InetAddress.getByName(gateway).isReachable(3000)
                } catch (_: Exception) { }
                // 每 18 个心跳（~9分钟）续期 WakeLock，防止 10 分钟超时后失效
                tick++
                if (tick >= 18) {
                    tick = 0
                    if (wakeLock?.isHeld == false) {
                        wakeLock?.acquire(10 * 60 * 1000L)
                    }
                }
            }
        }

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY   // 被系统杀死后自动重启
    }

    override fun onDestroy() {
        scope.cancel()
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun getDefaultGateway(): String {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val dhcp = wm.dhcpInfo
            val gw = dhcp.gateway
            // 小端整数转 IP 字符串
            String.format(
                "%d.%d.%d.%d",
                gw and 0xff, (gw shr 8) and 0xff,
                (gw shr 16) and 0xff, (gw shr 24) and 0xff
            )
        } catch (e: Exception) {
            "8.8.8.8"  // 回退到公共 DNS
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "kk_launcher_keepalive"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            val ch = NotificationChannel(
                channelId, "网络保活",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(ch)
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("KK Launcher")
            .setContentText("网络保活运行中")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val NOTIF_ID = 1001
        private const val HEARTBEAT_MS = 30_000L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }
    }
}
