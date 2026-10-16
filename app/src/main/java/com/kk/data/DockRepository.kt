package com.kk.tvlauncher.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DockRepository(context: Context) {

    companion object {
        const val MAX_DOCK_SIZE = 10
        private const val PREFS_NAME = "dock_prefs"
        private const val KEY_PACKAGES = "dock_packages"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getDockPackages(): List<String> {
        val json = prefs.getString(KEY_PACKAGES, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type)
        }.getOrElse { emptyList() }
    }

    fun saveDockPackages(packages: List<String>) {
        prefs.edit().putString(KEY_PACKAGES, gson.toJson(packages)).apply()
    }

    fun addApp(packageName: String): Boolean {
        val current = getDockPackages().toMutableList()
        if (current.size >= MAX_DOCK_SIZE || current.contains(packageName)) return false
        current.add(packageName)
        saveDockPackages(current)
        return true
    }

    fun removeApp(packageName: String) {
        val current = getDockPackages().toMutableList()
        current.remove(packageName)
        saveDockPackages(current)
    }

    fun reorder(from: Int, to: Int) {
        val current = getDockPackages().toMutableList()
        if (from !in current.indices || to !in current.indices) return
        val item = current.removeAt(from)
        current.add(to, item)
        saveDockPackages(current)
    }
}
