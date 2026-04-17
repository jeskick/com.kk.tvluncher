package com.kk.tvlauncher.ui.picker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kk.tvlauncher.databinding.ItemAppPickerBinding
import com.kk.tvlauncher.model.AppInfo

class AppPickerAdapter(
    private val selectedPackages: MutableSet<String>,
    private val onSelectionChanged: (Set<String>) -> Unit
) : ListAdapter<AppInfo, AppPickerAdapter.ViewHolder>(AppDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppPickerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val b: ItemAppPickerBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(app: AppInfo) {
            b.tvAppName.text = app.appName
            Glide.with(b.root).load(app.icon).into(b.ivIcon)
            updateSelection(selectedPackages.contains(app.packageName))

            b.root.setOnClickListener {
                val selected = !selectedPackages.contains(app.packageName)
                if (selected) {
                    selectedPackages.add(app.packageName)
                } else {
                    selectedPackages.remove(app.packageName)
                }
                updateSelection(selected)
                onSelectionChanged(selectedPackages.toSet())
            }

            b.root.setOnFocusChangeListener { _, hasFocus ->
                b.itemContainer.animate()
                    .scaleX(if (hasFocus) 1.10f else 1f)
                    .scaleY(if (hasFocus) 1.10f else 1f)
                    .setDuration(130).start()
            }
        }

        private fun updateSelection(selected: Boolean) {
            b.ivCheckBadge.visibility = if (selected) View.VISIBLE else View.GONE
            // 选中时 item 背景加深一点（通过 alpha 区分）
            b.itemContainer.alpha = if (selected) 1f else 0.85f
        }
    }

    private class AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(o: AppInfo, n: AppInfo) = o.packageName == n.packageName
        override fun areContentsTheSame(o: AppInfo, n: AppInfo) =
            o.appName == n.appName && o.icon == n.icon && o.banner == n.banner
    }
}
