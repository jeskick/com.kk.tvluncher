package com.kk.tvlauncher.ui.lock

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Calendar

/**
 * 桌面锁定。只维护开关、时段和密码，不碰界面其它状态。
 * 锁定时由 [LockInputGuard] 吃掉按键，并单独盖一层透明「已锁定」。
 */
object DesktopLock {

    const val KEY_ENABLED = "lock_enabled"
    const val KEY_PASSWORD = "lock_password"
    const val KEY_START = "lock_start_min"
    const val KEY_END = "lock_end_min"
    const val KEY_LOCKED = "desktop_locked"
    const val KEY_WINDOW = "lock_window_inside"

    const val DEFAULT_START = 22 * 60
    const val DEFAULT_END = 7 * 60

    val DEFAULT_PASSWORD = intArrayOf(
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT
    )

    private val DPAD = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun isLocked(context: Context) = prefs(context).getBoolean(KEY_LOCKED, false)

    fun password(context: Context): IntArray {
        val raw = prefs(context).getString(KEY_PASSWORD, null)
        if (raw.isNullOrBlank()) return DEFAULT_PASSWORD.copyOf()
        val keys = raw.split(',').mapNotNull { it.toIntOrNull() }.filter { it in DPAD }
        return if (keys.isEmpty()) DEFAULT_PASSWORD.copyOf() else keys.toIntArray()
    }

    fun encode(keys: IntArray) = keys.joinToString(",")

    fun format(keys: IntArray): String = keys.joinToString(" ") { keyName(it) }

    fun keyName(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> "上"
        KeyEvent.KEYCODE_DPAD_DOWN -> "下"
        KeyEvent.KEYCODE_DPAD_LEFT -> "左"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "右"
        else -> "?"
    }

    fun isPowerKey(code: Int) = code == KeyEvent.KEYCODE_POWER
        || code == KeyEvent.KEYCODE_TV_POWER
        || code == KeyEvent.KEYCODE_SLEEP
        || code == KeyEvent.KEYCODE_WAKEUP
        || code == KeyEvent.KEYCODE_SOFT_SLEEP

    /** 开始与结束相同表示不定时。跨过零点（如 22:00–07:00）同样成立。 */
    fun inWindow(nowMin: Int, startMin: Int, endMin: Int): Boolean {
        if (startMin == endMin) return false
        return if (startMin < endMin) nowMin in startMin until endMin
        else nowMin >= startMin || nowMin < endMin
    }

    fun nowMinutes(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    /**
     * 只处理定时。没开启定时时不改当前锁定，密码上锁仍然保留。
     * 开启后，跨过时段边界才自动锁定或解锁；时段内用密码解开，不会被下一次检查立刻锁回去。
     * @return 锁定状态有变化时返回新状态，否则 null
     */
    fun syncSchedule(context: Context): Boolean? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ENABLED, false)) {
            if (p.getBoolean(KEY_WINDOW, false)) {
                p.edit().putBoolean(KEY_WINDOW, false).apply()
            }
            return null
        }
        val inside = inWindow(
            nowMinutes(),
            p.getInt(KEY_START, DEFAULT_START),
            p.getInt(KEY_END, DEFAULT_END)
        )
        val wasInside = p.getBoolean(KEY_WINDOW, false)
        if (inside == wasInside) return null
        p.edit()
            .putBoolean(KEY_WINDOW, inside)
            .putBoolean(KEY_LOCKED, inside)
            .apply()
        return inside
    }

    fun toggle(context: Context): Boolean {
        val next = !isLocked(context)
        prefs(context).edit().putBoolean(KEY_LOCKED, next).apply()
        return next
    }

    enum class Feed { MATCHED, PREFIX, IGNORED }

    class SequenceTracker {
        private val buf = ArrayList<Int>(8)
        private var lastAt = 0L

        fun hasProgress() = buf.isNotEmpty()

        fun reset() {
            buf.clear()
            lastAt = 0L
        }

        fun feed(key: Int, password: IntArray, now: Long): Feed {
            if (password.isEmpty() || key !in DPAD) return Feed.IGNORED
            if (buf.isNotEmpty() && now - lastAt > 4_000L) buf.clear()
            lastAt = now
            val expect = if (buf.size < password.size) password[buf.size] else Int.MIN_VALUE
            if (key == expect) {
                buf.add(key)
                if (buf.size == password.size) {
                    buf.clear()
                    return Feed.MATCHED
                }
                return Feed.PREFIX
            }
            buf.clear()
            if (key == password[0]) {
                buf.add(key)
                if (password.size == 1) {
                    buf.clear()
                    return Feed.MATCHED
                }
                return Feed.PREFIX
            }
            return Feed.IGNORED
        }
    }
}

enum class KeyDecision { CONSUME, UI, SYSTEM }

/**
 * 盖一层不占焦点的「已锁定」，并决定按键是吃掉、交给界面，还是交给系统（电源键）。
 */
class LockInputGuard(
    private val activity: Activity,
    /** 未锁定时也监听密码。桌面为 true；设置页和选择页只在已经锁定后才拦截。 */
    private val armWhenUnlocked: Boolean = true,
    private val onChanged: ((locked: Boolean) -> Unit)? = null
) {
    private val tracker = DesktopLock.SequenceTracker()
    private val handler = Handler(Looper.getMainLooper())
    private var swallowUp = false
    private var started = false

    private val label: TextView = TextView(activity).apply {
        text = "已锁定"
        setTextColor(0x99FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
        letterSpacing = 0.28f
        gravity = Gravity.CENTER
        setShadowLayer(18f, 0f, 2f, 0x66000000)
        setBackgroundColor(Color.TRANSPARENT)
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    private val tick = object : Runnable {
        override fun run() {
            applySchedule()
            handler.postDelayed(this, 15_000L)
        }
    }

    init {
        activity.addContentView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
    }

    fun start() {
        val changed = DesktopLock.syncSchedule(activity)
        show(changed ?: DesktopLock.isLocked(activity))
        if (changed != null) onChanged?.invoke(changed)
        if (!started) {
            started = true
            handler.postDelayed(tick, 15_000L)
        }
    }

    fun stop() {
        started = false
        handler.removeCallbacksAndMessages(null)
    }

    fun decide(event: KeyEvent): KeyDecision {
        if (DesktopLock.isPowerKey(event.keyCode)) return KeyDecision.SYSTEM
        val locked = DesktopLock.isLocked(activity)
        // 密码与定时无关：桌面未锁定时也监听密码；设置页只在已经锁定后才拦截。
        if (!locked && !armWhenUnlocked) return KeyDecision.UI

        if (event.action == KeyEvent.ACTION_UP) {
            if (swallowUp || locked) {
                swallowUp = false
                return KeyDecision.CONSUME
            }
            return KeyDecision.UI
        }
        if (event.action != KeyEvent.ACTION_DOWN) {
            return if (locked) KeyDecision.CONSUME else KeyDecision.UI
        }
        if (event.repeatCount > 0) {
            return if (locked || tracker.hasProgress()) KeyDecision.CONSUME else KeyDecision.UI
        }

        when (tracker.feed(event.keyCode, DesktopLock.password(activity), System.currentTimeMillis())) {
            DesktopLock.Feed.MATCHED -> {
                val nowLocked = DesktopLock.toggle(activity)
                tracker.reset()
                show(nowLocked)
                onChanged?.invoke(nowLocked)
                swallowUp = true
                return KeyDecision.CONSUME
            }
            DesktopLock.Feed.PREFIX -> {
                swallowUp = true
                return KeyDecision.CONSUME
            }
            DesktopLock.Feed.IGNORED -> {
                if (locked) {
                    swallowUp = true
                    return KeyDecision.CONSUME
                }
                return KeyDecision.UI
            }
        }
    }

    private fun applySchedule() {
        val changed = DesktopLock.syncSchedule(activity) ?: return
        tracker.reset()
        show(changed)
        onChanged?.invoke(changed)
    }

    private fun show(locked: Boolean) {
        label.visibility = if (locked) View.VISIBLE else View.GONE
    }
}
