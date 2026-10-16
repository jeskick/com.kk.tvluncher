package com.kk.tvlauncher.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kk.tvlauncher.ui.MainActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 先启动保活服务，确保网络在 Launcher 打开前就稳定
            com.kk.tvlauncher.service.KeepAliveService.start(context)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        }
    }
}
