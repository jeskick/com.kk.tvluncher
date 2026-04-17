package com.kk.tvlauncher.utils

import android.icu.util.ChineseCalendar
import java.util.Date

/**
 * 使用 Android 内置 ICU4J ChineseCalendar，API 24+ 可用（minSdk=26 安全）。
 * 无需自维护历表，官方实现精确可靠。
 */
object LunarCalendar {

    private val monthNames = arrayOf(
        "正", "二", "三", "四", "五", "六",
        "七", "八", "九", "十", "冬", "腊"
    )
    private val dayNames = arrayOf(
        "初一","初二","初三","初四","初五","初六","初七","初八","初九","初十",
        "十一","十二","十三","十四","十五","十六","十七","十八","十九","二十",
        "廿一","廿二","廿三","廿四","廿五","廿六","廿七","廿八","廿九","三十"
    )

    /** 公历 → 农历字符串，如 "三月初一" 或 "闰三月初一" */
    fun solarToLunar(year: Int, month: Int, day: Int): String {
        return try {
            val cal = ChineseCalendar()
            cal.time = java.util.GregorianCalendar(year, month - 1, day).time
            val lunarMonth = cal.get(ChineseCalendar.MONTH) + 1   // 0-indexed
            val lunarDay   = cal.get(ChineseCalendar.DAY_OF_MONTH)
            val isLeap     = cal.get(ChineseCalendar.IS_LEAP_MONTH) == 1

            if (lunarMonth < 1 || lunarMonth > 12 || lunarDay < 1 || lunarDay > 30) return ""
            val monthStr = if (isLeap) "闰${monthNames[lunarMonth - 1]}月"
                           else "${monthNames[lunarMonth - 1]}月"
            "$monthStr${dayNames[lunarDay - 1]}"
        } catch (_: Exception) { "" }
    }
}
