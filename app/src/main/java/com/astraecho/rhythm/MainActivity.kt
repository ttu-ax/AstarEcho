package com.astraecho.rhythm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.astraecho.rhythm.audio.LinkMediaImporter
import com.astraecho.rhythm.data.SettingsStore
import com.astraecho.rhythm.data.SongRepository
import com.astraecho.rhythm.model.BackgroundImportTask
import com.astraecho.rhythm.model.BeatmapChart
import com.astraecho.rhythm.model.ChartConfig
import com.astraecho.rhythm.model.GameResult
import com.astraecho.rhythm.model.PlayerSettings
import com.astraecho.rhythm.model.SongItem
import com.astraecho.rhythm.ui.screens.ChartEditorScreen
import com.astraecho.rhythm.ui.screens.HomeScreen
import com.astraecho.rhythm.ui.screens.PlayScreen
import com.astraecho.rhythm.ui.screens.ResultScreen
import com.astraecho.rhythm.ui.screens.SongSelectScreen
import com.astraecho.rhythm.ui.screens.WebViewLinkCaptureDialog
import com.astraecho.rhythm.ui.theme.AstraEchoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private sealed interface AppScreen {
    data object Home : AppScreen
    data object SongSelect : AppScreen
    data class Editor(val song: SongItem, val chart: BeatmapChart) : AppScreen
    data class Playing(val song: SongItem, val chart: BeatmapChart, val fromEditor: Boolean = false) : AppScreen
    data class Result(val song: SongItem, val chart: BeatmapChart, val result: GameResult, val fromEditor: Boolean = false) : AppScreen
}

/**
 * Explicit multi-select document picker that sets ACTION_OPEN_DOCUMENT + EXTRA_ALLOW_MULTIPLE
 * + EXTRA_MIME_TYPES (audio and video) and extracts both clipData (multiple) and data (single).
 */
private class MultiMediaPickerContract : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val result = mutableListOf<Uri>()
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { result.add(it) }
            }
        } else {
            intent.data?.let { result.add(it) }
        }
        return result
    }
}

class MainActivity : ComponentActivity() {

    private var incomingShareUris by mutableStateOf<List<Uri>>(emptyList())
    private var incomingShareText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        extractSharePayload(intent)

        val settingsStore = SettingsStore(applicationContext)
        val repository = SongRepository(applicationContext)

        setContent {
            AstraEchoTheme {
                val scope = rememberCoroutineScope()
                var screen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
                var settings by remember { mutableStateOf(settingsStore.load()) }
                var songs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
                var selectedSong by remember { mutableStateOf<SongItem?>(null) }

                var isLaunching by remember { mutableStateOf(false) }
                var launchStatus by remember { mutableStateOf("") }
                var launchProgress by remember { mutableFloatStateOf(0f) }

                var bgTasks by remember { mutableStateOf<List<BackgroundImportTask>>(emptyList()) }
                var generatingSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }
                var chartCacheRevision by remember { mutableIntStateOf(0) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                var editorBanner by remember { mutableStateOf<String?>(null) }
                var webViewCaptureUrl by remember { mutableStateOf<String?>(null) }

                // Pending ZIP export payload (either multi-selected songs OR single song+chart from editor)
                var pendingExportSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
                var pendingExportExtraChart by remember { mutableStateOf<BeatmapChart?>(null) }

                fun upsertTask(id: String, title: String, status: String, progress: Float) {
                    val existingIdx = bgTasks.indexOfFirst { it.id == id }
                    val updated = BackgroundImportTask(id, title, status, progress.coerceIn(0f, 1f))
                    bgTasks = if (existingIdx >= 0) {
                        bgTasks.toMutableList().apply { set(existingIdx, updated) }
                    } else {
                        bgTasks + updated
                    }
                }

                fun removeTask(id: String) {
                    bgTasks = bgTasks.filterNot { it.id == id }
                }

                LaunchedEffect(Unit) {
                    val loaded = repository.loadAllSongs()
                    songs = loaded
                    if (selectedSong == null) selectedSong = loaded.firstOrNull()
                }

                fun currentChartConfig(): ChartConfig = ChartConfig(
                    laneCount = settings.laneCount,
                    difficulty = settings.difficulty,
                    onlyTapAndHold = settings.onlyTapAndHold
                )

                // SAF Launcher: Export Song+Chart(s) as .zip
                val exportZipLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/zip")
                ) { destUri ->
                    val exportList = pendingExportSongs
                    val extra = pendingExportExtraChart
                    pendingExportSongs = emptyList()
                    pendingExportExtraChart = null
                    if (destUri != null && exportList.isNotEmpty()) {
                        scope.launch {
                            errorMessage = null
                            editorBanner = "正在打包导出歌曲与谱面 (.zip)..."
                            runCatching {
                                val count = repository.exportSongsPackToUri(exportList, destUri, extra)
                                editorBanner = "已成功导出 $count 首歌曲与谱面 (.zip)！"
                                errorMessage = "已成功导出 $count 首歌曲与谱面 (.zip)！"
                            }.onFailure { e ->
                                val msg = "打包导出失败：${e.message ?: "请检查存储权限"}"
                                errorMessage = msg
                                editorBanner = msg
                            }
                        }
                    }
                }

                // SAF Launcher: Import .zip Song+Chart pack OR standalone .json chart
                val importPackLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { srcUri ->
                    if (srcUri != null) {
                        scope.launch {
                            errorMessage = null
                            editorBanner = "正在导入曲包 / 谱面..."
                            val curScreen = screen
                            val fallbackSong = (curScreen as? AppScreen.Editor)?.song ?: selectedSong
                            runCatching {
                                val (importedSongs, singleChart) = repository.importPackOrChartFromUri(
                                    srcUri = srcUri,
                                    fallbackSong = fallbackSong,
                                    fallbackConfig = currentChartConfig()
                                )
                                songs = repository.loadAllSongs()
                                chartCacheRevision++
                                if (singleChart != null && curScreen is AppScreen.Editor) {
                                    screen = AppScreen.Editor(curScreen.song, singleChart)
                                    editorBanner = "已导入 JSON 谱面 (${singleChart.notes.size} 音符)"
                                } else if (importedSongs.isNotEmpty()) {
                                    selectedSong = importedSongs.first()
                                    val msg = "已成功导入 ${importedSongs.size} 首曲包（含音频与谱面）！"
                                    errorMessage = msg
                                    editorBanner = msg
                                    if (curScreen is AppScreen.Editor) {
                                        screen = AppScreen.SongSelect
                                    }
                                }
                            }.onFailure { e ->
                                val msg = "导入失败：${e.message ?: "文件格式无效"}"
                                errorMessage = msg
                                editorBanner = msg
                            }
                        }
                    }
                }

                fun enqueueMediaUris(uris: List<Uri>) {
                    if (uris.isEmpty()) return
                    screen = AppScreen.SongSelect
                    errorMessage = null
                    val cfg = currentChartConfig()

                    val taskEntries = uris.mapIndexed { idx, uri ->
                        val tid = "media_${UUID.randomUUID().toString().take(6)}"
                        upsertTask(tid, "导入任务 #${idx + 1}", "等待导入...", 0.02f)
                        tid to uri
                    }

                    scope.launch {
                        taskEntries.forEachIndexed { idx, (tid, uri) ->
                            var displayTitle = "导入任务 #${idx + 1}"
                            upsertTask(tid, displayTitle, "正在导入 / 提取音轨...", 0.08f)

                            val imported = runCatching {
                                repository.importMediaUri(uri) { msg, p ->
                                    upsertTask(tid, displayTitle, msg, p * 0.45f)
                                }
                            }.onFailure { e ->
                                errorMessage = "部分媒体导入失败：${e.message ?: "格式不支持"}"
                            }.getOrNull()

                            if (imported != null) {
                                displayTitle = imported.title
                                songs = repository.loadAllSongs()
                                if (idx == 0) selectedSong = imported
                                generatingSongIds = generatingSongIds + imported.id
                                upsertTask(tid, displayTitle, "正在后台编谱...", 0.48f)

                                runCatching {
                                    repository.getOrGenerateChart(
                                        song = imported,
                                        config = cfg,
                                        onStatus = { msg, p ->
                                            upsertTask(tid, displayTitle, msg, 0.48f + p * 0.52f)
                                        }
                                    )
                                    songs = repository.loadAllSongs()
                                    chartCacheRevision++
                                }.onFailure { e ->
                                    errorMessage = "后台编谱失败《${imported.title}》：${e.message}"
                                }
                                generatingSongIds = generatingSongIds - imported.id
                            }
                            removeTask(tid)
                        }
                    }
                }

                fun enqueueLinkText(rawText: String) {
                    val urls = LinkMediaImporter.extractUrls(rawText)
                    if (urls.isEmpty()) {
                        errorMessage = "未在文本中识别到有效的 http/https 链接"
                        return
                    }
                    screen = AppScreen.SongSelect
                    errorMessage = null
                    val cfg = currentChartConfig()
                    val customApi = settings.customParserApiUrl.trim()

                    if (customApi.isBlank() && urls.size == 1 && LinkMediaImporter.isDouyinUrl(urls[0])) {
                        webViewCaptureUrl = urls[0]
                        return
                    }

                    val taskEntries = urls.mapIndexed { idx, url ->
                        val tid = "link_${UUID.randomUUID().toString().take(6)}"
                        upsertTask(tid, "链接任务 #${idx + 1}", "等待解析...", 0.02f)
                        tid to url
                    }

                    scope.launch {
                        taskEntries.forEachIndexed { idx, (tid, url) ->
                            var displayTitle = "链接任务 #${idx + 1}"
                            upsertTask(tid, displayTitle, "正在解析链接...", 0.1f)

                            val imported = runCatching {
                                repository.importFromUrl(
                                    rawUrl = url,
                                    customParserApiUrl = customApi,
                                    onStatus = { msg, p ->
                                        upsertTask(tid, displayTitle, msg, p * 0.55f)
                                    }
                                )
                            }.onFailure { e ->
                                errorMessage = "链接导入失败：${e.message ?: "请检查网络或链接有效性"}"
                            }.getOrNull()

                            if (imported != null) {
                                displayTitle = imported.title
                                songs = repository.loadAllSongs()
                                if (idx == 0) selectedSong = imported
                                generatingSongIds = generatingSongIds + imported.id

                                runCatching {
                                    repository.getOrGenerateChart(
                                        song = imported,
                                        config = cfg,
                                        onStatus = { msg, p ->
                                            upsertTask(tid, displayTitle, msg, 0.55f + p * 0.45f)
                                        }
                                    )
                                    songs = repository.loadAllSongs()
                                    chartCacheRevision++
                                }.onFailure { e ->
                                    errorMessage = "后台编谱失败《${imported.title}》：${e.message}"
                                }
                                generatingSongIds = generatingSongIds - imported.id
                            }
                            removeTask(tid)
                        }
                    }
                }

                LaunchedEffect(incomingShareUris) {
                    val uris = incomingShareUris
                    if (uris.isNotEmpty()) {
                        incomingShareUris = emptyList()
                        enqueueMediaUris(uris)
                    }
                }
                LaunchedEffect(incomingShareText) {
                    val txt = incomingShareText
                    if (!txt.isNullOrBlank()) {
                        incomingShareText = null
                        enqueueLinkText(txt)
                    }
                }

                val mediaPicker = rememberLauncherForActivityResult(
                    contract = MultiMediaPickerContract()
                ) { uris ->
                    enqueueMediaUris(uris)
                }

                fun updateSettings(newSettings: PlayerSettings) {
                    settings = newSettings
                    settingsStore.save(newSettings)
                }

                fun openEditorForSong(song: SongItem, startBlank: Boolean) {
                    scope.launch {
                        errorMessage = null
                        editorBanner = null
                        val cfg = currentChartConfig()
                        if (startBlank) {
                            val blank = repository.createBlankChart(song, cfg)
                            screen = AppScreen.Editor(song, blank)
                        } else {
                            val alreadyCached = withContext(Dispatchers.IO) {
                                repository.hasChartCached(song.id, cfg)
                            }
                            if (!alreadyCached) {
                                isLaunching = true
                                launchStatus = "正在生成基础谱面以供编辑..."
                                launchProgress = 0.08f
                            }
                            runCatching {
                                val chart = repository.getOrGenerateChart(
                                    song = song,
                                    config = cfg,
                                    onStatus = { msg, p ->
                                        if (!alreadyCached) {
                                            launchStatus = msg
                                            launchProgress = p
                                        }
                                    }
                                )
                                chartCacheRevision++
                                songs = repository.loadAllSongs()
                                screen = AppScreen.Editor(song, chart)
                            }.onFailure { e ->
                                errorMessage = "打开编辑器失败：${e.message}"
                            }
                            isLaunching = false
                        }
                    }
                }

                fun launchSong(song: SongItem) {
                    scope.launch {
                        errorMessage = null
                        val config = currentChartConfig()
                        val alreadyCached = withContext(Dispatchers.IO) {
                            repository.hasChartCached(song.id, config)
                        }
                        if (!alreadyCached) {
                            isLaunching = true
                            launchStatus = "正在准备音频分析..."
                            launchProgress = 0.05f
                        }
                        runCatching {
                            val chart = repository.getOrGenerateChart(
                                song = song,
                                config = config,
                                onStatus = { msg, p ->
                                    if (!alreadyCached) {
                                        launchStatus = msg
                                        launchProgress = p
                                    }
                                }
                            )
                            chartCacheRevision++
                            songs = repository.loadAllSongs()
                            if (chart.notes.isEmpty()) {
                                errorMessage = "当前谱面为空，请进入谱面编辑器添加音符或点击「自动生成」"
                            } else {
                                screen = AppScreen.Playing(song, chart, fromEditor = false)
                            }
                        }.onFailure { e ->
                            errorMessage = "谱面生成失败：${e.message ?: "请检查音频文件"}"
                        }
                        isLaunching = false
                    }
                }

                val currentConfig = currentChartConfig()
                val selectedChartReady = remember(selectedSong?.id, currentConfig, chartCacheRevision) {
                    val s = selectedSong
                    s != null && repository.hasChartCached(s.id, currentConfig)
                }

                when (val s = screen) {
                    is AppScreen.Home -> {
                        HomeScreen(
                            settings = settings,
                            onUpdateSettings = ::updateSettings,
                            onEnterSongSelect = { screen = AppScreen.SongSelect },
                            onQuickImportMedia = { mediaPicker.launch(Unit) }
                        )
                    }

                    is AppScreen.SongSelect -> {
                        SongSelectScreen(
                            songs = songs,
                            selectedSong = selectedSong,
                            selectedChartReady = selectedChartReady,
                            generatingSongIds = generatingSongIds,
                            bgTasks = bgTasks,
                            settings = settings,
                            isGenerating = isLaunching,
                            generationStatus = launchStatus,
                            generationProgress = launchProgress,
                            errorMessage = errorMessage,
                            onSelectSong = { selectedSong = it },
                            onRenameSong = { item, newName ->
                                scope.launch {
                                    val renamed = repository.renameImportedSong(item.id, newName)
                                    val updated = repository.loadAllSongs()
                                    songs = updated
                                    if (selectedSong?.id == item.id && renamed != null) {
                                        selectedSong = renamed
                                    }
                                }
                            },
                            onDeleteSong = { item ->
                                scope.launch {
                                    repository.deleteImportedSong(item.id)
                                    val updated = repository.loadAllSongs()
                                    songs = updated
                                    if (selectedSong?.id == item.id) {
                                        selectedSong = updated.firstOrNull()
                                    }
                                    chartCacheRevision++
                                }
                            },
                            onImportMedia = { mediaPicker.launch(Unit) },
                            onImportLinks = ::enqueueLinkText,
                            onOpenEditor = ::openEditorForSong,
                            onExportSongsZip = { chosenSongs ->
                                pendingExportSongs = chosenSongs
                                pendingExportExtraChart = null
                                val suggested = if (chosenSongs.size == 1) {
                                    "${chosenSongs.first().title.take(24).replace(Regex("[\\\\/:*?\"<>|]"), "_")}.zip"
                                } else {
                                    "AstraEcho_Pack_${chosenSongs.size}songs.zip"
                                }
                                exportZipLauncher.launch(suggested)
                            },
                            onImportPackFile = {
                                importPackLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*"))
                            },
                            onUpdateSettings = ::updateSettings,
                            onStartPlay = ::launchSong,
                            onBack = { screen = AppScreen.Home }
                        )
                    }

                    is AppScreen.Editor -> {
                        ChartEditorScreen(
                            song = s.song,
                            initialChart = s.chart,
                            settings = settings,
                            statusBanner = editorBanner,
                            onSaveChart = { edited ->
                                scope.launch {
                                    val cfg = ChartConfig(edited.laneCount, edited.difficulty, edited.onlyTapAndHold)
                                    repository.saveCustomChart(s.song, cfg, edited)
                                    songs = repository.loadAllSongs()
                                    chartCacheRevision++
                                }
                            },
                            onTestPlayChart = { edited ->
                                if (edited.notes.isEmpty()) {
                                    editorBanner = "请先放置至少 1 个音符再试玩"
                                } else {
                                    screen = AppScreen.Playing(s.song, edited, fromEditor = true)
                                }
                            },
                            onRegenerateAutoChart = {
                                scope.launch {
                                    editorBanner = "正在重新分析音频并生成基础谱面..."
                                    val cfg = ChartConfig(s.chart.laneCount, s.chart.difficulty, s.chart.onlyTapAndHold)
                                    runCatching {
                                        val auto = repository.getOrGenerateChart(s.song, cfg, forceRegenerate = true)
                                        chartCacheRevision++
                                        screen = AppScreen.Editor(s.song, auto)
                                        editorBanner = "已重新生成基础谱面 (${auto.notes.size} 音符)"
                                    }.onFailure { e ->
                                        editorBanner = "自动生成失败：${e.message}"
                                    }
                                }
                            },
                            onExportChartFile = { edited ->
                                pendingExportSongs = listOf(s.song)
                                pendingExportExtraChart = edited
                                val safeName = s.song.title.take(24).replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                exportZipLauncher.launch("${safeName}_${edited.laneCount}K.zip")
                            },
                            onImportChartFile = {
                                importPackLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*"))
                            },
                            onImportChartJsonText = { rawJson ->
                                scope.launch {
                                    runCatching {
                                        val cfg = ChartConfig(s.chart.laneCount, s.chart.difficulty, s.chart.onlyTapAndHold)
                                        val imported = repository.importChartFromJsonString(s.song, cfg, rawJson)
                                        chartCacheRevision++
                                        screen = AppScreen.Editor(s.song, imported)
                                        editorBanner = "已导入粘贴的谱面 (${imported.notes.size} 音符)"
                                    }.onFailure { e ->
                                        editorBanner = "JSON 导入失败：${e.message}"
                                    }
                                }
                            },
                            onBack = { screen = AppScreen.SongSelect }
                        )
                    }

                    is AppScreen.Playing -> {
                        PlayScreen(
                            audioPath = s.song.audioPath,
                            chart = s.chart,
                            settings = settings,
                            onUpdateSettings = ::updateSettings,
                            onFinished = { result ->
                                screen = AppScreen.Result(s.song, s.chart, result, fromEditor = s.fromEditor)
                            },
                            onExit = {
                                screen = if (s.fromEditor) AppScreen.Editor(s.song, s.chart) else AppScreen.SongSelect
                            }
                        )
                    }

                    is AppScreen.Result -> {
                        ResultScreen(
                            result = s.result,
                            onReplay = { screen = AppScreen.Playing(s.song, s.chart, fromEditor = s.fromEditor) },
                            onBackToSongs = {
                                screen = if (s.fromEditor) AppScreen.Editor(s.song, s.chart) else AppScreen.SongSelect
                            }
                        )
                    }
                }

                val captureTarget = webViewCaptureUrl
                if (captureTarget != null) {
                    WebViewLinkCaptureDialog(
                        pageUrl = captureTarget,
                        onCaptured = { captured ->
                            webViewCaptureUrl = null
                            val cfg = currentChartConfig()
                            val tid = "wv_${UUID.randomUUID().toString().take(6)}"
                            val initTitle = captured.title.ifBlank { "抖音音轨" }
                            upsertTask(tid, initTitle, "正在下载抖音音轨...", 0.1f)

                            scope.launch {
                                errorMessage = null
                                val imported = runCatching {
                                    repository.importFromCapturedWebViewMedia(captured) { msg, p ->
                                        upsertTask(tid, initTitle, msg, p * 0.55f)
                                    }
                                }.onFailure { e ->
                                    errorMessage = "下载音轨失败：${e.message}"
                                }.getOrNull()

                                if (imported != null) {
                                    songs = repository.loadAllSongs()
                                    selectedSong = imported
                                    generatingSongIds = generatingSongIds + imported.id
                                    runCatching {
                                        repository.getOrGenerateChart(
                                            song = imported,
                                            config = cfg,
                                            onStatus = { msg, p ->
                                                upsertTask(tid, imported.title, msg, 0.55f + p * 0.45f)
                                            }
                                        )
                                        songs = repository.loadAllSongs()
                                        chartCacheRevision++
                                    }.onFailure { e ->
                                        errorMessage = "后台编谱失败：${e.message}"
                                    }
                                    generatingSongIds = generatingSongIds - imported.id
                                }
                                removeTask(tid)
                            }
                        },
                        onDismiss = { webViewCaptureUrl = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharePayload(intent)
    }

    @Suppress("DEPRECATION")
    private fun extractSharePayload(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val type = intent.type.orEmpty()
                if (type.startsWith("text/")) {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!sharedText.isNullOrBlank()) {
                        incomingShareText = sharedText
                    }
                } else {
                    val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    if (uri != null) incomingShareUris = listOf(uri)
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris: List<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) incomingShareUris = uris
            }
        }
    }
}
