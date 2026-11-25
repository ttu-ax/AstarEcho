package com.astraecho.rhythm.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astraecho.rhythm.R
import com.astraecho.rhythm.model.BackgroundImportTask
import com.astraecho.rhythm.model.Difficulty
import com.astraecho.rhythm.model.PlayerSettings
import com.astraecho.rhythm.model.SongItem
import com.astraecho.rhythm.ui.theme.AstraColors
import kotlin.math.roundToInt

@Composable
fun SongSelectScreen(
    songs: List<SongItem>,
    selectedSong: SongItem?,
    selectedChartReady: Boolean,
    generatingSongIds: Set<String>,
    bgTasks: List<BackgroundImportTask>,
    settings: PlayerSettings,
    isGenerating: Boolean,
    generationStatus: String,
    generationProgress: Float,
    errorMessage: String?,
    onSelectSong: (SongItem) -> Unit,
    onRenameSong: (SongItem, String) -> Unit,
    onDeleteSong: (SongItem) -> Unit,
    onImportMedia: () -> Unit,
    onImportLinks: (String) -> Unit,
    onOpenEditor: (SongItem, Boolean) -> Unit,
    onExportSongsZip: (List<SongItem>) -> Unit,
    onImportPackFile: () -> Unit,
    onUpdateSettings: (PlayerSettings) -> Unit,
    onStartPlay: (SongItem) -> Unit,
    onBack: () -> Unit
) {
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var showPackDialog by remember { mutableStateOf(false) }
    var showEditorChoiceDialog by remember { mutableStateOf(false) }
    var linkInputText by remember { mutableStateOf("") }
    var renamingSong by remember { mutableStateOf<SongItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isTablet = maxWidth >= 840.dp
        val hPad = if (isTablet) 28.dp else 16.dp

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
                    Brush.verticalGradient(
                        listOf(Color(0xC2070A16), Color(0xE8070A16), Color(0xF5070A16))
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = hPad, vertical = if (isTablet) 18.dp else 10.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = AstraColors.TextPrimary)
                    }
                    Column {
                        Text(
                            "星轨曲库 · 智能编谱终端",
                            color = AstraColors.TextPrimary,
                            fontSize = if (isTablet) 20.sp else 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "支持导入本地音频/视频（文件选择器中长按可多选）及抖音链接",
                            color = AstraColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        onClick = { showPackDialog = true },
                        color = Color(0x33FFD166),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AstraColors.StarlightGold)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Archive, contentDescription = null, tint = AstraColors.StarlightGold, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("曲包导出/导入", color = AstraColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Surface(
                        onClick = { showLinkDialog = true },
                        color = Color(0x3338F2FF),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AstraColors.NeonCyan)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Link, contentDescription = null, tint = AstraColors.NeonCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("链接导入", color = AstraColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Surface(
                        onClick = onImportMedia,
                        color = Color(0x33FF3B8D),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AstraColors.ElectricMagenta)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = AstraColors.ElectricMagenta, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("批量导入音/视频", color = AstraColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Rounded.Tune, contentDescription = "Advanced Settings", tint = AstraColors.NeonCyan)
                    }
                }
            }

            // Non-blocking multi-task background import & auto-charting list (no flickering!)
            if (bgTasks.isNotEmpty()) {
                Surface(
                    color = Color(0xCC112046),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, AstraColors.NeonCyan.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        bgTasks.take(4).forEach { task ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.8.dp,
                                    color = AstraColors.NeonCyan
                                )
                                Text(
                                    text = "${task.title} · ${task.status}",
                                    color = AstraColors.TextPrimary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                LinearProgressIndicator(
                                    progress = { task.progress.coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .width(96.dp)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = AstraColors.NeonCyan,
                                    trackColor = Color(0x33FFFFFF)
                                )
                            }
                        }
                        if (bgTasks.size > 4) {
                            Text(
                                text = "还有 ${bgTasks.size - 4} 项后台任务排队中...",
                                color = AstraColors.TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = AstraColors.ElectricMagenta,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Landscape Split-Pane Studio Body
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(if (isTablet) 20.dp else 14.dp)
            ) {
                // LEFT PANE: Song Library
                LazyColumn(
                    modifier = Modifier
                        .weight(0.46f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(songs, key = { it.id }) { song ->
                        val isSelected = selectedSong?.id == song.id
                        val accent = Color(song.accentHex)
                        Surface(
                            color = if (isSelected) Color(0xDD1A2B59) else AstraColors.GlassCard,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                width = if (isSelected) 1.8.dp else 1.dp,
                                color = if (isSelected) accent else Color(0x2EFFFFFF)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSong(song) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(accent.copy(alpha = 0.22f))
                                ) {
                                    Icon(
                                        imageVector = if (song.isFromVideo) Icons.Rounded.Movie else Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = accent
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = song.title,
                                            color = AstraColors.TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            color = if (song.id in generatingSongIds) AstraColors.StarlightGold.copy(alpha = 0.25f) else accent.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = when {
                                                    song.id in generatingSongIds -> "后台编谱中..."
                                                    song.isBuiltIn -> "星际档案"
                                                    song.isFromVideo -> "视频提音"
                                                    else -> "本地音频"
                                                },
                                                color = if (song.id in generatingSongIds) AstraColors.StarlightGold else accent,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    val durSec = (song.durationMs / 1000).coerceAtLeast(1)
                                    val bpmLabel = if (song.bpm > 0) "${song.bpm} BPM" else "自动测速"
                                    Text(
                                        text = "${song.artist} · $bpmLabel · ${durSec / 60}:${(durSec % 60).toString().padStart(2, '0')}",
                                        color = AstraColors.TextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (!song.isBuiltIn) {
                                    IconButton(
                                        onClick = {
                                            renamingSong = song
                                            renameText = song.title
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "Rename", tint = AstraColors.NeonCyan, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { onDeleteSong(song) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = AstraColors.TextSecondary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // RIGHT PANE: Chart & Skin Configurator + Launch Button
                Surface(
                    color = AstraColors.GlassCard,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, AstraColors.GlassBorder),
                    modifier = Modifier
                        .weight(0.54f)
                        .fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isTablet) 18.dp else 12.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Top row: Lane count + Live Mini Preview
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1.15f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Lane Count: 4 / 5 / 6
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(4, 5, 6).forEach { count ->
                                            val selected = settings.laneCount == count
                                            Surface(
                                                onClick = { onUpdateSettings(settings.copy(laneCount = count)) },
                                                modifier = Modifier.weight(1f),
                                                color = if (selected) AstraColors.NeonCyan else Color(0x331B2A4E),
                                                shape = RoundedCornerShape(10.dp),
                                                border = BorderStroke(1.dp, if (selected) AstraColors.NeonCyan else AstraColors.GlassBorder)
                                            ) {
                                                Text(
                                                    text = "${count} 轨",
                                                    color = if (selected) Color(0xFF041320) else AstraColors.TextPrimary,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Difficulty Selector
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Difficulty.values().forEach { diff ->
                                            val selected = settings.difficulty == diff
                                            val accent = when (diff) {
                                                Difficulty.EASY -> AstraColors.NeonCyan
                                                Difficulty.NORMAL -> AstraColors.StarlightGold
                                                Difficulty.HARD -> AstraColors.ElectricMagenta
                                                Difficulty.MASTER -> AstraColors.CosmicPurple
                                            }
                                            Surface(
                                                onClick = { onUpdateSettings(settings.copy(difficulty = diff)) },
                                                modifier = Modifier.weight(1f),
                                                color = if (selected) accent.copy(alpha = 0.28f) else Color(0x281B2A4E),
                                                shape = RoundedCornerShape(9.dp),
                                                border = BorderStroke(1.dp, if (selected) accent else Color(0x33FFFFFF))
                                            ) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    modifier = Modifier.padding(vertical = 5.dp)
                                                ) {
                                                    Text(diff.tag, color = if (selected) accent else AstraColors.TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                                                    Text(diff.displayName.substringAfter("· "), color = AstraColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                    }
                                }

                                // Live Mini Track + Skin Preview
                                TrackTiltPreview(
                                    laneCount = settings.laneCount,
                                    tiltDegrees = settings.trackTiltDegrees,
                                    skin = settings.skin,
                                    modifier = Modifier
                                        .weight(0.85f)
                                        .height(if (isTablet) 104.dp else 82.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF050814))
                                        .border(1.dp, Color(settings.skin.railColor).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                )
                            }

                            // 4 Skins Switcher + current theme detail
                            SkinSelectorRow(
                                selectedSkin = settings.skin,
                                onSelect = { onUpdateSettings(settings.copy(skin = it)) }
                            )
                            Text(
                                text = "当前主题：${settings.skin.subtitle}",
                                color = Color(settings.skin.railColor),
                                fontSize = 11.sp
                            )

                            // Toggle: Only Tap and Hold
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("只有按键和长条", color = AstraColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (settings.onlyTapAndHold) "仅生成按键 + 长条（关闭跨轨滑动与长按滑动）"
                                        else "默认全键型（按键 + 长条 + 跨轨滑动 + 长按滑动）",
                                        color = if (settings.onlyTapAndHold) AstraColors.StarlightGold else AstraColors.TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = settings.onlyTapAndHold,
                                    onCheckedChange = { onUpdateSettings(settings.copy(onlyTapAndHold = it)) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF041320),
                                        checkedTrackColor = AstraColors.StarlightGold,
                                        uncheckedThumbColor = AstraColors.NeonCyan,
                                        uncheckedTrackColor = Color(0x4438F2FF)
                                    )
                                )
                            }

                            // Quick Track Tilt Slider
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "轨道斜度 ${settings.trackTiltDegrees.roundToInt()}°",
                                    color = AstraColors.TextSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.width(82.dp)
                                )
                                Slider(
                                    value = settings.trackTiltDegrees,
                                    onValueChange = { onUpdateSettings(settings.copy(trackTiltDegrees = it)) },
                                    valueRange = 0f..60f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(settings.skin.railColor),
                                        activeTrackColor = Color(settings.skin.railColor)
                                    )
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        val isSelectedBgGenerating = selectedSong != null && selectedSong.id in generatingSongIds
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { if (selectedSong != null) showEditorChoiceDialog = true },
                                enabled = selectedSong != null && !isGenerating && !isSelectedBgGenerating,
                                modifier = Modifier
                                    .weight(0.40f)
                                    .height(if (isTablet) 52.dp else 44.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.2.dp, AstraColors.StarlightGold)
                            ) {
                                Icon(Icons.Rounded.EditNote, contentDescription = null, tint = AstraColors.StarlightGold, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("谱面编辑器", color = AstraColors.StarlightGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { selectedSong?.let(onStartPlay) },
                                enabled = selectedSong != null && !isGenerating && !isSelectedBgGenerating,
                                modifier = Modifier
                                    .weight(0.60f)
                                    .height(if (isTablet) 52.dp else 44.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(settings.skin.tapMain),
                                    contentColor = Color(0xFF041320)
                                )
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = when {
                                        selectedSong == null -> "请选择或导入曲目"
                                        isSelectedBgGenerating -> "后台编谱中..."
                                        selectedChartReady -> "开始演奏 · ${settings.laneCount}K"
                                        else -> "生成 ${settings.laneCount}K 谱面并演奏"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // Progress overlay (Media extraction / DSP auto-charting)
        if (isGenerating) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC050814)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = AstraColors.DeepIndigo,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.5.dp, AstraColors.NeonCyan),
                    modifier = Modifier.fillMaxWidth(0.55f)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        CircularProgressIndicator(color = AstraColors.NeonCyan)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = generationStatus.ifBlank { "正在解析星律波形..." },
                            color = AstraColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { generationProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = AstraColors.NeonCyan,
                            trackColor = Color(0x33FFFFFF)
                        )
                    }
                }
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            settings = settings,
            onUpdateSettings = onUpdateSettings,
            onDismiss = { showSettingsDialog = false }
        )
    }

    if (showEditorChoiceDialog && selectedSong != null) {
        val target = selectedSong
        AlertDialog(
            onDismissRequest = { showEditorChoiceDialog = false },
            containerColor = AstraColors.DeepIndigo,
            title = { Text("打开谱面编辑器 · ${target.title}", color = AstraColors.StarlightGold, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "当前配置：${settings.laneCount} 轨 · ${settings.difficulty.displayName}",
                        color = AstraColors.TextSecondary,
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = {
                            showEditorChoiceDialog = false
                            onOpenEditor(target, false)
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AstraColors.NeonCyan, contentColor = Color(0xFF041320))
                    ) {
                        Text(
                            if (selectedChartReady) "修改当前已有谱面" else "先自动生成基础谱面再修改",
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            showEditorChoiceDialog = false
                            onOpenEditor(target, true)
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.2.dp, AstraColors.StarlightGold)
                    ) {
                        Text("从零开始新建空白谱面", color = AstraColors.StarlightGold, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showEditorChoiceDialog = false }) {
                    Text("取消", color = AstraColors.TextSecondary)
                }
            }
        )
    }

    if (showPackDialog) {
        var checkedIds by remember(songs) {
            mutableStateOf(
                songs.filter { !it.isBuiltIn }.map { it.id }.toSet()
                    .ifEmpty { selectedSong?.let { setOf(it.id) } ?: emptySet() }
            )
        }
        AlertDialog(
            onDismissRequest = { showPackDialog = false },
            containerColor = AstraColors.DeepIndigo,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("曲包打包导出 / 导入 (.zip)", color = AstraColors.StarlightGold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    TextButton(
                        onClick = {
                            checkedIds = if (checkedIds.size == songs.size) emptySet() else songs.map { it.id }.toSet()
                        }
                    ) {
                        Text(if (checkedIds.size == songs.size) "取消全选" else "全选", color = AstraColors.NeonCyan, fontSize = 12.sp)
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "勾选多首歌曲，连同原音频与全部已保存谱面打包为一个 .zip 文件；换设备直接导入即可开玩。",
                        color = AstraColors.TextSecondary,
                        fontSize = 11.sp
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(songs, key = { it.id }) { s ->
                            val isChecked = s.id in checkedIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isChecked) Color(0x3338F2FF) else Color(0x22FFFFFF))
                                    .clickable {
                                        checkedIds = if (isChecked) checkedIds - s.id else checkedIds + s.id
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { c ->
                                        checkedIds = if (c) checkedIds + s.id else checkedIds - s.id
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = AstraColors.NeonCyan, checkmarkColor = Color(0xFF041320))
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(s.title, color = AstraColors.TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (s.isBuiltIn) "内置乐章" else "导入歌曲", color = AstraColors.TextSecondary, fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val chosen = songs.filter { it.id in checkedIds }
                                if (chosen.isNotEmpty()) {
                                    showPackDialog = false
                                    onExportSongsZip(chosen)
                                }
                            },
                            enabled = checkedIds.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AstraColors.NeonCyan, contentColor = Color(0xFF041320))
                        ) {
                            Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("打包导出 (${checkedIds.size}首)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                showPackDialog = false
                                onImportPackFile()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AstraColors.StarlightGold, contentColor = Color(0xFF041320))
                        ) {
                            Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("导入曲包/谱面", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPackDialog = false }) {
                    Text("关闭", color = AstraColors.TextSecondary)
                }
            }
        )
    }

    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            containerColor = AstraColors.DeepIndigo,
            title = {
                Text("抖音 / 音视频链接导入", color = AstraColors.NeonCyan, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "直接粘贴抖音分享口令（自动提取短链）或音视频直链，支持多行批量后台导入与自动编谱。",
                        color = AstraColors.TextSecondary,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = linkInputText,
                        onValueChange = { linkInputText = it },
                        minLines = 3,
                        maxLines = 5,
                        placeholder = { Text("粘贴抖音分享文案或 https://v.douyin.com/... 链接", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            val clip = clipboard.getText()?.text.orEmpty()
                            if (clip.isNotBlank()) linkInputText = clip
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AstraColors.GlassBorder)
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp), tint = AstraColors.NeonCyan)
                        Spacer(Modifier.width(6.dp))
                        Text("从剪贴板一键粘贴", color = AstraColors.TextPrimary, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val txt = linkInputText.trim()
                        if (txt.isNotEmpty()) {
                            onImportLinks(txt)
                            linkInputText = ""
                        }
                        showLinkDialog = false
                    }
                ) {
                    Text("开始后台导入", color = AstraColors.NeonCyan, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) {
                    Text("取消", color = AstraColors.TextSecondary)
                }
            }
        )
    }

    val targetRename = renamingSong
    if (targetRename != null) {
        AlertDialog(
            onDismissRequest = { renamingSong = null },
            containerColor = AstraColors.DeepIndigo,
            title = {
                Text("重命名导入歌曲", color = AstraColors.NeonCyan, fontWeight = FontWeight.Bold)
            },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("曲目名称") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotEmpty()) {
                            onRenameSong(targetRename, trimmed)
                        }
                        renamingSong = null
                    }
                ) {
                    Text("保存", color = AstraColors.NeonCyan, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingSong = null }) {
                    Text("取消", color = AstraColors.TextSecondary)
                }
            }
        )
    }
}
