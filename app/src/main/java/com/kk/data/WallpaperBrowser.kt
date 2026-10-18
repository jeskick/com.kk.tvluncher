package com.kk.tvlauncher.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 本机目录和局域网共享，供壁纸选择。 */
object WallpaperBrowser {

    data class Item(
        val label: String,
        val detail: String,
        val location: String,
        val directory: Boolean
    )

    fun isImageName(name: String) = SmbImageFetcher.isImageName(name)

    fun localPlaces(context: Context): List<Item> {
        val roots = linkedSetOf<File>()
        Environment.getExternalStorageDirectory()?.let { roots.add(it) }
        File("/storage/emulated/0").let { if (it.isDirectory) roots.add(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(StorageManager::class.java)
            sm?.storageVolumes?.forEach { vol -> vol.directory?.let { roots.add(it) } }
        }
        File("/storage").listFiles()?.forEach { child ->
            if (child.isDirectory && child.name != "self" && child.name != "emulated") roots.add(child)
        }

        val seen = linkedSetOf<String>()
        val items = mutableListOf<Item>()
        fun add(file: File, label: String) {
            if (!file.isDirectory || !file.canRead()) return
            val path = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
            if (!seen.add(path)) return
            items += Item(label, path, path, true)
        }

        roots.forEach { root ->
            add(root, volumeLabel(context, root))
            listOf("Pictures", "DCIM", "Download", "Downloads", "Movies", "Pictures/Wallpapers").forEach { rel ->
                val child = File(root, rel)
                if (child.isDirectory) add(child, "${volumeLabel(context, root)} / ${child.name}")
            }
        }
        mediaDirs(context).forEach { add(it, "相册 / ${it.name}") }
        return items.sortedBy { it.label.lowercase() }
    }

    suspend fun listShares(host: String, user: String, pass: String): List<Item> {
        val entries = smbEntries("smb://$host/", user, pass).filter { it.directory }
        if (entries.isNotEmpty()) {
            return entries.map { Item(it.name, host, it.url, true) }
        }
        return listOf(Item("电脑 $host", "共享未能列出", "smb://$host/", true))
    }

    suspend fun children(location: String, user: String, pass: String): List<Item> {
        if (location.startsWith("smb://")) {
            return smbEntries(location, user, pass).map {
                Item(it.name, if (it.directory) "目录" else "图片", it.url, it.directory)
            }
        }
        val files = File(location).listFiles() ?: return emptyList()
        return files.mapNotNull { file ->
            if (file.name.startsWith(".")) return@mapNotNull null
            val dir = file.isDirectory
            if (!dir && !isImageName(file.name)) return@mapNotNull null
            Item(file.name, if (dir) "目录" else "图片", file.absolutePath, dir)
        }.sortedWith(compareBy({ !it.directory }, { it.label.lowercase() }))
    }

    /** 路由器账号和 Windows 来宾同时试。来宾可以读 Everyone 共享。 */
    private suspend fun smbEntries(location: String, user: String, pass: String): List<SmbImageFetcher.SmbEntry> =
        coroutineScope {
            val tries = mutableListOf<Pair<String, String>>()
            if (user.isNotBlank()) tries += user to pass
            if (tries.none { it.first.equals("Guest", true) }) tries += "Guest" to ""
            if (user.isBlank()) tries += "" to ""
            tries.map { (account, password) ->
                async(Dispatchers.IO) { SmbImageFetcher.listEntries(location, account, password) }
            }.awaitAll().firstOrNull { it.isNotEmpty() } ?: emptyList()
        }

    private fun volumeLabel(context: Context, file: File): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(StorageManager::class.java)
            val path = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
            val vol = sm?.storageVolumes?.firstOrNull { volume ->
                val dir = volume.directory ?: return@firstOrNull false
                val volPath = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
                volPath == path
            }
            if (vol != null) {
                val desc = vol.getDescription(context) ?: file.name
                return if (vol.isPrimary) "本机 $desc" else "外部存储 $desc"
            }
        }
        return "本机 ${file.name}"
    }

    private fun mediaDirs(context: Context): List<File> {
        val out = linkedSetOf<File>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.DATA),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                if (index < 0) return emptyList()
                while (cursor.moveToNext()) {
                    val path = cursor.getString(index) ?: continue
                    File(path).parentFile?.let { out.add(it) }
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out.toList()
    }
}
