package com.bubu.cast.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP server cho TV Receiver (port 8080), viết bằng Coroutines + ServerSocket.
 *
 * Endpoints:
 *  - GET  /ping    -> {"ok":true,"name":"Bubu Cast TV","code":"482916"}
 *  - POST /play    -> {url, title, headers:{...}, subtitles:[...]} -> phát video
 *  - POST /control -> {action:"play"|"pause"|"stop"|"seek", position?}
 *  - POST /cmd     -> tương tự /control (tương thích bản phone cũ)
 *  - GET  /status  -> {"state":"playing","position":123,"duration":456,"title":"..."}
 */
class TvReceiverServer(
    private val pairingCode: String,
    private val onPlay: (url: String, title: String, headers: Map<String, String>, subtitles: List<SubtitleInfo>) -> Unit,
    private val onControl: (action: String, positionSec: Long?) -> Unit,
    private val onGetStatus: () -> JSONObject
) {

    companion object {
        const val PORT = 8080
        const val SERVER_NAME = "Bubu Cast TV"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    /** Bắt đầu server (chạy nền) */
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
                        // Mỗi connection xử lý riêng (không chặn nhau)
                        launch { handleClient(client) }
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
            } catch (_: Exception) {
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val out = s.getOutputStream()

                // Đọc request line
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    sendJson(out, 400, JSONObject().put("ok", false).put("error", "Bad request"))
                    return
                }
                val method = parts[0].uppercase()
                val rawPath = parts[1]
                val path = rawPath.substringBefore("?")

                // Đọc headers -> Content-Length
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                // Đọc body theo CHAR (JSON thường là ASCII/UTF-8, đủ dùng)
                val body = if (contentLength > 0) {
                    val chars = CharArray(contentLength)
                    var charRead = 0
                    while (charRead < contentLength) {
                        val n = reader.read(chars, charRead, contentLength - charRead)
                        if (n < 0) break
                        charRead += n
                    }
                    String(chars, 0, charRead)
                } else ""

                when {
                    method == "GET" && path == "/ping" -> {
                        sendJson(
                            out, 200,
                            JSONObject()
                                .put("ok", true)
                                .put("name", SERVER_NAME)
                                .put("code", pairingCode)
                        )
                    }
                    method == "POST" && path == "/play" -> {
                        handlePlay(body, out)
                    }
                    method == "POST" && (path == "/control" || path == "/cmd") -> {
                        handleControl(body, out)
                    }
                    method == "GET" && path == "/status" -> {
                        val status = withContext(Dispatchers.Main) { onGetStatus() }
                        sendJson(out, 200, status)
                    }
                    else -> {
                        sendJson(
                            out, 404,
                            JSONObject().put("ok", false).put("error", "Not found: $path")
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Client ngắt giữa chừng — bỏ qua
        }
    }

    private suspend fun handlePlay(body: String, out: OutputStream) {
        try {
            val json = JSONObject(body)
            val url = json.optString("url", "")
            if (url.isBlank() || !url.startsWith("http")) {
                sendJson(
                    out, 400,
                    JSONObject().put("ok", false).put("error", "URL không hợp lệ")
                )
                return
            }
            val title = json.optString("title", "")

            // Headers: {User-Agent, Referer, Cookie...}
            val headers = mutableMapOf<String, String>()
            val hObj = json.optJSONObject("headers")
            if (hObj != null) {
                hObj.keys().forEach { key ->
                    headers[key] = hObj.optString(key, "")
                }
            }

            // Phụ đề: [{url, format, language, label}]
            val subtitles = mutableListOf<SubtitleInfo>()
            val subArr = json.optJSONArray("subtitles")
            if (subArr != null) {
                for (i in 0 until subArr.length()) {
                    val s = subArr.optJSONObject(i) ?: continue
                    val subUrl = s.optString("url", "")
                    if (subUrl.isNotBlank()) {
                        subtitles.add(
                            SubtitleInfo(
                                url = subUrl,
                                format = s.optString("format", "vtt"),
                                language = s.optString("language", "vi"),
                                label = s.optString("label", "Tiếng Việt")
                            )
                        )
                    }
                }
            }

            withContext(Dispatchers.Main) {
                onPlay(url, title, headers, subtitles)
            }
            sendJson(out, 200, JSONObject().put("ok", true))
        } catch (e: Exception) {
            sendJson(
                out, 400,
                JSONObject().put("ok", false).put("error", e.message ?: "Lỗi JSON")
            )
        }
    }

    private suspend fun handleControl(body: String, out: OutputStream) {
        try {
            val json = JSONObject(body)
            val action = json.optString("action", "").lowercase()
            val position = if (json.has("position")) json.optLong("position", -1).takeIf { it >= 0 } else null
            when (action) {
                "play", "pause", "stop", "seek" -> {
                    withContext(Dispatchers.Main) {
                        onControl(action, position)
                    }
                    sendJson(out, 200, JSONObject().put("ok", true))
                }
                else -> sendJson(
                    out, 400,
                    JSONObject().put("ok", false).put("error", "Action không hợp lệ: $action")
                )
            }
        } catch (e: Exception) {
            sendJson(
                out, 400,
                JSONObject().put("ok", false).put("error", e.message ?: "Lỗi JSON")
            )
        }
    }

    private fun sendJson(out: OutputStream, code: Int, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val statusText = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "OK"
        }
        val header = buildString {
            append("HTTP/1.1 $code $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun decodeParam(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }
}
