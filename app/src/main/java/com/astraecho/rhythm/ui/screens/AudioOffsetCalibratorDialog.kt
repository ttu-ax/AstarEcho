package com.astraecho.rhythm.ui.screens

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.astraecho.rhythm.model.NoteSkin
import com.astraecho.rhythm.ui.theme.AstraColors
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

private const val BEAT_MS = 500.0 // Exact 120 BPM metronome (4 beats = 2000ms loop)
private const val LOOP_MS = 2000.0

/**
 * Interactive Rhythm-Tap Audio Offset Calibrator:
 * Plays a steady 120 BPM synthesized metronome via MediaPlayer (matching actual gameplay latency),
 * renders falling calibration notes synchronized to the beat, and dynamically computes
 * the median audio offset from the user's taps!
 */
@Composable
fun AudioOffsetCalibratorDialog(
    initialOffsetMs: Int,
    skin: NoteSkin,
    onApplyOffset: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val accent = Color(skin.railColor)

    var calculatedOffsetMs by remember { mutableIntStateOf(initialOffsetMs) }
    var lastTapDeltaMs by remember { mutableStateOf<Int?>(null) }
    var tapFlashAlpha by remember { mutableDoubleStateOf(0.0) }
    val samples = remember { mutableStateListOf<Int>() }

    // High-precision loop phase in ms [0..2000)
    var loopPosMs by remember { mutableDoubleStateOf(0.0) }
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }

    // Create & start looping 120 BPM metronome WAV on MediaPlayer
    DisposableEffect(Unit) {
        val wavFile = ensureMetronomeWav(context)
        val mp = runCatching {
            MediaPlayer().apply {
                setDataSource(wavFile.absolutePath)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        mediaPlayerRef = mp

        onDispose {
            runCatching {
                mp?.stop()
                mp?.release()
            }
            mediaPlayerRef = null
        }
    }

    // Smooth 60-120fps visual beat clock gently synced to MediaPlayer
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                val dtMs = if (lastNanos > 0L) {
                    ((frameNanos - lastNanos) / 1_000_000.0).coerceIn(1.0, 40.0)
                } else 16.0
                lastNanos = frameNanos

                var nextPos = (loopPosMs + dtMs) % LOOP_MS
                val mp = mediaPlayerRef
                if (mp != null && runCatching { mp.isPlaying }.getOrDefault(false)) {
                    val audioPos = (mp.currentPosition % LOOP_MS.toInt()).toDouble()
                    var diff = audioPos - nextPos
                    if (diff > LOOP_MS / 2) diff -= LOOP_MS
                    if (diff < -LOOP_MS / 2) diff += LOOP_MS
                    if (abs(diff) > 35.0) {
                        nextPos = (nextPos + diff * 0.25 + LOOP_MS) % LOOP_MS
                    }
                }
                loopPosMs = nextPos
                if (tapFlashAlpha > 0.0) {
                    tapFlashAlpha = (tapFlashAlpha - dtMs / 240.0).coerceAtLeast(0.0)
                }
            }
        }
    }

    fun recordBeatTap() {
        tapFlashAlpha = 1.0
        val phaseInBeat = loopPosMs % BEAT_MS
        // Signed offset required so (audioPos + offset) lands exactly on the nearest 500ms beat:
        // If user hears beat late and taps at +80ms past beat -> offset = -80ms.
        // If user taps 40ms early (at 460ms) -> offset = +40ms.
        val rawDelta = if (phaseInBeat <= BEAT_MS * 0.5) {
            -phaseInBeat
        } else {
            BEAT_MS - phaseInBeat
        }
        val clamped = rawDelta.toInt().coerceIn(-150, 150)
        lastTapDeltaMs = clamped
        if (samples.size >= 12) samples.removeAt(0)
        samples.add(clamped)

        // Median is robust against occasional mis-taps
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        calculatedOffsetMs = if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            ((sorted[mid - 1] + sorted[mid]) / 2f).toInt()
        }.coerceIn(-150, 150)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = AstraColors.DeepIndigo,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.5.dp, accent),
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .fillMaxHeight(0.84f)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("音频判定偏移 · 节奏打击校准", color = AstraColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("聆听固定 120 BPM 节拍声，并在音符抵达判定线时轻触右侧打击区（建议连打 6-10 次）", color = AstraColors.TextSecondary, fontSize = 11.sp)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = AstraColors.TextSecondary)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Left: Falling Calibration Notes Track
                    Box(
                        modifier = Modifier
                            .weight(0.95f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF050814))
                            .border(1.2.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = ::recordBeatTap
                            )
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val judgeY = h * 0.80f
                            val trackLeft = w * 0.22f
                            val trackRight = w * 0.78f
                            val trackW = trackRight - trackLeft

                            // Track lane background
                            drawRect(
                                color = Color(0x5512264A),
                                topLeft = Offset(trackLeft, 0f),
                                size = Size(trackW, h)
                            )
                            // Side rails
                            drawLine(accent.copy(alpha = 0.7f), Offset(trackLeft, 0f), Offset(trackLeft, h), strokeWidth = 3f)
                            drawLine(accent.copy(alpha = 0.7f), Offset(trackRight, 0f), Offset(trackRight, h), strokeWidth = 3f)

                            // Tap flash beam
                            if (tapFlashAlpha > 0.02) {
                                drawRect(
                                    color = accent.copy(alpha = (tapFlashAlpha * 0.35).toFloat()),
                                    topLeft = Offset(trackLeft, 0f),
                                    size = Size(trackW, judgeY)
                                )
                            }

                            // Judgment line
                            drawLine(Color.White, Offset(trackLeft - 12f, judgeY), Offset(trackRight + 12f, judgeY), strokeWidth = 5f)

                            // Falling calibration notes at exact 500ms beat intervals (with live calculatedOffsetMs preview!)
                            val approachMs = 950.0
                            val effectivePos = (loopPosMs + calculatedOffsetMs + LOOP_MS) % LOOP_MS
                            for (beatIdx in -1..5) {
                                val beatTime = beatIdx * BEAT_MS
                                var dt = beatTime - effectivePos
                                while (dt < -250.0) dt += LOOP_MS
                                while (dt > LOOP_MS - 250.0) dt -= LOOP_MS
                                val prog = (1.0 - dt / approachMs).toFloat()
                                if (prog in 0f..1.18f) {
                                    val cy = prog * judgeY
                                    val isAccentBeat = ((beatIdx % 4 + 4) % 4) == 0
                                    val noteColor = if (isAccentBeat) Color(skin.holdMain) else Color(skin.tapMain)
                                    drawRoundRect(
                                        color = noteColor,
                                        topLeft = Offset(trackLeft + 10f, cy - 11f),
                                        size = Size(trackW - 20f, 22f),
                                        cornerRadius = CornerRadius(10f, 10f)
                                    )
                                }
                            }
                        }

                        Text(
                            text = "判定线",
                            color = AstraColors.TextSecondary,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 10.dp, bottom = 10.dp)
                        )
                    }

                    // Right: Live Calculation Readout + Big Tap Pad + Controls
                    Column(
                        modifier = Modifier
                            .weight(1.05f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            color = Color(0x55091228),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, AstraColors.GlassBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("动态推荐偏移", color = AstraColors.TextSecondary, fontSize = 12.sp)
                                    Text(
                                        text = "${if (calculatedOffsetMs >= 0) "+" else ""}$calculatedOffsetMs ms",
                                        color = accent,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }

                                val lastStr = lastTapDeltaMs?.let {
                                    val tag = when {
                                        abs(it) <= 18 -> "精准"
                                        it < 0 -> "偏晚 ${abs(it)}ms"
                                        else -> "偏早 ${it}ms"
                                    }
                                    "已采样 ${samples.size} 次 · 最近一次：$tag"
                                } ?: "尚未打击，请跟随节拍声点击下方打击区"

                                Text(lastStr, color = AstraColors.TextPrimary, fontSize = 12.sp)

                                // Scatter visualization bar (-150ms .. +150ms)
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(22.dp)
                                ) {
                                    val midX = size.width * 0.5f
                                    val midY = size.height * 0.5f
                                    drawLine(Color(0x55FFFFFF), Offset(0f, midY), Offset(size.width, midY), strokeWidth = 3f)
                                    drawLine(Color.White, Offset(midX, 2f), Offset(midX, size.height - 2f), strokeWidth = 3f)
                                    samples.forEach { s ->
                                        val nx = ((s + 150f) / 300f).coerceIn(0f, 1f)
                                        drawCircle(accent.copy(alpha = 0.7f), radius = 5f, center = Offset(nx * size.width, midY))
                                    }
                                    val avgX = ((calculatedOffsetMs + 150f) / 300f).coerceIn(0f, 1f) * size.width
                                    drawCircle(Color(skin.holdMain), radius = 7.5f, center = Offset(avgX, midY))
                                }
                            }
                        }

                        // Big Tactile Tap Pad
                        Surface(
                            onClick = ::recordBeatTap,
                            color = accent.copy(alpha = if (tapFlashAlpha > 0.3) 0.38f else 0.18f),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(2.dp, accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(vertical = 10.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("跟随节奏 · 点击此处打击", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                                    Spacer(Modifier.height(4.dp))
                                    Text("TAP ON THE BEAT", color = accent, fontSize = 11.sp, letterSpacing = 3.sp)
                                }
                            }
                        }

                        // Fine-tune & Apply Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { calculatedOffsetMs = (calculatedOffsetMs - 5).coerceIn(-150, 150) },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, AstraColors.GlassBorder)
                            ) { Text("-5ms", color = AstraColors.TextPrimary, fontSize = 12.sp) }

                            OutlinedButton(
                                onClick = { calculatedOffsetMs = (calculatedOffsetMs + 5).coerceIn(-150, 150) },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, AstraColors.GlassBorder)
                            ) { Text("+5ms", color = AstraColors.TextPrimary, fontSize = 12.sp) }

                            OutlinedButton(
                                onClick = {
                                    samples.clear()
                                    lastTapDeltaMs = null
                                    calculatedOffsetMs = 0
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, AstraColors.GlassBorder)
                            ) { Text("归零", color = AstraColors.TextSecondary, fontSize = 12.sp) }

                            Spacer(Modifier.width(4.dp))

                            Button(
                                onClick = {
                                    onApplyOffset(calculatedOffsetMs)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accent,
                                    contentColor = Color(0xFF041320)
                                )
                            ) {
                                Text("应用偏移", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ensureMetronomeWav(context: Context): File {
    val file = File(context.cacheDir, "astra_calibrator_120bpm_v1.wav")
    if (file.exists() && file.length() > 1024) return file

    val sr = 22050
    val totalSamples = sr * 2 // exact 2.000 seconds = 4 beats at 120 BPM (500ms each)
    val mix = FloatArray(totalSamples)
    val beatSamples = sr / 2 // 11025 samples = 500ms

    for (beat in 0 until 4) {
        val start = beat * beatSamples
        val freq = if (beat == 0) 1180.0 else 760.0
        val tickLen = (sr * 0.055).toInt()
        for (i in 0 until tickLen) {
            val idx = start + i
            if (idx >= totalSamples) break
            val t = i.toDouble() / sr
            val env = exp(-t * 52.0)
            mix[idx] = (sin(2.0 * PI * freq * t) * env * 0.85).toFloat()
        }
    }

    val dataBytes = mix.size * 2
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(36 + dataBytes)
        put("WAVE".toByteArray())
        put("fmt ".toByteArray())
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(sr)
        putInt(sr * 2)
        putShort(2)
        putShort(16)
        put("data".toByteArray())
        putInt(dataBytes)
    }.array()

    BufferedOutputStream(FileOutputStream(file)).use { out ->
        out.write(header)
        val chunk = ByteArray(8192)
        var idx = 0
        while (idx < mix.size) {
            var pos = 0
            while (idx < mix.size && pos < chunk.size) {
                val pcm = (mix[idx++] * 32767f).toInt().coerceIn(-32768, 32767)
                chunk[pos++] = (pcm and 0xFF).toByte()
                chunk[pos++] = ((pcm shr 8) and 0xFF).toByte()
            }
            out.write(chunk, 0, pos)
        }
    }
    return file
}
