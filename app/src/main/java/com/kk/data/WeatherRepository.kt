package com.kk.tvlauncher.data

import android.util.Log
import com.kk.tvlauncher.model.ForecastDay
import com.kk.tvlauncher.model.WeatherInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class WeatherRepository {

    companion object {
        const val API_HOST = "devapi.qweather.com"
        // 请在设置界面填入你的和风天气 API Key（https://dev.qweather.com/）
        const val DEFAULT_API_KEY = ""

        /** 常用城市预置 locationId，API 查询失败时兜底 */
        private val CITY_ID_MAP = mapOf(
            "东莞" to "101280701",
            "广州" to "101280101",
            "深圳" to "101280601",
            "北京" to "101010100",
            "上海" to "101020100",
            "杭州" to "101210101",
            "成都" to "101270101",
            "武汉" to "101200101",
            "南京" to "101190101",
            "西安" to "101110101"
        )
    }

    /**
     * 获取天气数据
     * @param city  城市名（中文），如"北京"
     * @param apiKey 和风天气 API Key（在设置里填写）
     */
    suspend fun getWeather(city: String, apiKey: String?): WeatherInfo =
        withContext(Dispatchers.IO) {
            if (apiKey.isNullOrBlank()) {
                return@withContext mockWeather(city)
            }
            runCatching {
                // Step 1: 城市名 → location ID
                val locationId = lookupCity(city, apiKey)
                locationId ?: return@runCatching mockWeather(city)

                // Step 2: 实时天气
                val nowJson = get("https://$API_HOST/v7/weather/now?location=$locationId&key=$apiKey&lang=zh")
                val now = nowJson.getJSONObject("now")

                // Step 3: 3天预报
                val d3Json = get("https://$API_HOST/v7/weather/3d?location=$locationId&key=$apiKey&lang=zh")
                val daily = d3Json.getJSONArray("daily")

                val dayNames = listOf("今天", "明天", "后天")
                val forecast = (0 until minOf(3, daily.length())).map { i ->
                    val d = daily.getJSONObject(i)
                    ForecastDay(
                        dayName = dayNames[i],
                        maxTemp = d.getString("tempMax").toIntOrNull() ?: 0,
                        minTemp = d.getString("tempMin").toIntOrNull() ?: 0,
                        weatherCode = qweatherIconToCode(d.getString("iconDay"))
                    )
                }

                val result = WeatherInfo(
                    city = city.ifBlank { "东莞" },
                    temperature = now.getString("temp").toIntOrNull() ?: 0,
                    feelsLike = now.getString("feelsLike").toIntOrNull() ?: 0,
                    description = now.getString("text"),
                    humidity = now.getString("humidity").toIntOrNull() ?: 0,
                    weatherCode = qweatherIconToCode(now.getString("icon")),
                    forecast = forecast
                )
                result
            }.getOrElse { e ->
                mockWeather(city)
            }
        }

    /** 城市名查询 locationId：先用自定义 API 域名，失败后查预置表 */
    private fun lookupCity(city: String, apiKey: String): String? {
        // 1. 优先查预置表（最快、最稳定）
        CITY_ID_MAP[city]?.let {
            return it
        }
        // 2. 动态查询：用自定义 API 域名的 geo 路径
        val encoded = java.net.URLEncoder.encode(city, "UTF-8")
        val url = "https://$API_HOST/geo/v2/city/lookup?location=$encoded&key=$apiKey&lang=zh"
        val json = get(url)
        val code = json.optString("code")
        if (code != "200") return null
        return json.getJSONArray("location").getJSONObject(0).getString("id")
    }

    /** 简单 GET 请求，返回 JSONObject（兼容非 2xx 响应） */
    private fun get(url: String): JSONObject {
        val conn = URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "KKLauncher/1.0")
        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "{}"
            err
        }
        conn.disconnect()
        return JSONObject(body)
    }

    /**
     * 和风天气图标码 → 我们内部 weatherCode
     * 和风图标是纯数字字符串，直接 toInt 即可，
     * WeatherInfo.weatherEmoji() 按数字范围映射 emoji。
     */
    private fun qweatherIconToCode(icon: String): Int = when (icon.toIntOrNull() ?: 100) {
        100, 150 -> 800          // 晴
        101, 151 -> 801          // 少云
        102, 152 -> 802          // 多云
        103, 104, 153, 154 -> 803 // 阴
        in 300..313 -> 500       // 雨
        in 314..399 -> 501       // 大雨
        in 400..499 -> 601       // 雪
        in 500..515 -> 741       // 雾/霾
        else -> 800
    }

    // ── 无 Key 时用模拟数据 ─────────────────────────────────

    private fun mockWeather(city: String) = WeatherInfo(
        city = city.ifBlank { "东莞" },
        temperature = 0,
        feelsLike = 0,
        description = "获取中...",
        humidity = 0,
        weatherCode = 800,
        forecast = emptyList()
    )
}
