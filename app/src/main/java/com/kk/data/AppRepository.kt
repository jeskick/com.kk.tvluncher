package com.kk.tvlauncher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.kk.tvlauncher.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(private val context: Context) {

    /** 获取所有已安装的 TV 应用（Leanback 分类优先，回退到 LAUNCHER 分类） */
    suspend fun getInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager

        // 优先获取声明了 LEANBACK_LAUNCHER 的应用（TV 专属）
        val leanbackIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }
        val leanbackPkgs = pm.queryIntentActivities(leanbackIntent, PackageManager.GET_META_DATA)
            .map { it.activityInfo.packageName }
            .toSet()

        // 补充普通桌面应用
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val allInfos = (pm.queryIntentActivities(leanbackIntent, PackageManager.GET_META_DATA)
                + pm.queryIntentActivities(launcherIntent, PackageManager.GET_META_DATA))
            .distinctBy { it.activityInfo.packageName }

        allInfos.mapNotNull { resolveInfo ->
            runCatching {
                val pkgName = resolveInfo.activityInfo.packageName
                if (pkgName == context.packageName) return@mapNotNull null
                AppInfo(
                    packageName = pkgName,
                    appName = resolveInfo.loadLabel(pm).toString(),
                    icon = resolveInfo.loadIcon(pm),
                    banner = runCatching { pm.getApplicationBanner(pkgName) }.getOrNull(),
                    isSystemApp = (resolveInfo.activityInfo.applicationInfo.flags
                            and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }.getOrNull()
        }.sortedBy { it.appName }
    }

    fun launchApp(packageName: String) {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
        intent?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(this)
        }
    }
}
