package com.astraecho.rhythm.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.astraecho.rhythm.engine.GameSurfaceView
import com.astraecho.rhythm.engine.HudSnapshot
import com.astraecho.rhythm.model.BeatmapChart
import com.astraecho.rhythm.model.GameResult
import com.astraecho.rhythm.model.NoteSkin
import com.astraecho.rhythm.model.PlayerSettings
import com.astraecho.rhythm.ui.theme.AstraColors
import kotlin.math.roundToInt

@Composable
fun PlayScreen(
    audioPath: String,
    chart: BeatmapChart,
    settings: PlayerSettings,
    onUpdateSettings: (PlayerSettings) -> Unit,
    onFinished: (GameResult) -> Unit,
    onExit: () -> Unit
) {
    var hud by remember { mutableStateOf(HudSnapshot()) }
    var isPaused by remember { mutableStateOf(false) }
    var surfaceRef by remember { mutableStateOf<GameSurfaceView?>(null) }

    BackHandler {
        isPaused = true
        surfaceRef?.setPaused(true)
    }

    // Skin-specific scoreboard & HUD shape / typography
    val hudShape = when (settings.skin) {
        NoteSkin.ASTRA_CYBER -> RoundedCornerShape(12.dp)
        NoteSkin.CLASSIC_MASTER -> RoundedCornerShape(6.dp)
        NoteSkin.SAKURA_PULSE -> RoundedCornerShape(22.dp)
        NoteSkin.CRIMSON_OVERDRIVE -> CutCornerShape(10.dp)
        NoteSkin.EULER_CHALK -> RoundedCornerShape(2.dp)
    }
    val hudFont = when (settings.skin) {
        NoteSkin.CRIMSON_OVERDRIVE -> FontFamily.Monospace
        NoteSkin.EULER_CHALK -> FontFamily.Serif
        else -> FontFamily.Default
    }
    val hudBgColor = when (settings.skin) {
        NoteSkin.ASTRA_CYBER -> Color(0x99081026)
        NoteSkin.CLASSIC_MASTER -> Color(0xAA081A42)
        NoteSkin.SAKURA_PULSE -> Color(0xAA240D32)
        NoteSkin.CRIMSON_OVERDRIVE -> Color(0xBB1E060C)
        NoteSkin.EULER_CHALK -> Color(0xCC0A0B0E)
    }
    val hudAccent = Color(settings.skin.railColor)

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GameSurfaceView(
                    context = ctx,
                    audioPath = audioPath,
                    chart = chart,
                    initialSettings = settings,
                    onHudUpdate = { hud = it },
                    onGameFinished = onFinished
                ).also { surfaceRef = it }
            },
            update = { view ->
                view.updateSettings(settings)
            }
        )

        DisposableEffect(Unit) {
            onDispose {
                surfaceRef?.stopAndRelease()
                surfaceRef = null
            }
        }

        // Left Wing HUD (Skin-styled accuracy, song info, GO-END progress bar)
        Surface(
            color = hudBgColor,
            shape = hudShape,
            border = BorderStroke(1.dp, hudAccent.copy(alpha = 0.55f)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 14.dp, start = 16.dp)
                .width(194.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = String.format("%.2f%%", hud.accuracy),
                    color = AstraColors.TextPrimary,
                    fontSize = 24.sp,
                    fontFamily = hudFont,
                    fontWeight = if (settings.skin == NoteSkin.CRIMSON_OVERDRIVE) FontWeight.Bold else FontWeight.Light,
                    letterSpacing = 1.sp
                )
                Text(
                    text = chart.title,
                    color = Color(settings.skin.holdMain),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${chart.laneCount}K · ${chart.difficulty.tag} · ${if (chart.onlyTapAndHold) "仅按/长" else "全键型"}",
                    color = hudAccent,
                    fontSize = 10.sp,
                    fontFamily = hudFont,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val curSec = (hud.currentTimeMs / 1000L).coerceAtLeast(0L)
                    val totSec = ((if (hud.durationMs > 0L) hud.durationMs else chart.durationMs) / 1000L).coerceAtLeast(1L)
                    val timeLabel = "${curSec / 60}:${(curSec % 60).toString().padStart(2, '0')} / ${totSec / 60}:${(totSec % 60).toString().padStart(2, '0')}"
                    Text("GO", color = AstraColors.TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(timeLabel, color = AstraColors.TextPrimary, fontSize = 10.sp, fontFamily = hudFont, fontWeight = FontWeight.SemiBold)
                    Text("END", color = AstraColors.TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(
                    progress = { hud.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = hudAccent,
                    trackColor = Color(0x44FFFFFF)
                )
            }
        }

        // Right Wing HUD (Skin-styled Scoreboard + Pause button)
        Surface(
            color = hudBgColor,
            shape = hudShape,
            border = BorderStroke(1.2.dp, hudAccent.copy(alpha = 0.65f)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 14.dp, end = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = when (settings.skin) {
                            NoteSkin.SAKURA_PULSE -> "✦ SCORE ✦"
                            NoteSkin.CRIMSON_OVERDRIVE -> "SYS//SCORE"
                            NoteSkin.EULER_CHALK -> "[ ∑ SCORE ]"
                            else -> "SCORE"
                        },
                        color = hudAccent,
                        fontSize = 9.sp,
                        fontFamily = hudFont,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = hud.score.toString().padStart(7, '0'),
                        color = AstraColors.TextPrimary,
                        fontSize = 20.sp,
                        fontFamily = hudFont,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                IconButton(
                    onClick = {
                        isPaused = true
                        surfaceRef?.setPaused(true)
                    }
                ) {
                    Icon(Icons.Rounded.Pause, contentDescription = "Pause", tint = hudAccent)
                }
            }
        }

        // Landscape Pause Overlay with instant bug-free Resume & Restart!
        if (isPaused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC050814)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = AstraColors.DeepIndigo,
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.5.dp, hudAccent),
                    modifier = Modifier.fillMaxWidth(0.74f)
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "星轨已暂停 · 实时调节",
                            color = AstraColors.TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )

                        SkinSelectorRow(
                            selectedSkin = settings.skin,
                            onSelect = { onUpdateSettings(settings.copy(skin = it)) }
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("轨道斜度", color = AstraColors.TextSecondary, fontSize = 12.sp)
                                    Text("${settings.trackTiltDegrees.roundToInt()}°", color = hudAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Slider(
                                    value = settings.trackTiltDegrees,
                                    onValueChange = { onUpdateSettings(settings.copy(trackTiltDegrees = it)) },
                                    valueRange = 0f..60f,
                                    colors = SliderDefaults.colors(thumbColor = hudAccent, activeTrackColor = hudAccent)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("下落流速", color = AstraColors.TextSecondary, fontSize = 12.sp)
                                    Text(String.format("%.2fx", settings.noteSpeed), color = AstraColors.StarlightGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Slider(
                                    value = settings.noteSpeed,
                                    onValueChange = { onUpdateSettings(settings.copy(noteSpeed = it)) },
                                    valueRange = 0.6f..1.8f,
                                    colors = SliderDefaults.colors(thumbColor = AstraColors.StarlightGold, activeTrackColor = AstraColors.StarlightGold)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    isPaused = false
                                    surfaceRef?.resumeWithCountdown()
                                },
                                modifier = Modifier.weight(1.3f).height(46.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = hudAccent,
                                    contentColor = Color(0xFF041320)
                                )
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("继续演奏", fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    isPaused = false
                                    hud = HudSnapshot()
                                    surfaceRef?.restartGame()
                                },
                                modifier = Modifier.weight(1f).height(46.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, AstraColors.GlassBorder)
                            ) {
                                Icon(Icons.Rounded.Replay, contentDescription = null, tint = AstraColors.TextPrimary)
                                Spacer(Modifier.width(4.dp))
                                Text("重来", color = AstraColors.TextPrimary)
                            }

                            OutlinedButton(
                                onClick = {
                                    surfaceRef?.stopAndRelease()
                                    onExit()
                                },
                                modifier = Modifier.weight(1f).height(46.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, AstraColors.ElectricMagenta)
                            ) {
                                Text("退出选曲", color = AstraColors.ElectricMagenta)
                            }
                        }
                    }
                }
            }
        }
    }
}
