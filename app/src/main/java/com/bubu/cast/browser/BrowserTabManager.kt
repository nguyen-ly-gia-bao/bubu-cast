package com.bubu.cast.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Thông tin video phát hiện được (kèm tab nguồn) */
data class DetectedVideo(
    val url: String,
    val tabId: Long,
    val pageTitle: String = "",
    // Thông tin thêm nếu đoán được từ URL
    val fileName: String = "",
    val isHls: Boolean = false,
    // Metadata từ HEAD request (có thể null nếu chưa probe)
    val mimeType: String = "",
    val sizeBytes: Long = -1L,
    val durationSec: Long = -1L
) {
    /** Chuỗi hiển thị định dạng (màu xanh lá): "mp4 (1920x1080 35,9MB)" */
    fun formatInfo(): String {
        val format = when {
            mimeType.contains("mpegurl", ignoreCase = true) || isHls -> "m3u8 (HLS)"
            mimeType.contains("webm", ignoreCase = true) -> "webm"
            mimeType.contains("matroska", ignoreCase = true) -> "mkv"
            mimeType.contains("mp4", ignoreCase = true) -> "mp4"
            fileName.lowercase().endsWith(".mp4") -> "mp4"
            fileName.lowercase().endsWith(".webm") -> "webm"
            fileName.lowercase().endsWith(".mkv") -> "mkv"
            else -> "video"
        }
        val sizeStr = if (sizeBytes > 0) " ${formatSize(sizeBytes)}" else ""
        return "$format$sizeStr"
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) "%.1fMB".format(mb).replace(".", ",")
        else "%dKB".format(bytes / 1024)
    }
}

/** Một tab trình duyệt với WebView độc lập */
data class BrowserTab(
    val id: Long,
    val webView: WebView,
    var title: String = "Tab mới",
    var url: String = "",
    var progress: Int = 0
)

/**
 * Quản lý nhiều tab WebView độc lập.
 *
 * Quy tắc chống lỗi tab cũ:
 *  1) Mỗi tab có WebView + WebViewClient RIÊNG (không dùng chung)
 *  2) Chuyển tab: tab cũ -> GONE, tab mới -> VISIBLE (chỉ 1 VISIBLE)
 *  3) onProgressChanged chỉ cập nhật cho tab đang active
 *  4) Video/AdBlock callback gắn với tabId để không lẫn
 */
class BrowserTabManager(
    private val context: Context,
    private val adBlocker: AdBlocker,
    private val adblockEnabled: () -> Boolean,
    private val jsEnabled: () -> Boolean,
    private val onVideoFound: (DetectedVideo) -> Unit,
    private val onAdBlocked: () -> Unit,
    private val onTabUpdated: () -> Unit = {}
) {
    /** Container chứa tất cả WebView (tạo 1 lần, dùng cho AndroidView) */
    val container: FrameLayout = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(0xFF0F1419.toInt())
    }

    /** Danh sách tab (observable cho Compose) */
    val tabs = mutableStateListOf<BrowserTab>()

    /** Index tab đang active */
    var currentIndex by mutableIntStateOf(0)
        private set

    /** Tab đang active (null nếu chưa có tab nào) */
    val currentTab: BrowserTab?
        get() = tabs.getOrNull(currentIndex)

    private var nextId = 1L

    private val _blockedCount = MutableStateFlow(0)
    val blockedCount: StateFlow<Int> = _blockedCount.asStateFlow()

    /** Tạo tab mới với WebView độc lập */
    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun newTab(url: String, switchTo: Boolean = true): BrowserTab {
        val tabId = nextId++

        val webView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Ban đầu GONE, sẽ VISIBLE khi được chọn
            visibility = View.GONE

            settings.javaScriptEnabled = jsEnabled()
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false

            // WebViewClient RIÊNG cho tab này
            webViewClient = BubuWebViewClient(
                adBlocker = adBlocker,
                adblockEnabled = adblockEnabled,
                onVideoFound = { videoUrl ->
                    // Gắn tabId để không lẫn video giữa các tab
                    val title = this@apply.title ?: ""
                    onVideoFound(
                        DetectedVideo(
                            url = videoUrl,
                            tabId = tabId,
                            pageTitle = title,
                            fileName = extractFileName(videoUrl),
                            isHls = videoUrl.lowercase().contains(".m3u8")
                        )
                    )
                },
                onAdBlocked = {
                    _blockedCount.value = _blockedCount.value + 1
                    onAdBlocked()
                },
                onPageStarted = {
                    onTabUpdated()
                },
                onPageFinished = { finishedUrl, title ->
                    // Cập nhật thông tin tab
                    findTab(tabId)?.let { tab ->
                        tab.url = finishedUrl
                        tab.title = title?.ifBlank { finishedUrl } ?: finishedUrl
                    }
                    onTabUpdated()
                }
            )

            // WebChromeClient RIÊNG: progress + title
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    // CHỈ cập nhật progress cho tab đang active
                    val tab = findTab(tabId) ?: return
                    tab.progress = newProgress
                    if (tabs.getOrNull(currentIndex)?.id == tabId) {
                        onTabUpdated()
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    findTab(tabId)?.let { tab ->
                        if (!title.isNullOrBlank()) {
                            tab.title = title
                            onTabUpdated()
                        }
                    }
                }
            }

            addJavascriptInterface(
                TabVideoJsBridge { urls ->
                    val title = this@apply.title ?: ""
                    urls.forEach { videoUrl ->
                        onVideoFound(
                            DetectedVideo(
                                url = videoUrl,
                                tabId = tabId,
                                pageTitle = title,
                                fileName = extractFileName(videoUrl),
                                isHls = videoUrl.lowercase().contains(".m3u8")
                            )
                        )
                    }
                },
                "BubuVideo"
            )
        }

        val tab = BrowserTab(id = tabId, webView = webView)
        tabs.add(tab)
        container.addView(webView)

        if (url.isNotBlank() && url != "about:blank") {
            webView.loadUrl(url)
        }

        if (switchTo) {
            switchTo(tabs.size - 1)
        }

        onTabUpdated()
        return tab
    }

    /** Chuyển sang tab khác: GONE tab cũ, VISIBLE tab mới */
    fun switchTo(index: Int) {
        if (index < 0 || index >= tabs.size) return
        // Ẩn TẤT CẢ trước (đảm bảo chỉ 1 VISIBLE)
        tabs.forEach { it.webView.visibility = View.GONE }
        currentIndex = index
        val tab = tabs[index]
        tab.webView.visibility = View.VISIBLE
        // Cập nhật JS setting (có thể đổi trong Cài đặt)
        tab.webView.settings.javaScriptEnabled = jsEnabled()
        onTabUpdated()
    }

    /** Đóng tab */
    fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val tab = tabs.removeAt(index)
        container.removeView(tab.webView)
        // Hủy WebView đúng lifecycle
        tab.webView.stopLoading()
        try {
            tab.webView.removeJavascriptInterface("BubuVideo")
        } catch (_: Exception) { }
        tab.webView.destroy()

        // Điều chỉnh currentIndex
        if (tabs.isEmpty()) {
            currentIndex = 0
        } else if (currentIndex >= tabs.size) {
            switchTo(tabs.size - 1)
        } else {
            // VISIBLE lại tab hiện tại (vì đã GONE hết trong switchTo logic)
            tabs.forEach { it.webView.visibility = View.GONE }
            tabs.getOrNull(currentIndex)?.webView?.visibility = View.VISIBLE
        }
        onTabUpdated()
    }

    /** Tải URL vào tab hiện tại */
    fun loadUrl(url: String) {
        currentTab?.webView?.loadUrl(url)
    }

    fun goBack() {
        currentTab?.webView?.let { if (it.canGoBack()) it.goBack() }
    }

    fun goForward() {
        currentTab?.webView?.let { if (it.canGoForward()) it.goForward() }
    }

    fun reload() {
        currentTab?.webView?.reload()
    }

    fun canGoBack(): Boolean = currentTab?.webView?.canGoBack() == true
    fun canGoForward(): Boolean = currentTab?.webView?.canGoForward() == true

    /** Lấy headers (UA, cookie) của tab hiện tại để gửi kèm khi cast */
    fun getCurrentHeaders(): Map<String, String> {
        val tab = currentTab ?: return emptyMap()
        val wv = tab.webView
        val url = tab.url
        return buildMap {
            val ua = try { wv.settings.userAgentString } catch (_: Exception) { null }
            if (!ua.isNullOrBlank()) put("User-Agent", ua)
            if (url.isNotBlank()) put("Referer", url)
            val cookie = try {
                android.webkit.CookieManager.getInstance().getCookie(url)
            } catch (_: Exception) { null }
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
    }

    private fun findTab(id: Long): BrowserTab? = tabs.find { it.id == id }

    /** Hủy toàn bộ (gọi khi màn hình bị hủy) */
    fun destroy() {
        tabs.forEach { tab ->
            try {
                tab.webView.stopLoading()
                tab.webView.removeJavascriptInterface("BubuVideo")
                tab.webView.destroy()
            } catch (_: Exception) { }
        }
        tabs.clear()
        container.removeAllViews()
    }

    private fun extractFileName(url: String): String {
        return try {
            val path = android.net.Uri.parse(url).path ?: return url
            val name = path.substringAfterLast("/").substringBefore("?")
            if (name.length > 50) "…" + name.takeLast(49) else name.ifBlank { url }
        } catch (_: Exception) {
            url
        }
    }
}

// VideoJsBridge đã định nghĩa trong BubuWebView.kt — dùng chung (internal)
// Để tránh trùng, định nghĩa lại ở đây với tên khác nếu cần.
// Thực tế: BubuWebView.kt có `private class VideoJsBridge` nên không dùng chung được.
// Tạo bản public cho TabManager:
@Suppress("FunctionName")
internal class TabVideoJsBridge(
    private val onVideos: (List<String>) -> Unit
) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    @android.webkit.JavascriptInterface
    fun onVideosFound(json: String) {
        try {
            val arr = org.json.JSONArray(json)
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
        } catch (_: Exception) { }
    }
}
