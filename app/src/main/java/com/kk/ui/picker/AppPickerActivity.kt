package com.kk.tvlauncher.ui.picker

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.kk.tvlauncher.data.AppRepository
import com.kk.tvlauncher.data.DockRepository
import com.kk.tvlauncher.databinding.ActivityAppPickerBinding
import com.kk.tvlauncher.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppPickerActivity : FragmentActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: AppPickerAdapter
    private val dockRepo by lazy { DockRepository(this) }
    private val selectedPackages = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化已选中的 Dock 应用
        selectedPackages.addAll(dockRepo.getDockPackages())

        adapter = AppPickerAdapter(
            selectedPackages = selectedPackages,
            onSelectionChanged = { updateConfirmButton(it) }
        )

        binding.rvApps.apply {
            layoutManager = GridLayoutManager(this@AppPickerActivity, 6)
            adapter = this@AppPickerActivity.adapter
        }

        binding.btnConfirm.setOnClickListener {
            saveDockAndFinish()
        }

        binding.btnCancel.setOnClickListener { finish() }

        loadApps()
    }

    private fun loadApps() {
        binding.progressBar.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.Main).launch {
            val apps = withContext(Dispatchers.IO) {
                AppRepository(this@AppPickerActivity).getInstalledApps()
            }
            binding.progressBar.visibility = View.GONE
            adapter.submitList(apps)
            binding.tvSelected.text = "已选 ${selectedPackages.size} / ${DockRepository.MAX_DOCK_SIZE}"
        }
    }

    private fun updateConfirmButton(selected: Set<String>) {
        binding.tvSelected.text = "已选 ${selected.size} / ${DockRepository.MAX_DOCK_SIZE}"
    }

    private fun saveDockAndFinish() {
        dockRepo.saveDockPackages(selectedPackages.toList())
        finish()
    }
}
