package com.kk.tvlauncher.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kk.tvlauncher.databinding.ItemDockAppBinding
import com.kk.tvlauncher.databinding.ItemDockAddBinding
import com.kk.tvlauncher.model.DockItem

private const val VIEW_TYPE_APP = 0
private const val VIEW_TYPE_ADD = 1

class DockAdapter(
    private val onAppClick: (String) -> Unit,
    private val onAppLongClick: (String) -> Unit,
    private val onAddClick: () -> Unit,
    /** 只有右移到最后一个"+"按钮时按下键才触发 */
    private val onLastItemDownKey: () -> Unit,
    var focusColorHex: String = "#CCFFFFFF"
) : ListAdapter<DockItem, RecyclerView.ViewHolder>(DockDiffCallback()) {

    /** 初始化时一次性列举 assets/icons/ 目录，避免每次 bind 都做 IO */
    private var cachedAssetIcons: Set<String> = emptySet()

    fun cacheAssetIcons(context: android.content.Context) {
        if (cachedAssetIcons.isNotEmpty()) return
        cachedAssetIcons = try {
            context.assets.list("icons")?.toHashSet() ?: emptySet()
        } catch (_: Exception) { emptySet() }
    }

    private fun applyFocusBg(v: View, hasFocus: Boolean) {
        val radius = v.resources.displayMetrics.density * 16
        val gd = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            if (hasFocus) {
                setColor(Color.parseColor("#38FFFFFF"))
                setStroke((v.resources.displayMetrics.density * 1.5f).toInt(),
                    runCatching { Color.parseColor(focusColorHex) }.getOrDefault(Color.WHITE))
            } else {
                setColor(Color.parseColor("#14FFFFFF"))
                setStroke((v.resources.displayMetrics.density * 1f).toInt(),
                    Color.parseColor("#28FFFFFF"))
            }
        }
        v.background = gd
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is DockItem.App -> VIEW_TYPE_APP
        is DockItem.AddButton -> VIEW_TYPE_ADD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_APP -> {
                val binding = ItemDockAppBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                AppViewHolder(binding)
            }
            else -> {
                val binding = ItemDockAddBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                AddViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DockItem.App -> (holder as AppViewHolder).bind(item)
            is DockItem.AddButton -> (holder as AddViewHolder).bind()
        }
    }

    inner class AppViewHolder(private val b: ItemDockAppBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: DockItem.App) {
            val app = item.appInfo
            val ctx = b.root.context

            // 优先检查自定义透明图标（assets/icons/<packageName>.png）
            val assetFile = "${app.packageName}.png"
            val customAsset = "icons/$assetFile"
            val hasCustom = cachedAssetIcons.contains(assetFile)

            when {
                hasCustom -> {
                    b.ivCustomIcon.visibility = View.VISIBLE
                    b.ivBanner.visibility = View.GONE
                    b.layoutFallback.visibility = View.GONE
                    // 直接读字节流，避免 Glide 对 asset:// 路径的兼容问题
                    try {
                        val bytes = ctx.assets.open(customAsset).use { it.readBytes() }
                        Glide.with(ctx).load(bytes).into(b.ivCustomIcon)
                    } catch (_: Exception) { }
                }
                app.banner != null -> {
                    b.ivCustomIcon.visibility = View.GONE
                    b.ivBanner.visibility = View.VISIBLE
                    b.layoutFallback.visibility = View.GONE
                    Glide.with(ctx).load(app.banner).into(b.ivBanner)
                }
                else -> {
                    b.ivCustomIcon.visibility = View.GONE
                    b.ivBanner.visibility = View.GONE
                    b.layoutFallback.visibility = View.VISIBLE
                    b.tvAppName.text = app.appName
                    Glide.with(ctx).load(app.icon).into(b.ivIcon)
                }
            }

            b.root.setOnClickListener { onAppClick(app.packageName) }
            b.root.setOnLongClickListener {
                onAppLongClick(app.packageName)
                true
            }
            applyFocusBg(b.root, false)
            b.root.setOnFocusChangeListener { v, hasFocus ->
                applyFocusBg(v, hasFocus)
                v.pivotX = v.width / 2f
                v.pivotY = v.height / 2f
                v.animate()
                    .scaleX(if (hasFocus) 1.12f else 1.0f)
                    .scaleY(if (hasFocus) 1.12f else 1.0f)
                    .translationZ(if (hasFocus) 16f else 0f)
                    .setDuration(160)
                    .start()
            }
            // 普通 App 项：下键不触发全部应用；第一个项的左键循环到最后一项
            b.root.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when {
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT && bindingAdapterPosition == 0 -> {
                        focusPosition(itemCount - 1); true
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                        bindingAdapterPosition == itemCount - 1 -> {
                        focusPosition(0); true
                    }
                    else -> false
                }
            }
        }
    }

    inner class AddViewHolder(private val b: ItemDockAddBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind() {
            b.root.setOnClickListener { onAddClick() }
            applyFocusBg(b.root, false)
            b.root.setOnFocusChangeListener { v, hasFocus ->
                applyFocusBg(v, hasFocus)
                v.pivotX = v.width / 2f
                v.pivotY = v.height / 2f
                v.animate()
                    .scaleX(if (hasFocus) 1.12f else 1.0f)
                    .scaleY(if (hasFocus) 1.12f else 1.0f)
                    .translationZ(if (hasFocus) 16f else 0f)
                    .setDuration(160)
                    .start()
            }
            // AddButton 是最后一项：下键打开全部应用；右键循环到第一项
            b.root.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { onLastItemDownKey(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { focusPosition(0); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        // 如果就只有 Add 按钮（Dock 空），左键也循环不出效果
                        if (itemCount <= 1) true else false
                    }
                    else -> false
                }
            }
        }
    }

    /** 让指定位置的 item 获得焦点（必要时先滚动到可见范围） */
    private fun focusPosition(pos: Int) {
        if (pos < 0 || pos >= itemCount) return
        attachedRecyclerView?.let { rv ->
            val lm = rv.layoutManager ?: return
            val view = lm.findViewByPosition(pos)
            if (view != null) {
                view.requestFocus()
            } else {
                rv.scrollToPosition(pos)
                rv.post { rv.layoutManager?.findViewByPosition(pos)?.requestFocus() }
            }
        }
    }

    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
    }

    private class DockDiffCallback : DiffUtil.ItemCallback<DockItem>() {
        override fun areItemsTheSame(oldItem: DockItem, newItem: DockItem): Boolean {
            if (oldItem is DockItem.AddButton && newItem is DockItem.AddButton) return true
            if (oldItem is DockItem.App && newItem is DockItem.App)
                return oldItem.appInfo.packageName == newItem.appInfo.packageName
            return false
        }

        override fun areContentsTheSame(oldItem: DockItem, newItem: DockItem): Boolean {
            if (oldItem is DockItem.App && newItem is DockItem.App)
                return oldItem.appInfo.appName == newItem.appInfo.appName
            return oldItem is DockItem.AddButton && newItem is DockItem.AddButton
        }
    }
}
