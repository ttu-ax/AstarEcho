package com.astraecho.rhythm.ui.screens

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astraecho.rhythm.model.BeatmapChart
import com.astraecho.rhythm.model.ChartNote
import com.astraecho.rhythm.model.NoteSkin
import com.astraecho.rhythm.model.NoteType
import com.astraecho.rhythm.model.PlayerSettings
import com.astraecho.rhythm.model.SlidePoint
import com.astraecho.rhythm.model.SongItem
import com.astraecho.rhythm.ui.theme.AstraColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

enum class EditorTool(val label: String, val shortHint: String) {
    TAP("点按", "点击网格放置/选中单键"),
    HOLD("长条", "在轨道上按住向上拖动拉出长条"),
    SLIDE("跨轨滑键", "在网格上横向跨轨拖动生成滑键"),
    HOLD_SLIDE("长按滑键", "向上并跨轨拖动绘制折线长按滑键"),
    ERASE("橡皮擦", "点击或划过音符直接删除"),
    SCRUB("拖时间轴", "上下滑动网格浏览时间轴")
}

/**
 * Mobile-first landscape Beatmap Editor:
 * - Supports creating charts from scratch ("从零清空") or modifying auto-generated charts
 * - Visual beat-grid timeline with direct touch placement, drag-to-hold, drag-to-slide, and erase
 * - Live Thumb-Tap Recording ("跟拍录制"): tap, hold, or slide on the bottom lane pads while listening (at 1.0x / 0.75x / 0.5x!)
 * - Selected Note Inspector for +/-10ms timing nudges, lane shifts, duration & slide target edits
 * - 60-step Undo/Redo, Test-Play in 3D perspective stage, Save, and File/Clipboard Import & Export!
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChartEditorScreen(
    song: SongItem,
    initialChart: BeatmapChart,
    settings: PlayerSettings,
    statusBanner: String?,
    onSaveChart: (BeatmapChart) -> Unit,
    onTestPlayChart: (BeatmapChart) -> Unit,
    onRegenerateAutoChart: () -> Unit,
    onExportChartFile: (BeatmapChart) -> Unit,
    onImportChartFile: () -> Unit,
    onImportChartJsonText: (String) -> Unit,
    onBack: () -> Unit
) {
    val skin = settings.skin
    val accent = Color(skin.railColor)
    val lanes = initialChart.laneCount.coerceIn(4, 6)
    val durationMs = max(initialChart.durationMs, song.durationMs).coerceAtLeast(10_000L)
    val clipboard = LocalClipboardManager.current

    var bpm by remember(initialChart.songId) { mutableIntStateOf(initialChart.bpm.coerceIn(60, 240)) }
    val notes = remember(initialChart) {
        mutableStateListOf<ChartNote>().apply { addAll(initialChart.notes.sortedBy { it.timeMs }) }
    }
    var nextNoteId by remember(initialChart) {
        mutableIntStateOf((initialChart.notes.maxOfOrNull { it.id } ?: 0) + 1)
    }

    // Undo / Redo stacks
    val undoStack = remember { mutableStateListOf<List<ChartNote>>() }
    val redoStack = remember { mutableStateListOf<List<ChartNote>>() }

    fun pushUndoSnapshot() {
        if (undoStack.size >= 50) undoStack.removeAt(0)
        undoStack.add(notes.toList())
        redoStack.clear()
    }

    fun performUndo() {
        if (undoStack.isEmpty()) return
        redoStack.add(notes.toList())
        val prev = undoStack.removeAt(undoStack.lastIndex)
        notes.clear()
        notes.addAll(prev)
    }

    fun performRedo() {
        if (redoStack.isEmpty()) return
        undoStack.add(notes.toList())
        val next = redoStack.removeAt(redoStack.lastIndex)
        notes.clear()
        notes.addAll(next)
    }

    var selectedNoteId by remember { mutableStateOf<Int?>(null) }
    val selectedNote = notes.firstOrNull { it.id == selectedNoteId }

    var activeTool by remember { mutableStateOf(EditorTool.TAP) }
    var isLiveRecording by remember { mutableStateOf(false) }
    var snapDivision by remember { mutableIntStateOf(4) } // 1, 2, 4, 8, 16, or 0 (free)
    var visibleWindowMs by remember { mutableLongStateOf(2200L) } // Zoom level: 1400ms, 2200ms, 3400ms
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    var currentTimeMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }

    var showClearConfirm by remember { mutableStateOf(false) }
    var showAutoGenConfirm by remember { mutableStateOf(false) }
    var showImportExportDialog by remember { mutableStateOf(false) }
    var jsonTransferText by remember { mutableStateOf("") }
    var toastHint by remember { mutableStateOf<String?>(null) }

    fun buildCurrentChart(): BeatmapChart {
        val sorted = notes.sortedWith(compareBy<ChartNote> { it.timeMs }.thenBy { it.lane })
            .mapIndexed { idx, n -> n.copy(id = idx + 1) }
        return initialChart.copy(
            bpm = bpm,
            durationMs = durationMs,
            laneCount = lanes,
            notes = sorted
        )
    }

    val beatMs: Double = 60_000.0 / bpm.coerceAtLeast(40)

    fun snapTime(rawMs: Long): Long {
        val clamped = rawMs.coerceIn(60L, durationMs - 150L)
        if (snapDivision <= 0) return clamped
        val stepMs = beatMs / snapDivision
        return ((clamped / stepMs).roundToLong() * stepMs).toLong().coerceIn(60L, durationMs - 150L)
    }

    // Prepare MediaPlayer
    DisposableEffect(song.audioPath) {
        val mp = runCatching {
            MediaPlayer().apply {
                setDataSource(song.audioPath)
                prepare()
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

    fun applyPlaybackSpeed(mp: MediaPlayer, speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                mp.playbackParams = PlaybackParams().setSpeed(speed)
            }
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayerRef ?: return
        if (isPlaying) {
            runCatching { if (mp.isPlaying) mp.pause() }
            isPlaying = false
        } else {
            runCatching {
                if (currentTimeMs >= durationMs - 200L) currentTimeMs = 0L
                mp.seekTo(currentTimeMs.toInt())
                applyPlaybackSpeed(mp, playbackSpeed)
                if (!mp.isPlaying) mp.start()
                isPlaying = true
            }
        }
    }

    fun seekToMs(targetMs: Long) {
        val clamped = targetMs.coerceIn(0L, durationMs)
        currentTimeMs = clamped
        val mp = mediaPlayerRef
        if (mp != null && isPlaying) {
            runCatching { mp.seekTo(clamped.toInt()) }
        }
    }

    // Smooth 60-120fps playhead sync while audio plays
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            withFrameNanos {
                val mp = mediaPlayerRef
                if (mp != null && runCatching { mp.isPlaying }.getOrDefault(false)) {
                    currentTimeMs = mp.currentPosition.toLong().coerceIn(0L, durationMs)
                    if (currentTimeMs >= durationMs - 80L) {
                        isPlaying = false
                        runCatching { mp.pause() }
                    }
                } else {
                    isPlaying = false
                }
            }
        }
    }

    BackHandler {
        if (isPlaying) togglePlayPause()
        onBack()
    }

    // Live Recording active pointer state per pointerId
    data class LiveRecTouch(
        val startLane: Int,
        val startTimeMs: Long,
        var currentLane: Int,
        val waypoints: MutableList<SlidePoint> = mutableListOf()
    )
    val liveRecPointers = remember { mutableStateMapOf<Int, LiveRecTouch>() }
    var recPadWidthPx by remember { mutableFloatStateOf(1f) }

    // Canvas gesture scratch state for drag-creating HOLD / SLIDE / HOLD_SLIDE on timeline
    var dragStartLane by remember { mutableIntStateOf(-1) }
    var dragStartTimeMs by remember { mutableLongStateOf(-1L) }
    var dragCurLane by remember { mutableIntStateOf(-1) }
    var dragCurTimeMs by remember { mutableLongStateOf(-1L) }
    val dragWaypoints = remember { mutableStateListOf<SlidePoint>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AstraColors.VoidBg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        if (isPlaying) togglePlayPause()
                        onBack()
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = AstraColors.TextPrimary)
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "谱面编辑器 · ${song.title}",
                            color = AstraColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            color = accent.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = "${lanes}K · ${initialChart.difficulty.tag} · ${notes.size} 音符",
                                color = accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = toastHint ?: statusBanner ?: activeTool.shortHint,
                        color = if (toastHint != null) AstraColors.StarlightGold else AstraColors.TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }

            // Top Right Action Buttons: Undo / Redo / Import-Export / Test Play / Save
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = ::performUndo,
                    enabled = undoStack.isNotEmpty(),
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Undo,
                        contentDescription = "Undo",
                        tint = if (undoStack.isNotEmpty()) AstraColors.TextPrimary else Color(0x44FFFFFF)
                    )
                }
                IconButton(
                    onClick = ::performRedo,
                    enabled = redoStack.isNotEmpty(),
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Redo,
                        contentDescription = "Redo",
                        tint = if (redoStack.isNotEmpty()) AstraColors.TextPrimary else Color(0x44FFFFFF)
                    )
                }

                Surface(
                    onClick = { showImportExportDialog = true },
                    color = Color(0x331B2A4E),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, AstraColors.GlassBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = null, tint = AstraColors.NeonCyan, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导入/导出", color = AstraColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Surface(
                    onClick = {
                        if (isPlaying) togglePlayPause()
                        val chart = buildCurrentChart()
                        onSaveChart(chart)
                        onTestPlayChart(chart)
                    },
                    color = Color(0x33FFD166),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, AstraColors.StarlightGold)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = AstraColors.StarlightGold, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("试玩谱面", color = AstraColors.StarlightGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = {
                        val chart = buildCurrentChart()
                        onSaveChart(chart)
                        toastHint = "已保存谱面 (${chart.notes.size} 音符)"
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF041320))
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("保存", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Main 3-Column Landscape Editor Workspace
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ================= LEFT PANEL: Transport, Time Scrub, Snap & BPM =================
            Surface(
                color = AstraColors.GlassCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, AstraColors.GlassBorder),
                modifier = Modifier
                    .weight(0.28f)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Time readout + Play/Pause button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = formatEditorTimeMs(currentTimeMs),
                                color = AstraColors.TextPrimary,
                                fontSize = 18.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "/ ${formatEditorTimeMs(durationMs)}",
                                color = AstraColors.TextSecondary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Button(
                            onClick = ::togglePlayPause,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPlaying) AstraColors.ElectricMagenta else accent,
                                contentColor = if (isPlaying) Color.White else Color(0xFF041320)
                            )
                        ) {
                            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(3.dp))
                            Text(if (isPlaying) "暂停" else "播放", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Song position slider
                    Slider(
                        value = currentTimeMs.toFloat(),
                        onValueChange = { seekToMs(it.toLong()) },
                        valueRange = 0f..durationMs.toFloat(),
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
                    )

                    // Quick Beat Step buttons (-4拍, -1拍, +1拍, +4拍)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(-4 to "-4拍", -1 to "-1拍", 1 to "+1拍", 4 to "+4拍").forEach { (beats, label) ->
                            Surface(
                                onClick = { seekToMs(currentTimeMs + (beats * beatMs).toLong()) },
                                modifier = Modifier.weight(1f),
                                color = Color(0x331B2A4E),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0x33FFFFFF))
                            ) {
                                Text(
                                    text = label,
                                    color = AstraColors.TextPrimary,
                                    fontSize = 10.sp,
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                    }

                    // Playback Speed (1.0x / 0.75x / 0.5x for easy mobile live recording!)
                    Text("播放倍速（慢速更易跟拍录谱）", color = AstraColors.TextSecondary, fontSize = 10.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        listOf(0.5f to "0.5x", 0.75f to "0.75x", 1.0f to "1.0x").forEach { (spd, label) ->
                            val sel = abs(playbackSpeed - spd) < 0.05f
                            Surface(
                                onClick = {
                                    playbackSpeed = spd
                                    mediaPlayerRef?.let { if (isPlaying) applyPlaybackSpeed(it, spd) }
                                },
                                modifier = Modifier.weight(1f),
                                color = if (sel) AstraColors.StarlightGold.copy(alpha = 0.25f) else Color(0x281B2A4E),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, if (sel) AstraColors.StarlightGold else Color(0x33FFFFFF))
                            ) {
                                Text(
                                    text = label,
                                    color = if (sel) AstraColors.StarlightGold else AstraColors.TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 5.dp)
                                )
                            }
                        }
                    }

                    // Beat Grid Snap Division
                    Text("节拍网格吸附", color = AstraColors.TextSecondary, fontSize = 10.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(2 to "1/2", 4 to "1/4", 8 to "1/8", 16 to "1/16", 0 to "自由").forEach { (div, label) ->
                            val sel = snapDivision == div
                            Surface(
                                onClick = { snapDivision = div },
                                modifier = Modifier.weight(1f),
                                color = if (sel) accent.copy(alpha = 0.25f) else Color(0x281B2A4E),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, if (sel) accent else Color(0x33FFFFFF))
                            ) {
                                Text(
                                    text = label,
                                    color = if (sel) accent else AstraColors.TextPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 5.dp)
                                )
                            }
                        }
                    }

                    // BPM & Timeline Zoom
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("BPM: $bpm", color = AstraColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SmallActionChip("-1") { bpm = (bpm - 1).coerceIn(60, 240) }
                            SmallActionChip("+1") { bpm = (bpm + 1).coerceIn(60, 240) }
                            SmallActionChip(
                                when (visibleWindowMs) {
                                    1400L -> "视野:近"
                                    2200L -> "视野:中"
                                    else -> "视野:远"
                                }
                            ) {
                                visibleWindowMs = when (visibleWindowMs) {
                                    2200L -> 1400L
                                    1400L -> 3400L
                                    else -> 2200L
                                }
                            }
                        }
                    }

                    // Template Actions: Clear from Scratch vs Auto-Chart Base
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showClearConfirm = true },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AstraColors.ElectricMagenta)
                        ) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = AstraColors.ElectricMagenta, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("从零清空", color = AstraColors.ElectricMagenta, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { showAutoGenConfirm = true },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AstraColors.NeonCyan)
                        ) {
                            Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, tint = AstraColors.NeonCyan, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("自动生成", color = AstraColors.NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ================= CENTER PANEL: Visual Timeline Grid + Bottom Live Record Keys =================
            Column(
                modifier = Modifier
                    .weight(0.44f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF050814))
                    .border(1.2.dp, accent.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
            ) {
                // Visual Timeline Canvas (Upper 78%)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.78f)
                ) {
                    val pastMs = (visibleWindowMs * 0.18f).toLong()
                    val futureMs = visibleWindowMs - pastMs

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(activeTool, lanes, visibleWindowMs, snapDivision, bpm, initialChart.onlyTapAndHold) {
                                detectTapGestures { tapOffset ->
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val rulerW = w * 0.12f
                                    if (tapOffset.x < rulerW) return@detectTapGestures

                                    val laneW = (w - rulerW) / lanes
                                    val tappedLane = ((tapOffset.x - rulerW) / laneW).toInt().coerceIn(0, lanes - 1)
                                    val playheadY = h * 0.82f
                                    val clickedTimeMs = (currentTimeMs + ((playheadY - tapOffset.y) / playheadY) * futureMs).toLong()
                                        .coerceIn(60L, durationMs - 150L)

                                    // Check if user tapped an existing note
                                    val hitExisting = notes.firstOrNull { n ->
                                        val timeMatch = if (n.type == NoteType.HOLD || n.type == NoteType.HOLD_SLIDE) {
                                            clickedTimeMs in (n.timeMs - 85L)..(n.endTimeMs + 85L)
                                        } else {
                                            abs(n.timeMs - clickedTimeMs) <= 95L
                                        }
                                        timeMatch && (n.lane == tappedLane || (n.type == NoteType.SLIDE && tappedLane in min(n.lane, n.slideTargetLane)..max(n.lane, n.slideTargetLane)))
                                    }

                                    if (activeTool == EditorTool.ERASE) {
                                        if (hitExisting != null) {
                                            pushUndoSnapshot()
                                            notes.remove(hitExisting)
                                            if (selectedNoteId == hitExisting.id) selectedNoteId = null
                                        }
                                        return@detectTapGestures
                                    }

                                    if (hitExisting != null) {
                                        if (selectedNoteId == hitExisting.id && activeTool == EditorTool.TAP) {
                                            // Tapping an already-selected note again in TAP mode deletes it quickly!
                                            pushUndoSnapshot()
                                            notes.remove(hitExisting)
                                            selectedNoteId = null
                                            toastHint = "已删除音符"
                                        } else {
                                            selectedNoteId = hitExisting.id
                                            toastHint = "已选中音符（可在右侧微调，再次点击删除）"
                                        }
                                        return@detectTapGestures
                                    }

                                    if (activeTool == EditorTool.SCRUB) return@detectTapGestures

                                    val snappedT = snapTime(clickedTimeMs)
                                    pushUndoSnapshot()
                                    val newId = nextNoteId++
                                    val created = when (activeTool) {
                                        EditorTool.TAP -> ChartNote(newId, snappedT, tappedLane, NoteType.TAP)
                                        EditorTool.HOLD -> ChartNote(
                                            newId, snappedT, tappedLane, NoteType.HOLD,
                                            durationMs = max(360L, beatMs.toLong()),
                                            slideTargetLane = tappedLane
                                        )
                                        EditorTool.SLIDE -> {
                                            if (initialChart.onlyTapAndHold) {
                                                ChartNote(newId, snappedT, tappedLane, NoteType.TAP)
                                            } else {
                                                val target = if (tappedLane < lanes - 1) tappedLane + 1 else tappedLane - 1
                                                ChartNote(newId, snappedT, tappedLane, NoteType.SLIDE, slideTargetLane = target)
                                            }
                                        }
                                        EditorTool.HOLD_SLIDE -> {
                                            if (initialChart.onlyTapAndHold) {
                                                ChartNote(newId, snappedT, tappedLane, NoteType.HOLD, durationMs = max(460L, beatMs.toLong()))
                                            } else {
                                                val target = if (tappedLane < lanes - 1) tappedLane + 1 else tappedLane - 1
                                                val dur = max(560L, (beatMs * 1.5).toLong())
                                                val mid = snappedT + dur / 2
                                                ChartNote(
                                                    id = newId,
                                                    timeMs = snappedT,
                                                    lane = tappedLane,
                                                    type = NoteType.HOLD_SLIDE,
                                                    durationMs = dur,
                                                    slideTargetLane = target,
                                                    pathPoints = listOf(
                                                        SlidePoint(snappedT, tappedLane),
                                                        SlidePoint(mid, tappedLane),
                                                        SlidePoint(mid + 70L, target),
                                                        SlidePoint(snappedT + dur, target)
                                                    )
                                                )
                                            }
                                        }
                                        else -> return@detectTapGestures
                                    }
                                    notes.add(created)
                                    selectedNoteId = created.id
                                }
                            }
                            .pointerInput(activeTool, lanes, visibleWindowMs, snapDivision, bpm, initialChart.onlyTapAndHold) {
                                detectDragGestures(
                                    onDragStart = { startOffset ->
                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        val rulerW = w * 0.12f
                                        val playheadY = h * 0.82f

                                        if (startOffset.x < rulerW || activeTool == EditorTool.SCRUB) {
                                            dragStartLane = -2 // Timeline scrub mode
                                            return@detectDragGestures
                                        }

                                        val laneW = (w - rulerW) / lanes
                                        val lane = ((startOffset.x - rulerW) / laneW).toInt().coerceIn(0, lanes - 1)
                                        val tMs = snapTime(
                                            (currentTimeMs + ((playheadY - startOffset.y) / playheadY) * futureMs).toLong()
                                        )
                                        dragStartLane = lane
                                        dragStartTimeMs = tMs
                                        dragCurLane = lane
                                        dragCurTimeMs = tMs
                                        dragWaypoints.clear()
                                        dragWaypoints.add(SlidePoint(tMs, lane))
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        val rulerW = w * 0.12f
                                        val playheadY = h * 0.82f

                                        if (dragStartLane == -2) {
                                            // Drag down -> advance time; drag up -> rewind time
                                            val deltaMs = ((dragAmount.y / playheadY) * futureMs).toLong()
                                            seekToMs(currentTimeMs + deltaMs)
                                            return@detectDragGestures
                                        }

                                        val laneW = (w - rulerW) / lanes
                                        val lane = ((change.position.x - rulerW) / laneW).toInt().coerceIn(0, lanes - 1)
                                        val tMs = snapTime(
                                            (currentTimeMs + ((playheadY - change.position.y) / playheadY) * futureMs).toLong()
                                        )

                                        if (activeTool == EditorTool.ERASE) {
                                            val hit = notes.firstOrNull { n ->
                                                abs(n.timeMs - tMs) <= 95L && n.lane == lane
                                            }
                                            if (hit != null) {
                                                pushUndoSnapshot()
                                                notes.remove(hit)
                                            }
                                            return@detectDragGestures
                                        }

                                        dragCurLane = lane
                                        dragCurTimeMs = max(dragStartTimeMs, tMs)
                                        if (activeTool == EditorTool.HOLD_SLIDE && dragCurTimeMs > dragStartTimeMs) {
                                            val lastPt = dragWaypoints.lastOrNull()
                                            if (lastPt == null || lastPt.lane != lane) {
                                                val bendT = max((lastPt?.timeMs ?: dragStartTimeMs) + 60L, dragCurTimeMs)
                                                if (lastPt != null && bendT > lastPt.timeMs + 70L) {
                                                    dragWaypoints.add(SlidePoint(bendT - 60L, lastPt.lane))
                                                }
                                                dragWaypoints.add(SlidePoint(bendT, lane))
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        if (dragStartLane >= 0) {
                                            val sLane = dragStartLane
                                            val eLane = dragCurLane.coerceIn(0, lanes - 1)
                                            val sTime = dragStartTimeMs
                                            val eTime = max(sTime, dragCurTimeMs)
                                            val dur = eTime - sTime

                                            when (activeTool) {
                                                EditorTool.HOLD -> if (dur >= 180L) {
                                                    pushUndoSnapshot()
                                                    val n = ChartNote(nextNoteId++, sTime, sLane, NoteType.HOLD, dur.coerceAtLeast(280L), sLane)
                                                    notes.add(n)
                                                    selectedNoteId = n.id
                                                }
                                                EditorTool.SLIDE -> if (!initialChart.onlyTapAndHold && eLane != sLane) {
                                                    pushUndoSnapshot()
                                                    val n = ChartNote(nextNoteId++, sTime, sLane, NoteType.SLIDE, 0L, eLane)
                                                    notes.add(n)
                                                    selectedNoteId = n.id
                                                }
                                                EditorTool.HOLD_SLIDE -> if (!initialChart.onlyTapAndHold && dur >= 280L) {
                                                    pushUndoSnapshot()
                                                    val pts = dragWaypoints.toMutableList()
                                                    if (pts.last().timeMs < eTime) {
                                                        pts.add(SlidePoint(eTime, eLane))
                                                    }
                                                    val n = if (pts.size >= 3 && pts.any { it.lane != sLane }) {
                                                        ChartNote(nextNoteId++, sTime, sLane, NoteType.HOLD_SLIDE, eTime - sTime, pts.last().lane, pts)
                                                    } else {
                                                        ChartNote(nextNoteId++, sTime, sLane, NoteType.HOLD, dur.coerceAtLeast(320L), sLane)
                                                    }
                                                    notes.add(n)
                                                    selectedNoteId = n.id
                                                }
                                                else -> Unit
                                            }
                                        }
                                        dragStartLane = -1
                                        dragWaypoints.clear()
                                    },
                                    onDragCancel = {
                                        dragStartLane = -1
                                        dragWaypoints.clear()
                                    }
                                )
                            }
                    ) {
                        val w = size.width
                        val h = size.height
                        val rulerW = w * 0.12f
                        val trackW = w - rulerW
                        val laneW = trackW / lanes
                        val playheadY = h * 0.82f

                        fun timeToY(tMs: Long): Float {
                            val dt = (tMs - currentTimeMs).toFloat()
                            return playheadY - (dt / futureMs.toFloat()) * playheadY
                        }

                        // Left Time Ruler strip (always draggable to scrub time!)
                        drawRect(color = Color(0xFF0A1024), topLeft = Offset.Zero, size = Size(rulerW, h))
                        drawLine(Color(0x4438F2FF), Offset(rulerW, 0f), Offset(rulerW, h), strokeWidth = 1.5f)

                        // Lane dividers
                        for (i in 1 until lanes) {
                            val lx = rulerW + i * laneW
                            drawLine(Color(0x3038F2FF), Offset(lx, 0f), Offset(lx, h), strokeWidth = 1.5f)
                        }

                        // Beat & Sub-beat horizontal grid lines
                        val minVisMs = (currentTimeMs - pastMs).coerceAtLeast(0L)
                        val maxVisMs = currentTimeMs + futureMs
                        val subDiv = if (snapDivision > 0) snapDivision else 4
                        val subStepMs = beatMs / subDiv
                        val firstStepIdx = (minVisMs / subStepMs).toLong()
                        val lastStepIdx = (maxVisMs / subStepMs).toLong() + 1L

                        for (stepIdx in firstStepIdx..lastStepIdx) {
                            val tMs = (stepIdx * subStepMs).toLong()
                            if (tMs < 0L || tMs > durationMs) continue
                            val y = timeToY(tMs)
                            if (y < 0f || y > h) continue
                            val isMajorBeat = (stepIdx % subDiv) == 0L
                            val isBarBeat = (stepIdx % (subDiv * 4)) == 0L
                            drawLine(
                                color = when {
                                    isBarBeat -> AstraColors.StarlightGold.copy(alpha = 0.55f)
                                    isMajorBeat -> Color(0x6638F2FF)
                                    else -> Color(0x22FFFFFF)
                                },
                                start = Offset(rulerW, y),
                                end = Offset(w, y),
                                strokeWidth = if (isBarBeat) 2.5f else if (isMajorBeat) 1.6f else 1f
                            )
                            if (isMajorBeat) {
                                drawLine(
                                    color = AstraColors.NeonCyan,
                                    start = Offset(rulerW - 10f, y),
                                    end = Offset(rulerW, y),
                                    strokeWidth = 2f
                                )
                            }
                        }

                        // Draw visible notes
                        for (n in notes) {
                            if (n.endTimeMs < minVisMs - 200L || n.timeMs > maxVisMs + 200L) continue
                            val isSel = (n.id == selectedNoteId)
                            val yStart = timeToY(n.timeMs)
                            val xLeft = rulerW + n.lane * laneW + 5f
                            val xRight = rulerW + (n.lane + 1) * laneW - 5f
                            val xMid = (xLeft + xRight) * 0.5f

                            when (n.type) {
                                NoteType.HOLD -> {
                                    val yEnd = timeToY(n.endTimeMs)
                                    drawRoundRect(
                                        color = Color(skin.holdBody).copy(alpha = 0.55f),
                                        topLeft = Offset(xLeft + laneW * 0.14f, min(yEnd, yStart)),
                                        size = Size((xRight - xLeft) - laneW * 0.28f, abs(yStart - yEnd).coerceAtLeast(8f)),
                                        cornerRadius = CornerRadius(6f, 6f)
                                    )
                                }
                                NoteType.HOLD_SLIDE -> {
                                    if (n.pathPoints.size >= 2) {
                                        val railPath = Path()
                                        n.pathPoints.forEachIndexed { idx, pt ->
                                            val px = rulerW + (pt.lane + 0.5f) * laneW
                                            val py = timeToY(pt.timeMs)
                                            if (idx == 0) railPath.moveTo(px, py) else railPath.lineTo(px, py)
                                        }
                                        drawPath(railPath, color = Color(skin.slideMain).copy(alpha = 0.65f), style = Stroke(width = laneW * 0.22f))
                                        drawPath(railPath, color = Color(skin.slideHighlight), style = Stroke(width = 4f))
                                    }
                                }
                                NoteType.SLIDE -> {
                                    val targetMid = rulerW + (n.slideTargetLane + 0.5f) * laneW
                                    drawLine(
                                        color = Color(skin.slideHighlight),
                                        start = Offset(xMid, yStart),
                                        end = Offset(targetMid, yStart),
                                        strokeWidth = 6f
                                    )
                                    // Arrowhead at targetMid
                                    val dir = if (n.slideTargetLane >= n.lane) 1f else -1f
                                    drawLine(Color(skin.slideHighlight), Offset(targetMid, yStart), Offset(targetMid - 12f * dir, yStart - 9f), strokeWidth = 4f)
                                    drawLine(Color(skin.slideHighlight), Offset(targetMid, yStart), Offset(targetMid - 12f * dir, yStart + 9f), strokeWidth = 4f)
                                }
                                else -> Unit
                            }

                            val headColor = when (n.type) {
                                NoteType.TAP -> Color(skin.tapMain)
                                NoteType.HOLD -> Color(skin.holdMain)
                                NoteType.SLIDE, NoteType.HOLD_SLIDE -> Color(skin.slideMain)
                            }
                            drawRoundRect(
                                color = headColor,
                                topLeft = Offset(xLeft, yStart - 9f),
                                size = Size(xRight - xLeft, 18f),
                                cornerRadius = CornerRadius(7f, 7f)
                            )
                            if (isSel) {
                                drawRoundRect(
                                    color = Color.White,
                                    topLeft = Offset(xLeft - 3f, yStart - 12f),
                                    size = Size(xRight - xLeft + 6f, 24f),
                                    cornerRadius = CornerRadius(9f, 9f),
                                    style = Stroke(width = 3f)
                                )
                            }
                        }

                        // In-progress drag preview for HOLD / SLIDE / HOLD_SLIDE
                        if (dragStartLane >= 0) {
                            val y0 = timeToY(dragStartTimeMs)
                            val y1 = timeToY(dragCurTimeMs)
                            val x0 = rulerW + (dragStartLane + 0.5f) * laneW
                            val x1 = rulerW + (dragCurLane + 0.5f) * laneW
                            drawLine(Color.White.copy(alpha = 0.8f), Offset(x0, y0), Offset(x1, y1), strokeWidth = 5f)
                        }

                        // Glowing Playhead Line
                        drawLine(
                            color = if (isLiveRecording) AstraColors.ElectricMagenta else Color.White,
                            start = Offset(0f, playheadY),
                            end = Offset(w, playheadY),
                            strokeWidth = 4.5f
                        )
                    }

                    Text(
                        text = "时间轴\n上下拖",
                        color = AstraColors.TextSecondary,
                        fontSize = 8.sp,
                        lineHeight = 10.sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                    )
                }

                // Bottom Tactile Live-Record / Preview Pads (Lower 22%)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.22f)
                        .background(Color(0xFF0A1024))
                        .onSizeChanged { recPadWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                        .pointerInteropFilter { ev ->
                            val laneW = recPadWidthPx / lanes
                            when (ev.actionMasked) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                                    val idx = ev.actionIndex
                                    val pid = ev.getPointerId(idx)
                                    val lane = (ev.getX(idx) / laneW).toInt().coerceIn(0, lanes - 1)
                                    val tStart = snapTime(currentTimeMs)
                                    liveRecPointers[pid] = LiveRecTouch(
                                        startLane = lane,
                                        startTimeMs = tStart,
                                        currentLane = lane,
                                        waypoints = mutableListOf(SlidePoint(tStart, lane))
                                    )
                                }

                                MotionEvent.ACTION_MOVE -> {
                                    for (i in 0 until ev.pointerCount) {
                                        val pid = ev.getPointerId(i)
                                        val state = liveRecPointers[pid] ?: continue
                                        val newLane = (ev.getX(i) / laneW).toInt().coerceIn(0, lanes - 1)
                                        if (newLane != state.currentLane) {
                                            val nowT = snapTime(currentTimeMs)
                                            if (nowT > state.startTimeMs + 140L) {
                                                state.waypoints.add(SlidePoint(nowT, state.currentLane))
                                                state.waypoints.add(SlidePoint(nowT + 60L, newLane))
                                            }
                                            state.currentLane = newLane
                                        }
                                    }
                                }

                                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                                    val idx = ev.actionIndex
                                    val pid = ev.getPointerId(idx)
                                    val state = liveRecPointers.remove(pid)
                                    if (state != null) {
                                        val endT = snapTime(currentTimeMs)
                                        val dur = (endT - state.startTimeMs).coerceAtLeast(0L)
                                        val movedLane = state.currentLane != state.startLane || state.waypoints.any { it.lane != state.startLane }

                                        pushUndoSnapshot()
                                        val newId = nextNoteId++
                                        val recorded = when {
                                            dur >= 280L && movedLane && !initialChart.onlyTapAndHold -> {
                                                val pts = state.waypoints.toMutableList()
                                                if (pts.last().timeMs < endT) pts.add(SlidePoint(endT, state.currentLane))
                                                ChartNote(newId, state.startTimeMs, state.startLane, NoteType.HOLD_SLIDE, endT - state.startTimeMs, state.currentLane, pts)
                                            }
                                            dur >= 280L -> {
                                                ChartNote(newId, state.startTimeMs, state.startLane, NoteType.HOLD, dur, state.startLane)
                                            }
                                            movedLane && !initialChart.onlyTapAndHold -> {
                                                ChartNote(newId, state.startTimeMs, state.startLane, NoteType.SLIDE, 0L, state.currentLane)
                                            }
                                            else -> {
                                                ChartNote(newId, state.startTimeMs, state.startLane, NoteType.TAP, 0L, state.startLane)
                                            }
                                        }
                                        notes.add(recorded)
                                        selectedNoteId = recorded.id
                                    }
                                }
                            }
                            true
                        },
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val activeLanes = liveRecPointers.values.map { it.currentLane }.toSet()
                    for (lane in 0 until lanes) {
                        val isPressed = lane in activeLanes
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(3.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isPressed) accent.copy(alpha = 0.55f)
                                    else if (isLiveRecording) Color(0x33FF3B8D)
                                    else Color(0x33162447)
                                )
                                .border(
                                    width = if (isPressed) 2.dp else 1.dp,
                                    color = if (isPressed) Color.White else if (isLiveRecording) AstraColors.ElectricMagenta else accent.copy(alpha = 0.45f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${lane + 1} 轨",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "点/长按/滑",
                                    color = AstraColors.TextSecondary,
                                    fontSize = 8.sp
                                )
                            }
                        }
                    }
                }
            }

            // ================= RIGHT PANEL: Tools, Live-Rec Toggle & Selected Note Inspector =================
            Surface(
                color = AstraColors.GlassCard,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, AstraColors.GlassBorder),
                modifier = Modifier
                    .weight(0.28f)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Live Recording Quick Toggle
                    Surface(
                        onClick = {
                            isLiveRecording = !isLiveRecording
                            if (isLiveRecording && !isPlaying) togglePlayPause()
                        },
                        color = if (isLiveRecording) Color(0x44FF3B8D) else Color(0x281B2A4E),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.5.dp, if (isLiveRecording) AstraColors.ElectricMagenta else AstraColors.GlassBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Rounded.FiberManualRecord,
                                contentDescription = null,
                                tint = if (isLiveRecording) AstraColors.ElectricMagenta else AstraColors.TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isLiveRecording) "正在跟拍录制（敲击下方琴键）" else "开启实时跟拍录制",
                                color = AstraColors.TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // 6 Grid Editing Tools (2x3 Grid)
                    Text("网格画笔工具", color = AstraColors.TextSecondary, fontSize = 10.sp)
                    val toolRows = listOf(
                        listOf(EditorTool.TAP, EditorTool.HOLD, EditorTool.ERASE),
                        listOf(EditorTool.SLIDE, EditorTool.HOLD_SLIDE, EditorTool.SCRUB)
                    )
                    toolRows.forEach { rowTools ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            rowTools.forEach { tool ->
                                val disabledByMode = initialChart.onlyTapAndHold && (tool == EditorTool.SLIDE || tool == EditorTool.HOLD_SLIDE)
                                val sel = activeTool == tool
                                Surface(
                                    onClick = { if (!disabledByMode) activeTool = tool },
                                    modifier = Modifier.weight(1f),
                                    color = when {
                                        disabledByMode -> Color(0x181B2A4E)
                                        sel -> accent.copy(alpha = 0.28f)
                                        else -> Color(0x281B2A4E)
                                    },
                                    shape = RoundedCornerShape(9.dp),
                                    border = BorderStroke(1.dp, if (sel) accent else Color(0x33FFFFFF))
                                ) {
                                    Text(
                                        text = tool.label,
                                        color = when {
                                            disabledByMode -> Color(0x55FFFFFF)
                                            sel -> accent
                                            else -> AstraColors.TextPrimary
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Selected Note Inspector
                    Surface(
                        color = Color(0x44080E22),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(9.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (selectedNote == null) {
                                Text(
                                    text = "未选中音符\n点击时间轴上的音符可微调时刻、轨道、时长或删除",
                                    color = AstraColors.TextSecondary,
                                    fontSize = 10.sp,
                                    lineHeight = 15.sp
                                )
                            } else {
                                fun updateSelected(transform: (ChartNote) -> ChartNote) {
                                    val idx = notes.indexOfFirst { it.id == selectedNote.id }
                                    if (idx >= 0) {
                                        pushUndoSnapshot()
                                        notes[idx] = transform(notes[idx])
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "选中：${selectedNote.type.name} · ${selectedNote.lane + 1}轨 · ${selectedNote.timeMs}ms",
                                        color = accent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    SmallActionChip("删除", AstraColors.ElectricMagenta) {
                                        pushUndoSnapshot()
                                        notes.removeAll { it.id == selectedNote.id }
                                        selectedNoteId = null
                                    }
                                }

                                // Timing fine-tune
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(-50L to "-50ms", -10L to "-10ms", 10L to "+10ms", 50L to "+50ms").forEach { (dt, lbl) ->
                                        SmallActionChip(lbl, modifier = Modifier.weight(1f)) {
                                            updateSelected { n ->
                                                val newT = (n.timeMs + dt).coerceIn(60L, durationMs - 150L)
                                                val shift = newT - n.timeMs
                                                n.copy(
                                                    timeMs = newT,
                                                    pathPoints = n.pathPoints.map { pt -> pt.copy(timeMs = pt.timeMs + shift) }
                                                )
                                            }
                                        }
                                    }
                                }

                                // Lane shift & NoteType switch
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    SmallActionChip("← 左移轨", modifier = Modifier.weight(1f)) {
                                        updateSelected { n ->
                                            val nl = (n.lane - 1).coerceIn(0, lanes - 1)
                                            n.copy(lane = nl, slideTargetLane = if (n.type == NoteType.SLIDE) n.slideTargetLane else nl)
                                        }
                                    }
                                    SmallActionChip("右移轨 →", modifier = Modifier.weight(1f)) {
                                        updateSelected { n ->
                                            val nl = (n.lane + 1).coerceIn(0, lanes - 1)
                                            n.copy(lane = nl, slideTargetLane = if (n.type == NoteType.SLIDE) n.slideTargetLane else nl)
                                        }
                                    }
                                }

                                if (selectedNote.type == NoteType.HOLD || selectedNote.type == NoteType.HOLD_SLIDE) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        SmallActionChip("时长 -100ms", modifier = Modifier.weight(1f)) {
                                            updateSelected { n -> n.copy(durationMs = (n.durationMs - 100L).coerceAtLeast(240L)) }
                                        }
                                        SmallActionChip("时长 +100ms", modifier = Modifier.weight(1f)) {
                                            updateSelected { n -> n.copy(durationMs = (n.durationMs + 100L).coerceAtMost(3000L)) }
                                        }
                                    }
                                }

                                if (selectedNote.type == NoteType.SLIDE) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        SmallActionChip("滑向 ←", modifier = Modifier.weight(1f)) {
                                            updateSelected { n ->
                                                val tl = (n.slideTargetLane - 1).coerceIn(0, lanes - 1)
                                                n.copy(slideTargetLane = if (tl == n.lane) (tl - 1).coerceIn(0, lanes - 1) else tl)
                                            }
                                        }
                                        SmallActionChip("滑向 →", modifier = Modifier.weight(1f)) {
                                            updateSelected { n ->
                                                val tl = (n.slideTargetLane + 1).coerceIn(0, lanes - 1)
                                                n.copy(slideTargetLane = if (tl == n.lane) (tl + 1).coerceIn(0, lanes - 1) else tl)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirm Clear Chart ("从零开始")
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = AstraColors.DeepIndigo,
            title = { Text("清空谱面 · 从零开始", color = AstraColors.ElectricMagenta, fontWeight = FontWeight.Bold) },
            text = { Text("确定要清空当前所有音符，从空白谱面开始制作吗？（可通过顶部「撤销」恢复）", color = AstraColors.TextPrimary, fontSize = 13.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pushUndoSnapshot()
                        notes.clear()
                        selectedNoteId = null
                        showClearConfirm = false
                        toastHint = "已清空为空白谱面，可点击网格或开启跟拍录制"
                    }
                ) { Text("确定清空", color = AstraColors.ElectricMagenta, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消", color = AstraColors.TextSecondary) }
            }
        )
    }

    // Confirm Re-run AutoChartGenerator
    if (showAutoGenConfirm) {
        AlertDialog(
            onDismissRequest = { showAutoGenConfirm = false },
            containerColor = AstraColors.DeepIndigo,
            title = { Text("重新自动生成基础谱面", color = AstraColors.NeonCyan, fontWeight = FontWeight.Bold) },
            text = { Text("将根据当前歌曲音频自动生成一套基础谱面供你继续修改，是否继续？", color = AstraColors.TextPrimary, fontSize = 13.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAutoGenConfirm = false
                        onRegenerateAutoChart()
                    }
                ) { Text("生成基础谱面", color = AstraColors.NeonCyan, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showAutoGenConfirm = false }) { Text("取消", color = AstraColors.TextSecondary) }
            }
        )
    }

    // Chart Import / Export Dialog (Supports File SAF + Clipboard JSON!)
    if (showImportExportDialog) {
        AlertDialog(
            onDismissRequest = { showImportExportDialog = false },
            containerColor = AstraColors.DeepIndigo,
            title = { Text("谱面与歌曲打包导入 / 导出", color = AstraColors.NeonCyan, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "导出会自动将「当前歌曲音频 + 谱面」一起打包为 .zip，换设备导入即可直接演奏。",
                        color = AstraColors.TextSecondary,
                        fontSize = 11.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val chart = buildCurrentChart()
                                onSaveChart(chart)
                                onExportChartFile(chart)
                                showImportExportDialog = false
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AstraColors.NeonCyan, contentColor = Color(0xFF041320))
                        ) {
                            Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("导出曲包 .zip", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                showImportExportDialog = false
                                onImportChartFile()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AstraColors.StarlightGold, contentColor = Color(0xFF041320))
                        ) {
                            Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("导入 .zip / .json", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val json = buildCurrentChart().toJson().toString()
                                clipboard.setText(AnnotatedString(json))
                                toastHint = "谱面 JSON 已复制到剪贴板"
                                showImportExportDialog = false
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AstraColors.GlassBorder)
                        ) {
                            Text("复制谱面到剪贴板", color = AstraColors.TextPrimary, fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                val clip = clipboard.getText()?.text.orEmpty()
                                if (clip.isNotBlank()) jsonTransferText = clip
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AstraColors.GlassBorder)
                        ) {
                            Text("粘贴剪贴板谱面", color = AstraColors.TextPrimary, fontSize = 11.sp)
                        }
                    }

                    OutlinedTextField(
                        value = jsonTransferText,
                        onValueChange = { jsonTransferText = it },
                        minLines = 2,
                        maxLines = 4,
                        placeholder = { Text("或在此粘贴谱面 JSON 文本导入...", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                if (jsonTransferText.isNotBlank()) {
                    TextButton(
                        onClick = {
                            onImportChartJsonText(jsonTransferText)
                            jsonTransferText = ""
                            showImportExportDialog = false
                        }
                    ) {
                        Text("导入粘贴的 JSON", color = AstraColors.NeonCyan, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportExportDialog = false }) {
                    Text("关闭", color = AstraColors.TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun SmallActionChip(
    label: String,
    accentColor: Color = AstraColors.TextPrimary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color(0x331B2A4E),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Text(
            text = label,
            color = accentColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
        )
    }
}

private fun formatEditorTimeMs(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val min = clamped / 60_000L
    val sec = (clamped % 60_000L) / 1000L
    val tenth = (clamped % 1000L) / 100L
    return "${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}.$tenth"
}
