package com.astraecho.rhythm.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.astraecho.rhythm.audio.WebViewCapturedMedia
import com.astraecho.rhythm.audio.WebViewMediaResolver
import com.astraecho.rhythm.ui.theme.AstraColors

/**
 * Visible in-app WebView dialog for dynamic Douyin share pages.
 * Lets the user see the page directly (tap Play if autoplay is blocked or complete any prompt),
 * and automatically captures the audio/video stream the moment playback starts!
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewLinkCaptureDialog(
    pageUrl: String,
    onCaptured: (WebViewCapturedMedia) -> Unit,
    onDismiss: () -> Unit
) {
    var statusText by remember { mutableStateOf("正在加载页面，视频开始播放后将自动捕获音轨（若未自动播放请点击画面播放）") }
    var alreadyCaptured by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = AstraColors.DeepIndigo,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.5.dp, AstraColors.NeonCyan),
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("抖音分享页 · 音轨捕获", color = AstraColors.NeonCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(statusText, color = AstraColors.TextSecondary, fontSize = 11.sp, maxLines = 1)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭", color = AstraColors.ElectricMagenta, fontWeight = FontWeight.Bold)
                    }
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            val ua = settings.userAgentString
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                mediaPlaybackRequiresUserGesture = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                useWideViewPort = true
                                loadWithOverviewMode = true
                            }
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val scheme = request?.url?.scheme?.lowercase().orEmpty()
                                    // Block snssdk1128://, intent://, market:// deep-link redirects so the web page stays open!
                                    return scheme != "http" && scheme != "https"
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val url = request?.url?.toString() ?: return null
                                    if (!alreadyCaptured && WebViewMediaResolver.isTargetMediaStream(url)) {
                                        val isVid = WebViewMediaResolver.isLikelyVideoStream(url)
                                        val cookies = runCatching {
                                            CookieManager.getInstance().getCookie(url).orEmpty()
                                        }.getOrDefault("")
                                        post {
                                            if (!alreadyCaptured) {
                                                alreadyCaptured = true
                                                statusText = "已捕获音视频流，正在转入后台下载与编谱..."
                                                val pageTitle = title?.trim().orEmpty()
                                                    .removeSuffix(" - 抖音")
                                                    .removeSuffix("-抖音")
                                                    .trim()
                                                onCaptured(
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
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (alreadyCaptured) return
                                    // Trigger play on visible video if present & inspect src
                                    view?.evaluateJavascript(
                                        "(function(){var v=document.querySelector('video,audio');if(v){try{v.muted=true;v.play();}catch(e){} return v.currentSrc||v.src||(v.querySelector('source')?v.querySelector('source').src:'');}return '';})()"
                                    ) { raw ->
                                        val cleaned = raw?.trim()?.trim('"')?.replace("\\u002F", "/")?.replace("\\/", "/").orEmpty()
                                        if (!alreadyCaptured && (cleaned.startsWith("http://") || cleaned.startsWith("https://"))) {
                                            alreadyCaptured = true
                                            val cookies = runCatching {
                                                CookieManager.getInstance().getCookie(cleaned).orEmpty()
                                            }.getOrDefault("")
                                            val pageTitle = view.title?.trim().orEmpty()
                                                .removeSuffix(" - 抖音")
                                                .removeSuffix("-抖音")
                                                .trim()
                                            onCaptured(
                                                WebViewCapturedMedia(
                                                    mediaUrl = cleaned,
                                                    title = pageTitle,
                                                    cookies = cookies,
                                                    userAgent = ua,
                                                    isVideo = WebViewMediaResolver.isLikelyVideoStream(cleaned)
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                            loadUrl(pageUrl)
                        }
                    }
                )
            }
        }
    }
}
