package com.astraecho.rhythm.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.astraecho.rhythm.R
import com.astraecho.rhythm.engine.TrackProjection
import com.astraecho.rhythm.model.NoteSkin
import com.astraecho.rhythm.model.PlayerSettings
import com.astraecho.rhythm.ui.theme.AstraColors
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    settings: PlayerSettings,
    onUpdateSettings: (PlayerSettings) -> Unit,
    onEnterSongSelect: () -> Unit,
    onQuickImportMedia: () -> Unit
) {
    var showLoreDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isTablet = maxWidth >= 840.dp
        val hPad = if (isTablet) 48.dp else 24.dp
        val emblemSize = if (isTablet) 172.dp else 124.dp

        Image(
            painter = painterResource(id = R.drawable.bg_home),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0x99070A16),
                            Color(0xB8070A16),
                            Color(0xE0070A16)
                        )
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = hPad, vertical = if (isTablet) 36.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isTablet) 44.dp else 24.dp)
        ) {
            // Left Hero Column
            Column(
                modifier = Modifier.weight(1.05f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    color = AstraColors.GlassCard,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, AstraColors.GlassBorder)
                ) {
                    Text(
                        text = "VEGA RING OBSERVATORY · 2147",
                        color = AstraColors.NeonCyan,
                        fontSize = if (isTablet) 12.sp else 10.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }

                Spacer(Modifier.height(if (isTablet) 24.dp else 14.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(emblemSize)
                        .clip(CircleShape)
                        .border(
                            width = 2.5.dp,
                            brush = Brush.sweepGradient(
                                listOf(
                                    Color(settings.skin.tapMain),
                                    Color(settings.skin.slideMain),
                                    Color(settings.skin.holdMain),
                                    Color(settings.skin.tapMain)
                                )
                            ),
                            shape = CircleShape
                        )
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.hero_emblem),
                        contentDescription = "Astra Echo Emblem",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(Modifier.height(if (isTablet) 20.dp else 12.dp))

                Text(
                    text = "星 律 回 响",
                    color = AstraColors.TextPrimary,
                    fontSize = if (isTablet) 42.sp else 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 6.sp
                )
                Text(
                    text = "ASTRA ECHO",
                    color = Color(settings.skin.railColor),
                    fontSize = if (isTablet) 15.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "4/5/6 轨自由切换 · 跨轨滑动与长按滑键 · 音视频智能编谱",
                    color = AstraColors.TextSecondary,
                    fontSize = if (isTablet) 14.sp else 11.sp
                )
            }

            // Right Command Deck Card
            Surface(
                color = AstraColors.GlassCard,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.2.dp, AstraColors.GlassBorder),
                modifier = Modifier.weight(0.95f)
            ) {
                Column(
                    modifier = Modifier.padding(if (isTablet) 28.dp else 18.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isTablet) 14.dp else 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "共鸣控制台",
                            color = AstraColors.TextPrimary,
                            fontSize = if (isTablet) 18.sp else 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = Color(settings.skin.tapMain).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(settings.skin.tapMain))
                        ) {
                            Text(
                                text = "皮肤：${settings.skin.displayName}",
                                color = Color(settings.skin.tapHighlight),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Button(
                        onClick = onEnterSongSelect,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isTablet) 58.dp else 48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AstraColors.NeonCyan,
                            contentColor = Color(0xFF041320)
                        )
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("进入星轨 · 选择乐章", fontSize = if (isTablet) 16.sp else 14.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onQuickImportMedia,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isTablet) 54.dp else 46.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.5.dp, AstraColors.ElectricMagenta),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0x2EFF3B8D),
                            contentColor = AstraColors.TextPrimary
                        )
                    ) {
                        Icon(Icons.Rounded.VideoLibrary, contentDescription = null, tint = AstraColors.ElectricMagenta)
                        Spacer(Modifier.width(8.dp))
                        Text("导入音频 / 视频 · 提取音轨编谱", fontSize = if (isTablet) 15.sp else 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(if (isTablet) 48.dp else 42.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AstraColors.GlassBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color(0x33162447),
                                contentColor = AstraColors.TextPrimary
                            )
                        ) {
                            Icon(Icons.Rounded.Palette, contentDescription = null, modifier = Modifier.size(17.dp), tint = AstraColors.NeonCyan)
                            Spacer(Modifier.width(6.dp))
                            Text("皮肤与轨道", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = { showLoreDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(if (isTablet) 48.dp else 42.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AstraColors.GlassBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color(0x33162447),
                                contentColor = AstraColors.TextPrimary
                            )
                        ) {
                            Icon(Icons.Rounded.AutoStories, contentDescription = null, modifier = Modifier.size(17.dp), tint = AstraColors.StarlightGold)
                            Spacer(Modifier.width(6.dp))
                            Text("世界观", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    if (showLoreDialog) {
        AlertDialog(
            onDismissRequest = { showLoreDialog = false },
            containerColor = AstraColors.DeepIndigo,
            title = {
                Text("世界观档案 · 星律回响", color = AstraColors.StarlightGold, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "公元 2147 年，深空观测站「织女星环」捕获到来自银河悬臂的神秘脉冲信号——失落古文明以纯声波铭刻在星际尘埃中的记忆残片「星律（Astra）」。\n\n" +
                        "你作为「共鸣调律者」，将把内置档案乐章，或是你从本地音频与视频中提取的私藏旋律，实时解构为倾泻而下的星辰音轨：\n" +
                        "· 单点晶键：在判定线衔接处轻触共鸣\n" +
                        "· 长按星带：按住直至尾端光环绽放\n" +
                        "· 跨轨滑键 & 长按滑键：顺发光箭矢与光轨跨道滑动",
                    color = AstraColors.TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showLoreDialog = false }) {
                    Text("知晓了", color = AstraColors.NeonCyan, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            settings = settings,
            onUpdateSettings = onUpdateSettings,
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
fun SettingsDialog(
    settings: PlayerSettings,
    onUpdateSettings: (PlayerSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var showCalibrator by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = AstraColors.DeepIndigo,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.5.dp, Color(settings.skin.railColor)),
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .fillMaxHeight(0.84f)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("皮肤切换与轨道手感校准", color = AstraColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) {
                        Text("完成", color = AstraColors.NeonCyan, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Left: Live Seamless 3D Track & Skin Preview
                    Column(modifier = Modifier.weight(0.95f)) {
                        Text("实时轨道与皮肤预览", color = AstraColors.TextSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        TrackTiltPreview(
                            laneCount = settings.laneCount,
                            tiltDegrees = settings.trackTiltDegrees,
                            skin = settings.skin,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF050814))
                                .border(1.dp, AstraColors.GlassBorder, RoundedCornerShape(14.dp))
                        )
                    }

                    // Right: Skin Picker + Sliders
                    Column(
                        modifier = Modifier
                            .weight(1.05f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("按键与特效皮肤", color = AstraColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        SkinSelectorRow(
                            selectedSkin = settings.skin,
                            onSelect = { onUpdateSettings(settings.copy(skin = it)) }
                        )

                        Spacer(Modifier.height(2.dp))

                        SliderItem(
                            label = "下落轨道斜度",
                            valueText = "${settings.trackTiltDegrees.roundToInt()}°",
                            value = settings.trackTiltDegrees,
                            range = 0f..60f,
                            accent = Color(settings.skin.railColor),
                            onChange = { onUpdateSettings(settings.copy(trackTiltDegrees = it)) }
                        )

                        SliderItem(
                            label = "音符下落流速",
                            valueText = String.format("%.2fx", settings.noteSpeed),
                            value = settings.noteSpeed,
                            range = 0.6f..1.8f,
                            accent = AstraColors.StarlightGold,
                            onChange = { onUpdateSettings(settings.copy(noteSpeed = it)) }
                        )

                        // Interactive Rhythm-Tap Audio Offset Calibration Launcher
                        Surface(
                            onClick = { showCalibrator = true },
                            color = Color(0x33FF3B8D),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AstraColors.ElectricMagenta),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("音频判定偏移 · 节奏打击校准", color = AstraColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("跟随固定节拍声打击下落音符，自动计算延迟", color = AstraColors.TextSecondary, fontSize = 10.sp)
                                }
                                Text(
                                    text = "${if (settings.audioOffsetMs >= 0) "+" else ""}${settings.audioOffsetMs} ms →",
                                    color = AstraColors.ElectricMagenta,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }

                        // Optional Custom Link Parser API fallback
                        OutlinedTextField(
                            value = settings.customParserApiUrl,
                            onValueChange = { onUpdateSettings(settings.copy(customParserApiUrl = it)) },
                            singleLine = true,
                            label = { Text("自定义链接解析接口（可选备用，支持 {url}）", fontSize = 11.sp) },
                            placeholder = { Text("留空则使用内置直连解析", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    if (showCalibrator) {
        AudioOffsetCalibratorDialog(
            initialOffsetMs = settings.audioOffsetMs,
            skin = settings.skin,
            onApplyOffset = { offset -> onUpdateSettings(settings.copy(audioOffsetMs = offset)) },
            onDismiss = { showCalibrator = false }
        )
    }
}

@Composable
fun SkinSelectorRow(
    selectedSkin: NoteSkin,
    onSelect: (NoteSkin) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NoteSkin.values().forEach { skin ->
            val selected = skin == selectedSkin
            Surface(
                color = if (selected) Color(skin.tapMain).copy(alpha = 0.24f) else Color(0x33162447),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Color(skin.tapMain) else Color(0x33FFFFFF)),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(skin) }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(skin.tapMain)))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(skin.holdMain)))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(skin.slideMain)))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = skin.displayName,
                        color = if (selected) Color.White else AstraColors.TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderItem(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = AstraColors.TextPrimary, fontSize = 12.sp)
            Text(valueText, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
        )
    }
}

@Composable
fun TrackTiltPreview(
    laneCount: Int,
    tiltDegrees: Float,
    skin: NoteSkin,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val proj = TrackProjection(size.width, size.height, laneCount, tiltDegrees)
        val railColor = Color(skin.railColor)

        // Seamless track + keyboard trapezoid
        val trackPath = Path().apply {
            moveTo(proj.topLeft, proj.topY)
            lineTo(proj.topRight, proj.topY)
            lineTo(proj.keyBottomRight, proj.bottomY)
            lineTo(proj.keyBottomLeft, proj.bottomY)
            close()
        }
        drawPath(trackPath, color = Color(0x660B1530))

        // Seamless bottom key zone highlight
        val keyPath = Path().apply {
            moveTo(proj.judgeLeft, proj.judgeY)
            lineTo(proj.judgeRight, proj.judgeY)
            lineTo(proj.keyBottomRight, proj.bottomY)
            lineTo(proj.keyBottomLeft, proj.bottomY)
            close()
        }
        drawPath(keyPath, color = Color(skin.keyGlowColor).copy(alpha = 0.22f))

        // Continuous lane dividers top -> bottom
        for (i in 1 until laneCount) {
            drawLine(
                color = railColor.copy(alpha = 0.45f),
                start = Offset(proj.xAt(i.toFloat(), 0f), proj.topY),
                end = Offset(proj.keyBottomXAt(i.toFloat()), proj.bottomY),
                strokeWidth = 1.5f
            )
        }
        // Outer rails
        drawLine(railColor, Offset(proj.topLeft, proj.topY), Offset(proj.keyBottomLeft, proj.bottomY), strokeWidth = 3f)
        drawLine(railColor, Offset(proj.topRight, proj.topY), Offset(proj.keyBottomRight, proj.bottomY), strokeWidth = 3f)

        // Seamless judgment line at top of keys
        drawLine(Color.White, Offset(proj.judgeLeft, proj.judgeY), Offset(proj.judgeRight, proj.judgeY), strokeWidth = 3.5f)

        // Sample Hold Ribbon on lane 0
        val holdPath = Path().apply {
            moveTo(proj.xAt(0.24f, 0.25f), proj.yAt(0.25f))
            lineTo(proj.xAt(0.76f, 0.25f), proj.yAt(0.25f))
            lineTo(proj.xAt(0.76f, 0.65f), proj.yAt(0.65f))
            lineTo(proj.xAt(0.24f, 0.65f), proj.yAt(0.65f))
            close()
        }
        drawPath(holdPath, color = Color(skin.holdBody).copy(alpha = 0.7f))
        drawLine(
            Color(skin.holdMain),
            Offset(proj.xAt(0.08f, 0.65f), proj.yAt(0.65f)),
            Offset(proj.xAt(0.92f, 0.65f), proj.yAt(0.65f)),
            strokeWidth = 8f
        )

        // Sample Cross-Lane Slide arrow (lane 1 -> lane 2)
        val slideY = proj.yAt(0.48f)
        drawLine(
            Color(skin.slideHighlight),
            Offset(proj.xAt(1.5f, 0.48f), slideY),
            Offset(proj.xAt(2.5f, 0.48f), slideY),
            strokeWidth = 5f
        )
        drawLine(
            Color(skin.slideMain),
            Offset(proj.xAt(1.08f, 0.48f), slideY),
            Offset(proj.xAt(1.92f, 0.48f), slideY),
            strokeWidth = 9f
        )

        // Sample Tap note on last lane
        val tapLane = laneCount - 1
        val tapY = proj.yAt(0.80f)
        drawLine(
            Color(skin.tapMain),
            Offset(proj.xAt(tapLane + 0.08f, 0.80f), tapY),
            Offset(proj.xAt(tapLane + 0.92f, 0.80f), tapY),
            strokeWidth = 9f
        )
    }
}
