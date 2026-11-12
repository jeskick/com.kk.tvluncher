@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kk.tvlauncher.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.nio.charset.StandardCharsets

/** 只读文件头，取出宽高。视频走容器解析，图片走文件头。 */
object SmbProbe {

    fun named(name: String): String? = fromName(name)

    fun resolution(entry: SmbMedia.Entry, user: String, pass: String): String? {
        val fromFile = runCatching {
            when (entry.kind) {
                SmbMedia.Kind.IMAGE -> image(entry.url, user, pass)
                SmbMedia.Kind.VIDEO -> video(entry.url, user, pass)
                else -> null
            }
        }.getOrNull()
        return fromFile ?: fromName(entry.name)
    }

    private fun video(url: String, user: String, pass: String): String? {
        val uri = Uri.parse(url)
        val dataSource = SmbDataSource(user, pass)
        var extractor: Extractor? = null
        try {
            var position = 0L
            var remaining = dataSource.open(DataSpec.Builder().setUri(uri).setPosition(0).build())
            var input = DefaultExtractorInput(dataSource, position, remaining)
            val extractors = DefaultExtractorsFactory().createExtractors(uri, emptyMap())
            var found: Extractor? = null
            for (candidate in extractors) {
                if (found != null) {
                    candidate.release()
                    continue
                }
                val matched = runCatching { candidate.sniff(input) }.getOrDefault(false)
                input.resetPeekPosition()
                if (matched) found = candidate else candidate.release()
            }
            val selected = found ?: return null
            extractor = selected
            var resolution: String? = null
            selected.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                    override fun format(format: Format) {
                        if (resolution == null && format.width > 0 && format.height > 0) {
                            resolution = "${format.width}×${format.height}"
                        }
                    }

                    override fun sampleData(
                        input: androidx.media3.common.DataReader,
                        length: Int,
                        allowEndOfInput: Boolean,
                        sampleDataPart: Int
                    ): Int {
                        val buf = ByteArray(length.coerceAtMost(64 * 1024))
                        var got = 0
                        while (got < length) {
                            val n = input.read(buf, 0, minOf(buf.size, length - got))
                            if (n == C.RESULT_END_OF_INPUT) {
                                return if (allowEndOfInput && got == 0) C.RESULT_END_OF_INPUT else got
                            }
                            if (n <= 0) break
                            got += n
                        }
                        return got
                    }

                    override fun sampleData(
                        data: androidx.media3.common.util.ParsableByteArray,
                        length: Int,
                        sampleDataPart: Int
                    ) {
                        data.skipBytes(length)
                    }

                    override fun sampleMetadata(
                        timeUs: Long,
                        flags: Int,
                        size: Int,
                        offset: Int,
                        cryptoData: TrackOutput.CryptoData?
                    ) = Unit
                }

                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            })
            val holder = PositionHolder()
            var reads = 0
            var seeks = 0
            while (resolution == null && reads < 40 && seeks < 6) {
                val result = selected.read(input, holder)
                reads++
                if (result == Extractor.RESULT_SEEK) {
                    if (holder.position == position) break
                    position = holder.position
                    seeks++
                    dataSource.close()
                    remaining = dataSource.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                    input = DefaultExtractorInput(dataSource, position, remaining)
                    continue
                }
                if (result == Extractor.RESULT_END_OF_INPUT) break
            }
            return resolution
        } finally {
            runCatching { extractor?.release() }
            runCatching { dataSource.close() }
        }
    }

    private fun image(url: String, user: String, pass: String): String? {
        val raf = SmbMedia.open(url, user, pass)
        try {
            val cap = minOf(raf.length(), 128 * 1024L).toInt()
            if (cap < 16) return null
            val buf = ByteArray(cap)
            var off = 0
            while (off < cap) {
                val n = raf.read(buf, off, cap - off)
                if (n <= 0) break
                off += n
            }
            val size = decodeImage(buf, off) ?: return null
            return "${size.first}×${size.second}"
        } finally {
            runCatching { raf.close() }
        }
    }

    private fun fromName(name: String): String? {
        val lower = name.lowercase()
        val word = when {
            "4320p" in lower -> "7680×4320"
            "2160p" in lower || "4k" in lower -> "3840×2160"
            "1440p" in lower -> "2560×1440"
            "1080p" in lower -> "1920×1080"
            "720p" in lower -> "1280×720"
            "480p" in lower -> "720×480"
            else -> null
        }
        if (word != null) return word
        val match = Regex("(\\d{3,5})\\s*[xX×]\\s*(\\d{3,5})").find(name) ?: return null
        return "${match.groupValues[1]}×${match.groupValues[2]}"
    }

    private fun decodeImage(buf: ByteArray, len: Int): Pair<Int, Int>? {
        if (len >= 24 && buf[0] == 0x89.toByte() && buf[1] == 0x50.toByte()) {
            return be32(buf, 16) to be32(buf, 20)
        }
        if (len >= 10 && buf[0] == 'G'.code.toByte() && buf[1] == 'I'.code.toByte()) {
            return le16(buf, 6) to le16(buf, 8)
        }
        if (len >= 26 && buf[0] == 'B'.code.toByte() && buf[1] == 'M'.code.toByte()) {
            return le32(buf, 18) to kotlin.math.abs(le32(buf, 22))
        }
        if (len >= 30 && text(buf, 0, 4) == "RIFF" && text(buf, 8, 4) == "WEBP") {
            return webp(buf, len)
        }
        if (len >= 4 && buf[0] == 0xFF.toByte() && buf[1] == 0xD8.toByte()) {
            return jpeg(buf, len)
        }
        return null
    }

    private fun webp(buf: ByteArray, len: Int): Pair<Int, Int>? {
        val kind = text(buf, 12, 4)
        if (kind == "VP8X" && len >= 30) {
            val width = 1 + (u8(buf[24]) or (u8(buf[25]) shl 8) or (u8(buf[26]) shl 16))
            val height = 1 + (u8(buf[27]) or (u8(buf[28]) shl 8) or (u8(buf[29]) shl 16))
            return width to height
        }
        if (kind == "VP8 " && len >= 30 && buf[23] == 0x9D.toByte()) {
            return (le16(buf, 26) and 0x3FFF) to (le16(buf, 28) and 0x3FFF)
        }
        if (kind == "VP8L" && len >= 25 && buf[20] == 0x2F.toByte()) {
            val bits = le32(buf, 21)
            return (bits and 0x3FFF) + 1 to ((bits shr 14) and 0x3FFF) + 1
        }
        return null
    }

    private fun jpeg(buf: ByteArray, len: Int): Pair<Int, Int>? {
        var i = 2
        while (i + 8 < len) {
            if (buf[i] != 0xFF.toByte()) {
                i++
                continue
            }
            while (i < len && buf[i] == 0xFF.toByte()) i++
            if (i >= len) return null
            val marker = u8(buf[i])
            i++
            if (marker == 0xD8 || marker == 0x01) continue
            if (marker == 0xD9 || marker == 0xDA) return null
            if (i + 1 >= len) return null
            val size = be16(buf, i)
            val sof = marker in 0xC0..0xC3 || marker in 0xC5..0xC7 || marker in 0xC9..0xCB || marker in 0xCD..0xCF
            if (sof && i + 6 < len) return be16(buf, i + 5) to be16(buf, i + 3)
            if (size < 2) return null
            i += size
        }
        return null
    }

    private fun text(buf: ByteArray, offset: Int, length: Int): String =
        String(buf, offset, length, StandardCharsets.US_ASCII)

    private fun u8(b: Byte) = b.toInt() and 0xFF
    private fun be16(buf: ByteArray, i: Int) = (u8(buf[i]) shl 8) or u8(buf[i + 1])
    private fun le16(buf: ByteArray, i: Int) = u8(buf[i]) or (u8(buf[i + 1]) shl 8)
    private fun be32(buf: ByteArray, i: Int) = (be16(buf, i) shl 16) or be16(buf, i + 2)
    private fun le32(buf: ByteArray, i: Int) = le16(buf, i) or (le16(buf, i + 2) shl 16)
}
