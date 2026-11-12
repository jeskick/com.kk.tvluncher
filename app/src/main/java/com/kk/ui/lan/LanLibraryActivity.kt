package com.kk.tvlauncher.ui.lan

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.kk.tvlauncher.R
import com.kk.tvlauncher.data.SmbMedia
import com.kk.tvlauncher.data.SmbScanner
import com.kk.tvlauncher.databinding.ActivityLanLibraryBinding
import com.kk.tvlauncher.ui.MainViewModel
import com.kk.tvlauncher.ui.lock.KeyDecision
import com.kk.tvlauncher.ui.lock.LockInputGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 扫描局域网共享，选中图片或视频后直接播放。 */
class LanLibraryActivity : FragmentActivity() {

    private lateinit var binding: ActivityLanLibraryBinding
    private val stack = ArrayDeque<String>()
    private var rootItems = emptyList<SmbMedia.Entry>()
    private var alive = true
    private val lockGuard by lazy { LockInputGuard(this, armWhenUnlocked = false) }
    private val user by lazy {
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("smb_user", null)?.takeIf { it.isNotBlank() }
            ?: MainViewModel.DEFAULT_SMB_USER
    }
    private val pass by lazy {
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("smb_pass", null)?.takeIf { it.isNotBlank() }
            ?: MainViewModel.DEFAULT_SMB_PASS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLanLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnScan.setOnClickListener { scan() }
        binding.btnScan.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        lockGuard.start()
    }

    override fun onPause() {
        lockGuard.stop()
        super.onPause()
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (lockGuard.decide(event)) {
            KeyDecision.CONSUME -> return true
            KeyDecision.SYSTEM -> return super.dispatchKeyEvent(event)
            KeyDecision.UI -> Unit
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE)
        ) {
            if (stack.isNotEmpty()) {
                stack.removeLast()
                if (stack.isEmpty()) show(rootItems, "按确定播放") else open(stack.last())
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun scan() {
        binding.scanProgress.visibility = View.VISIBLE
        binding.tvStatus.text = "正在扫描局域网"
        binding.btnScan.isEnabled = false
        stack.clear()
        lifecycleScope.launch {
            val hosts = SmbScanner.scan(this@LanLibraryActivity)
            val shares = hosts.map { host ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(15_000) {
                        SmbMedia.list("smb://$host/", user, pass).filter { it.kind == SmbMedia.Kind.DIRECTORY }
                    }.orEmpty()
                }
            }.awaitAll().flatten()
            if (!alive) return@launch
            rootItems = shares
            binding.scanProgress.visibility = View.GONE
            binding.btnScan.isEnabled = true
            binding.tvStatus.text = if (shares.isEmpty()) "没有找到共享目录" else "找到 ${shares.size} 个共享，按确定进入"
            show(shares, binding.tvStatus.text.toString())
        }
    }

    private fun open(location: String) {
        binding.scanProgress.visibility = View.VISIBLE
        binding.tvStatus.text = "正在打开"
        lifecycleScope.launch {
            val children = withContext(Dispatchers.IO) {
                withTimeoutOrNull(15_000) { SmbMedia.list(location, user, pass) }.orEmpty()
            }
            if (!alive || stack.lastOrNull() != location) return@launch
            binding.scanProgress.visibility = View.GONE
            val visible = children.filter { it.kind != SmbMedia.Kind.SUBTITLE }
            binding.tvStatus.text = if (visible.isEmpty()) "这个目录里没有可播放的文件" else location
            show(visible, binding.tvStatus.text.toString())
        }
    }

    private fun show(items: List<SmbMedia.Entry>, status: String) {
        binding.tvStatus.text = status
        val list = binding.llList
        list.removeAllViews()
        val rows = mutableListOf<SmbMedia.Entry>()
        if (stack.isNotEmpty()) rows += SmbMedia.Entry("返回上一级", "..", SmbMedia.Kind.DIRECTORY)
        rows += items.take(300)
        rows.forEach { item ->
            list.addView(row(item) {
                when {
                    item.url == ".." -> {
                        stack.removeLast()
                        if (stack.isEmpty()) show(rootItems, "按确定播放") else open(stack.last())
                    }
                    item.kind == SmbMedia.Kind.DIRECTORY -> {
                        stack.addLast(item.url)
                        open(item.url)
                    }
                    else -> play(item, items)
                }
            })
        }
        (list.getChildAt(0) ?: binding.btnScan).requestFocus()
    }

    private fun play(item: SmbMedia.Entry, folder: List<SmbMedia.Entry>) {
        val playable = folder.filter { it.kind == SmbMedia.Kind.IMAGE || it.kind == SmbMedia.Kind.VIDEO }
        LanPlayRequest.user = user
        LanPlayRequest.pass = pass
        LanPlayRequest.items = playable
        LanPlayRequest.index = playable.indexOf(item).coerceAtLeast(0)
        startActivity(android.content.Intent(this, LanPlayActivity::class.java))
    }

    private fun row(item: SmbMedia.Entry, onClick: () -> Unit): TextView {
        val pad = (10 * resources.displayMetrics.density).toInt()
        val mark = when (item.kind) {
            SmbMedia.Kind.VIDEO -> "视频"
            SmbMedia.Kind.IMAGE -> "图片"
            SmbMedia.Kind.DIRECTORY -> if (item.url == "..") "" else "目录"
            SmbMedia.Kind.SUBTITLE -> "字幕"
        }
        return TextView(this).apply {
            text = if (mark.isBlank()) item.name else "${item.name}    $mark"
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(getColor(R.color.text_primary))
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(R.drawable.bg_input)
            isFocusable = true
            isClickable = true
            isFocusableInTouchMode = true
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundResource(if (hasFocus) R.drawable.bg_btn_accent else R.drawable.bg_input)
                (v as TextView).setTextColor(getColor(if (hasFocus) R.color.bg_dark else R.color.text_primary))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = pad / 2 }
        }
    }
}
