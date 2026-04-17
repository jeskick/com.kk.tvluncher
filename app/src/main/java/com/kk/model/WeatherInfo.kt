package com.kk.tvlauncher.model

import com.kk.tvlauncher.R

data class WeatherInfo(
    val city: String,
    val temperature: Int,
    val feelsLike: Int,
    val description: String,
    val humidity: Int,
    val weatherCode: Int,
    val forecast: List<ForecastDay>
) {
    fun weatherEmoji(): String = when (weatherCode) {
        in 200..232 -> "⛈"
        in 300..321 -> "🌦"
        in 500..531 -> "🌧"
        511        -> "🌨"
        in 600..622 -> "❄"
        in 700..781 -> "🌫"
        800        -> "☀"
        801        -> "🌤"
        802        -> "⛅"
        in 803..804 -> "☁"
        else       -> "🌡"
    }

    /** 返回对应的卡通天气图标 drawable 资源 ID */
    fun weatherDrawable(): Int = when (weatherCode) {
        in 200..232 -> R.drawable.ic_weather_thunder       // 雷暴
        in 300..321 -> R.drawable.ic_weather_rain          // 毛毛雨
        in 500..504 -> R.drawable.ic_weather_heavy_rain    // 中到大雨
        in 520..531 -> R.drawable.ic_weather_rain          // 阵雨
        511         -> R.drawable.ic_weather_snow           // 冻雨
        in 600..622 -> R.drawable.ic_weather_snow          // 雪
        in 700..781 -> R.drawable.ic_weather_fog           // 雾/霾/沙尘
        800         -> R.drawable.ic_weather_sunny         // 晴
        801         -> R.drawable.ic_weather_partly_cloudy // 少云
        802         -> R.drawable.ic_weather_partly_cloudy // 晴间多云
        in 803..804 -> R.drawable.ic_weather_overcast      // 多云/阴
        else        -> R.drawable.ic_weather_sunny
    }
}

data class ForecastDay(
    val dayName: String,
    val maxTemp: Int,
    val minTemp: Int,
    val weatherCode: Int
) {
    fun weatherEmoji(): String = WeatherInfo(
        "", 0, 0, "", 0, weatherCode, emptyList()
    ).weatherEmoji()
}
