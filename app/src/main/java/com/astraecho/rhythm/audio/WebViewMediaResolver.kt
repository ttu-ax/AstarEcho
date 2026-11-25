package com.astraecho.rhythm.audio

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class WebViewCapturedMedia(
    val mediaUrl: String,
    val title: String,
    val cookies: String,
    val userAgent: String,
    val isVideo: Boolean
)

/**
 * Loads a dynamic JS-rendered share page (such as modern Douyin iesdouyin.com pages)
 * in a standard Android WebView and observes the actual audio/video stream URL
 * requested by the page's media player.
 *
 * Respects platform gates: if no media stream loads within the timeout (e.g. login or verification required),
 * returns null cleanly without attempting any bypass.
 */
object WebViewMediaResolver {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun captureMediaStream(
        context: Context,
        pageUrl: String,
        timeoutMs: Long = 16_000L
    ): WebViewCapturedMedia? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val webView = WebView(context.applicationContext)
                var resolved = false

                fun finish(result: WebViewCapturedMedia?) {
                    if (resolved) return
                    resolved = true
                    runCatching {
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.destroy()
                    }
                    if (cont.isActive) cont.resume(result)
                }

                val ua = webView.settings.userAgentString
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val scheme = request?.url?.scheme?.lowercase().orEmpty()
                        return scheme != "http" && scheme != "https"
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        if (!resolved && isTargetMediaStream(url)) {
                            val isVid = isLikelyVideoStream(url)
                            val cookies = runCatching {
                                CookieManager.getInstance().getCookie(url).orEmpty()
                            }.getOrDefault("")
                            webView.post {
                                val pageTitle = webView.title?.trim().orEmpty()
                                    .removeSuffix(" - 抖音")
                                    .removeSuffix("-抖音")
                                    .trim()
                                finish(
                                    WebViewCapturedMedia(
                                        mediaUrl = url,
                                        title = pageTitle,
                                        cookies = cookies,
                                        userAgent = ua,
                                        isVideo = isVid
                                    )
                                )
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (resolved) return
                        // Also inspect rendered <video>/<audio> src elements directly once page finishes!
                        view?.evaluateJavascript(
                            "(function(){var v=document.querySelector('video,audio');if(v){return v.currentSrc||v.src||(v.querySelector('source')?v.querySelector('source').src:'');}return '';})()"
                        ) { raw ->
                            val cleaned = raw?.trim()?.trim('"')?.replace("\\u002F", "/")?.replace("\\/", "/").orEmpty()
                            if (!resolved && (cleaned.startsWith("http://") || cleaned.startsWith("https://"))) {
                                val cookies = runCatching {
                                    CookieManager.getInstance().getCookie(cleaned).orEmpty()
                                }.getOrDefault("")
                                val pageTitle = view.title?.trim().orEmpty()
                                    .removeSuffix(" - 抖音")
                                    .removeSuffix("-抖音")
                                    .trim()
                                finish(
                                    WebViewCapturedMedia(
                                        mediaUrl = cleaned,
                                        title = pageTitle,
                                        cookies = cookies,
                                        userAgent = ua,
                                        isVideo = isLikelyVideoStream(cleaned)
                                    )
                                )
                            }
                        }
                    }
                }

                cont.invokeOnCancellation {
                    webView.post { finish(null) }
                }

                webView.loadUrl(pageUrl)
            }
        }
    }

    internal fun isTargetMediaStream(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".png") ||
            lower.endsWith(".jpg") || lower.endsWith(".webp") || lower.endsWith(".svg") ||
            lower.contains("favicon") || lower.contains(".woff") || lower.contains(".ttf")
        ) return false

        return lower.contains("douyinvod.com") ||
            lower.contains("zjcdn.com") && (lower.contains("video") || lower.contains("tos")) ||
            lower.contains("aweme.snssdk.com/aweme/v1/play") ||
            lower.contains("/video/tos/") ||
            lower.contains("mime_type=video") ||
            lower.contains("mime_type=audio") ||
            lower.substringBefore('?').let { p ->
                p.endsWith(".mp4") || p.endsWith(".mp3") || p.endsWith(".m4a") || p.endsWith(".aac")
            }
    }

    internal fun isLikelyVideoStream(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore('?')
        if (path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac") || lower.contains("mime_type=audio")) {
            return false
        }
        return true
    }
}
