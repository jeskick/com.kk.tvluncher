package com.kk.tvlauncher.ui.settings

import android.app.AlertDialog
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.kk.tvlauncher.R
import com.kk.tvlauncher.data.SmbScanner
import com.kk.tvlauncher.data.WallpaperBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 打开后自动列出本机目录和局域网共享，可选一张图片或一个目录。 */
object WallpaperPicker {

    private const val UP = ".."

    fun show(activity: FragmentActivity, user: String, pass: String, onPick: (String) -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_wallpaper_picker, null)
        val status = view.findViewById<TextView>(R.id.tvPickStatus)
        val progress = view.findViewById<ProgressBar>(R.id.pickProgress)
        val useDir = view.findViewById<TextView>(R.id.btnUseDir)
        val list = view.findViewById<LinearLayout>(R.id.llPickList)
        val stack = ArrayDeque<String>()
        val rootItems = mutableListOf<WallpaperBrowser.Item>()
        var shown = emptyList<WallpaperBrowser.Item>()
        var alive = true

        val dialog = AlertDialog.Builder(activity)
            .setTitle("选择图片或目录")
            .setView(view)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnDismissListener { alive = false }

        lateinit var openDir: (String) -> Unit

        fun render(items: List<WallpaperBrowser.Item>, focusFirst: Boolean) {
            if (!alive) return
            shown = items
            val focused = (list.focusedChild as? TextView)?.text?.toString()
            list.removeAllViews()
            val rows = mutableListOf<WallpaperBrowser.Item>()
            if (stack.isNotEmpty()) rows += WallpaperBrowser.Item("返回上一级", "", UP, true)
            rows += items.take(300)
            useDir.visibility = if (stack.isNotEmpty()) View.VISIBLE else View.GONE
            rows.forEach { item ->
                val row = rowView(activity, item) {
                    when {
                        item.location == UP -> {
                            stack.removeLast()
                            if (stack.isEmpty()) render(rootItems, true) else openDir(stack.last())
                        }
                        item.directory -> {
                            stack.addLast(item.location)
                            openDir(item.location)
                        }
                        else -> {
                            onPick(item.location)
                            dialog.dismiss()
                        }
                    }
                }
                val gap = (6 * activity.resources.displayMetrics.density).toInt()
                row.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = gap }
                list.addView(row)
            }
            val restore = (0 until list.childCount)
                .map { list.getChildAt(it) as TextView }
                .firstOrNull { it.text.toString() == focused }
            val target = when {
                restore != null -> restore
                focusFirst && useDir.visibility == View.VISIBLE -> useDir
                focusFirst -> list.getChildAt(0)
                else -> null
            }
            target?.requestFocus()
        }

        openDir = { location ->
            progress.visibility = View.VISIBLE
            status.text = "正在打开 $location"
            activity.lifecycleScope.launch {
                val children = withTimeoutOrNull(15_000) {
                    WallpaperBrowser.children(location, user, pass)
                }.orEmpty()
                if (!alive || stack.lastOrNull() != location) return@launch
                progress.visibility = View.GONE
                status.text = if (children.isEmpty()) "这个目录里没有图片或子目录" else location
                render(children, true)
            }
        }

        useDir.setOnClickListener {
            val dir = stack.lastOrNull() ?: return@setOnClickListener
            if (shown.none { !it.directory }) {
                Toast.makeText(activity, "这个目录里没有图片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val path = if (dir.startsWith("smb://") && !dir.endsWith("/")) "$dir/" else dir
            onPick(path)
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(
            (680 * activity.resources.displayMetrics.density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        activity.lifecycleScope.launch {
            val local = withContext(Dispatchers.IO) { WallpaperBrowser.localPlaces(activity) }
            if (!alive) return@launch
            rootItems += local
            if (stack.isEmpty()) {
                status.text = "本机 ${local.size} 个目录，正在搜索局域网"
                render(rootItems, true)
            }
            val hosts = SmbScanner.scan(activity)
            val shares = hosts.map { host ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(15_000) { WallpaperBrowser.listShares(host, user, pass) }.orEmpty()
                }
            }.awaitAll().flatten()
            if (!alive) return@launch
            rootItems += shares
            progress.visibility = View.GONE
            if (stack.isEmpty()) {
                status.text = "本机 ${local.size} 个目录，局域网 ${shares.size} 个共享"
                render(rootItems, list.childCount == 0)
            }
        }
    }

    private fun rowView(activity: FragmentActivity, item: WallpaperBrowser.Item, onClick: () -> Unit): TextView {
        val pad = (10 * activity.resources.displayMetrics.density).toInt()
        return TextView(activity).apply {
            text = if (item.detail.isBlank() || item.location == UP) item.label
            else "${item.label}    ${item.detail}"
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(activity.getColor(R.color.text_primary))
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(R.drawable.bg_input)
            isFocusable = true
            isClickable = true
            isFocusableInTouchMode = true
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setOnClickListener { onClick() }
        }
    }
}
