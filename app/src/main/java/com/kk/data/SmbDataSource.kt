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

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        file?.close()
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
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), remaining).toInt()
        val read = try {
            file?.read(buffer, offset, want) ?: C.RESULT_END_OF_INPUT
        } catch (e: Exception) {
            throw IOException(e.message, e)
        }
        if (read <= 0) return C.RESULT_END_OF_INPUT
        remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        remaining = 0L
        try {
            file?.close()
        } catch (_: Exception) {
        }
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
