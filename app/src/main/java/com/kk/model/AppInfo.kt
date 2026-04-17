package com.kk.tvlauncher.model

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    val banner: Drawable? = null,   // TV 宽幅 Banner（16:9），可能为 null
    val isSystemApp: Boolean = false
)
