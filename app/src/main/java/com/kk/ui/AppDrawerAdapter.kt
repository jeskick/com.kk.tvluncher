package com.kk.tvlauncher.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kk.tvlauncher.databinding.ItemAppDrawerBinding
import com.kk.tvlauncher.model.AppInfo

class AppDrawerAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    var focusColorHex: String = "#CCFFFFFF"
) : ListAdapter<AppInfo, AppDrawerAdapter.ViewHolder>(AppDiffCallback()) {

    private fun applyFocusBg(v: View, hasFocus: Boolean) {
        val dp = v.resources.displayMetrics.density
        val gd = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp * 14
            if (hasFocus) {
                setColor(Color.parseColor("#44FFFFFF"))
                setStroke((dp * 1.5f).toInt(),
                    runCatching { Color.parseColor(focusColorHex) }.getOrDefault(Color.WHITE))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(0, Color.TRANSPARENT)
            }
        }
        v.background = gd
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppDrawerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val b: ItemAppDrawerBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(app: AppInfo) {
            b.tvAppName.text = app.appName
            Glide.with(b.root).load(app.icon).into(b.ivIcon)
            b.root.setOnClickListener { onAppClick(app) }
            applyFocusBg(b.root, false)
            b.root.setOnFocusChangeListener { v, hasFocus ->
                applyFocusBg(v, hasFocus)
                v.animate()
                    .scaleX(if (hasFocus) 1.15f else 1.0f)
                    .scaleY(if (hasFocus) 1.15f else 1.0f)
                    .setDuration(150)
                    .start()
            }
        }
    }

    private class AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(o: AppInfo, n: AppInfo) = o.packageName == n.packageName
        override fun areContentsTheSame(o: AppInfo, n: AppInfo) = o.appName == n.appName
    }
}
