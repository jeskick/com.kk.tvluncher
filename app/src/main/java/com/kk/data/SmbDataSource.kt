@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kk.tvlauncher.data

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

/** 让播放器按需读取共享文件，不用先把整段视频下到电视上。 */
class SmbDataSource(
    private val user: String,
    private val pass: String
) : BaseDataSource(true) {

    private var file: SmbRandomAccessFile? = null
    private var uri: Uri? = null
    private var fileLength = 0L
    private var remaining = 0L
    private var started = false
    private var closed = true
    private val ahead = ByteArray(1024 * 1024)
    private var aheadPos = 0
    private var aheadLen = 0
    private val main = Handler(Looper.getMainLooper())
    private val drop = Runnable { releaseIfIdle() }

    override fun open(dataSpec: DataSpec): Long = SmbMedia.exclusive {
        transferInitializing(dataSpec)
        main.removeCallbacks(drop)
        closed = false
        aheadPos = 0
        aheadLen = 0
        val target = dataSpec.uri.toString()
        val raf = if (file != null && uri?.toString() == target) {
            file!!
        } else {
            file?.let { SmbMedia.close(it) }
            file = null
            uri = null
            try {
                SmbMedia.open(target, user, pass)
            } catch (e: Exception) {
                throw IOException(e.message, e)
            }
        }
        if (file == null) fileLength = raf.length()
        raf.seek(dataSpec.position.coerceAtLeast(0L))
        val available = (fileLength - dataSpec.position).coerceAtLeast(0)
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available
        else minOf(dataSpec.length, available)
        file = raf
        uri = dataSpec.uri
        started = true
        transferStarted(dataSpec)
        remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = SmbMedia.exclusive {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (aheadPos >= aheadLen) {
            val pull = minOf(ahead.size.toLong(), remaining).toInt()
            var filled = 0
            while (filled < pull) {
                val n = try {
                    file?.read(ahead, filled, pull - filled) ?: -1
                } catch (e: Exception) {
                    throw IOException(e.message, e)
                }
                if (n <= 0) break
                filled += n
            }
            if (filled <= 0) return C.RESULT_END_OF_INPUT
            aheadPos = 0
            aheadLen = filled
        }
        val n = minOf(length, aheadLen - aheadPos)
        System.arraycopy(ahead, aheadPos, buffer, offset, n)
        aheadPos += n
        remaining -= n
        bytesTransferred(n)
        n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        SmbMedia.exclusive {
            closed = true
            remaining = 0L
            aheadPos = 0
            aheadLen = 0
        }
        // 快进会先关再立刻重开。连接先留着，下一次打开直接 seek，避免大文件每次都重新登录。
        main.removeCallbacks(drop)
        main.post(drop)
        if (started) {
            started = false
            transferEnded()
        }
    }

    private fun releaseIfIdle() {
        SmbMedia.exclusive {
            if (!closed) return@exclusive
            file?.let { SmbMedia.close(it) }
            file = null
            uri = null
            fileLength = 0L
        }
    }

    class Factory(private val user: String, private val pass: String) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(user, pass)
    }
}

/** 视频仍走共享连接；同目录字幕先读进内存，避免和正片抢一条连接。 */
class PlaybackSource(
    private val user: String,
    private val pass: String,
    private val files: Map<String, ByteArray>
) : DataSource {

    private val smb = SmbDataSource(user, pass)
    private var memory: ByteArray? = null
    private var memoryUri: Uri? = null
    private var memoryPos = 0

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        smb.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val bytes = files[dataSpec.uri.toString()]
        if (bytes != null) {
            memory = bytes
            memoryUri = dataSpec.uri
            memoryPos = dataSpec.position.toInt().coerceAtLeast(0).coerceAtMost(bytes.size)
            return (bytes.size - memoryPos).toLong()
        }
        memory = null
        memoryUri = null
        return smb.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytes = memory ?: return smb.read(buffer, offset, length)
        if (memoryPos >= bytes.size) return C.RESULT_END_OF_INPUT
        val n = minOf(length, bytes.size - memoryPos)
        System.arraycopy(bytes, memoryPos, buffer, offset, n)
        memoryPos += n
        return n
    }

    override fun getUri(): Uri? = if (memory != null) memoryUri else smb.getUri()

    override fun close() {
        if (memory != null) {
            memory = null
            memoryUri = null
            return
        }
        smb.close()
    }

    class Factory(
        private val user: String,
        private val pass: String,
        private val files: Map<String, ByteArray>
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = PlaybackSource(user, pass, files)
    }
}
