package com.kk.tvlauncher.ui.lan

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 扫描局域网共享，选中图片或视频后直接播放。 */
class LanLibraryActivity : FragmentActivity() {

    private lateinit var binding: ActivityLanLibraryBinding
    private val stack = ArrayDeque<String>()
    private val cache = HashMap<String, List<SmbMedia.Entry>>()
    private var rootItems = emptyList<SmbMedia.Entry>()
    private var alive = true
    private var opening = false
    private var page = 0
    private var loadJob: Job? = null
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
        loadJob?.cancel()
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
                goBack()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun scan() {
        binding.scanProgress.visibility = View.VISIBLE
        binding.summaryBar.visibility = View.GONE
        binding.tvStatus.visibility = View.GONE
        binding.tvPath.text = "正在扫描局域网"
        binding.btnScan.isEnabled = false
        stack.clear()
        cache.clear()
        page++
        opening = true
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val hosts = SmbScanner.scan(this@LanLibraryActivity)
            val shares = hosts.map { host ->
                async(Dispatchers.IO) {
                    SmbMedia.list("smb://$host/", user, pass).orEmpty()
                        .filter { it.kind == SmbMedia.Kind.DIRECTORY }
                }
            }.awaitAll().flatten()
            opening = false
            if (!alive) return@launch
            rootItems = shares
            binding.scanProgress.visibility = View.GONE
            binding.btnScan.isEnabled = true
            if (shares.isEmpty()) {
                binding.tvPath.text = "没有找到共享目录"
                show(emptyList(), null)
            } else {
                show(shares, null)
            }
        }
    }

    private fun goBack() {
        loadJob?.cancel()
        opening = false
        page++
        stack.removeLast()
        binding.scanProgress.visibility = View.GONE
        binding.tvStatus.visibility = View.GONE
        if (stack.isEmpty()) show(rootItems, null) else showCached(stack.last())
    }

    private fun showCached(location: String) {
        val saved = cache[location]
        if (saved != null) show(saved.filter { it.kind != SmbMedia.Kind.SUBTITLE }, location)
        else open(location, push = false)
    }

    private fun open(location: String, push: Boolean = false) {
        if (push) {
            if (opening) return
            stack.addLast(location)
        }
        cache[location]?.let {
            opening = false
            show(it.filter { entry -> entry.kind != SmbMedia.Kind.SUBTITLE }, location)
            return
        }
        val token = ++page
        opening = true
        loadJob?.cancel()
        binding.scanProgress.visibility = View.VISIBLE
        binding.tvStatus.visibility = View.GONE
        binding.tvPath.text = "正在打开 ${placeName(location)}"
        loadJob = lifecycleScope.launch {
            val children = withContext(Dispatchers.IO) { SmbMedia.list(location, user, pass) }
            if (!alive || token != page || stack.lastOrNull() != location) return@launch
            opening = false
            binding.scanProgress.visibility = View.GONE
            if (children == null) {
                if (stack.isNotEmpty()) stack.removeLast()
                val parent = stack.lastOrNull()
                if (parent == null) show(rootItems, null)
                else cache[parent]?.let { saved ->
                    show(saved.filter { entry -> entry.kind != SmbMedia.Kind.SUBTITLE }, parent)
                }
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.text = "这个目录暂时打不开"
                return@launch
            }
            cache[location] = children
            show(children.filter { it.kind != SmbMedia.Kind.SUBTITLE }, location)
        }
    }

    private fun show(items: List<SmbMedia.Entry>, location: String?) {
        binding.summaryBar.visibility = View.GONE
        binding.tvPath.text = if (location == null) "局域网" else placeName(location)
        val list = binding.llList
        list.removeAllViews()
        if (items.isEmpty() && location != null) {
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = "这个目录里没有可播放的文件"
            binding.btnScan.requestFocus()
            return
        }
        binding.tvStatus.visibility = View.GONE
        val rows = mutableListOf<Row>()
        if (stack.isNotEmpty()) {
            rows += addRow(SmbMedia.Entry("返回上一级", "..", SmbMedia.Kind.DIRECTORY))
        }
        items.take(300).forEach { item -> rows += addRow(item) }
        bindClicks(rows, items)
        (list.getChildAt(0) ?: binding.btnScan).requestFocus()
    }

    private fun bindClicks(rows: List<Row>, items: List<SmbMedia.Entry>) {
        rows.forEach { row ->
            row.view.setOnClickListener {
                val item = row.item
                when {
                    item.url == ".." -> goBack()
                    item.kind == SmbMedia.Kind.DIRECTORY -> open(item.url, push = true)
                    else -> play(item, items)
                }
            }
        }
    }

    private fun addRow(item: SmbMedia.Entry): Row {
        val view = layoutInflater.inflate(R.layout.item_lan_entry, binding.llList, false)
        val back = item.url == ".."
        val badge = view.findViewById<TextView>(R.id.badge)
        val accent = view.findViewById<View>(R.id.accent)
        badge.text = when {
            back -> "返回"
            item.kind == SmbMedia.Kind.VIDEO -> "视频"
            item.kind == SmbMedia.Kind.IMAGE -> "图片"
            else -> "目录"
        }
        val color = when {
            back -> getColor(R.color.text_secondary)
            item.kind == SmbMedia.Kind.VIDEO -> getColor(R.color.accent)
            item.kind == SmbMedia.Kind.IMAGE -> Color.parseColor("#FF8EB6FF")
            else -> Color.parseColor("#99FFFFFF")
        }
        badge.setTextColor(color)
        accent.setBackgroundColor(color)
        view.findViewById<TextView>(R.id.name).text = item.name
        view.findViewById<TextView>(R.id.meta).text = ""
        binding.llList.addView(view)
        return Row(item, view)
    }

    private fun play(item: SmbMedia.Entry, folder: List<SmbMedia.Entry>) {
        val full = stack.lastOrNull()?.let { cache[it] } ?: folder
        val playable = folder.filter { it.kind == SmbMedia.Kind.IMAGE || it.kind == SmbMedia.Kind.VIDEO }
        LanPlayRequest.user = user
        LanPlayRequest.pass = pass
        LanPlayRequest.items = playable
        LanPlayRequest.folder = full
        LanPlayRequest.index = playable.indexOf(item).coerceAtLeast(0)
        startActivity(android.content.Intent(this, LanPlayActivity::class.java))
    }

    private fun placeName(url: String): String {
        val parts = url.removePrefix("smb://").trim('/').split('/').filter { it.isNotBlank() }
        return parts.joinToString("   /   ").ifBlank { "局域网" }
    }

    private data class Row(val item: SmbMedia.Entry, val view: View)

    companion object {
        fun formatSize(bytes: Long): String {
            val gb = bytes / (1024.0 * 1024.0 * 1024.0)
            if (gb >= 10) return String.format(Locale.US, "%.1f GB", gb)
            if (gb >= 1) return String.format(Locale.US, "%.2f GB", gb)
            val mb = bytes / (1024.0 * 1024.0)
            if (mb >= 10) return String.format(Locale.US, "%.0f MB", mb)
            if (mb >= 1) return String.format(Locale.US, "%.1f MB", mb)
            val kb = bytes / 1024.0
            if (kb >= 1) return String.format(Locale.US, "%.0f KB", kb)
            return "$bytes B"
        }
    }
}
