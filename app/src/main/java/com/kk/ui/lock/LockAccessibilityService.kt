package com.kk.tvlauncher.ui.lock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 锁屏后在系统层拦截。桌面本身收不到设置键、语音键和已经打开的其它应用。
 * 未锁定时不处理任何按键。方向键仍交给桌面，密码可以解开。
 */
class LockAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastHomeAt = 0L
    private var registered = false

    private val recheck = Runnable { bringHomeIfForeign() }

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handler.removeCallbacks(recheck)
            handler.post(recheck)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = info.eventTypes or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.feedbackType = info.feedbackType or AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 50
        serviceInfo = info
        if (!registered) {
            val filter = IntentFilter(DesktopLock.ACTION_LOCK_CHANGED).apply {
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(lockReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(lockReceiver, filter)
            }
            registered = true
        }
        bringHomeIfForeign()
    }

    override fun onDestroy() {
        if (registered) {
            unregisterReceiver(lockReceiver)
            registered = false
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!DesktopLock.isLocked(this)) return
        bringHomeIfForeign()
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, 200)
    }

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!DesktopLock.isLocked(this)) return false
        if (DesktopLock.isPowerKey(event.keyCode)) return false
        if (!ownWindowFocused()) {
            bringHomeIfForeign()
            return true
        }
        return !passThrough(event.keyCode)
    }

    private fun passThrough(code: Int) = code == KeyEvent.KEYCODE_DPAD_UP
        || code == KeyEvent.KEYCODE_DPAD_DOWN
        || code == KeyEvent.KEYCODE_DPAD_LEFT
        || code == KeyEvent.KEYCODE_DPAD_RIGHT
        || code == KeyEvent.KEYCODE_DPAD_CENTER
        || code == KeyEvent.KEYCODE_ENTER
        || code == KeyEvent.KEYCODE_NUMPAD_ENTER
        || code == KeyEvent.KEYCODE_BACK

    private fun ownWindowFocused(): Boolean {
        val pkg = focusedPackage() ?: return true
        return pkg == packageName
    }

    /** 当前焦点窗口的包名。输入法视为自己的窗口，避免误拉回桌面。调用方不必回收。 */
    private fun focusedPackage(): String? {
        val list = windows ?: return null
        try {
            val focused = list.firstOrNull { it.isFocused } ?: return null
            if (focused.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return packageName
            val root = focused.root ?: return null
            return try {
                root.packageName?.toString()
            } finally {
                root.recycle()
            }
        } finally {
            list.forEach { runCatching { it.recycle() } }
        }
    }

    private fun bringHomeIfForeign() {
        if (!DesktopLock.isLocked(this)) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return
        val pkg = focusedPackage() ?: return
        if (pkg == packageName || pkg.contains("dream", ignoreCase = true)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastHomeAt < 500) return
        lastHomeAt = now
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(home)
        } catch (_: RuntimeException) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
                ?: return false
            val name = LockAccessibilityService::class.java.name
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val service = info.resolveInfo?.serviceInfo ?: return@any false
                    service.packageName == context.packageName &&
                        (service.name == name || service.name.endsWith("LockAccessibilityService"))
                }
        }
    }
}
