package com.kk.tvlauncher.ui

import android.app.Application
import android.content.Context
import android.util.Log
import com.kk.tvlauncher.BuildConfig
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.kk.tvlauncher.data.AppRepository
import com.kk.tvlauncher.data.DockRepository
import com.kk.tvlauncher.data.HttpImageFetcher
import com.kk.tvlauncher.data.SmbImageFetcher
import com.kk.tvlauncher.data.SmbJImageFetcher
import com.kk.tvlauncher.data.WebDavImageFetcher
import com.kk.tvlauncher.data.WeatherRepository
import com.kk.tvlauncher.model.AppInfo
import com.kk.tvlauncher.model.DockItem
import com.kk.tvlauncher.model.WeatherInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appRepo    = AppRepository(application)
    private val dockRepo   = DockRepository(application)
    private val weatherRepo = WeatherRepository()
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _dockItems      = MutableLiveData<List<DockItem>>()
    val dockItems: LiveData<List<DockItem>> = _dockItems

    private val _allApps        = MutableLiveData<List<AppInfo>>()
    val allApps: LiveData<List<AppInfo>> = _allApps

    private val _weather        = MutableLiveData<WeatherInfo?>()
    val weather: LiveData<WeatherInfo?> = _weather

    private val _backgroundPath = MutableLiveData<String?>()
    val backgroundPath: LiveData<String?> = _backgroundPath

    /** 缓存的 SMB 图片列表（供幻灯片轮播）*/
    private var smbImageList: List<String> = emptyList()
    private var slideshowJob: Job? = null

    init { loadAll() }

    fun loadAll() {
        loadDock()
        loadAllApps()
        loadWeather()
        loadBackground()
    }

    // ── Dock ──────────────────────────────────────────────────────────────────

    fun loadDock() {
        viewModelScope.launch {
            val pkgs = dockRepo.getDockPackages()
            val installed = appRepo.getInstalledApps()
            val pkgMap = installed.associateBy { it.packageName }
            val items: List<DockItem> = pkgs
                .mapNotNull { pkgMap[it] }
                .map { DockItem.App(it) } + listOf(DockItem.AddButton)
            _dockItems.value = items
        }
    }

    fun loadAllApps() {
        viewModelScope.launch { _allApps.value = appRepo.getInstalledApps() }
    }

    // ── 天气 ──────────────────────────────────────────────────────────────────

    fun loadWeather() {
        val city   = prefs.getString("weather_city", BuildConfig.DEFAULT_WEATHER_CITY) ?: BuildConfig.DEFAULT_WEATHER_CITY
        val apiKey = prefs.getString("weather_api_key", null)
            ?.takeIf { it.isNotBlank() }
            ?: WeatherRepository.DEFAULT_API_KEY
        viewModelScope.launch {
            var result = weatherRepo.getWeather(city, apiKey)
            // 如果返回的是占位数据（temperature==0），延迟5s后重试一次
            if (result.temperature == 0 && result.description == "获取中...") {
                delay(5000L)
                result = weatherRepo.getWeather(city, apiKey)
            }
            _weather.value = result
        }
    }

    // ── 背景 / SMB 幻灯片 ─────────────────────────────────────────────────────

    fun loadBackground() {
        val saved        = prefs.getString("background_path", null)
        val builtinMode  = prefs.getString("builtin_wallpaper", "随机切换") ?: "随机切换"
        val smbDir       = prefs.getString("smb_path", DEFAULT_SMBJ_DIR)
        val smbUser      = prefs.getString("smb_user", null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_SMB_USER
        val smbPass      = prefs.getString("smb_pass", null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_SMB_PASS
        val intervalSec  = prefs.getInt("slideshow_interval", 10)


        slideshowJob?.cancel()

        // 先立即显示一张内置壁纸防黑屏（仅在无手动指定时）
        if (saved.isNullOrBlank()) {
            val quickBg = if (builtinMode == "随机SMB") getRandomBuiltinWallpaper()
                          else getBuiltinWallpaper()
            _backgroundPath.value = quickBg
        }

        when {
            // 1. 用户手动指定了具体文件路径
            !saved.isNullOrBlank() -> {
                _backgroundPath.value = saved
            }

            // 2. 随机SMB：从 SMB/WebDAV/HTTP 目录拉取图片做幻灯片
            builtinMode == "随机SMB" -> {
                loadSmbSlideshow(smbDir, smbUser, smbPass, intervalSec.toLong())
            }

            // 3. 内置壁纸（随机切换 or 单张固定）
            else -> startBuiltinSlideshow(intervalSec.toLong())
        }
    }

    /** 从 SMB/WebDAV/HTTP 拉取图片列表并启动幻灯片，失败则回退到内置 */
    private fun loadSmbSlideshow(smbDir: String?, smbUser: String, smbPass: String, intervalSec: Long) {
        // 关键修复：整个加载+轮播链路必须作为一个可取消的 Job 管理，
        // 否则 loadBackground 被重复调用时，旧链路仍在后台运行，
        // 导致多个 startSlideshow 并发触发，出现图片刚显示就被切走的现象。
        slideshowJob = viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                when {
                    smbDir.isNullOrBlank() -> emptyList()
                    WebDavImageFetcher.isWebDavUrl(smbDir) ->
                        WebDavImageFetcher.listImages(smbDir, smbUser, smbPass)
                    HttpImageFetcher.isHttpUrl(smbDir) ->
                        HttpImageFetcher.listImages(smbDir)
                    else -> {
                        val norm = SmbImageFetcher.normalizePath(smbDir)
                        SmbImageFetcher.listImages(norm, smbUser, smbPass)
                    }
                }
            }
            if (!isActive) return@launch
            if (list.isEmpty()) {
                // 列表空退回内置幻灯片（直接在同一个协程里 delay 轮播，保证 cancel 时一起停）
                runBuiltinSlideshowLoop(intervalSec)
                return@launch
            }
            smbImageList = list.shuffled()
            _backgroundPath.value = smbImageList.first()
            runSlideshowLoop(intervalSec)
        }
    }

    /** 使用内置壁纸启动幻灯片 */
    private fun startBuiltinSlideshow(intervalSec: Long) {
        slideshowJob = viewModelScope.launch {
            runBuiltinSlideshowLoop(intervalSec)
        }
    }

    /** 内置壁纸轮播循环（在当前协程里 delay，保证可取消） */
    private suspend fun runBuiltinSlideshowLoop(intervalSec: Long) {
        val list = getBuiltinWallpaperList().shuffled()
        smbImageList = list
        if (list.isEmpty()) return
        _backgroundPath.value = list.first()
        if (list.size <= 1) return
        runSlideshowLoop(intervalSec)
    }

    /** 定时随机切换图片（复用当前协程，而不是新开 Job） */
    private suspend fun runSlideshowLoop(intervalSec: Long) {
        var last = _backgroundPath.value
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(intervalSec * 1000L)
            if (smbImageList.isEmpty()) break
            // 避免连续选到同一张
            var next = smbImageList.random()
            if (smbImageList.size > 1) {
                var tries = 0
                while (next == last && tries < 5) {
                    next = smbImageList.random(); tries++
                }
            }
            last = next
            _backgroundPath.value = next
        }
    }

    /** 重新从 SMB 拉取列表并刷新（供外部调用）*/
    fun refreshSmbImages() {
        loadBackground()
    }

    /**
     * 手动切换到下一张壁纸（不重启轮播 Job，只是 emit 一张新图）。
     * 用于 HOME 键短按切换。
     */
    fun nextWallpaper() {
        if (smbImageList.isEmpty()) {
            // 列表尚未就绪，回退到立刻换一张内置图
            _backgroundPath.value = getBuiltinWallpaper()
            return
        }
        val current = _backgroundPath.value
        var next = smbImageList.random()
        if (smbImageList.size > 1) {
            var tries = 0
            while (next == current && tries < 5) {
                next = smbImageList.random(); tries++
            }
        }
        _backgroundPath.value = next
    }

    /**
     * 根据 URL 协议自动选择加载方式（SMB / WebDAV / HTTP）。
     * 供 MainActivity 在 Dispatchers.IO 中调用，再把 ByteArray 交给 Glide。
     */
    fun loadImageBytes(url: String): ByteArray? {
        val user = prefs.getString("smb_user", null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_SMB_USER
        val pass = prefs.getString("smb_pass", null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_SMB_PASS
        return when {
            WebDavImageFetcher.isWebDavUrl(url) -> WebDavImageFetcher.loadBytes(url, user, pass)
            url.startsWith("smb://")            -> SmbImageFetcher.loadBytes(url, user, pass)
            else                                -> null  // http:// 由 Glide 直接处理
        }
    }

    @Deprecated("Use loadImageBytes", ReplaceWith("loadImageBytes(smbUrl)"))
    fun loadSmbBytes(smbUrl: String) = loadImageBytes(smbUrl)

    /**
     * 从当前幻灯片列表中随机取一张不同于 currentUrl 的图片，供竖屏拼合使用。
     * 只在 IO 线程调用（会尝试解码 bounds）。
     */
    fun getPortraitCandidate(currentUrl: String): String? {
        val candidates = smbImageList.filter { it != currentUrl }
        if (candidates.isEmpty()) return null
        // 随机取一张，不预先解码（由调用方决定是否可用）
        return candidates.random()
    }

    /** 获取内置壁纸随机路径 */
    fun getBuiltinWallpaper(): String {
        val selected = prefs.getString("builtin_wallpaper", "随机切换") ?: "随机切换"
        return when (selected) {
            "随机SMB", "随机切换" -> "file:///android_asset/wallpapers/${BUILTIN_WALLPAPERS.random()}"
            else -> "file:///android_asset/wallpapers/$selected"
        }
    }

    /** 快速取一张随机内置壁纸（不依赖 pref，供防黑屏占位） */
    private fun getRandomBuiltinWallpaper(): String =
        "file:///android_asset/wallpapers/${BUILTIN_WALLPAPERS.random()}"

    /** 获取内置壁纸列表（供幻灯片），"随机SMB"模式由外部处理 */
    fun getBuiltinWallpaperList(): List<String> {
        val selected = prefs.getString("builtin_wallpaper", "随机切换") ?: "随机切换"
        return when (selected) {
            "随机SMB", "随机切换" -> BUILTIN_WALLPAPERS.map { "file:///android_asset/wallpapers/$it" }
            else -> listOf("file:///android_asset/wallpapers/$selected")
        }
    }

    companion object {
        val BUILTIN_WALLPAPERS = listOf(
            "17ad181ad496361.jpg",
            "651df1b075b8157.jpg",
            "67681fbc72277d3.jpg",
            "minecraft-tiny.jpg",
            "triumph-street.jpg"
        )
        /** 默认背景（内置壁纸随机）通过 getBuiltinWallpaper() 获取 */
        const val DEFAULT_BG        = "file:///android_asset/wallpapers/minecraft-tiny.jpg"
        /** WebDAV 地址（在设置界面配置，格式：https://NAS_IP:PORT/path/） */
        const val DEFAULT_WEBDAV_DIR = ""
        /** SMB 共享目录（优先读 local.properties，其次设置界面） */
        val DEFAULT_SMBJ_DIR   get() = BuildConfig.DEFAULT_SMB_DIR.ifBlank { "" }
        val DEFAULT_SMB_DIR    get() = BuildConfig.DEFAULT_SMB_DIR.ifBlank { "" }
        val DEFAULT_SMB_USER   get() = BuildConfig.DEFAULT_SMB_USER.ifBlank { "" }
        val DEFAULT_SMB_PASS   get() = BuildConfig.DEFAULT_SMB_PASS.ifBlank { "" }
    }

    fun addToDock(packageName: String) { dockRepo.addApp(packageName); loadDock() }
    fun removeFromDock(packageName: String) { dockRepo.removeApp(packageName); loadDock() }
    fun launchApp(packageName: String) = appRepo.launchApp(packageName)
    fun getDockPackages(): List<String> = dockRepo.getDockPackages()

    /** 按使用频率重排当前 Dock 列表（不改变持久化顺序，只影响显示） */
    fun sortDockByUsage(usagePrefs: android.content.SharedPreferences) {
        val current = _dockItems.value ?: return
        val apps = current.filterIsInstance<DockItem.App>()
            .sortedByDescending { usagePrefs.getInt(it.appInfo.packageName, 0) }
        _dockItems.value = apps + listOf(DockItem.AddButton)
    }
}
