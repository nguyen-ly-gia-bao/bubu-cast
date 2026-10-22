package com.bubu.cast.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/**
 * Tìm Bubu Cast TV Receiver trong mạng LAN.
 * Receiver chạy HTTP server port 8080, endpoint GET /ping trả về:
 *   {"ok":true,"name":"Bubu Cast TV","code":"482916"}
 * Mã 6 số do TV tự sinh khi mở app, hiển thị to trên màn hình TV.
 *
 * Quét song song toàn subnet /24 (giống VideoCast cũ).
 */
class WebReceiverDiscovery(private val context: Context) : DeviceDiscovery {

    override val type = DeviceType.WEB

    companion object {
        const val RECEIVER_PORT = 8080
        private const val PING_TIMEOUT_MS = 2000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    override val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    override suspend fun startScan() {
        if (_isScanning.value) return
        val base = getWifiSubnetBase() ?: return // không có WiFi -> không quét
        _isScanning.value = true
        try {
            val found = withContext(Dispatchers.IO) {
                (1..254).map { last ->
                    async {
                        val ip = "$base$last"
                        pingReceiver(ip)?.let { (name, code) ->
                            Device(
                                id = "web-$ip",
                                name = name,
                                ip = ip,
                                type = DeviceType.WEB,
                                code = code
                            )
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            _devices.value = found
        } finally {
            _isScanning.value = false
        }
    }

    override fun stopScan() {
        _isScanning.value = false
    }

    override fun release() {
        scope.cancel()
    }

    /**
     * Ping 1 IP, trả về Pair(tên, mã kết nối) nếu là receiver.
     * Trả về null nếu không phải receiver hoặc không tới được.
     */
    private fun pingReceiver(ip: String): Pair<String, String>? {
        return try {
            val conn = URL("http://$ip:$RECEIVER_PORT/ping")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = PING_TIMEOUT_MS
            conn.readTimeout = PING_TIMEOUT_MS
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            if (json.optBoolean("ok", false)) {
                val name = json.optString("name", "Bubu Cast TV").ifBlank { "Bubu Cast TV" }
                val code = json.optString("code", "")
                Pair(name, code)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Kết nối bằng mã 6 số: quét nhanh subnet, so mã từng IP.
     * @return Device nếu tìm thấy mã khớp, null nếu không.
     */
    suspend fun connectByCode(code: String): Device? = withContext(Dispatchers.IO) {
        val clean = code.trim()
        if (clean.length != 6 || !clean.all { it.isDigit() }) return@withContext null
        val base = getWifiSubnetBase() ?: return@withContext null
        // Quét song song, dừng sớm khi tìm thấy (dùng map + firstNotNull)
        val found = (1..254).map { last ->
            async {
                val ip = "$base$last"
                val ping = pingReceiver(ip)
                if (ping != null && ping.second == clean) {
                    Device(
                        id = "web-$ip",
                        name = ping.first,
                        ip = ip,
                        type = DeviceType.WEB,
                        code = ping.second
                    )
                } else null
            }
        }.awaitAll().firstOrNull { it != null }
        // Cập nhật danh sách nếu tìm thấy
        found?.let { dev ->
            val current = _devices.value.toMutableList()
            if (current.none { it.id == dev.id }) {
                current.add(dev)
                _devices.value = current
            }
        }
        found
    }

    /**
     * Kết nối bằng IP thủ công: ping trực tiếp IP đó.
     * @return Device nếu IP đó là receiver, null nếu không.
     */
    suspend fun connectByIp(rawIp: String): Device? = withContext(Dispatchers.IO) {
        // Chấp nhận cả "192.168.2.210" và "192.168.2.210:8080"
        var ip = rawIp.trim()
            .removePrefix("http://")
            .removeSuffix("/")
        if (ip.contains(":")) ip = ip.substringBefore(":")
        if (!isValidIpv4(ip)) return@withContext null
        val ping = pingReceiver(ip) ?: return@withContext null
        val dev = Device(
            id = "web-$ip",
            name = ping.first,
            ip = ip,
            type = DeviceType.WEB,
            code = ping.second
        )
        val current = _devices.value.toMutableList()
        if (current.none { it.id == dev.id }) {
            current.add(dev)
            _devices.value = current
        }
        dev
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { p ->
            val n = p.toIntOrNull() ?: return false
            n in 0..255 && (p == "0" || !p.startsWith("0"))
        }
    }

    /** Lấy subnet base WiFi, vd "192.168.2." */
    private fun getWifiSubnetBase(): String? {
        // Kiểm tra đang dùng WiFi thật
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(net) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        } catch (_: Exception) {
            return null
        }
        // Lấy IP của interface WiFi
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        // Bỏ qua mobile data / VPN thường dùng dải khác — chỉ lấy khi hợp lý
                        val lastDot = ip.lastIndexOf('.')
                        if (lastDot > 0) {
                            return ip.substring(0, lastDot + 1)
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    /** Tạo controller gửi lệnh phát tới receiver */
    fun createController(device: Device): WebReceiverController {
        return WebReceiverController(device)
    }
}

/**
 * Điều khiển Bubu Cast TV Receiver qua HTTP:
 * POST http://<ip>:8080/play  {url, title, headers}
 * POST http://<ip>:8080/cmd   {action: play|pause|stop, position}
 */
class WebReceiverController(override val device: Device) : DeviceController {

    private fun baseUrl() = "http://${device.ip}:${WebReceiverDiscovery.RECEIVER_PORT}"

    private fun post(path: String, json: JSONObject): Result<Unit> {
        return try {
            val conn = URL(baseUrl() + path).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = json.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code in 200..299) Result.success(Unit)
            else Result.failure(Exception("Receiver trả về HTTP $code"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun playUrl(
        url: String,
        title: String,
        headers: Map<String, String>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val json = JSONObject()
        json.put("url", url)
        json.put("title", title)
        // Gửi header để receiver giả lập trình duyệt khi cần
        val h = JSONObject()
        headers.forEach { (k, v) -> h.put(k, v) }
        json.put("headers", h)
        post("/play", json)
    }

    override suspend fun play(): Result<Unit> = withContext(Dispatchers.IO) {
        post("/cmd", JSONObject().put("action", "play"))
    }

    override suspend fun pause(): Result<Unit> = withContext(Dispatchers.IO) {
        post("/cmd", JSONObject().put("action", "pause"))
    }

    override suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        post("/cmd", JSONObject().put("action", "stop"))
    }

    override suspend fun seekTo(seconds: Long): Result<Unit> = withContext(Dispatchers.IO) {
        post(
            "/cmd",
            JSONObject().put("action", "seek").put("position", seconds)
        )
    }

    override suspend fun getState(): PlaybackState = withContext(Dispatchers.IO) {
        try {
            val conn = URL(baseUrl() + "/status").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode != 200) return@withContext PlaybackState.IDLE
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            when (json.optString("state", "idle").lowercase()) {
                "playing" -> PlaybackState.PLAYING
                "paused" -> PlaybackState.PAUSED
                "buffering" -> PlaybackState.BUFFERING
                "stopped" -> PlaybackState.STOPPED
                "error" -> PlaybackState.ERROR
                else -> PlaybackState.IDLE
            }
        } catch (_: Exception) {
            PlaybackState.IDLE
        }
    }

    override fun disconnect() {
        // Web receiver không giữ session — stop là đủ
    }
}
