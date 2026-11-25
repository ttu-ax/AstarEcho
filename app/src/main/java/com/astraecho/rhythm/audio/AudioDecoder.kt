package com.astraecho.rhythm.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val durationMs: Long
)

/**
 * Decodes local audio files (WAV directly; MP3/M4A/AAC/OGG/FLAC via MediaExtractor + MediaCodec)
 * into mono float PCM downsampled to <= 22050 Hz for fast on-device spectral analysis.
 */
object AudioDecoder {

    fun decodeToMonoFloat(
        filePath: String,
        onProgress: ((Float) -> Unit)? = null
    ): DecodedAudio {
        if (filePath.endsWith(".wav", ignoreCase = true)) {
            val wav = tryDecodeWavFast(filePath, onProgress)
            if (wav != null) return wav
        }
        return decodeWithMediaCodec(filePath, onProgress)
    }

    private fun tryDecodeWavFast(filePath: String, onProgress: ((Float) -> Unit)?): DecodedAudio? {
        return runCatching {
            val file = File(filePath)
            if (!file.exists() || file.length() < 44) return null
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(44)
                raf.readFully(header)
                val riff = String(header, 0, 4)
                val wave = String(header, 8, 4)
                if (riff != "RIFF" || wave != "WAVE") return null

                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val audioFormat = bb.getShort(20).toInt()
                val channels = bb.getShort(22).toInt()
                val sampleRate = bb.getInt(24)
                val bitsPerSample = bb.getShort(34).toInt()
                if (audioFormat != 1 || bitsPerSample != 16 || channels !in 1..2 || sampleRate <= 0) {
                    return null
                }

                // Locate "data" chunk
                raf.seek(12)
                var dataSize = 0
                val chunkHeader = ByteArray(8)
                while (raf.filePointer + 8 <= raf.length()) {
                    raf.readFully(chunkHeader)
                    val id = String(chunkHeader, 0, 4)
                    val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (id == "data") {
                        dataSize = size
                        break
                    } else {
                        raf.seek(raf.filePointer + size)
                    }
                }
                if (dataSize <= 0) return null

                val bytesPerFrame = channels * 2
                val totalFrames = dataSize / bytesPerFrame
                val decimate = if (sampleRate > 24000) 2 else 1
                val outCount = totalFrames / decimate
                val out = FloatArray(outCount)

                val chunkBytes = ByteArray(16384 * bytesPerFrame)
                var framesRead = 0
                var outIdx = 0
                while (framesRead < totalFrames) {
                    val remainingFrames = totalFrames - framesRead
                    val toReadBytes = minOf(chunkBytes.size, remainingFrames * bytesPerFrame)
                    val actual = raf.read(chunkBytes, 0, toReadBytes)
                    if (actual <= 0) break
                    val readFrames = actual / bytesPerFrame
                    val buf = ByteBuffer.wrap(chunkBytes, 0, actual).order(ByteOrder.LITTLE_ENDIAN)
                    for (f in 0 until readFrames) {
                        val sample = if (channels == 1) {
                            buf.short / 32768f
                        } else {
                            val l = buf.short / 32768f
                            val r = buf.short / 32768f
                            (l + r) * 0.5f
                        }
                        if ((framesRead + f) % decimate == 0 && outIdx < outCount) {
                            out[outIdx++] = sample
                        }
                    }
                    framesRead += readFrames
                    onProgress?.invoke(framesRead.toFloat() / totalFrames)
                }

                val outRate = sampleRate / decimate
                val durationMs = ((outIdx.toDouble() / outRate.toDouble()) * 1000.0).toLong()
                DecodedAudio(out.copyOf(outIdx), outRate, durationMs)
            }
        }.getOrNull()
    }

    private fun decodeWithMediaCodec(
        filePath: String,
        onProgress: ((Float) -> Unit)?
    ): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(filePath)

        var audioTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                format = f
                break
            }
        }
        require(audioTrackIndex >= 0 && format != null) { "未找到有效的音频轨道" }

        extractor.selectTrack(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        var currentSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
        var currentChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }

        // Target ~22050 Hz mono output regardless of whether MediaCodec changes sample rate (e.g. HE-AAC SBR 24k -> 48k)!
        var decimate = if (currentSampleRate > 24000) 2 else 1
        var outSampleRate = currentSampleRate / decimate

        // Dynamic float buffer (capped at 12 minutes to prevent OOM on giant files)
        val maxSamples = 24000 * 720
        val chunks = ArrayList<FloatArray>()
        var currentChunk = FloatArray(16384)
        var chunkPos = 0
        var totalSamples = 0
        var frameCounter = 0

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone && totalSamples < maxSamples) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                            if (durationUs > 0) {
                                onProgress?.invoke((pts.toFloat() / durationUs).coerceIn(0f, 0.99f))
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // CRITICAL for MP4/HE-AAC videos: update real decoded sampleRate & channel count!
                    val outFmt = codec.outputFormat
                    if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        currentSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                    }
                    if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        currentChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    }
                    decimate = when {
                        currentSampleRate >= 44100 -> 2
                        else -> 1
                    }
                    outSampleRate = currentSampleRate / decimate
                } else if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frameCount = shortBuf.remaining() / currentChannels

                        for (f in 0 until frameCount) {
                            var mono = 0f
                            for (c in 0 until currentChannels) {
                                mono += shortBuf.get() / 32768f
                            }
                            mono /= currentChannels

                            if (frameCounter % decimate == 0) {
                                if (chunkPos == currentChunk.size) {
                                    chunks.add(currentChunk)
                                    currentChunk = FloatArray(16384)
                                    chunkPos = 0
                                }
                                currentChunk[chunkPos++] = mono
                                totalSamples++
                                if (totalSamples >= maxSamples) break
                            }
                            frameCounter++
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        val result = FloatArray(totalSamples)
        var pos = 0
        for (c in chunks) {
            System.arraycopy(c, 0, result, pos, c.size)
            pos += c.size
        }
        if (chunkPos > 0) {
            System.arraycopy(currentChunk, 0, result, pos, chunkPos)
        }
        onProgress?.invoke(1f)

        val computedDurationMs = ((totalSamples.toDouble() / outSampleRate.toDouble()) * 1000.0).toLong()
        val finalDurationMs = if (durationUs > 0L) {
            minOf(computedDurationMs, (durationUs / 1000L) + 1500L)
        } else {
            computedDurationMs
        }
        return DecodedAudio(result, outSampleRate, finalDurationMs)
    }
}
