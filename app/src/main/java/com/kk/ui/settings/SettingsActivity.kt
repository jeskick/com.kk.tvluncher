package com.kk.tvlauncher.ui.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import com.kk.tvlauncher.BuildConfig
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.kk.tvlauncher.R
import com.kk.tvlauncher.data.SmbScanner
import com.kk.tvlauncher.databinding.ActivitySettingsBinding
import com.kk.tvlauncher.ui.MainViewModel
import kotlinx.coroutines.launch

class SettingsActivity : FragmentActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    // 焦点颜色预设 (label, hexColor)
    private val colorPresets = listOf(
        "白色" to "#CCFFFFFF",
        "蓝色" to "#CC4FC3F7",
        "绿色" to "#CC66BB6A",
        "玫瑰" to "#CCE94560",
        "金色" to "#CCFFCA28",
        "橙色" to "#CCFF7043"
    )
    private var selectedColorHex = "#CCFFFFFF"

    // 内置壁纸列表 (value → 显示名)
    private val builtinWallpaperMap = linkedMapOf(
        "随机切换"           to "随机切换（内置）",
        "随机SMB"            to "随机SMB目录",
        "17ad181ad496361.jpg" to "城市夜景",
        "651df1b075b8157.jpg" to "自然风光",
        "67681fbc72277d3.jpg" to "抽象艺术",
        "minecraft-tiny.jpg"  to "游戏风格",
        "triumph-street.jpg"  to "街道实景"
    )
    private var selectedBuiltin = "随机切换"

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val uriStr = uri.toString()
            prefs.edit().putString("background_path", uriStr).apply()
            binding.tvBgPath.text = uri.lastPathSegment ?: uriStr
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        loadSavedValues()
        setupColorPicker()
        setupButtons()
    }

    private fun loadSavedValues() {
        val bgPath = prefs.getString("background_path", null)
        val builtinPref = prefs.getString("builtin_wallpaper", "随机切换")
        selectedBuiltin = builtinPref ?: "随机切换"

        binding.tvBgPath.text = when {
            bgPath == null -> "内置壁纸（${builtinWallpaperMap[selectedBuiltin] ?: selectedBuiltin}）"
            bgPath.startsWith("content://") -> bgPath.substringAfterLast("/")
            else -> bgPath
        }
        binding.tvBuiltinWallpaper.text = builtinWallpaperMap[selectedBuiltin] ?: selectedBuiltin

        binding.etWeatherCity.setText(prefs.getString("weather_city", BuildConfig.DEFAULT_WEATHER_CITY))
        binding.etWeatherApiKey.setText(prefs.getString("weather_api_key", ""))

        // SMB 路径迁移
        val savedPath = prefs.getString("smb_path", null)
        val smb = when {
            savedPath.isNullOrBlank() -> MainViewModel.DEFAULT_SMBJ_DIR
            savedPath.startsWith("https://") || savedPath.startsWith("http://") -> {
                MainViewModel.DEFAULT_SMBJ_DIR.also {
                    prefs.edit().putString("smb_path", it).apply()
                }
            }
            else -> savedPath
        }
        binding.tvSmbPath.text = smb
        binding.etSmbUser.setText(prefs.getString("smb_user", MainViewModel.DEFAULT_SMB_USER))
        binding.etSmbPass.setText(prefs.getString("smb_pass", MainViewModel.DEFAULT_SMB_PASS))
        binding.etSlideshowInterval.setText(prefs.getInt("slideshow_interval", 10).toString())

        // 透明度
        val alpha = prefs.getInt("ui_alpha", 70)
        binding.seekbarAlpha.progress = alpha
        binding.tvAlphaValue.text = "$alpha%"

        // 焦点颜色
        selectedColorHex = prefs.getString("focus_color", "#CCFFFFFF") ?: "#CCFFFFFF"
    }

    private fun setupColorPicker() {
        val dp = resources.displayMetrics.density
        val sizePx = (36 * dp).toInt()
        val marginPx = (8 * dp).toInt()
        val radiusPx = (8 * dp)

        colorPresets.forEachIndexed { index, (label, hex) ->
            val view = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    setMargins(0, 0, marginPx, 0)
                }
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radiusPx
                    setColor(Color.parseColor(hex))
                    if (hex == selectedColorHex) {
                        setStroke((3 * dp).toInt(), Color.WHITE)
                    } else {
                        setStroke((1 * dp).toInt(), Color.parseColor("#44FFFFFF"))
                    }
                }
                background = shape
                tag = hex
                contentDescription = label
                isFocusable = true
                isClickable = true
                setOnClickListener { v ->
                    selectedColorHex = v.tag as String
                    refreshColorSelection()
                }
                setOnFocusChangeListener { v, hasFocus ->
                    (v.background as? GradientDrawable)?.setStroke(
                        if (hasFocus) (3 * dp).toInt() else (1 * dp).toInt(),
                        if (hasFocus) Color.WHITE else Color.parseColor("#44FFFFFF")
                    )
                }
            }
            binding.llColorPicker.addView(view)
        }

        // SeekBar 透明度监听
        binding.seekbarAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.tvAlphaValue.text = "$progress%"
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    private fun refreshColorSelection() {
        val dp = resources.displayMetrics.density
        for (i in 0 until binding.llColorPicker.childCount) {
            val v = binding.llColorPicker.getChildAt(i)
            val hex = v.tag as? String ?: continue
            (v.background as? GradientDrawable)?.setStroke(
                if (hex == selectedColorHex) (3 * dp).toInt() else (1 * dp).toInt(),
                if (hex == selectedColorHex) Color.WHITE else Color.parseColor("#44FFFFFF")
            )
        }
    }

    private fun setupButtons() {
        // 本地图片
        binding.btnPickBg.setOnClickListener { pickImageLauncher.launch(arrayOf("image/*")) }
        binding.btnClearBg.setOnClickListener {
            prefs.edit().remove("background_path").apply()
            binding.tvBgPath.text = "内置壁纸（$selectedBuiltin）"
        }

        // 内置壁纸选择
        binding.btnPickBuiltin.setOnClickListener { showBuiltinWallpaperPicker() }

        // SMB
        binding.btnPickSmb.setOnClickListener { showSmbPicker() }
        binding.btnClearSmb.setOnClickListener {
            prefs.edit().remove("smb_path").apply()
            binding.tvSmbPath.text = "未设置"
        }

        // 系统按钮
        binding.btnSystemSettings.setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        binding.btnSystemWifi.setOnClickListener { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }

        // 保存 / 取消
        binding.btnSave.setOnClickListener {
            val intervalStr = binding.etSlideshowInterval.text.toString().trim()
            val interval = intervalStr.toIntOrNull()?.coerceIn(3, 3600) ?: 10
            prefs.edit()
                .putString("weather_city", binding.etWeatherCity.text.toString().trim())
                .putString("weather_api_key", binding.etWeatherApiKey.text.toString().trim())
                .putString("smb_user", binding.etSmbUser.text.toString().trim())
                .putString("smb_pass", binding.etSmbPass.text.toString())
                .putInt("slideshow_interval", interval)
                .putInt("ui_alpha", binding.seekbarAlpha.progress)
                .putString("focus_color", selectedColorHex)
                .putString("builtin_wallpaper", selectedBuiltin)
                .apply()
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun showBuiltinWallpaperPicker() {
        val keys   = builtinWallpaperMap.keys.toList()
        val labels = builtinWallpaperMap.values.toTypedArray()
        val current = keys.indexOf(selectedBuiltin).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("选择内置壁纸")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                selectedBuiltin = keys[which]
                binding.tvBuiltinWallpaper.text = labels[which]
                if (prefs.getString("background_path", null)?.startsWith("content://") != true) {
                    prefs.edit()
                        .remove("background_path")
                        .putString("builtin_wallpaper", selectedBuiltin)
                        .apply()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSmbPicker() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_smb_picker, null)
        val tvStatus  = view.findViewById<TextView>(R.id.tvScanStatus)
        val listView  = view.findViewById<android.widget.ListView>(R.id.lvSmbHosts)
        val etManual  = view.findViewById<EditText>(R.id.etManualSmb)
        val btnScan   = view.findViewById<TextView>(R.id.btnScan)
        val progress  = view.findViewById<ProgressBar>(R.id.scanProgress)

        etManual.setText(prefs.getString("smb_path", MainViewModel.DEFAULT_SMBJ_DIR))

        val foundHosts = mutableListOf<String>()
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, foundHosts)
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle("SMB 壁纸路径")
            .setView(view)
            .setPositiveButton("确认") { _, _ ->
                val selected = if (listView.checkedItemPosition >= 0)
                    foundHosts[listView.checkedItemPosition] else null
                val path = selected?.let { "smb://$it/kk/" } ?: etManual.text.toString().trim()
                if (path.isNotBlank()) {
                    prefs.edit().putString("smb_path", path).apply()
                    binding.tvSmbPath.text = path
                }
            }
            .setNegativeButton("取消", null)
            .create()

        btnScan.setOnClickListener {
            foundHosts.clear()
            adapter.notifyDataSetChanged()
            progress.visibility = View.VISIBLE
            tvStatus.text = "正在扫描局域网..."
            btnScan.isEnabled = false
            lifecycleScope.launch {
                SmbScanner.scan(this@SettingsActivity, onFound = { host ->
                    runOnUiThread {
                        foundHosts.add(host)
                        adapter.notifyDataSetChanged()
                        tvStatus.text = "已发现 ${foundHosts.size} 台设备..."
                    }
                })
                runOnUiThread {
                    progress.visibility = View.GONE
                    btnScan.isEnabled = true
                    tvStatus.text = if (foundHosts.isEmpty()) "未找到 SMB 设备" else "找到 ${foundHosts.size} 台设备，点击选择"
                }
            }
        }
        dialog.show()
    }
}
