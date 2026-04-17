package com.kk.tvlauncher.utils

/**
 * 纯算法农历转换，无需外部依赖。
 * 数据来源：寿星万年历算法
 */
object LunarCalendar {

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

    private val chineseMonths = arrayOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    private val chineseDays = arrayOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    )

    private fun leapMonth(y: Int) = lunarInfo[y - 1900] and 0xf
    private fun leapDays(y: Int): Int {
        if (leapMonth(y) == 0) return 0
        return if (lunarInfo[y - 1900] and 0x10000 != 0) 30 else 29
    }
    private fun monthDays(y: Int, m: Int): Int =
        if (lunarInfo[y - 1900] and (0x10000 shr m) != 0) 30 else 29
    private fun yearDays(y: Int): Int {
        var i = 0x8000; var sum = 348
        while (i > 0x8) { if (lunarInfo[y - 1900] and i != 0) sum++; i = i shr 1 }
        return sum + leapDays(y)
    }

    /** 将公历日期转换为农历字符串，例如："腊月廿三" */
    fun solarToLunar(year: Int, month: Int, day: Int): String {
        var offset = 0
        for (y in 1900 until year) offset += yearDays(y)
        // 1900年1月31日为农历正月初一
        val base = 30 + 31 + 29 + 31 // 偏移量修正（1900-01-31）
        offset += dayOfYear(year, month, day) - base + 30
        if (offset <= 0) return ""

        var lunarYear = 1900; var lunarMonth = 1; var lunarDay = 1
        var isLeap = false
        var i = 1900
        while (i < 2050 && offset > 0) {
            val days = yearDays(i)
            if (offset <= days) break
            offset -= days
            i++
        }
        lunarYear = i

        val leapM = leapMonth(lunarYear)
        var hasLeap = false
        var m = 1
        while (m <= 12 && offset > 0) {
            if (leapM > 0 && m == leapM + 1 && !hasLeap) {
                val ld = leapDays(lunarYear)
                if (offset <= ld) { isLeap = true; break }
                offset -= ld; hasLeap = true
            }
            val md = monthDays(lunarYear, m)
            if (offset <= md) break
            offset -= md
            m++
        }
        lunarMonth = m
        lunarDay = offset

        val monthStr = if (isLeap) "闰${chineseMonths[lunarMonth - 1]}月" else "${chineseMonths[lunarMonth - 1]}月"
        val dayStr = if (lunarDay in 1..30) chineseDays[lunarDay - 1] else ""
        return "$monthStr$dayStr"
    }

    private fun dayOfYear(year: Int, month: Int, day: Int): Int {
        val daysInMonth = intArrayOf(0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (isLeapYear(year)) daysInMonth[2] = 29
        var d = 0; for (i in 1 until month) d += daysInMonth[i]
        return d + day
    }

    private fun isLeapYear(year: Int) = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
}
