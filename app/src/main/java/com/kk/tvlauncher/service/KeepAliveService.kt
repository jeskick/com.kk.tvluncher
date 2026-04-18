package com.kk.tvlauncher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * 前台服务：
 *  1. 持有 WiFi 高性能锁 → 熄屏后 WiFi 不断连，ADB 可访问
 *  2. 持有 CPU 部分唤醒锁 → 防止 CPU 完全休眠导致 ADB 超时
 *  3. 定时主动发起 TCP 心跳，维持路由器 NAT 表项与 WiFi 链路活跃
 *  4. 熄屏时提高心跳频率（ADB 端口 5555 的 TCP 握手）
 */
class KeepAliveService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var screenOn: Boolean = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON  -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // ── WiFi 高性能锁（防止 WiFi 进入低功耗/断开）────────────────────────
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG)
        wifiLock?.setReferenceCounted(false)
        wifiLock?.acquire()

        // ── CPU 部分唤醒锁（保持网络栈活跃，让 ADB 不超时）──────────────────
        //   不设超时：前台服务生命周期本身有界，在 onDestroy 释放即可，
        //   之前的 10 分钟超时 + 续期逻辑有竞态（isHeld=true 时不续期，
        //   超时后会有几分钟无锁窗口）导致熄屏后 ADB 无响应。
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::cpu")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()

        // 初始化屏幕状态
        val pmCheck = getSystemService(POWER_SERVICE) as PowerManager
        screenOn = pmCheck.isInteractive

        // 监听亮/熄屏广播（动态注册不需要 Manifest 权限）
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })

        // ── 升为前台服务（Android 8+ 必须有通知）────────────────────────────
        startForeground(NOTIF_ID, buildNotification())

        // ── 心跳协程 ──────────────────────────────────────────────────────
        //   亮屏：30s 一次；熄屏：10s 一次（NAT/WiFi 更易掉线时更密集）
        //   心跳方式：TCP connect 到网关或 8.8.8.8:53，比 ICMP 更穿透 Doze
        scope.launch {
            while (isActive) {
                val gateway = getDefaultGateway()
                heartbeat(gateway)
                val interval = if (screenOn) HEARTBEAT_SCREEN_ON_MS else HEARTBEAT_SCREEN_OFF_MS
                delay(interval)
            }
        }
    }

    /** 主动发起 TCP 连接，真正打通 NAT 表项（ICMP 在部分路由上会丢） */
    private fun heartbeat(gateway: String) {
        // 1. 连网关 TCP 53（DNS 常开）— 最稳
        tryTcp(gateway, 53, 2000)
        // 2. 再打一次本机 5555 ADB 端口，保证 ADB TCP keepalive
        tryTcp("127.0.0.1", 5555, 1000)
    }

    private fun tryTcp(host: String, port: Int, timeoutMs: Int) {
        try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            }
        } catch (_: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY   // 被系统杀死后自动重启
    }

    override fun onDestroy() {
        scope.cancel()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
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
        private const val HEARTBEAT_SCREEN_ON_MS  = 30_000L
        private const val HEARTBEAT_SCREEN_OFF_MS = 10_000L  // 熄屏加密心跳

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }
    }
}
