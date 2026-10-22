package com.bubu.cast.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Kết quả probe metadata video */
data class VideoProbeResult(
    val mimeType: String = "",
    val sizeBytes: Long = -1L,
    val supportsRange: Boolean = false
)

/**
 * Lấy metadata video bằng HEAD request (nhẹ, không tải body).
 * Dùng để hiển thị "mp4 (35,9MB)" trong danh sách video.
 */
object VideoProbe {

    /**
     * Probe 1 URL video.
     * @param headers header gửi kèm (User-Agent, Referer, Cookie từ WebView)
     */
    suspend fun probe(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): VideoProbeResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            val code = conn.responseCode
            if (code !in 200..299) {
                // Một số server không hỗ trợ HEAD -> thử GET với Range 0-0
                return@withContext probeWithRange(url, headers)
            }

            VideoProbeResult(
                mimeType = conn.contentType?.substringBefore(";")?.trim() ?: "",
                sizeBytes = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L,
                supportsRange = conn.getHeaderField("Accept-Ranges") == "bytes"
            )
        } catch (_: Exception) {
            VideoProbeResult()
        } finally {
            conn?.disconnect()
        }
    }

    /** Fallback: GET với Range: bytes=0-0 để lấy header */
    private fun probeWithRange(
        url: String,
        headers: Map<String, String>
    ): VideoProbeResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Range", "bytes=0-0")
            // Đọc 1 byte rồi đóng ngay
            try {
                conn.inputStream.read()
            } catch (_: Exception) { }

            val contentRange = conn.getHeaderField("Content-Range") // bytes 0-0/12345
            val sizeFromRange = contentRange
                ?.substringAfterLast("/")
                ?.toLongOrNull() ?: -1L

            VideoProbeResult(
                mimeType = conn.contentType?.substringBefore(";")?.trim() ?: "",
                sizeBytes = sizeFromRange.takeIf { it > 0 }
                    ?: conn.getHeaderField("Content-Length")?.toLongOrNull()
                    ?: -1L,
                supportsRange = contentRange != null ||
                    conn.getHeaderField("Accept-Ranges") == "bytes"
            )
        } catch (_: Exception) {
            VideoProbeResult()
        } finally {
            conn?.disconnect()
        }
    }
}
