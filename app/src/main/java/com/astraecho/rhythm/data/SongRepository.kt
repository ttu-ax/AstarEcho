package com.astraecho.rhythm.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.astraecho.rhythm.audio.AudioDecoder
import com.astraecho.rhythm.audio.AudioFeatureFrames
import com.astraecho.rhythm.audio.AutoChartGenerator
import com.astraecho.rhythm.audio.BuiltInAudioGenerator
import com.astraecho.rhythm.audio.LinkMediaImporter
import com.astraecho.rhythm.audio.MediaAudioExtractor
import com.astraecho.rhythm.audio.WebViewCapturedMedia
import com.astraecho.rhythm.model.BeatmapChart
import com.astraecho.rhythm.model.ChartConfig
import com.astraecho.rhythm.model.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SongRepository(private val context: Context) {

    private val builtinDir = File(context.filesDir, "builtin_audio")
    private val importedDir = File(context.filesDir, "imported_audio")
    private val featuresDir = File(context.filesDir, "features")
    private val chartsDir = File(context.filesDir, "charts")
    private val importedIndexFile = File(context.filesDir, "imported_songs.json")

    private val featureCache = ConcurrentHashMap<String, AudioFeatureFrames>()

    suspend fun loadAllSongs(): List<SongItem> = withContext(Dispatchers.IO) {
        val builtIns = BuiltInAudioGenerator.TRACKS.map { spec ->
            val wavFile = BuiltInAudioGenerator.ensureTrackWav(builtinDir, spec)
            SongItem(
                id = spec.id,
                title = spec.title,
                subtitle = spec.subtitle,
                artist = spec.artist,
                audioPath = wavFile.absolutePath,
                bpm = spec.bpm,
                durationMs = spec.durationSec * 1000L,
                isBuiltIn = true,
                isFromVideo = false,
                accentHex = spec.accentHex
            )
        }
        val imported = loadImportedIndex()
        imported + builtIns
    }

    suspend fun importMediaUri(
        uri: Uri,
        onStatus: ((String, Float) -> Unit)? = null
    ): SongItem = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri) ?: "Custom_Media_${System.currentTimeMillis()}.mp3"
        val songId = "user_${UUID.randomUUID().toString().take(8)}"

        val extracted = MediaAudioExtractor.importOrExtractAudio(
            context = context,
            uri = uri,
            displayName = displayName,
            destDir = importedDir,
            songId = songId,
            onStatus = onStatus
        )
        val destFile = extracted.audioFile

        var title = displayName.substringBeforeLast('.')
        var artist = if (extracted.isFromVideo) "视频提取音轨 · Video Audio" else "本地导入 · Custom Audio"
        var durationMs = 60_000L

        val retriever = MediaMetadataRetriever()
        runCatching {
            retriever.setDataSource(destFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }?.let { artist = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.let { durationMs = it }
        }
        runCatching { retriever.release() }

        val newItem = SongItem(
            id = songId,
            title = title,
            subtitle = if (extracted.isFromVideo) "视频分离音频 · 自动谱面" else "自定义音频 · 自动谱面",
            artist = artist,
            audioPath = destFile.absolutePath,
            bpm = 0,
            durationMs = durationMs,
            isBuiltIn = false,
            isFromVideo = extracted.isFromVideo,
            accentHex = if (extracted.isFromVideo) 0xFFFF3B8D else 0xFFB388FF
        )

        val current = loadImportedIndex().toMutableList()
        current.add(0, newItem)
        saveImportedIndex(current)
        newItem
    }

    /**
     * Imports audio directly from a Douyin share URL or direct audio/video URL.
     */
    suspend fun importFromUrl(
        rawUrl: String,
        customParserApiUrl: String = "",
        onStatus: ((String, Float) -> Unit)? = null
    ): SongItem = withContext(Dispatchers.IO) {
        val songId = "link_${UUID.randomUUID().toString().take(8)}"
        val downloaded = LinkMediaImporter.downloadAndExtract(
            context = context,
            rawUrl = rawUrl,
            destDir = importedDir,
            songId = songId,
            customParserApiUrl = customParserApiUrl,
            onStatus = onStatus
        )
        val destFile = downloaded.audioFile

        var durationMs = 60_000L
        val retriever = MediaMetadataRetriever()
        runCatching {
            retriever.setDataSource(destFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.let { durationMs = it }
        }
        runCatching { retriever.release() }

        val newItem = SongItem(
            id = songId,
            title = downloaded.title,
            subtitle = if (downloaded.isFromVideo) "链接视频提音 · 自动谱面" else "链接音频 · 自动谱面",
            artist = downloaded.artist,
            audioPath = destFile.absolutePath,
            bpm = 0,
            durationMs = durationMs,
            isBuiltIn = false,
            isFromVideo = downloaded.isFromVideo,
            accentHex = 0xFF38F2FF
        )

        val current = loadImportedIndex().toMutableList()
        current.add(0, newItem)
        saveImportedIndex(current)
        newItem
    }

    suspend fun importFromCapturedWebViewMedia(
        captured: WebViewCapturedMedia,
        onStatus: ((String, Float) -> Unit)? = null
    ): SongItem = withContext(Dispatchers.IO) {
        val songId = "link_${UUID.randomUUID().toString().take(8)}"
        val downloaded = LinkMediaImporter.downloadCapturedMedia(
            context = context,
            captured = captured,
            destDir = importedDir,
            songId = songId,
            onStatus = onStatus
        )
        val destFile = downloaded.audioFile

        var durationMs = 60_000L
        val retriever = MediaMetadataRetriever()
        runCatching {
            retriever.setDataSource(destFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.let { durationMs = it }
        }
        runCatching { retriever.release() }

        val newItem = SongItem(
            id = songId,
            title = downloaded.title,
            subtitle = if (downloaded.isFromVideo) "抖音视频提音 · 自动谱面" else "抖音音频 · 自动谱面",
            artist = downloaded.artist,
            audioPath = destFile.absolutePath,
            bpm = 0,
            durationMs = durationMs,
            isBuiltIn = false,
            isFromVideo = downloaded.isFromVideo,
            accentHex = 0xFF38F2FF
        )

        val current = loadImportedIndex().toMutableList()
        current.add(0, newItem)
        saveImportedIndex(current)
        newItem
    }

    /**
     * Renames an imported song and updates its persisted chart metadata titles.
     */
    suspend fun renameImportedSong(songId: String, newTitle: String): SongItem? = withContext(Dispatchers.IO) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return@withContext null
        var updatedItem: SongItem? = null
        val updatedList = loadImportedIndex().map { item ->
            if (item.id == songId) {
                item.copy(title = trimmed).also { updatedItem = it }
            } else item
        }
        saveImportedIndex(updatedList)

        // Also update cached chart JSON titles so in-game HUD shows the new name immediately
        chartsDir.listFiles()?.filter { it.name.startsWith("${songId}_") }?.forEach { file ->
            runCatching {
                val obj = JSONObject(file.readText())
                obj.put("title", trimmed)
                file.writeText(obj.toString())
            }
        }
        updatedItem
    }

    /**
     * Deletes an imported song along with its extracted audio, persisted DSP feature cache,
     * and ALL generated beatmap chart JSON files to reclaim disk space completely.
     */
    suspend fun deleteImportedSong(songId: String) = withContext(Dispatchers.IO) {
        val current = loadImportedIndex().toMutableList()
        val removed = current.firstOrNull { it.id == songId } ?: return@withContext
        current.removeAll { it.id == songId }
        saveImportedIndex(current)

        runCatching { File(removed.audioPath).delete() }
        featureCache.remove(songId)
        runCatching { File(featuresDir, "${songId}_v3.bin").delete() }
        featuresDir.listFiles()?.filter { it.name.startsWith("${songId}_") }?.forEach { it.delete() }
        chartsDir.listFiles()?.filter { it.name.startsWith("${songId}_") }?.forEach { it.delete() }
    }

    fun hasChartCached(songId: String, config: ChartConfig): Boolean {
        val cacheKey = chartCacheKey(songId, config)
        val chartFile = File(chartsDir, "$cacheKey.json")
        return chartFile.exists() && chartFile.length() > 32
    }

    private fun chartCacheKey(songId: String, config: ChartConfig): String {
        return "${songId}_v7_${config.clampedLanes}k_${config.difficulty.name}_${if (config.onlyTapAndHold) "noSlide" else "all"}"
    }

    fun createBlankChart(song: SongItem, config: ChartConfig): BeatmapChart {
        return BeatmapChart(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            bpm = if (song.bpm in 40..300) song.bpm else 120,
            durationMs = song.durationMs.coerceAtLeast(15_000L),
            laneCount = config.clampedLanes,
            difficulty = config.difficulty,
            onlyTapAndHold = config.onlyTapAndHold,
            notes = emptyList()
        )
    }

    /**
     * Saves a user-edited or imported BeatmapChart directly into the persistent chart cache.
     */
    suspend fun saveCustomChart(
        song: SongItem,
        config: ChartConfig,
        chart: BeatmapChart
    ): BeatmapChart = withContext(Dispatchers.IO) {
        if (!chartsDir.exists()) chartsDir.mkdirs()
        val lanes = config.clampedLanes
        val cleanedNotes = chart.notes
            .sortedWith(compareBy<com.astraecho.rhythm.model.ChartNote> { it.timeMs }.thenBy { it.lane })
            .mapIndexed { idx, n ->
                val clampedLane = n.lane.coerceIn(0, lanes - 1)
                val clampedTarget = n.slideTargetLane.coerceIn(0, lanes - 1)
                val clampedPts = n.pathPoints.map { pt -> pt.copy(lane = pt.lane.coerceIn(0, lanes - 1)) }
                n.copy(
                    id = idx + 1,
                    lane = clampedLane,
                    slideTargetLane = clampedTarget,
                    pathPoints = clampedPts
                )
            }

        val normalized = chart.copy(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            laneCount = lanes,
            difficulty = config.difficulty,
            onlyTapAndHold = config.onlyTapAndHold,
            notes = cleanedNotes
        )

        val chartFile = File(chartsDir, "${chartCacheKey(song.id, config)}.json")
        chartFile.writeText(normalized.toJson().toString())

        if (!song.isBuiltIn && normalized.bpm in 40..300 && normalized.bpm != song.bpm) {
            val list = loadImportedIndex().map {
                if (it.id == song.id) it.copy(bpm = normalized.bpm) else it
            }
            saveImportedIndex(list)
        }
        normalized
    }

    /**
     * Exports a BeatmapChart as formatted JSON to a user-selected SAF Uri.
     */
    suspend fun exportChartToUri(chart: BeatmapChart, destUri: Uri) = withContext(Dispatchers.IO) {
        val jsonText = chart.toJson().toString(2)
        context.contentResolver.openOutputStream(destUri, "wt")?.use { out ->
            out.write(jsonText.toByteArray(Charsets.UTF_8))
        } ?: error("无法写入导出文件")
    }

    /**
     * Imports a BeatmapChart JSON from a user-selected SAF Uri and binds it to [song] & [config].
     */
    suspend fun importChartFromUri(
        song: SongItem,
        config: ChartConfig,
        srcUri: Uri
    ): BeatmapChart = withContext(Dispatchers.IO) {
        val rawJson = context.contentResolver.openInputStream(srcUri)?.bufferedReader(Charsets.UTF_8)?.use {
            it.readText()
        } ?: error("无法读取谱面文件")
        importChartFromJsonString(song, config, rawJson)
    }

    /**
     * Parses a BeatmapChart JSON string, adapts it to [song] & [config], and saves it to cache.
     */
    suspend fun importChartFromJsonString(
        song: SongItem,
        config: ChartConfig,
        rawJson: String
    ): BeatmapChart = withContext(Dispatchers.IO) {
        val obj = runCatching { JSONObject(rawJson.trim()) }.getOrElse {
            error("无效的谱面 JSON 格式")
        }
        val parsed = runCatching { BeatmapChart.fromJson(obj) }.getOrElse { e ->
            error("谱面结构解析失败：${e.message}")
        }
        val trueDur = if (song.durationMs > 2000L) song.durationMs else parsed.durationMs
        val targetConfig = config.copy(
            laneCount = parsed.laneCount.coerceIn(4, 6)
        )
        saveCustomChart(
            song = song,
            config = targetConfig,
            chart = parsed.copy(durationMs = trueDur)
        )
    }

    /**
     * Exports one or more songs TOGETHER WITH their audio files and all saved charts into a single .zip pack!
     * Also allows passing an explicit [extraChart] (e.g. unsaved/current chart from the editor).
     */
    suspend fun exportSongsPackToUri(
        songs: List<SongItem>,
        destUri: Uri,
        extraChart: BeatmapChart? = null
    ): Int = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(destUri, "wt")?.use { rawOut ->
            exportSongsPackToStream(songs, rawOut, extraChart)
        } ?: error("无法写入导出 ZIP 文件")
    }

    internal fun exportSongsPackToStream(
        songs: List<SongItem>,
        rawOut: java.io.OutputStream,
        extraChart: BeatmapChart? = null
    ): Int {
        var exportedCount = 0
        val manifestSongs = JSONArray()

        ZipOutputStream(BufferedOutputStream(rawOut)).use { zos ->
            for (song in songs) {
                val audioFile = File(song.audioPath)
                if (!audioFile.exists()) continue

                val ext = audioFile.extension.ifBlank { "mp3" }.lowercase()
                val audioEntryName = "songs/${song.id}/audio.$ext"

                // 1. Write audio file into ZIP
                zos.putNextEntry(ZipEntry(audioEntryName))
                FileInputStream(audioFile).use { fis -> fis.copyTo(zos) }
                zos.closeEntry()

                // 2. Collect all cached charts for this song (plus extraChart if provided)
                val chartEntries = JSONArray()
                val writtenKeys = HashSet<String>()

                if (extraChart != null && extraChart.songId == song.id) {
                    val cfg = ChartConfig(extraChart.laneCount, extraChart.difficulty, extraChart.onlyTapAndHold)
                    val key = chartCacheKey(song.id, cfg)
                    val entryPath = "songs/${song.id}/charts/$key.json"
                    zos.putNextEntry(ZipEntry(entryPath))
                    zos.write(extraChart.toJson().toString(2).toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                    writtenKeys.add(key)
                    chartEntries.put(JSONObject().apply {
                        put("laneCount", extraChart.laneCount)
                        put("difficulty", extraChart.difficulty.name)
                        put("onlyTapAndHold", extraChart.onlyTapAndHold)
                        put("entry", entryPath)
                    })
                }

                chartsDir.listFiles()
                    ?.filter { it.name.startsWith("${song.id}_v7_") && it.name.endsWith(".json") }
                    ?.forEach { cf ->
                        val key = cf.name.removeSuffix(".json")
                        if (key in writtenKeys) return@forEach
                        val parsed = runCatching { BeatmapChart.fromJson(JSONObject(cf.readText())) }.getOrNull()
                            ?: return@forEach
                        val entryPath = "songs/${song.id}/charts/${cf.name}"
                        zos.putNextEntry(ZipEntry(entryPath))
                        zos.write(cf.readBytes())
                        zos.closeEntry()
                        writtenKeys.add(key)
                        chartEntries.put(JSONObject().apply {
                            put("laneCount", parsed.laneCount)
                            put("difficulty", parsed.difficulty.name)
                            put("onlyTapAndHold", parsed.onlyTapAndHold)
                            put("entry", entryPath)
                        })
                    }

                manifestSongs.put(JSONObject().apply {
                    put("origId", song.id)
                    put("title", song.title)
                    put("subtitle", song.subtitle)
                    put("artist", song.artist)
                    put("bpm", song.bpm)
                    put("durationMs", song.durationMs)
                    put("isFromVideo", song.isFromVideo)
                    put("accentHex", song.accentHex)
                    put("audioEntry", audioEntryName)
                    put("audioExt", ext)
                    put("charts", chartEntries)
                })
                exportedCount++
            }

            // 3. Write manifest.json at root of ZIP
            val manifest = JSONObject().apply {
                put("format", "astra_echo_pack")
                put("version", 1)
                put("songs", manifestSongs)
            }
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return exportedCount
    }

    /**
     * Imports a .zip Song+Chart pack (restoring both audio files and all bundled charts!),
     * OR a standalone .json chart file (bound to [fallbackSong] & [fallbackConfig]).
     * Returns the list of newly imported/updated SongItems.
     */
    suspend fun importPackOrChartFromUri(
        srcUri: Uri,
        fallbackSong: SongItem?,
        fallbackConfig: ChartConfig
    ): Pair<List<SongItem>, BeatmapChart?> = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "import_pack_${UUID.randomUUID().toString().take(8)}.tmp")
        try {
            context.contentResolver.openInputStream(srcUri)?.use { inp ->
                FileOutputStream(tempFile).use { out -> inp.copyTo(out) }
            } ?: error("无法读取所选文件")

            // Detect ZIP magic header ("PK\x03\x04")
            val isZip = tempFile.length() >= 4 && FileInputStream(tempFile).use { fis ->
                val b = ByteArray(4)
                fis.read(b) == 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()
            }

            if (isZip) {
                val importedSongs = importZipPackFile(tempFile)
                importedSongs to null
            } else {
                val targetSong = fallbackSong ?: error("导入单个 JSON 谱面时请先选中对应歌曲")
                val chart = importChartFromJsonString(targetSong, fallbackConfig, tempFile.readText(Charsets.UTF_8))
                listOf(targetSong) to chart
            }
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    internal fun importZipPackFile(zipFile: File): List<SongItem> {
        if (!importedDir.exists()) importedDir.mkdirs()
        if (!chartsDir.exists()) chartsDir.mkdirs()

        val tempExtractDir = File(context.cacheDir, "unzip_${UUID.randomUUID().toString().take(8)}")
        tempExtractDir.mkdirs()
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // Prevent zip-slip path traversal
                        val outFile = File(tempExtractDir, entry.name)
                        if (outFile.canonicalPath.startsWith(tempExtractDir.canonicalPath)) {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val manifestFile = File(tempExtractDir, "manifest.json")
            require(manifestFile.exists()) { "ZIP 包内缺少 manifest.json，不是有效的星律回响曲包" }
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val songsArr = manifest.getJSONArray("songs")

            val newlyImported = mutableListOf<SongItem>()
            val existingIndex = loadImportedIndex().toMutableList()

            for (i in 0 until songsArr.length()) {
                val sObj = songsArr.getJSONObject(i)
                val audioEntry = sObj.getString("audioEntry")
                val srcAudio = File(tempExtractDir, audioEntry)
                if (!srcAudio.exists()) continue

                val ext = sObj.optString("audioExt", srcAudio.extension.ifBlank { "mp3" })
                val newSongId = "pack_${UUID.randomUUID().toString().take(8)}"
                val destAudio = File(importedDir, "$newSongId.$ext")
                srcAudio.copyTo(destAudio, overwrite = true)

                val songItem = SongItem(
                    id = newSongId,
                    title = sObj.optString("title", "导入曲目"),
                    subtitle = "曲包导入 · 含谱面",
                    artist = sObj.optString("artist", "曲包分享"),
                    audioPath = destAudio.absolutePath,
                    bpm = sObj.optInt("bpm", 120),
                    durationMs = sObj.optLong("durationMs", 60_000L),
                    isBuiltIn = false,
                    isFromVideo = sObj.optBoolean("isFromVideo", false),
                    accentHex = sObj.optLong("accentHex", 0xFF38F2FF)
                )
                existingIndex.add(0, songItem)
                newlyImported.add(songItem)

                // Restore all bundled charts under newSongId
                val chartsArr = sObj.optJSONArray("charts")
                if (chartsArr != null) {
                    for (cIdx in 0 until chartsArr.length()) {
                        val cObj = chartsArr.getJSONObject(cIdx)
                        val cFile = File(tempExtractDir, cObj.getString("entry"))
                        if (!cFile.exists()) continue
                        runCatching {
                            val parsed = BeatmapChart.fromJson(JSONObject(cFile.readText(Charsets.UTF_8)))
                            val cfg = ChartConfig(
                                laneCount = parsed.laneCount.coerceIn(4, 6),
                                difficulty = parsed.difficulty,
                                onlyTapAndHold = parsed.onlyTapAndHold
                            )
                            val adapted = parsed.copy(songId = newSongId, title = songItem.title)
                            val destChart = File(chartsDir, "${chartCacheKey(newSongId, cfg)}.json")
                            destChart.writeText(adapted.toJson().toString())
                        }
                    }
                }
            }

            if (newlyImported.isNotEmpty()) {
                saveImportedIndex(existingIndex)
            }
            return newlyImported
        } finally {
            runCatching { tempExtractDir.deleteRecursively() }
        }
    }

    suspend fun getOrGenerateChart(
        song: SongItem,
        config: ChartConfig,
        forceRegenerate: Boolean = false,
        onStatus: ((String, Float) -> Unit)? = null
    ): BeatmapChart = withContext(Dispatchers.IO) {
        if (!chartsDir.exists()) chartsDir.mkdirs()
        if (!featuresDir.exists()) featuresDir.mkdirs()

        val cacheKey = "${song.id}_v7_${config.clampedLanes}k_${config.difficulty.name}_${if (config.onlyTapAndHold) "noSlide" else "all"}"
        val chartFile = File(chartsDir, "$cacheKey.json")

        if (!forceRegenerate && chartFile.exists()) {
            val cached = runCatching {
                BeatmapChart.fromJson(JSONObject(chartFile.readText()))
            }.getOrNull()
            if (cached != null && cached.notes.isNotEmpty()) {
                onStatus?.invoke("已秒加载本地谱面", 1f)
                return@withContext cached.copy(title = song.title)
            }
        }

        // 1. Try RAM feature cache -> 2. Try disk binary feature cache -> 3. Decode & persist once!
        val featureFile = File(featuresDir, "${song.id}_v7.bin")
        val features = featureCache[song.id]
            ?: AudioFeatureFrames.readFromFile(featureFile)?.also {
                featureCache[song.id] = it
                onStatus?.invoke("已加载持久化音频特征...", 0.85f)
            }
            ?: run {
                onStatus?.invoke("首次解码音频波形（后续免解码）...", 0.1f)
                val decoded = AudioDecoder.decodeToMonoFloat(song.audioPath) { p ->
                    onStatus?.invoke("首次解码音频波形（后续免解码）...", 0.1f + p * 0.45f)
                }
                onStatus?.invoke("正在分析频谱与节拍...", 0.55f)
                val analyzed = AutoChartGenerator.analyzePcm(decoded.samples, decoded.sampleRate) { p ->
                    onStatus?.invoke("正在分析频谱与节拍...", 0.55f + p * 0.35f)
                }
                featureCache[song.id] = analyzed
                runCatching { analyzed.writeToFile(featureFile) }
                analyzed
            }

        onStatus?.invoke("正在编织 ${config.clampedLanes}K 星律谱面...", 0.92f)
        val rawChart = AutoChartGenerator.generateChart(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            features = features,
            config = config
        )

        // Guarantee chart duration & notes NEVER extend past the real playable audio length!
        val trueDurationMs = if (song.durationMs > 2000L) minOf(rawChart.durationMs, song.durationMs) else rawChart.durationMs
        val chart = rawChart.copy(
            durationMs = trueDurationMs,
            notes = rawChart.notes.filter { it.endTimeMs <= trueDurationMs - 150L }
        )

        if (!song.isBuiltIn && song.bpm == 0) {
            val list = loadImportedIndex().map {
                if (it.id == song.id) it.copy(bpm = chart.bpm, durationMs = chart.durationMs) else it
            }
            saveImportedIndex(list)
        }

        runCatching { chartFile.writeText(chart.toJson().toString()) }
        onStatus?.invoke("谱面生成并已持久化 (${chart.notes.size} 音符)", 1f)
        chart
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
    }

    private fun loadImportedIndex(): List<SongItem> {
        if (!importedIndexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(importedIndexFile.readText())
            val list = mutableListOf<SongItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val path = o.getString("audioPath")
                if (File(path).exists()) {
                    list.add(
                        SongItem(
                            id = o.getString("id"),
                            title = o.getString("title"),
                            subtitle = o.optString("subtitle", "自定义音频 · 自动谱面"),
                            artist = o.optString("artist", "本地导入"),
                            audioPath = path,
                            bpm = o.optInt("bpm", 0),
                            durationMs = o.optLong("durationMs", 60_000L),
                            isBuiltIn = false,
                            isFromVideo = o.optBoolean("isFromVideo", false),
                            accentHex = o.optLong("accentHex", 0xFFB388FF)
                        )
                    )
                }
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun saveImportedIndex(songs: List<SongItem>) {
        val arr = JSONArray()
        songs.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("subtitle", s.subtitle)
                put("artist", s.artist)
                put("audioPath", s.audioPath)
                put("bpm", s.bpm)
                put("durationMs", s.durationMs)
                put("isFromVideo", s.isFromVideo)
                put("accentHex", s.accentHex)
            })
        }
        importedIndexFile.writeText(arr.toString())
    }
}
