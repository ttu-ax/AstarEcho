package com.astraecho.rhythm.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ExtractedMediaResult(
    val audioFile: File,
    val isFromVideo: Boolean
)

/**
 * Handles importing both direct audio files and video files.
 * For video files, extracts the audio track into a playable .m4a (lossless demux via MediaMuxer)
 * with automatic PCM->WAV fallback for non-MP4 audio codecs (e.g. Opus/Vorbis in WebM/MKV).
 */
object MediaAudioExtractor {

    private val videoExtensions = setOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "m4v", "ts")

    fun isVideoUri(context: Context, uri: Uri, displayName: String): Boolean {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        if (mime.startsWith("video/", ignoreCase = true)) return true
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return ext in videoExtensions
    }

    fun importOrExtractAudio(
        context: Context,
        uri: Uri,
        displayName: String,
        destDir: File,
        songId: String,
        onStatus: ((String, Float) -> Unit)? = null
    ): ExtractedMediaResult {
        if (!destDir.exists()) destDir.mkdirs()

        val isVideo = isVideoUri(context, uri, displayName)
        if (!isVideo) {
            val ext = displayName.substringAfterLast('.', "mp3").lowercase().take(6)
            val destFile = File(destDir, "$songId.$ext")
            onStatus?.invoke("正在导入音频文件...", 0.35f)
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选音频文件")
            onStatus?.invoke("音频导入完成", 1f)
            return ExtractedMediaResult(destFile, isFromVideo = false)
        }

        // Video file: copy temporarily so MediaExtractor has reliable seekable file access
        onStatus?.invoke("正在读取视频文件...", 0.15f)
        val ext = displayName.substringAfterLast('.', "mp4").lowercase().take(6)
        val tempVideo = File(context.cacheDir, "tmp_$songId.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempVideo.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选视频文件")

            return extractAudioFromLocalVideoFile(tempVideo, destDir, songId, onStatus)
        } finally {
            runCatching { tempVideo.delete() }
        }
    }

    /**
     * Extracts playable audio (.m4a lossless demux, or .wav fallback) from an already-local video file.
     */
    fun extractAudioFromLocalVideoFile(
        videoFile: File,
        destDir: File,
        songId: String,
        onStatus: ((String, Float) -> Unit)? = null
    ): ExtractedMediaResult {
        if (!destDir.exists()) destDir.mkdirs()

        onStatus?.invoke("正在从视频分离音轨...", 0.45f)
        val m4aFile = File(destDir, "$songId.m4a")
        val demuxOk = runCatching {
            demuxAudioTrackToM4a(videoFile.absolutePath, m4aFile.absolutePath) { p ->
                onStatus?.invoke("正在从视频分离音轨...", 0.45f + p * 0.45f)
            }
        }.getOrDefault(false)

        if (demuxOk && m4aFile.exists() && m4aFile.length() > 1024) {
            // Sanity-check extracted M4A duration against source video duration so non-zero PTS never inflates length!
            val srcDurMs = readMediaDurationMs(videoFile.absolutePath)
            val outDurMs = readMediaDurationMs(m4aFile.absolutePath)
            val durationValid = (srcDurMs <= 0L) || (outDurMs in (srcDurMs - 4000L)..(srcDurMs + 4000L))
            if (durationValid) {
                onStatus?.invoke("视频音频提取完成", 1f)
                return ExtractedMediaResult(m4aFile, isFromVideo = true)
            }
            m4aFile.delete()
        } else {
            m4aFile.delete()
        }

        onStatus?.invoke("正在转码视频音频...", 0.55f)
        val decoded = AudioDecoder.decodeToMonoFloat(videoFile.absolutePath) { p ->
            onStatus?.invoke("正在转码视频音频...", 0.55f + p * 0.35f)
        }
        require(decoded.samples.isNotEmpty()) { "该视频中未找到可播放的音频轨道" }

        val wavFile = File(destDir, "$songId.wav")
        writeWav16Mono(wavFile, decoded.samples, decoded.sampleRate)
        onStatus?.invoke("视频音频提取完成", 1f)
        return ExtractedMediaResult(wavFile, isFromVideo = true)
    }

    private fun demuxAudioTrackToM4a(
        videoPath: String,
        outM4aPath: String,
        onProgress: ((Float) -> Unit)?
    ): Boolean {
        val extractor = MediaExtractor()
        extractor.setDataSource(videoPath)
        var audioIndex = -1
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioIndex = i
                audioFormat = fmt
                break
            }
        }
        if (audioIndex < 0 || audioFormat == null) {
            extractor.release()
            return false
        }

        val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
            audioFormat.getLong(MediaFormat.KEY_DURATION)
        } else 0L

        extractor.selectTrack(audioIndex)
        val muxer = MediaMuxer(outM4aPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            val dstTrack = muxer.addTrack(audioFormat)
            muxer.start()
            muxerStarted = true

            val maxBuf = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(128 * 1024)
            } else {
                256 * 1024
            }
            val buffer = ByteBuffer.allocate(maxBuf)
            val info = MediaCodec.BufferInfo()
            var basePtsUs = -1L
            var lastWrittenPtsUs = -1L

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val rawPts = extractor.sampleTime
                if (rawPts < 0L) {
                    extractor.advance()
                    continue
                }
                if (basePtsUs < 0L) basePtsUs = rawPts
                val normalizedPts = (rawPts - basePtsUs).coerceAtLeast(0L)
                // Guard against corrupt trailing PTS jumps that inflate file duration to 10+ minutes
                if (durationUs > 0L && normalizedPts > durationUs + 4_000_000L) break
                if (normalizedPts < lastWrittenPtsUs) {
                    extractor.advance()
                    continue
                }
                lastWrittenPtsUs = normalizedPts

                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = normalizedPts
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(dstTrack, buffer, info)
                if (durationUs > 0L) {
                    onProgress?.invoke((normalizedPts.toFloat() / durationUs).coerceIn(0f, 1f))
                }
                extractor.advance()
            }
            return true
        } finally {
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }
    }

    private fun readMediaDurationMs(path: String): Long {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }

    private fun writeWav16Mono(file: File, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataBytes)
        }.array()

        BufferedOutputStream(FileOutputStream(file)).use { out ->
            out.write(header)
            val chunk = ByteArray(8192)
            var idx = 0
            while (idx < samples.size) {
                var pos = 0
                while (idx < samples.size && pos < chunk.size) {
                    val pcm = (samples[idx++] * 32767f).toInt().coerceIn(-32768, 32767)
                    chunk[pos++] = (pcm and 0xFF).toByte()
                    chunk[pos++] = ((pcm shr 8) and 0xFF).toByte()
                }
                out.write(chunk, 0, pos)
            }
        }
    }
}
