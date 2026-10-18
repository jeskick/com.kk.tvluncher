package com.kk.tvlauncher.ui.picker

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.kk.tvlauncher.data.AppRepository
import com.kk.tvlauncher.data.DockRepository
import com.kk.tvlauncher.databinding.ActivityAppPickerBinding
import com.kk.tvlauncher.ui.MainViewModel
import com.kk.tvlauncher.ui.lock.KeyDecision
import com.kk.tvlauncher.ui.lock.LockInputGuard
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppPickerActivity : FragmentActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: AppPickerAdapter
    private val dockRepo by lazy { DockRepository(this) }
    private val selectedPackages = mutableSetOf<String>()
    private val lockGuard by lazy { LockInputGuard(this, armWhenUnlocked = false) }

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
            onLeaveUp = {
                binding.btnCancel.requestFocus()
                true
            }
        }

        binding.btnCancel.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    binding.btnConfirm.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> true
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusPickerAt(0)
                    true
                }
                else -> false
            }
        }
        binding.btnConfirm.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    binding.btnCancel.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> true
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusPickerAt(0)
                    true
                }
                else -> false
            }
        }

        binding.btnConfirm.setOnClickListener {
            saveDockAndFinish()
        }

        binding.btnCancel.setOnClickListener { finish() }

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        lockGuard.start()
    }

    override fun onPause() {
        lockGuard.stop()
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return when (lockGuard.decide(event)) {
            KeyDecision.CONSUME -> true
            KeyDecision.SYSTEM -> super.dispatchKeyEvent(event)
            KeyDecision.UI -> super.dispatchKeyEvent(event)
        }
    }

    private fun focusPickerAt(index: Int) {
        val rv = binding.rvApps
        val shown = rv.layoutManager?.findViewByPosition(index)
        if (shown != null) shown.requestFocus()
        else {
            rv.scrollToPosition(index)
            rv.post { rv.layoutManager?.findViewByPosition(index)?.requestFocus() }
        }
    }

    private fun loadApps() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
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
