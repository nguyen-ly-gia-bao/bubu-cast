package com.bubu.cast.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.Locale

/** Đuôi file video nhận diện được */
private val VIDEO_EXTENSIONS = listOf(
    ".mp4", ".m3u8", ".webm", ".mkv", ".mov", ".avi", ".flv", ".ts", ".m4v"
)

/** Kiểm tra URL có phải luồng video không (bỏ qua blob: vì không share được) */
fun isVideoUrl(url: String): Boolean {
    if (url.startsWith("blob:")) return false
    val lower = url.lowercase(Locale.US).substringBefore("?")
    return VIDEO_EXTENSIONS.any { lower.endsWith(it) } ||
        lower.contains(".m3u8") // HLS đôi khi nằm trong query
}

/** Chuẩn hóa input thành URL (thêm https:// hoặc tìm Google) */
fun normalizeUrl(input: String): String {
    val t = input.trim()
    if (t.isEmpty()) return ""
    if (t.startsWith("http://") || t.startsWith("https://")) return t
    return if (t.contains(".") && !t.contains(" ")) {
        "https://$t"
    } else {
        "https://www.google.com/search?q=${Uri.encode(t)}"
    }
}

/** Bridge nhận danh sách video từ JavaScript */
private class VideoJsBridge(
    private val onVideos: (List<String>) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onVideosFound(json: String) {
        try {
            val arr = JSONArray(json)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val u = arr.optString(i)
                if (u.isNotEmpty() && isVideoUrl(u) && !list.contains(u)) {
                    list.add(u)
                }
            }
            if (list.isNotEmpty()) {
                main.post { onVideos(list) }
            }
        } catch (_: Exception) { /* bỏ qua JSON lỗi */ }
    }
}

/**
 * WebViewClient với chặn QC 5 lớp (dùng chung cho BubuWebView và BrowserTabManager).
 *  1) shouldInterceptRequest + AdBlocker.isBlocked (KHÔNG truyền type=0)
 *  2) Chặn popup trong onPageFinished (JS)
 *  3) Inject CSS cosmetic trong onPageFinished (JS)
 *  4) Ẩn text "nhà tài trợ"/"tin888" trong onPageFinished (JS)
 */
internal class BubuWebViewClient(
    private val adBlocker: AdBlocker,
    private val adblockEnabled: () -> Boolean,
    private val onVideoFound: (String) -> Unit,
    private val onAdBlocked: () -> Unit,
    private val onPageStarted: (String) -> Unit,
    private val onPageFinished: (String, String?) -> Unit
) : WebViewClient() {

    private val main = Handler(Looper.getMainLooper())

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url.toString()

        // Phát hiện video từ network request
        if (isVideoUrl(url)) {
            main.post { onVideoFound(url) }
        }

        // Lớp 1: chặn QC theo EasyList
        if (adblockEnabled()) {
            val pageUrl = view.url ?: ""
            // QUAN TRỌNG: dùng guessType, không truyền 0
            val type = adBlocker.guessType(url)
            if (adBlocker.isBlocked(url, pageUrl, type)) {
                main.post { onAdBlocked() }
                return WebResourceResponse(
                    "text/plain", "utf-8", 200, "OK",
                    emptyMap(),
                    ByteArrayInputStream(ByteArray(0))
                )
            }
        }
        return null
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        main.post { onPageStarted(url) }
    }

    override fun onPageFinished(view: WebView, url: String) {
        val host = try {
            Uri.parse(url).host ?: ""
        } catch (_: Exception) { "" }
        val title = view.title

        // Lớp 2+3+4: inject JS sau khi trang load xong
        if (adblockEnabled()) {
            val css = adBlocker.getGenericCss() + adBlocker.getDomainCss(host)
            val cssEscaped = css
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
            view.evaluateJavascript(
                """
                (function() {
                    // Lop 2: chan popup
                    try { window.open = function() { return null; }; } catch(e) {}
                    document.addEventListener('click', function(ev) {
                        var t = ev.target;
                        while (t && t.tagName !== 'A') t = t.parentElement;
                        if (t && t.getAttribute('target') === '_blank') {
                            t.setAttribute('target', '_self');
                        }
                    }, true);
                    // Lop 3: CSS cosmetic
                    try {
                        var st = document.createElement('style');
                        st.textContent = "$cssEscaped";
                        document.head.appendChild(st);
                    } catch(e) {}
                    // Lop 4: an text tai tro (chi an element nho, tranh an ca trang)
                    try {
                        var els = document.querySelectorAll('div,aside,section,span');
                        for (var i = 0; i < els.length; i++) {
                            var el = els[i];
                            if (el.children.length > 3) continue;
                            var txt = (el.innerText || '').toLowerCase();
                            if (txt.indexOf('nhà tài trợ') >= 0 || txt.indexOf('tin888') >= 0) {
                                el.style.display = 'none';
                            }
                        }
                    } catch(e) {}
                    // Quet the <video> de phat hien video
                    try {
                        var found = [];
                        document.querySelectorAll('video').forEach(function(v) {
                            var s = v.currentSrc || v.src;
                            if (s) found.push(s);
                            v.querySelectorAll('source').forEach(function(src) {
                                if (src.src) found.push(src.src);
                            });
                        });
                        if (found.length > 0 && window.BubuVideo) {
                            window.BubuVideo.onVideosFound(JSON.stringify(found));
                        }
                    } catch(e) {}
                })();
                """.trimIndent(),
                null
            )
        } else {
            // Dù tắt QC vẫn quét video
            view.evaluateJavascript(
                """
                (function() {
                    try {
                        var found = [];
                        document.querySelectorAll('video').forEach(function(v) {
                            var s = v.currentSrc || v.src;
                            if (s) found.push(s);
                        });
                        if (found.length > 0 && window.BubuVideo) {
                            window.BubuVideo.onVideosFound(JSON.stringify(found));
                        }
                    } catch(e) {}
                })();
                """.trimIndent(),
                null
            )
        }

        main.post { onPageFinished(url, title) }
    }
}

/**
 * Composable bọc Android WebView.
 * Lifecycle: destroy khi composable bị hủy (DisposableEffect).
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun BubuWebView(
    initialUrl: String,
    adBlocker: AdBlocker,
    adblockEnabled: Boolean,
    jsEnabled: Boolean,
    onVideoFound: (String) -> Unit,
    onAdBlocked: () -> Unit,
    onPageStarted: (String) -> Unit = {},
    onPageFinished: (String, String?) -> Unit = { _, _ -> },
    onWebViewCreated: (WebView) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Giữ callback mới nhất để WebViewClient không bị stale
    val currentOnVideoFound = remember(onVideoFound) { onVideoFound }
    val currentOnAdBlocked = remember(onAdBlocked) { onAdBlocked }
    val enabledState = remember(adblockEnabled) { adblockEnabled }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = jsEnabled
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false

                addJavascriptInterface(
                    VideoJsBridge { urls -> urls.forEach { currentOnVideoFound(it) } },
                    "BubuVideo"
                )

                webViewClient = BubuWebViewClient(
                    adBlocker = adBlocker,
                    adblockEnabled = { enabledState },
                    onVideoFound = currentOnVideoFound,
                    onAdBlocked = currentOnAdBlocked,
                    onPageStarted = onPageStarted,
                    onPageFinished = onPageFinished
                )

                loadUrl(initialUrl)
                onWebViewCreated(this)
            }
        },
        update = { wv ->
            // Cập nhật JS khi setting đổi
            wv.settings.javaScriptEnabled = jsEnabled
        },
        modifier = modifier,
        onRelease = { wv ->
            // Lifecycle đúng: hủy WebView khi không dùng nữa
            wv.stopLoading()
            wv.removeJavascriptInterface("BubuVideo")
            wv.destroy()
        }
    )

    DisposableEffect(Unit) {
        onDispose { /* AndroidView onRelease đã xử lý destroy */ }
    }
}
