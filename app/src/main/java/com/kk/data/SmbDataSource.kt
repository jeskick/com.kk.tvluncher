@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kk.tvlauncher.data

import android.net.Uri
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
    private var remaining = 0L
    private var started = false
    private val ahead = ByteArray(1024 * 1024)
    private var aheadPos = 0
    private var aheadLen = 0

    override fun open(dataSpec: DataSpec): Long = SmbMedia.exclusive {
        transferInitializing(dataSpec)
        file?.let { SmbMedia.close(it) }
        file = null
        aheadPos = 0
        aheadLen = 0
        val raf = try {
            SmbMedia.open(dataSpec.uri.toString(), user, pass)
        } catch (e: Exception) {
            throw IOException(e.message, e)
        }
        val size = raf.length()
        if (dataSpec.position > 0) raf.seek(dataSpec.position)
        val available = (size - dataSpec.position).coerceAtLeast(0)
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
        uri = null
        remaining = 0L
        file?.let { SmbMedia.close(it) }
        file = null
        if (started) {
            started = false
            transferEnded()
        }
    }

    class Factory(private val user: String, private val pass: String) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(user, pass)
    }
}
