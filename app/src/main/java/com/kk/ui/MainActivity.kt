package com.kk.tvlauncher.ui

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : FragmentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var dockAdapter: DockAdapter
    private lateinit var appDrawerAdapter: AppDrawerAdapter

    private val clockHandler = Handler(Looper.getMainLooper())
    private var isAppDrawerVisible = false

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        com.kk.tvlauncher.service.KeepAliveService.start(this)

        setupTopBar()
        setupDock()
        setupAppDrawer()
        observeViewModel()
        applyUiSettings()

        clockHandler.post(clockRunnable)
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadAll()
        applyUiSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockRunnable)
    }

    // ── 读取并应用外观设置 ──────────────────────────────────────────────────────

    private fun applyUiSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val alpha = prefs.getInt("ui_alpha", 70) / 100f
        val focusColor = prefs.getString("focus_color", "#CCFFFFFF") ?: "#CCFFFFFF"

        binding.weatherCard.alpha = alpha
        binding.weatherCard.cardElevation = 0f

        // 将焦点颜色同步给 Adapter（下次 bind 时生效）
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
            binding.btnSettings.setPadding(
                if (hasFocus) 24 else 0, 0, if (hasFocus) 24 else 0, 0
            )
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
            binding.btnWifi.setPadding(
                if (hasFocus) 20 else 0, 0, if (hasFocus) 20 else 0, 0
            )
            binding.btnWifi.layoutParams.width =
                if (hasFocus) ViewGroup.LayoutParams.WRAP_CONTENT else btnH
            binding.btnWifi.requestLayout()
        }

        updateWifiState()
    }

    private fun updateWifiState() {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
        val ssid = wm?.connectionInfo?.ssid?.replace("\"", "") ?: ""
        val label = if (ssid.isNotBlank() && ssid != "<unknown ssid>") ssid else ""
        binding.btnWifi.tag = label
    }

    private fun updateClock() {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("MM月dd日  EEE", Locale.CHINESE)
        val now = Date()
        binding.tvTime.text = timeFormat.format(now)
        binding.tvDate.text = dateFormat.format(now)
    }

    // ── Dock ──────────────────────────────────────────────────────────────────

    private fun setupDock() {
        dockAdapter = DockAdapter(
            onAppClick = { pkg -> viewModel.launchApp(pkg) },
            onAppLongClick = { pkg -> viewModel.removeFromDock(pkg) },
            onAddClick = { startActivity(Intent(this, AppPickerActivity::class.java)) },
            onDownKey = { showAppDrawer() }
        )

        val gapPx = (14 * resources.displayMetrics.density).toInt()
        binding.rvDock.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = dockAdapter
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    outRect.left = gapPx; outRect.right = gapPx
                }
            })
        }
    }

    // ── App 抽屉（遥控器向下滑出）─────────────────────────────────────────────

    private fun setupAppDrawer() {
        appDrawerAdapter = AppDrawerAdapter(onAppClick = { app -> viewModel.launchApp(app.packageName) })

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
        binding.appDrawerPanel.visibility = View.VISIBLE
        // 抽屉显示时隐藏 Dock
        binding.llDockContainer.animate().alpha(0f).setDuration(200).withEndAction {
            binding.llDockContainer.visibility = View.INVISIBLE
        }.start()
        binding.appDrawerPanel.doOnLayout { panel ->
            panel.translationY = panel.height.toFloat()
            panel.animate().translationY(0f).setDuration(280).start()
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
                // 恢复 Dock
                binding.llDockContainer.visibility = View.VISIBLE
                binding.llDockContainer.animate().alpha(1f).setDuration(200).start()
                binding.rvDock.requestFocus()
            }
            .start()
    }

    // ── 遥控器按键拦截 ────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
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

            val forecasts = info.forecast
            if (forecasts.isNotEmpty()) {
                binding.tvForecast1Day.text = forecasts[0].dayName
                binding.tvForecast1Temp.text = "${forecasts[0].maxTemp}° / ${forecasts[0].minTemp}°"
                binding.tvForecast1Icon.text = forecasts[0].weatherEmoji()
            }
            if (forecasts.size > 1) {
                binding.tvForecast2Day.text = forecasts[1].dayName
                binding.tvForecast2Temp.text = "${forecasts[1].maxTemp}° / ${forecasts[1].minTemp}°"
                binding.tvForecast2Icon.text = forecasts[1].weatherEmoji()
            }
            if (forecasts.size > 2) {
                binding.tvForecast3Day.text = forecasts[2].dayName
                binding.tvForecast3Temp.text = "${forecasts[2].maxTemp}° / ${forecasts[2].minTemp}°"
                binding.tvForecast3Icon.text = forecasts[2].weatherEmoji()
            }
        }

        viewModel.backgroundPath.observe(this) { path ->
            if (path.isNullOrBlank()) return@observe
            when {
                path.startsWith("smb://") || path.startsWith("https://") -> {
                    // SMB / WebDAV：IO 线程读取，检测竖屏并可能拼接
                    lifecycleScope.launch(Dispatchers.IO) {
                        val bytes = viewModel.loadImageBytes(path)
                        val finalBitmap = bytes?.let { tryMergePortrait(it, path) }
                        withContext(Dispatchers.Main) {
                            if (finalBitmap != null) {
                                Glide.with(this@MainActivity)
                                    .load(finalBitmap)
                                    .transition(DrawableTransitionOptions.withCrossFade(800))
                                    .centerCrop()
                                    .into(binding.ivBackground)
                            } else if (bytes != null) {
                                Glide.with(this@MainActivity)
                                    .load(bytes)
                                    .transition(DrawableTransitionOptions.withCrossFade(800))
                                    .centerCrop()
                                    .into(binding.ivBackground)
                            }
                        }
                    }
                }
                else -> {
                    // file:///android_asset/ 或 content:// → Glide 原生支持
                    Glide.with(this)
                        .load(path)
                        .transition(DrawableTransitionOptions.withCrossFade(800))
                        .centerCrop()
                        .into(binding.ivBackground)
                }
            }
        }
    }

    /**
     * 检测图片是否为竖屏。若是，尝试从 SMB 列表找另一张竖屏图随机拼合并排。
     * @return 拼合后的 Bitmap，或 null（表示使用原始 bytes）
     */
    private fun tryMergePortrait(bytes: ByteArray, currentUrl: String): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            if (h <= w * 1.15) return null  // 不是竖屏，不拼合

            // 是竖屏，找另一张竖屏候选
            val candidate = viewModel.getPortraitCandidate(currentUrl) ?: return null
            val bytes2 = viewModel.loadImageBytes(candidate) ?: return null

            // 解码两张图，等比缩放后无缝并排
            val b1 = BitmapFactory.decodeByteArray(bytes,  0, bytes.size)  ?: return null
            val b2 = BitmapFactory.decodeByteArray(bytes2, 0, bytes2.size) ?: return null

            val targetH = maxOf(b1.height, b2.height).coerceAtMost(1080)
            val w1 = (b1.width  * (targetH.toFloat() / b1.height)).toInt()
            val w2 = (b2.width  * (targetH.toFloat() / b2.height)).toInt()
            // 拉伸两侧各补 blendW 像素，使总宽刚好覆盖目标宽度（TV 1920 / 2 = 960）
            val targetW = 1920
            // 按比例分配宽度，使两张图均匀铺满 1920
            val ratio = targetW.toFloat() / (w1 + w2)
            val fw1 = (w1 * ratio).toInt()
            val fw2 = targetW - fw1

            val s1 = Bitmap.createScaledBitmap(b1, fw1, targetH, true)
            val s2 = Bitmap.createScaledBitmap(b2, fw2, targetH, true)

            val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)

            // 两张图直接 0 间距并排
            canvas.drawBitmap(s1, 0f, 0f, null)
            canvas.drawBitmap(s2, fw1.toFloat(), 0f, null)

            b1.recycle(); b2.recycle(); s1.recycle(); s2.recycle()
            result
        } catch (e: Exception) {
            null
        }
    }
}
