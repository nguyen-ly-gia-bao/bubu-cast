package com.bubu.cast.discovery

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * Tìm TV/thiết bị DLNA (UPnP MediaRenderer) bằng SSDP M-SEARCH thuần túy.
 * Không cần thư viện ngoài. Chỉ discovery — điều khiển qua Web Receiver.
 */
class DlnaDiscovery(private val context: Context) : DeviceDiscovery {

    override val type = DeviceType.DLNA

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    override val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    override fun startScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        scope.launch {
            try {
                val found = ssdpSearch()
                _devices.value = found
            } catch (_: Exception) {
            } finally {
                _isScanning.value = false
            }
        }
    }

    override fun stopScan() {
        _isScanning.value = false
    }

    override fun release() {
        scope.cancel()
    }

    private suspend fun ssdpSearch(): List<Device> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Device>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = 3000
            val group = InetAddress.getByName("239.255.255.250")
            val msg = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: 239.255.255.250:1900\r\n")
                append("MAN: \"ns=01; ns=01\"\r\n")
                append("MX: 2\r\n")
                append("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
                append("USER-AGENT: BubuCast/1.0\r\n")
                append("\r\n")
            }.toByteArray()
            val packet = DatagramPacket(msg, msg.size, group, 1900)
            repeat(2) {
                socket.send(packet)
                val buf = ByteArray(4096)
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 2500) {
                    try {
                        val resp = DatagramPacket(buf, buf.size)
                        socket.receive(resp)
                        val text = String(resp.data, 0, resp.length)
                        parseSsdpResponse(text, resp.address.hostAddress ?: "")?.let { dev ->
                            results[dev.id] = dev
                        }
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            socket?.close()
        }
        results.values.toList()
    }

    private fun parseSsdpResponse(text: String, ip: String): Device? {
        if (!text.contains("200 OK", ignoreCase = true)) return null
        val lines = text.lines()
        var usn = ""
        var server = ""
        var location = ""
        for (line in lines) {
            val l = line.trim()
            when {
                l.startsWith("USN:", ignoreCase = true) -> usn = l.substringAfter(":").trim()
                l.startsWith("SERVER:", ignoreCase = true) -> server = l.substringAfter(":").trim()
                l.startsWith("LOCATION:", ignoreCase = true) -> location = l.substringAfter(":").trim()
            }
        }
        if (usn.isEmpty()) return null
        val name = server.ifEmpty { "DLNA TV ($ip)" }.take(40)
        return Device(
            id = usn.ifEmpty { UUID.randomUUID().toString() },
            name = name,
            ip = ip,
            type = DeviceType.DLNA,
            extra = location
        )
    }
}
