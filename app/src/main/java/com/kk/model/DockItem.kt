package com.kk.tvlauncher.model

sealed class DockItem {
    data class App(val appInfo: AppInfo) : DockItem()
    object AddButton : DockItem()
}
