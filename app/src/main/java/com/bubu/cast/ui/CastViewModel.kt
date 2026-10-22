package com.bubu.cast.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bubu.cast.discovery.CastDiscovery
import com.bubu.cast.discovery.Device
import com.bubu.cast.discovery.DeviceController
import com.bubu.cast.discovery.DeviceType
import com.bubu.cast.discovery.DlnaDiscovery
import com.bubu.cast.discovery.PlaybackState
import com.bubu.cast.discovery.WebReceiverDiscovery
import com.bubu.cast.player.VideoProxyServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Trạng thái kết nối tới thiết bị nhận */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val device: Device) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/**
 * ViewModel trung tâm: quản lý tìm thiết bị (DLNA/Cast/Web),
 * kết nối (chọn tay / mã 6 số / IP thủ công), và điều khiển phát.
 */
class CastViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext

    val dlnaDiscovery = DlnaDiscovery(context)
    val castDiscovery = CastDiscovery(context)
    val webDiscovery = WebReceiverDiscovery(context)

    /** Proxy server nội bộ (dùng khi bật "Route video through phone") */
    val proxyServer = VideoProxyServer(context)

    /** Có đang route qua phone không (đọc từ DataStore) */
    private val _routeThroughPhone = MutableStateFlow(false)
    val routeThroughPhone: StateFlow<Boolean> = _routeThroughPhone.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    /** Controller của thiết bị đang kết nối */
    var controller: DeviceController? = null
        private set

    /** Các giao thức được bật để quét (mặc định quét cả 3) — khai báo TRƯỚC allDevices */
    private val _enabledProtocols =
        MutableStateFlow(setOf(DeviceType.DLNA, DeviceType.CAST, DeviceType.WEB))
    val enabledProtocols: StateFlow<Set<DeviceType>> = _enabledProtocols.asStateFlow()

    /** Danh sách thiết bị gộp từ các nguồn đang bật */
    val allDevices: StateFlow<List<Device>> = combine(
        dlnaDiscovery.devices,
        castDiscovery.devices,
        webDiscovery.devices,
        _enabledProtocols
    ) { dlna, cast, web, enabled ->
        val list = mutableListOf<Device>()
        if (DeviceType.DLNA in enabled) list.addAll(dlna)
        if (DeviceType.CAST in enabled) list.addAll(cast)
        if (DeviceType.WEB in enabled) list.addAll(web)
        list.toList()
    }.let { flow ->
        // Convert Flow thành StateFlow thủ công
        val state = MutableStateFlow<List<Device>>(emptyList())
        viewModelScope.launch { flow.collect { state.value = it } }
        state.asStateFlow()
    }

    private val _isScanningAny = MutableStateFlow(false)
    val isScanningAny: StateFlow<Boolean> = _isScanningAny.asStateFlow()

    private var scanJob: Job? = null

    fun setProtocolsEnabled(protocols: Set<DeviceType>) {
        _enabledProtocols.value = protocols.ifEmpty { setOf(DeviceType.WEB) }
    }

    /** Quét các giao thức đang bật */
    fun startScan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _isScanningAny.value = true
            try {
                val enabled = _enabledProtocols.value
                // Chạy song song các giao thức được bật
                if (DeviceType.DLNA in enabled) {
                    launch { runCatching { dlnaDiscovery.startScan() } }
                }
                if (DeviceType.CAST in enabled) {
                    launch { runCatching { castDiscovery.startScan() } }
                }
                if (DeviceType.WEB in enabled) {
                    launch { runCatching { webDiscovery.startScan() } }
                }
            } finally {
                _isScanningAny.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        dlnaDiscovery.stopScan()
        castDiscovery.stopScan()
        webDiscovery.stopScan()
        _isScanningAny.value = false
    }

    /** Kết nối tới 1 thiết bị trong danh sách (đã ping/xác thực khi quét) */
    fun connect(device: Device, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _connection.value = ConnectionState.Connecting
            try {
                val ctrl: DeviceController? = when (device.type) {
                    DeviceType.DLNA -> dlnaDiscovery.createController(device)
                    DeviceType.CAST -> castDiscovery.createController()
                    DeviceType.WEB -> {
                        // Ping lại để chắc chắn thiết bị còn online (trạng thái thật)
                        val verified = webDiscovery.connectByIp(device.ip)
                        if (verified != null) webDiscovery.createController(verified)
                        else null
                    }
                }
                if (ctrl != null) {
                    controller?.disconnect()
                    controller = ctrl
                    _connection.value = ConnectionState.Connected(device)
                    onDone(true, "")
                } else {
                    _connection.value = ConnectionState.Failed("Không kết nối được tới ${device.name}")
                    onDone(false, "Không kết nối được tới ${device.name}")
                }
            } catch (e: Exception) {
                _connection.value = ConnectionState.Failed(e.message ?: "Lỗi kết nối")
                onDone(false, e.message ?: "Lỗi kết nối")
            }
        }
    }

    /**
     * Kết nối bằng mã 6 số (Web Receiver).
     * Quét subnet, so mã từng IP.
     */
    fun connectByCode(code: String, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _connection.value = ConnectionState.Connecting
            val dev = webDiscovery.connectByCode(code)
            if (dev != null) {
                controller?.disconnect()
                controller = webDiscovery.createController(dev)
                _connection.value = ConnectionState.Connected(dev)
                onDone(true, "")
            } else {
                _connection.value = ConnectionState.Failed("Không tìm thấy TV với mã này")
                onDone(false, "Không tìm thấy TV với mã này")
            }
        }
    }

    /** Kết nối bằng IP nhập tay (Web Receiver) */
    fun connectByIp(ip: String, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _connection.value = ConnectionState.Connecting
            val dev = webDiscovery.connectByIp(ip)
            if (dev != null) {
                controller?.disconnect()
                controller = webDiscovery.createController(dev)
                _connection.value = ConnectionState.Connected(dev)
                onDone(true, "")
            } else {
                _connection.value = ConnectionState.Failed("Không kết nối được tới $ip")
                onDone(false, "Không kết nối được tới $ip")
            }
        }
    }

    /** Ngắt kết nối hiện tại */
    fun disconnect() {
        try {
            controller?.disconnect()
        } catch (_: Exception) { }
        controller = null
        _connection.value = ConnectionState.Disconnected
    }

    /** Gửi video tới thiết bị đang kết nối */
    fun castVideo(
        url: String,
        title: String = "",
        headers: Map<String, String> = emptyMap(),
        onDone: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val ctrl = controller
        val dev = (_connection.value as? ConnectionState.Connected)?.device
        if (ctrl == null || dev == null) {
            onDone(false, "Chưa kết nối thiết bị")
            return
        }
        viewModelScope.launch {
            // Nếu bật route-through-phone: gửi URL proxy local thay vì URL gốc
            val finalUrl: String
            val finalHeaders: Map<String, String>
            if (_routeThroughPhone.value) {
                proxyServer.defaultHeaders = headers
                val proxyUrl = proxyServer.getProxyUrl(url)
                if (proxyUrl != null) {
                    finalUrl = proxyUrl
                    // TV phát URL local -> không cần header
                    finalHeaders = emptyMap()
                } else {
                    // Không lấy được IP -> fallback URL gốc
                    finalUrl = url
                    finalHeaders = headers
                }
            } else {
                finalUrl = url
                finalHeaders = headers
            }
            val result = ctrl.playUrl(finalUrl, title, finalHeaders)
            result.fold(
                onSuccess = { onDone(true, "") },
                onFailure = { onDone(false, it.message ?: "Gửi thất bại") }
            )
        }
    }

    /** Bật/tắt route through phone (lưu DataStore + start/stop proxy) */
    fun setRouteThroughPhone(
        enabled: Boolean,
        appSettings: com.bubu.cast.data.AppSettings
    ) {
        viewModelScope.launch {
            appSettings.setRouteThroughPhone(enabled)
            _routeThroughPhone.value = enabled
            if (enabled) {
                proxyServer.start()
            } else {
                proxyServer.stop()
            }
        }
    }

    /** Đồng bộ trạng thái route từ DataStore khi khởi động */
    fun syncRouteSetting(appSettings: com.bubu.cast.data.AppSettings) {
        viewModelScope.launch {
            try {
                val enabled = appSettings.routeThroughPhone.first()
                _routeThroughPhone.value = enabled
                if (enabled) {
                    try { proxyServer.start() } catch (_: Exception) { }
                }
            } catch (_: Exception) {
                // DataStore lỗi (vd: data cũ hỏng) -> dùng mặc định, không crash
                _routeThroughPhone.value = false
            }
        }
    }

    override fun onCleared() {
        stopScan()
        try { controller?.disconnect() } catch (_: Exception) { }
        proxyServer.release()
        dlnaDiscovery.release()
        castDiscovery.release()
        webDiscovery.release()
        super.onCleared()
    }
}
