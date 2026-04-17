package com.kk.tvlauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.activity.viewModels
import androidx.core.view.doOnLayout
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.kk.tvlauncher.R
import com.kk.tvlauncher.databinding.ActivityMainBinding
import com.kk.tvlauncher.ui.picker.AppPickerActivity
import com.kk.tvlauncher.ui.settings.SettingsActivity
import com.kk.tvlauncher.utils.LunarCalendar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : FragmentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var dockAdapter: DockAdapter
    private lateinit var appDrawerAdapter: AppDrawerAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isAppDrawerVisible = false

    // ── Dock 自动隐藏 ──────────────────────────────────────────────────────────
    private var isDockVisible = true
    private var dockAutoHideSeconds = 10          // 默认 10 秒，0 = 禁用
    private val dockHideRunnable = Runnable { hideDockWithAnim() }

    // ── 时钟更新 ──────────────────────────────────────────────────────────────
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            mainHandler.postDelayed(this, 30_000)
        }
    }

    // ── 天气卡片展开状态 ──────────────────────────────────────────────────────
    private var isWeatherExpanded = false
    private var weatherCollapsedH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        com.kk.tvlauncher.service.KeepAliveService.start(this)

        setupTopBar()
        setupDock()
        setupAppDrawer()
        setupWeatherCard()
        observeViewModel()
        applyUiSettings()

        mainHandler.post(clockRunnable)
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadAll()
        applyUiSettings()
        resetDockHideTimer()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ── 读取并应用外观设置 ──────────────────────────────────────────────────────

    private fun applyUiSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val alpha = prefs.getInt("ui_alpha", 70) / 100f
        val focusColor = prefs.getString("focus_color", "#CCFFFFFF") ?: "#CCFFFFFF"
        dockAutoHideSeconds = prefs.getInt("dock_auto_hide_secs", 10)

        binding.weatherCard.alpha = alpha
        binding.weatherCard.cardElevation = 0f

        if (::dockAdapter.isInitialized)       dockAdapter.focusColorHex = focusColor
        if (::appDrawerAdapter.isInitialized)  appDrawerAdapter.focusColorHex = focusColor
    }

    // ── 顶部工具栏 ─────────────────────────────────────────────────────────────

    private fun setupTopBar() {
        val btnH = resources.getDimensionPixelSize(R.dimen.top_btn_height)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnSettings.setOnFocusChangeListener { _, hasFocus ->
            binding.tvSettingsLabel.visibility = if (hasFocus) View.VISIBLE else View.GONE
            binding.tvSettingsLabel.text = if (hasFocus) "设置" else ""
            binding.btnSettings.setPadding(if (hasFocus) 24 else 0, 0, if (hasFocus) 24 else 0, 0)
            binding.btnSettings.layoutParams.width =
                if (hasFocus) ViewGroup.LayoutParams.WRAP_CONTENT else btnH
            binding.btnSettings.requestLayout()
        }

        binding.btnWifi.setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
        }
        binding.btnWifi.setOnFocusChangeListener { _, hasFocus ->
            val ssid = binding.btnWifi.tag as? String ?: ""
            binding.tvWifiLabel.visibility = if (hasFocus) View.VISIBLE else View.GONE
            binding.tvWifiLabel.text = if (hasFocus) ssid.ifBlank { "WiFi" } else ""
            binding.btnWifi.setPadding(if (hasFocus) 20 else 0, 0, if (hasFocus) 20 else 0, 0)
            binding.btnWifi.layoutParams.width =
                if (hasFocus) ViewGroup.LayoutParams.WRAP_CONTENT else btnH
            binding.btnWifi.requestLayout()
        }
        updateWifiState()
    }

    private fun updateWifiState() {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
        val ssid = wm?.connectionInfo?.ssid?.replace("\"", "") ?: ""
        binding.btnWifi.tag = if (ssid.isNotBlank() && ssid != "<unknown ssid>") ssid else ""
    }

    // ── 时钟 + 农历 ────────────────────────────────────────────────────────────

    private fun updateClock() {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("MM月dd日  EEE", Locale.CHINESE)
        val now = Date()
        val cal = Calendar.getInstance().apply { time = now }
        binding.tvTime.text = timeFormat.format(now)

        val lunar = LunarCalendar.solarToLunar(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
        binding.tvDate.text = "${dateFormat.format(now)}  $lunar"
    }

    // ── 天气卡片焦点展开 ───────────────────────────────────────────────────────

    private fun setupWeatherCard() {
        binding.weatherCard.isFocusable = true
        binding.weatherCard.isFocusableInTouchMode = false
        binding.weatherCard.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        binding.llWeatherExpanded.visibility = View.GONE

        binding.weatherCard.setOnFocusChangeListener { v, hasFocus ->
            // 高亮边框
            val dp = v.resources.displayMetrics.density
            val gd = if (hasFocus) GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18 * dp
                setStroke((2 * dp).toInt(), Color.parseColor("#AAFFFFFF"))
            } else null
            binding.weatherCard.foreground = gd

            if (hasFocus && !isWeatherExpanded) expandWeather()
            else if (!hasFocus && isWeatherExpanded) collapseWeather()
        }

        // 遥控器 OK 键也可切换展开
        binding.weatherCard.setOnClickListener {
            if (isWeatherExpanded) collapseWeather() else expandWeather()
        }
    }

    private fun expandWeather() {
        isWeatherExpanded = true
        binding.llWeatherExpanded.visibility = View.VISIBLE
        binding.llWeatherExpanded.alpha = 0f
        binding.llWeatherExpanded.animate()
            .alpha(1f).setDuration(250).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun collapseWeather() {
        isWeatherExpanded = false
        binding.llWeatherExpanded.animate()
            .alpha(0f).setDuration(180).withEndAction {
                binding.llWeatherExpanded.visibility = View.GONE
            }.start()
    }

    // ── Dock ──────────────────────────────────────────────────────────────────

    private fun setupDock() {
        dockAdapter = DockAdapter(
            onAppClick     = { pkg -> recordUsage(pkg); viewModel.launchApp(pkg) },
            onAppLongClick = { pkg -> viewModel.removeFromDock(pkg) },
            onAddClick     = { startActivity(Intent(this, AppPickerActivity::class.java)) },
            // 功能6：只有最后一个按钮（+）的下键才触发打开全部应用
            onLastItemDownKey = { showAppDrawer() }
        )

        val gapPx = (14 * resources.displayMetrics.density).toInt()
        binding.rvDock.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = dockAdapter
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: Rect, v: View, parent: RecyclerView, s: RecyclerView.State) {
                    outRect.left = gapPx; outRect.right = gapPx
                }
            })
        }
    }

    // ── Dock 自动隐藏 ──────────────────────────────────────────────────────────

    private fun resetDockHideTimer() {
        mainHandler.removeCallbacks(dockHideRunnable)
        if (!isDockVisible) showDockWithAnim()
        if (dockAutoHideSeconds > 0)
            mainHandler.postDelayed(dockHideRunnable, dockAutoHideSeconds * 1000L)
    }

    private fun showDockWithAnim() {
        if (isDockVisible) return
        isDockVisible = true
        binding.llDockContainer.visibility = View.VISIBLE
        binding.llDockContainer.translationY = binding.llDockContainer.height.toFloat()
        binding.llDockContainer.animate()
            .translationY(0f).alpha(1f).setDuration(260)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun hideDockWithAnim() {
        if (!isDockVisible || isAppDrawerVisible) return
        isDockVisible = false
        binding.llDockContainer.animate()
            .translationY(binding.llDockContainer.height.toFloat())
            .alpha(0f).setDuration(300)
            .withEndAction { binding.llDockContainer.visibility = View.INVISIBLE }
            .start()
    }

    // ── App 抽屉（遥控器向下滑出）─────────────────────────────────────────────

    private fun setupAppDrawer() {
        appDrawerAdapter = AppDrawerAdapter(onAppClick = { app ->
            recordUsage(app.packageName)
            viewModel.launchApp(app.packageName)
        })

        binding.rvAppDrawer.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 7)
            adapter = appDrawerAdapter
        }

        binding.btnCloseDrawer.setOnClickListener { hideAppDrawer() }
        binding.appDrawerPanel.visibility = View.GONE
    }

    private fun showAppDrawer() {
        if (isAppDrawerVisible) return
        isAppDrawerVisible = true
        mainHandler.removeCallbacks(dockHideRunnable)   // 抽屉开着时不自动隐藏
        binding.appDrawerPanel.visibility = View.VISIBLE
        binding.llDockContainer.animate().alpha(0f).setDuration(200).withEndAction {
            binding.llDockContainer.visibility = View.INVISIBLE
        }.start()
        binding.appDrawerPanel.doOnLayout { panel ->
            panel.translationY = panel.height.toFloat()
            panel.animate().translationY(0f).setDuration(280)
                .setInterpolator(DecelerateInterpolator()).start()
            binding.rvAppDrawer.requestFocus()
        }
    }

    fun hideAppDrawer() {
        if (!isAppDrawerVisible) return
        isAppDrawerVisible = false
        binding.appDrawerPanel.animate()
            .translationY(binding.appDrawerPanel.height.toFloat())
            .setDuration(240)
            .withEndAction {
                binding.appDrawerPanel.visibility = View.GONE
                binding.llDockContainer.visibility = View.VISIBLE
                isDockVisible = true
                binding.llDockContainer.translationY = 0f
                binding.llDockContainer.animate().alpha(1f).setDuration(200).start()
                binding.rvDock.requestFocus()
                resetDockHideTimer()
            }.start()
    }

    // ── 使用频率记录 ───────────────────────────────────────────────────────────

    private fun recordUsage(pkg: String) {
        val prefs = getSharedPreferences("app_usage", Context.MODE_PRIVATE)
        val count = prefs.getInt(pkg, 0)
        prefs.edit().putInt(pkg, count + 1).apply()
        // 通知 ViewModel 按频率重排 Dock
        viewModel.sortDockByUsage(getSharedPreferences("app_usage", Context.MODE_PRIVATE))
    }

    // ── 遥控器按键拦截（任意按键重置 Dock 隐藏计时器）─────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            resetDockHideTimer()   // 任意按键重置计时
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (isAppDrawerVisible) { hideAppDrawer(); return true }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (isAppDrawerVisible) {
                        val lm = binding.rvAppDrawer.layoutManager as? GridLayoutManager
                        if ((lm?.findFirstCompletelyVisibleItemPosition() ?: -1) == 0) {
                            hideAppDrawer(); return true
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ── ViewModel 观察 ────────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.dockItems.observe(this) { items -> dockAdapter.submitList(items) }
        viewModel.allApps.observe(this) { apps -> appDrawerAdapter.submitList(apps) }

        viewModel.weather.observe(this) { info ->
            info ?: return@observe
            binding.tvWeatherCity.text = info.city
            binding.tvWeatherTemp.text = "${info.temperature}°"
            binding.tvWeatherDesc.text = info.description
            binding.tvWeatherHumidity.text = "湿度 ${info.humidity}%"
            binding.ivWeatherIcon.setImageResource(info.weatherDrawable())

            val fc = info.forecast
            fun bindDay(dayView: android.widget.TextView, iconView: android.widget.TextView,
                        tempView: android.widget.TextView, idx: Int) {
                if (idx < fc.size) {
                    dayView.text  = fc[idx].dayName
                    iconView.text = fc[idx].weatherEmoji()
                    tempView.text = "${fc[idx].maxTemp}° / ${fc[idx].minTemp}°"
                }
            }
            bindDay(binding.tvForecast1Day, binding.tvForecast1Icon, binding.tvForecast1Temp, 0)
            bindDay(binding.tvForecast2Day, binding.tvForecast2Icon, binding.tvForecast2Temp, 1)
            bindDay(binding.tvForecast3Day, binding.tvForecast3Icon, binding.tvForecast3Temp, 2)
            // 扩展区（获得焦点时显示）
            bindDay(binding.tvForecast4Day, binding.tvForecast4Icon, binding.tvForecast4Temp, 3)
            bindDay(binding.tvForecast5Day, binding.tvForecast5Icon, binding.tvForecast5Temp, 4)
        }

        viewModel.backgroundPath.observe(this) { path ->
            if (path.isNullOrBlank()) return@observe
            val transition = getWallpaperTransition()
            when {
                path.startsWith("smb://") || path.startsWith("https://") -> {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val bytes = viewModel.loadImageBytes(path)
                        val finalBitmap = bytes?.let { tryMergePortrait(it, path) }
                        withContext(Dispatchers.Main) {
                            when {
                                finalBitmap != null -> loadBg(finalBitmap, transition)
                                bytes != null       -> loadBg(bytes, transition)
                            }
                        }
                    }
                }
                else -> loadBg(path, transition)
            }
        }
    }

    // ── 壁纸过渡动画 ──────────────────────────────────────────────────────────

    private fun getWallpaperTransition(): String =
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("wallpaper_transition", "fade") ?: "fade"

    private fun loadBg(src: Any, transition: String) {
        val req = Glide.with(this).let {
            when (src) {
                is Bitmap     -> it.load(src)
                is ByteArray  -> it.load(src)
                is String     -> it.load(src)
                else          -> it.load(src)
            }
        }.centerCrop()

        when (transition) {
            "none"  -> req.into(binding.ivBackground)
            "fade"  -> req.transition(DrawableTransitionOptions.withCrossFade(700)).into(binding.ivBackground)
            "slow"  -> req.transition(DrawableTransitionOptions.withCrossFade(1800)).into(binding.ivBackground)
            else    -> req.transition(DrawableTransitionOptions.withCrossFade(700)).into(binding.ivBackground)
        }
    }

    // ── 竖屏图片拼合 ──────────────────────────────────────────────────────────

    private fun tryMergePortrait(bytes: ByteArray, currentUrl: String): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outHeight <= opts.outWidth * 1.15) return null

            val candidate = viewModel.getPortraitCandidate(currentUrl) ?: return null
            val bytes2 = viewModel.loadImageBytes(candidate) ?: return null
            val b1 = BitmapFactory.decodeByteArray(bytes,  0, bytes.size)  ?: return null
            val b2 = BitmapFactory.decodeByteArray(bytes2, 0, bytes2.size) ?: return null

            val targetH = maxOf(b1.height, b2.height).coerceAtMost(1080)
            val w1 = (b1.width * (targetH.toFloat() / b1.height)).toInt()
            val w2 = (b2.width * (targetH.toFloat() / b2.height)).toInt()
            val targetW = 1920
            val ratio = targetW.toFloat() / (w1 + w2)
            val fw1 = (w1 * ratio).toInt()
            val fw2 = targetW - fw1
            val s1 = Bitmap.createScaledBitmap(b1, fw1, targetH, true)
            val s2 = Bitmap.createScaledBitmap(b2, fw2, targetH, true)
            val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            Canvas(result).apply {
                drawBitmap(s1, 0f, 0f, null)
                drawBitmap(s2, fw1.toFloat(), 0f, null)
            }
            b1.recycle(); b2.recycle(); s1.recycle(); s2.recycle()
            result
        } catch (_: Exception) { null }
    }
}
