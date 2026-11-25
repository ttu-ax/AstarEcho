package com.astraecho.rhythm.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates 3 musical, synthwave/electronic WAV demo tracks on-device so the game
 * is immediately playable out of the box before the user imports their own music.
 */
object BuiltInAudioGenerator {

    private const val SAMPLE_RATE = 22050

    data class BuiltInTrackSpec(
        val id: String,
        val title: String,
        val subtitle: String,
        val artist: String,
        val bpm: Int,
        val durationSec: Int,
        val accentHex: Long,
        val patternIndex: Int
    )

    val TRACKS = listOf(
        BuiltInTrackSpec(
            id = "builtin_starlight_overture",
            title = "星光序曲 · Starlight Overture",
            subtitle = "织女星环档案 #01 · 适合初次共鸣",
            artist = "ASTRA ARCHIVE",
            bpm = 118,
            durationSec = 42,
            accentHex = 0xFF38F2FF,
            patternIndex = 0
        ),
        BuiltInTrackSpec(
            id = "builtin_neon_horizon",
            title = "霓虹地平线 · Neon Horizon",
            subtitle = "织女星环档案 #02 · 律动脉冲",
            artist = "ASTRA ARCHIVE",
            bpm = 132,
            durationSec = 45,
            accentHex = 0xFFFF3B8D,
            patternIndex = 1
        ),
        BuiltInTrackSpec(
            id = "builtin_supernova_pulse",
            title = "超新星脉冲 · Supernova Pulse",
            subtitle = "织女星环档案 #03 · 高能星轨",
            artist = "ASTRA ARCHIVE",
            bpm = 150,
            durationSec = 45,
            accentHex = 0xFFFFD166,
            patternIndex = 2
        )
    )

    fun ensureTrackWav(dir: File, spec: BuiltInTrackSpec): File {
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${spec.id}.wav")
        if (file.exists() && file.length() > 1024) return file
        synthesizeTrack(file, spec)
        return file
    }

    private fun synthesizeTrack(outFile: File, spec: BuiltInTrackSpec) {
        val totalSamples = SAMPLE_RATE * spec.durationSec
        val mix = FloatArray(totalSamples)
        val beatSec = 60.0 / spec.bpm
        val sixteenthSec = beatSec / 4.0
        val totalSixteenths = (spec.durationSec / sixteenthSec).toInt()
        val rng = Random(spec.patternIndex * 997 + 42)

        // Chord progressions (MIDI note numbers)
        val chordRoots = when (spec.patternIndex) {
            0 -> intArrayOf(57, 53, 48, 55) // Am - F - C - G
            1 -> intArrayOf(50, 57, 55, 52) // Dm - Am - G - Em
            else -> intArrayOf(52, 48, 55, 50) // Em - C - G - D
        }
        val leadScale = when (spec.patternIndex) {
            0 -> intArrayOf(69, 72, 74, 76, 79, 81, 84)
            1 -> intArrayOf(62, 65, 67, 69, 72, 74, 77)
            else -> intArrayOf(64, 67, 69, 71, 74, 76, 79)
        }

        for (step in 0 until totalSixteenths) {
            val tStart = step * sixteenthSec
            val startIdx = (tStart * SAMPLE_RATE).toInt()
            if (startIdx >= totalSamples) break

            val bar = step / 16
            val stepInBar = step % 16
            val rootMidi = chordRoots[bar % chordRoots.size]

            // 1. Kick drum on beats
            val isKick = when (spec.patternIndex) {
                0 -> stepInBar == 0 || stepInBar == 8 || (bar % 2 == 1 && stepInBar == 10)
                1 -> stepInBar % 4 == 0
                else -> stepInBar % 4 == 0 || (stepInBar == 14 && bar % 2 == 1)
            }
            if (isKick) {
                addKick(mix, startIdx, 0.22, 0.55f)
            }

            // 2. Snare / clap on 4 and 12
            if (stepInBar == 4 || stepInBar == 12) {
                addSnare(mix, startIdx, 0.16, 0.34f, rng)
            }

            // 3. Hi-hat
            if (stepInBar % 2 == 0 || (spec.patternIndex == 2 && stepInBar % 2 == 1)) {
                val vol = if (stepInBar % 4 == 2) 0.16f else 0.08f
                addHiHat(mix, startIdx, 0.055, vol, rng)
            }

            // 4. Synth bass
            val isBass = stepInBar % 4 == 0 || stepInBar == 6 || stepInBar == 14
            if (isBass) {
                val bassFreq = midiToFreq(rootMidi - 12)
                addSawSynth(mix, startIdx, sixteenthSec * 1.8, bassFreq, 0.24f)
            }

            // 5. Lush pad chord at start of each bar (creates clear sustain for Hold notes!)
            if (stepInBar == 0) {
                val dur = beatSec * 2.2
                addPad(mix, startIdx, dur, midiToFreq(rootMidi), 0.11f)
                addPad(mix, startIdx, dur, midiToFreq(rootMidi + 7), 0.09f)
                addPad(mix, startIdx, dur, midiToFreq(rootMidi + 12), 0.08f)
            }

            // 6. Bright arpeggio / lead melody
            val playLead = when (spec.patternIndex) {
                0 -> stepInBar == 0 || stepInBar == 3 || stepInBar == 6 || stepInBar == 8 || stepInBar == 12
                1 -> stepInBar % 2 == 0 && (stepInBar != 10)
                else -> stepInBar % 2 == 0 || stepInBar == 7 || stepInBar == 15
            }
            if (playLead && bar >= 1) {
                val noteIdx = (step + bar * 2) % leadScale.size
                val freq = midiToFreq(leadScale[noteIdx])
                val dur = if (stepInBar == 8) sixteenthSec * 3.5 else sixteenthSec * 1.5
                addPluck(mix, startIdx, dur, freq, 0.22f)
            }
        }

        // Normalize and fade out last 1.5 seconds
        var peak = 0.01f
        for (s in mix) {
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a
        }
        val gain = 0.88f / peak
        val fadeSamples = (SAMPLE_RATE * 1.5).toInt()
        for (i in mix.indices) {
            var v = mix[i] * gain
            if (i > totalSamples - fadeSamples) {
                val rem = (totalSamples - i).toFloat() / fadeSamples
                v *= rem.coerceIn(0f, 1f)
            }
            mix[i] = v.coerceIn(-1f, 1f)
        }

        writeWav16Mono(outFile, mix, SAMPLE_RATE)
    }

    private fun midiToFreq(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private fun addKick(buf: FloatArray, start: Int, durSec: Double, vol: Float) {
        val len = (durSec * SAMPLE_RATE).toInt()
        var phase = 0.0
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SAMPLE_RATE
            val freq = 48.0 + 115.0 * exp(-t * 28.0)
            phase += 2.0 * PI * freq / SAMPLE_RATE
            val env = exp(-t * 14.0)
            buf[idx] += (sin(phase) * env * vol).toFloat()
        }
    }

    private fun addSnare(buf: FloatArray, start: Int, durSec: Double, vol: Float, rng: Random) {
        val len = (durSec * SAMPLE_RATE).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SAMPLE_RATE
            val noise = (rng.nextFloat() * 2f - 1f) * exp(-t * 24.0)
            val tone = sin(2.0 * PI * 195.0 * t) * exp(-t * 32.0) * 0.45
            buf[idx] += ((noise + tone) * vol).toFloat()
        }
    }

    private fun addHiHat(buf: FloatArray, start: Int, durSec: Double, vol: Float, rng: Random) {
        val len = (durSec * SAMPLE_RATE).toInt()
        var prev = 0f
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SAMPLE_RATE
            val raw = rng.nextFloat() * 2f - 1f
            val hp = raw - prev * 0.85f
            prev = raw
            buf[idx] += (hp * exp(-t * 60.0) * vol).toFloat()
        }
    }

    private fun addSawSynth(buf: FloatArray, start: Int, durSec: Double, freq: Double, vol: Float) {
        val len = (durSec * SAMPLE_RATE).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SAMPLE_RATE
            val env = (if (t < 0.01) t / 0.01 else exp(-(t - 0.01) * 7.0))
            val s1 = sin(2.0 * PI * freq * t)
            val s2 = 0.5 * sin(2.0 * PI * freq * 2.0 * t)
            val s3 = 0.25 * sin(2.0 * PI * freq * 3.0 * t)
            buf[idx] += ((s1 + s2 + s3) * 0.6 * env * vol).toFloat()
        }
    }

    private fun addPad(buf: FloatArray, start: Int, durSec: Double, freq: Double, vol: Float) {
        val len = (durSec * SAMPLE_RATE).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SAMPLE_RATE
            val attack = (t / 0.12).coerceAtMost(1.0)
            val release = ((durSec - t) / 0.25).coerceIn(0.0, 1.0)
            val env = attack * release
            val wave = sin(2.0 * PI * freq * t) * 0.7 + sin(2.0 * PI * freq * 1.004 * t) * 0.3
            buf[idx] += (wave * env * vol).toFloat()
        }
    }

    private fun addPluck(buf: FloatArray, start: Int, durSec: Double, freq: Double, vol: Float) {
        val len = (durSec * SAMPLE_RATE).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / SAMPLE_RATE
            val env = (if (t < 0.006) t / 0.006 else exp(-(t - 0.006) * 6.5))
            val wave = sin(2.0 * PI * freq * t) + 0.35 * sin(2.0 * PI * freq * 2.0 * t) + 0.15 * sin(2.0 * PI * freq * 3.0 * t)
            buf[idx] += (wave * 0.65 * env * vol).toFloat()
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
            putShort(1) // PCM
            putShort(1) // Mono
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
