package com.astraecho.rhythm.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DownloadedLinkMedia(
    val audioFile: File,
    val title: String,
    val artist: String,
    val isFromVideo: Boolean
)

/**
 * Resolves and downloads audio/video from:
 * 1. Douyin share text / short links (e.g. "复制打开抖音... https://v.douyin.com/xxx/")
 * 2. Direct audio or video URLs (.mp3, .m4a, .wav, .mp4, etc.)
 * 3. Optional user-configured fallback parser API endpoint.
 *
 * Note: Respects platform access controls — if a page presents a CAPTCHA / verification gate,
 * stops immediately and reports a clear error without attempting to bypass it.
 */
object LinkMediaImporter {

    private const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"

    private val urlRegex = Regex("""https?://[^\s"'<>]+""")
    private val douyinIdRegex = Regex("""(?:video|note|slides)/(\d{15,22})""")
    private val rawIdRegex = Regex("""(\d{18,21})""")

    /**
     * Extracts all HTTP/HTTPS URLs from pasted share text or multi-line link input.
     */
    fun extractUrls(rawText: String): List<String> {
        return urlRegex.findAll(rawText)
            .map { it.value.trimEnd(',', '.', ')', ']', '，', '。', '！', '!', ';', '；') }
            .distinct()
            .toList()
    }

    fun isDouyinUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("douyin.com") || lower.contains("iesdouyin.com")
    }

    suspend fun downloadAndExtract(
        context: Context,
        rawUrl: String,
        destDir: File,
        songId: String,
        customParserApiUrl: String = "",
        onStatus: ((String, Float) -> Unit)? = null
    ): DownloadedLinkMedia {
        if (!destDir.exists()) destDir.mkdirs()

        val resolved = when {
            customParserApiUrl.isNotBlank() && isDouyinUrl(rawUrl) -> {
                onStatus?.invoke("正在通过自定义接口解析链接...", 0.12f)
                resolveViaCustomApi(rawUrl, customParserApiUrl)
            }
            isDouyinUrl(rawUrl) -> {
                onStatus?.invoke("正在解析抖音分享链接...", 0.12f)
                resolveDouyinShareUrl(context, rawUrl, onStatus)
            }
            else -> {
                val guessedName = rawUrl.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
                    .takeIf { it.isNotBlank() } ?: "网络音频"
                ResolvedStream(
                    mediaUrl = rawUrl,
                    title = guessedName,
                    artist = "网络链接导入",
                    preferVideoDemux = rawUrl.substringBefore('?').endsWith(".mp4", ignoreCase = true)
                )
            }
        }

        onStatus?.invoke("正在下载媒体流...", 0.30f)
        val tmpDownload = File(context.cacheDir, "dl_$songId.bin")
        try {
            val contentType = downloadToFile(
                urlStr = resolved.mediaUrl,
                dest = tmpDownload,
                cookies = resolved.cookies,
                userAgent = resolved.userAgent
            ) { p ->
                onStatus?.invoke("正在下载媒体流...", 0.30f + p * 0.40f)
            }

            val isVideo = resolved.preferVideoDemux ||
                contentType.startsWith("video/", ignoreCase = true) ||
                resolved.mediaUrl.substringBefore('?').lowercase().let {
                    it.endsWith(".mp4") || it.endsWith(".mov") || it.endsWith(".mkv") || it.endsWith(".webm")
                }

            val finalFile: File
            val fromVideo: Boolean
            if (isVideo) {
                val extracted = MediaAudioExtractor.extractAudioFromLocalVideoFile(
                    videoFile = tmpDownload,
                    destDir = destDir,
                    songId = songId,
                    onStatus = { msg, p -> onStatus?.invoke(msg, 0.70f + p * 0.30f) }
                )
                finalFile = extracted.audioFile
                fromVideo = true
            } else {
                val ext = guessAudioExt(resolved.mediaUrl, contentType)
                finalFile = File(destDir, "$songId.$ext")
                tmpDownload.copyTo(finalFile, overwrite = true)
                fromVideo = false
                onStatus?.invoke("音频下载完成", 1f)
            }

            return DownloadedLinkMedia(
                audioFile = finalFile,
                title = resolved.title.ifBlank { "抖音音轨_$songId" },
                artist = resolved.artist.ifBlank { "链接导入" },
                isFromVideo = fromVideo
            )
        } finally {
            runCatching { tmpDownload.delete() }
        }
    }

    /**
     * Downloads an already-captured stream (from WebViewLinkCaptureDialog) and extracts audio if video.
     */
    fun downloadCapturedMedia(
        context: Context,
        captured: WebViewCapturedMedia,
        destDir: File,
        songId: String,
        onStatus: ((String, Float) -> Unit)? = null
    ): DownloadedLinkMedia {
        if (!destDir.exists()) destDir.mkdirs()
        onStatus?.invoke("正在下载捕获的媒体流...", 0.20f)
        val tmpDownload = File(context.cacheDir, "dl_$songId.bin")
        try {
            val contentType = downloadToFile(
                urlStr = captured.mediaUrl,
                dest = tmpDownload,
                cookies = captured.cookies,
                userAgent = captured.userAgent
            ) { p ->
                onStatus?.invoke("正在下载捕获的媒体流...", 0.20f + p * 0.45f)
            }

            val isVideo = captured.isVideo || contentType.startsWith("video/", ignoreCase = true)
            val finalFile: File
            val fromVideo: Boolean
            if (isVideo) {
                val extracted = MediaAudioExtractor.extractAudioFromLocalVideoFile(
                    videoFile = tmpDownload,
                    destDir = destDir,
                    songId = songId,
                    onStatus = { msg, p -> onStatus?.invoke(msg, 0.65f + p * 0.35f) }
                )
                finalFile = extracted.audioFile
                fromVideo = true
            } else {
                val ext = guessAudioExt(captured.mediaUrl, contentType)
                finalFile = File(destDir, "$songId.$ext")
                tmpDownload.copyTo(finalFile, overwrite = true)
                fromVideo = false
                onStatus?.invoke("音频下载完成", 1f)
            }

            return DownloadedLinkMedia(
                audioFile = finalFile,
                title = captured.title.ifBlank { "抖音音轨_$songId" }.take(38),
                artist = "抖音链接导入",
                isFromVideo = fromVideo
            )
        } finally {
            runCatching { tmpDownload.delete() }
        }
    }

    private data class ResolvedStream(
        val mediaUrl: String,
        val title: String,
        val artist: String,
        val preferVideoDemux: Boolean,
        val cookies: String = "",
        val userAgent: String = MOBILE_UA
    )

    private fun resolveViaCustomApi(targetUrl: String, apiTemplate: String): ResolvedStream {
        val encoded = URLEncoder.encode(targetUrl, "UTF-8")
        val endpoint = if (apiTemplate.contains("{url}")) {
            apiTemplate.replace("{url}", encoded)
        } else if (apiTemplate.contains("?")) {
            "$apiTemplate&url=$encoded"
        } else {
            "$apiTemplate?url=$encoded"
        }
        val body = httpGetString(endpoint, followRedirects = true)
        val json = runCatching { JSONObject(body) }.getOrElse {
            error("自定义解析接口返回的不是有效 JSON")
        }
        val data = json.optJSONObject("data") ?: json
        val audioUrl = firstNonBlank(
            data.optString("music_url"),
            data.optString("audio_url"),
            data.optString("music"),
            data.optString("audio")
        )
        val videoUrl = firstNonBlank(
            data.optString("video_url"),
            data.optString("play_url"),
            data.optString("url")
        )
        val chosen = audioUrl ?: videoUrl ?: error("自定义接口未返回音视频链接")
        val title = firstNonBlank(data.optString("title"), data.optString("desc"), "抖音链接曲目")!!
        val artist = firstNonBlank(data.optString("author"), data.optString("nickname"), "链接解析导入")!!
        return ResolvedStream(
            mediaUrl = chosen,
            title = title.take(40),
            artist = artist.take(28),
            preferVideoDemux = (audioUrl == null)
        )
    }

    private suspend fun resolveDouyinShareUrl(
        context: Context,
        shareUrl: String,
        onStatus: ((String, Float) -> Unit)?
    ): ResolvedStream {
        // 1. Resolve short link redirect to extract canonical video ID & full share URL
        val finalUrl = resolveRedirectUrl(shareUrl)
        val videoId = douyinIdRegex.find(finalUrl)?.groupValues?.getOrNull(1)
            ?: rawIdRegex.find(finalUrl)?.groupValues?.getOrNull(1)
            ?: error("未能从抖音链接中识别作品 ID，请检查链接是否完整")

        // 2. Fast path: try static SSR JSON first
        val sharePageUrl = if (finalUrl.contains("iesdouyin.com/share/video/")) finalUrl else {
            "https://www.iesdouyin.com/share/video/$videoId/"
        }
        val html = runCatching { httpGetString(sharePageUrl, followRedirects = true) }.getOrDefault("")
        val routerJsonStr = extractRouterDataJson(html)
        if (routerJsonStr != null) {
            val item = runCatching { findItemDetailJson(JSONObject(routerJsonStr)) }.getOrNull()
            if (item != null) {
                val desc = item.optString("desc", "").trim()
                val author = item.optJSONObject("author")?.optString("nickname", "")?.trim().orEmpty()
                val musicObj = item.optJSONObject("music")
                val musicTitle = musicObj?.optString("title", "")?.trim().orEmpty()
                val musicUrl = extractFirstUrlFromList(musicObj?.optJSONObject("play_url")?.optJSONArray("url_list"))
                val videoObj = item.optJSONObject("video")
                val rawVideoUrl = extractFirstUrlFromList(videoObj?.optJSONObject("play_addr")?.optJSONArray("url_list"))
                    ?.replace("playwm", "play")
                val mediaUrl = musicUrl ?: rawVideoUrl
                if (mediaUrl != null) {
                    val cleanTitle = when {
                        desc.isNotBlank() -> desc.replace(Regex("#\\S+"), "").trim().ifBlank { desc }.take(36)
                        musicTitle.isNotBlank() -> musicTitle.take(36)
                        else -> "抖音作品_$videoId"
                    }
                    return ResolvedStream(
                        mediaUrl = mediaUrl,
                        title = cleanTitle,
                        artist = if (author.isNotBlank()) "抖音 · $author" else "抖音链接导入",
                        preferVideoDemux = (musicUrl == null)
                    )
                }
            }
        }

        // 3. Modern dynamic SSR path: render the public share page in Android WebView to capture its media stream!
        onStatus?.invoke("正在通过内置浏览器加载抖音分享页...", 0.20f)
        val captured = WebViewMediaResolver.captureMediaStream(context, sharePageUrl)
            ?: error("未能从抖音分享页获取到媒体流（可能该作品受限、需登录或触发平台校验，可改用「系统分享本地视频」或在设置中填写备用解析接口）")

        return ResolvedStream(
            mediaUrl = captured.mediaUrl,
            title = captured.title.ifBlank { "抖音作品_$videoId" }.take(38),
            artist = "抖音链接导入",
            preferVideoDemux = captured.isVideo,
            cookies = captured.cookies,
            userAgent = captured.userAgent
        )
    }

    internal fun extractRouterDataJson(html: String): String? {
        val marker = "window._ROUTER_DATA"
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val eq = html.indexOf('=', idx)
        if (eq < 0) return null
        val braceStart = html.indexOf('{', eq)
        if (braceStart < 0) return null
        val scriptEnd = html.indexOf("</script>", braceStart)
        val raw = (if (scriptEnd > braceStart) html.substring(braceStart, scriptEnd) else html.substring(braceStart))
            .trim()
            .trimEnd(';')
        return raw.takeIf { it.startsWith("{") && it.endsWith("}") }
    }

    private fun findItemDetailJson(obj: JSONObject): JSONObject? {
        if (obj.has("video") && (obj.has("desc") || obj.has("music"))) {
            return obj
        }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k)
            when (v) {
                is JSONObject -> {
                    val found = findItemDetailJson(v)
                    if (found != null) return found
                }
                is JSONArray -> {
                    for (i in 0 until v.length()) {
                        val elem = v.optJSONObject(i) ?: continue
                        val found = findItemDetailJson(elem)
                        if (found != null) return found
                    }
                }
            }
        }
        return null
    }

    private fun extractFirstUrlFromList(arr: JSONArray?): String? {
        if (arr == null) return null
        for (i in 0 until arr.length()) {
            val u = arr.optString(i).trim()
            if (u.startsWith("http://") || u.startsWith("https://")) return u
        }
        return null
    }

    private fun resolveRedirectUrl(startUrl: String): String {
        var current = startUrl
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", MOBILE_UA)
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return current
                    current = URL(URL(current), loc).toString()
                } else {
                    return current
                }
            } finally {
                conn.disconnect()
            }
        }
        return current
    }

    private fun httpGetString(urlStr: String, followRedirects: Boolean): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = followRedirects
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", MOBILE_UA)
            setRequestProperty("Accept", "text/html,application/json,*/*")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("网络请求失败 (HTTP $code)")
            text
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadToFile(
        urlStr: String,
        dest: File,
        cookies: String = "",
        userAgent: String = MOBILE_UA,
        onProgress: ((Float) -> Unit)?
    ): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Referer", "https://www.iesdouyin.com/")
            if (cookies.isNotBlank()) {
                setRequestProperty("Cookie", cookies)
            }
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) error("媒体下载失败 (HTTP $code)")
            val contentType = conn.contentType.orEmpty()
            val totalBytes = conn.contentLengthLong
            var downloaded = 0L

            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (totalBytes > 0L) {
                            onProgress?.invoke((downloaded.toFloat() / totalBytes).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (downloaded < 1024L) error("下载到的媒体文件过小或为空")
            contentType
        } finally {
            conn.disconnect()
        }
    }

    private fun guessAudioExt(url: String, contentType: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".mp3") || contentType.contains("mpeg") -> "mp3"
            path.endsWith(".m4a") || contentType.contains("mp4") || contentType.contains("aac") -> "m4a"
            path.endsWith(".wav") || contentType.contains("wav") -> "wav"
            path.endsWith(".ogg") || contentType.contains("ogg") -> "ogg"
            path.endsWith(".flac") || contentType.contains("flac") -> "flac"
            else -> "mp3"
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()
}
