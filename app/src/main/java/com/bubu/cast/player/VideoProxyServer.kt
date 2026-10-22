package com.bubu.cast.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Proxy server nội bộ trên điện thoại (port 8081).
 *
 * Giải quyết triệt để vụ host chặn thiếu header/cookie:
 *  - Phone tải video bằng đúng header/cookie của WebView
 *  - Serve lại qua http://<phone_ip>:8081/proxy?url=<encoded>
 *  - TV chỉ cần phát URL local này (không cần auth)
 *
 * Hỗ trợ:
 *  - Range request (để seek được)
 *  - HLS: rewrite URL segment trong playlist .m3u8 qua proxy
 *
 * Tự viết bằng ServerSocket (không thêm dependency).
 */
class VideoProxyServer(private val context: Context) {

    companion object {
        const val PORT = 8081
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    /** Header mặc định gửi kèm (lấy từ WebView: User-Agent, Referer, Cookie) */
    @Volatile
    var defaultHeaders: Map<String, String> = emptyMap()

    /** Server đang chạy không */
    fun isRunning(): Boolean = running.get() && serverSocket?.isBound == true

    /** Bắt đầu server (idempotent) */
    fun start() {
        if (running.get()) return
        scope.launch {
            try {
                val server = ServerSocket(PORT)
                serverSocket = server
                running.set(true)
                while (running.get()) {
                    try {
                        val client = server.accept()
                        // Xử lý mỗi connection trên thread riêng
                        scope.launch { handleClient(client) }
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
            } catch (_: Exception) {
                running.set(false)
            }
        }
    }

    /** Dừng server */
    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) { }
        serverSocket = null
    }

    /** Giải phóng */
    fun release() {
        stop()
        scope.cancel()
    }

    /**
     * Tạo URL proxy cho 1 URL gốc.
     * VD: http://192.168.2.5:8081/proxy?url=https%3A%2F%2F...
     */
    fun getProxyUrl(originalUrl: String): String? {
        val phoneIp = getPhoneIp() ?: return null
        if (!isRunning()) start()
        return "http://$phoneIp:$PORT/proxy?url=${URLEncoder.encode(originalUrl, "UTF-8")}"
    }

    /** Lấy IP WiFi của điện thoại */
    fun getPhoneIp(): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(net) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        } catch (_: Exception) {
            return null
        }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        // Bỏ qua IP link-local
                        if (!ip.startsWith("169.254.")) return ip
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    /** Xử lý 1 HTTP connection từ TV */
    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val out = s.getOutputStream()

                // Đọc request line: GET /proxy?url=... HTTP/1.1
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2 || parts[0] != "GET") {
                    sendError(out, 405, "Method Not Allowed")
                    return
                }

                val path = parts[1]
                if (!path.startsWith("/proxy?")) {
                    sendError(out, 404, "Not Found")
                    return
                }

                // Lấy tham số url
                val query = path.substringAfter("?", "")
                val params = parseQuery(query)
                val encodedUrl = params["url"] ?: run {
                    sendError(out, 400, "Missing url")
                    return
                }
                val originalUrl = URLDecoder.decode(encodedUrl, "UTF-8")

                // Đọc headers từ client (để lấy Range)
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(":").trim()
                    }
                }

                // Forward tới URL gốc
                forwardRequest(originalUrl, rangeHeader, out)
            }
        } catch (_: Exception) {
            // Client ngắt kết nối giữa chừng — bỏ qua
        }
    }

    /** Forward request tới URL gốc với đầy đủ header, stream về client */
    private fun forwardRequest(
        originalUrl: String,
        rangeHeader: String?,
        out: OutputStream
    ) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(originalUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true

            // Gửi header của WebView (quan trọng!)
            defaultHeaders.forEach { (k, v) ->
                conn.setRequestProperty(k, v)
            }
            // Forward Range để seek được
            if (rangeHeader != null) {
                conn.setRequestProperty("Range", rangeHeader)
            }

            val responseCode = conn.responseCode

            // Kiểm tra có phải HLS playlist không
            val contentType = conn.contentType ?: ""
            val isHls = originalUrl.lowercase().contains(".m3u8") ||
                contentType.contains("mpegurl", ignoreCase = true) ||
                contentType.contains("m3u8", ignoreCase = true)

            if (isHls) {
                // Rewrite playlist: đổi URL segment thành qua proxy
                val playlist = conn.inputStream.bufferedReader().readText()
                val rewritten = rewriteHlsPlaylist(playlist, originalUrl)
                val bytes = rewritten.toByteArray(Charsets.UTF_8)
                sendResponse(
                    out = out,
                    code = 200,
                    message = "OK",
                    headers = mapOf(
                        "Content-Type" to "application/x-mpegURL",
                        "Content-Length" to bytes.size.toString(),
                        "Access-Control-Allow-Origin" to "*",
                        "Connection" to "close"
                    ),
                    body = bytes
                )
                return
            }

            // Stream trực tiếp (video/mp4, webm...)
            val responseHeaders = mutableMapOf<String, String>()
            conn.headerFields.forEach { (key, values) ->
                if (key != null && values.isNotEmpty()) {
                    // Forward các header quan trọng
                    when (key.lowercase()) {
                        "content-type", "content-length", "content-range",
                        "accept-ranges", "content-disposition" ->
                            responseHeaders[key] = values[0]
                    }
                }
            }
            responseHeaders["Access-Control-Allow-Origin"] = "*"
            responseHeaders["Connection"] = "close"

            // Gửi status line + headers
            val statusMessage = when (responseCode) {
                206 -> "Partial Content"
                200 -> "OK"
                else -> "OK"
            }
            val headerStr = buildString {
                append("HTTP/1.1 $responseCode $statusMessage\r\n")
                responseHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
                append("\r\n")
            }
            out.write(headerStr.toByteArray(Charsets.UTF_8))
            out.flush()

            // Stream body
            val input = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            input?.use { ins ->
                val buffer = ByteArray(32 * 1024)
                var read: Int
                while (ins.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                }
                out.flush()
            }
        } catch (e: Exception) {
            try {
                sendError(out, 502, "Proxy error: ${e.message}")
            } catch (_: Exception) { }
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Rewrite HLS playlist: đổi mọi URL segment thành qua proxy.
     * Xử lý cả URL tuyệt đối và tương đối.
     */
    private fun rewriteHlsPlaylist(playlist: String, playlistUrl: String): String {
        val baseUrl = playlistUrl.substringBeforeLast("/") + "/"
        return playlist.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> {
                    // Rewrite URI trong tag (vd #EXT-X-KEY:METHOD=AES-128,URI="key.key")
                    if (trimmed.contains("URI=\"")) {
                        val uriRegex = """URI="([^"]+)"""".toRegex()
                        uriRegex.replace(trimmed) { match ->
                            val original = match.groupValues[1]
                            val absolute = resolveUrl(original, baseUrl, playlistUrl)
                            """URI="${getProxyUrlForRewrite(absolute)}""""
                        }
                    } else line
                }
                else -> {
                    // Dòng URL segment
                    val absolute = resolveUrl(trimmed, baseUrl, playlistUrl)
                    getProxyUrlForRewrite(absolute)
                }
            }
        }
    }

    /** Resolve URL tương đối thành tuyệt đối */
    private fun resolveUrl(url: String, baseUrl: String, playlistUrl: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> {
                val domain = playlistUrl.substringBefore("/", playlistUrl.indexOf("://") + 3)
                    .let {
                        // Lấy scheme://host
                        val schemeEnd = playlistUrl.indexOf("://") + 3
                        val hostEnd = playlistUrl.indexOf("/", schemeEnd)
                        if (hostEnd > 0) playlistUrl.substring(0, hostEnd) else playlistUrl
                    }
                domain + url
            }
            else -> baseUrl + url
        }
    }

    /** Tạo URL proxy cho rewrite (dùng IP hiện tại) */
    private fun getProxyUrlForRewrite(absoluteUrl: String): String {
        val phoneIp = getPhoneIp() ?: "127.0.0.1"
        return "http://$phoneIp:$PORT/proxy?url=${URLEncoder.encode(absoluteUrl, "UTF-8")}"
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split("&").mapNotNull { param ->
            val idx = param.indexOf("=")
            if (idx > 0) {
                param.substring(0, idx) to param.substring(idx + 1)
            } else null
        }.toMap()
    }

    private fun sendResponse(
        out: OutputStream,
        code: Int,
        message: String,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        val headerStr = buildString {
            append("HTTP/1.1 $code $message\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(headerStr.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun sendError(out: OutputStream, code: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        sendResponse(
            out, code, message,
            mapOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "Content-Length" to body.size.toString(),
                "Connection" to "close"
            ),
            body
        )
    }
}
