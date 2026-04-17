package com.kk.tvlauncher.utils

import java.util.Calendar

/**
 * 农历转换工具 — 纯算法，无外部依赖。
 * 以 1900-01-31（庚子年正月初一）为历元，
 * 通过公历日期差精确定位农历月日。
 */
object LunarCalendar {

    /**
     * lunarInfo[i] 对应 (1900+i) 年的农历信息：
     *  - bits 20-17 : 闰月月份 (0=无闰月)
     *  - bits 16-5  : 1-12月大小月 (1=大月30天, 0=小月29天)
     *  - bit  4     : 闰月大小 (1=30天, 0=29天)
     *  - bits 3-0   : 保留
     */
    private val lunarInfo = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0,
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06aa0, 0x0a6b6, 0x056a0, 0x02b40, 0x0acb5,
        0x092e0, 0x0cab5, 0x0c950, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0,
        0x0cab5, 0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0,
        0x0f930, 0x06952, 0x06aa0, 0x0ad50, 0x0ab54, 0x04b60, 0x0a570, 0x05264, 0x0d160, 0x0e968,
        0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252, 0x0d520
    )

    private val monthNames = arrayOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    private val dayNames = arrayOf(
        "初一","初二","初三","初四","初五","初六","初七","初八","初九","初十",
        "十一","十二","十三","十四","十五","十六","十七","十八","十九","二十",
        "廿一","廿二","廿三","廿四","廿五","廿六","廿七","廿八","廿九","三十"
    )

    // 某年闰月月份
    private fun leapMonth(y: Int) = lunarInfo[y - 1900] and 0xf
    // 某年闰月天数
    private fun leapDays(y: Int) = if (leapMonth(y) == 0) 0
                                    else if (lunarInfo[y - 1900] and 0x10000 != 0) 30 else 29
    // 某年某月天数（m 从 1 起）
    private fun monthDays(y: Int, m: Int) =
        if (lunarInfo[y - 1900] and (0x10000 shr m) != 0) 30 else 29
    // 某农历年总天数
    private fun yearDays(y: Int): Int {
        var sum = 348
        var i = 0x8000
        while (i > 0x8) { if (lunarInfo[y - 1900] and i != 0) sum++; i = i shr 1 }
        return sum + leapDays(y)
    }

    /**
     * 公历 → 农历字符串，如 "三月廿一"
     */
    fun solarToLunar(year: Int, month: Int, day: Int): String {
        // 历元：1900-01-31 = 农历 1900 年正月初一
        val epoch = Calendar.getInstance().apply { set(1900, 0, 31, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
        val target = Calendar.getInstance().apply { set(year, month - 1, day, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
        var offset = ((target.timeInMillis - epoch.timeInMillis) / 86400000L).toInt()
        if (offset < 0) return ""

        // 依次减去每个农历年的总天数，定位农历年
        var lunarYear = 1900
        var y = 1900
        while (y < 2050 && offset >= yearDays(y)) {
            offset -= yearDays(y)
            y++
        }
        lunarYear = y

        // 在农历年内依次减去每月天数，定位农历月
        val leapM = leapMonth(lunarYear)
        var hasLeap = false
        var lunarMonth = 1
        var isLeap = false
        var m = 1
        while (m <= 12) {
            // 处理闰月：在该月之后插入闰月
            if (leapM > 0 && m == leapM + 1 && !hasLeap) {
                val ld = leapDays(lunarYear)
                if (offset < ld) { isLeap = true; lunarMonth = leapM; break }
                offset -= ld; hasLeap = true
            }
            val md = monthDays(lunarYear, m)
            if (offset < md) { lunarMonth = m; break }
            offset -= md
            m++
        }
        val lunarDay = offset + 1   // offset 现在是月内偏移（0-based），+1 得到日

        if (lunarDay < 1 || lunarDay > 30) return ""
        val monthStr = if (isLeap) "闰${monthNames[lunarMonth - 1]}月" else "${monthNames[lunarMonth - 1]}月"
        return "$monthStr${dayNames[lunarDay - 1]}"
    }
}
