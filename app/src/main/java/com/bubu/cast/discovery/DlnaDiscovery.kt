package com.bubu.cast.discovery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fourthline.cling.android.AndroidUpnpService
import org.fourthline.cling.android.AndroidUpnpServiceImpl
import org.fourthline.cling.controlpoint.ActionCallback
import org.fourthline.cling.model.action.ActionInvocation
import org.fourthline.cling.model.message.UpnpResponse
import org.fourthline.cling.model.meta.Device as ClingDevice
import org.fourthline.cling.model.meta.Service as ClingService
import org.fourthline.cling.model.types.UDAServiceType
import org.fourthline.cling.registry.DefaultRegistryListener
import org.fourthline.cling.registry.Registry
import org.fourthline.cling.support.avtransport.callback.Pause
import org.fourthline.cling.support.avtransport.callback.Play
import org.fourthline.cling.support.avtransport.callback.Seek
import org.fourthline.cling.support.avtransport.callback.SetAVTransportURI
import org.fourthline.cling.support.avtransport.callback.Stop
import org.fourthline.cling.support.model.SeekMode

/**
 * Tìm TV/thiết bị DLNA (UPnP MediaRenderer) trong mạng LAN bằng Cling.
 * Gửi video qua SetAVTransportURI + điều khiển Play/Pause/Stop/Seek.
 */
class DlnaDiscovery(private val context: Context) : DeviceDiscovery {

    override val type = DeviceType.DLNA

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    override val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Map UDN -> Cling device để điều khiển
    private val clingDevices = mutableMapOf<String, ClingDevice<*, *, *>>()

    private var upnpService: AndroidUpnpService? = null
    private var bound = false

    private val registryListener = object : DefaultRegistryListener() {
        override fun deviceAdded(registry: Registry, device: ClingDevice<*, *, *>) {
            // Chỉ nhận MediaRenderer (có service AVTransport)
            val av = device.findService(UDAServiceType("AVTransport")) ?: return
            val udn = device.identity.udn.identifierString
            val name = device.details.friendlyName ?: "DLNA Renderer"
            val ip = device.identity.descriptorURL?.host ?: ""
            scope.launch {
                clingDevices[udn] = device
                val current = _devices.value.toMutableList()
                if (current.none { it.id == udn }) {
                    current.add(Device(id = udn, name = name, ip = ip, type = DeviceType.DLNA))
                    _devices.value = current
                }
            }
        }

        override fun deviceRemoved(registry: Registry, device: ClingDevice<*, *, *>) {
            val udn = device.identity.udn.identifierString
            scope.launch {
                clingDevices.remove(udn)
                _devices.value = _devices.value.filterNot { it.id == udn }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val svc = service as AndroidUpnpService
            upnpService = svc
            svc.registry.addListener(registryListener)
            // Quét ngay khi bind xong
            scope.launch(Dispatchers.IO) {
                try {
                    svc.controlPoint.search()
                } catch (_: Exception) { }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            upnpService = null
        }
    }

    override suspend fun startScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        // Bind Cling service (chỉ bind 1 lần)
        if (!bound) {
            try {
                val intent = Intent(context, AndroidUpnpServiceImpl::class.java)
                bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (_: Exception) {
                _isScanning.value = false
                return
            }
        }
        // Gửi M-SEARCH tìm MediaRenderer
        scope.launch(Dispatchers.IO) {
            try {
                // Đợi service bind xong (tối đa 5s)
                var waited = 0
                while (upnpService == null && waited < 50) {
                    Thread.sleep(100)
                    waited++
                }
                upnpService?.controlPoint?.search()
            } catch (_: Exception) { }
        }
    }

    override fun stopScan() {
        _isScanning.value = false
    }

    override fun release() {
        stopScan()
        try {
            upnpService?.registry?.removeListener(registryListener)
            if (bound) {
                context.unbindService(serviceConnection)
                bound = false
            }
        } catch (_: Exception) { }
        upnpService = null
        clingDevices.clear()
        scope.cancel()
    }

    /** Lấy Cling device theo id (UDN) */
    internal fun getClingDevice(id: String): ClingDevice<*, *, *>? = clingDevices[id]

    /** Tạo controller để phát/điều khiển trên 1 thiết bị DLNA */
    fun createController(device: Device): DlnaController? {
        val clingDevice = clingDevices[device.id] ?: return null
        val svc = upnpService ?: return null
        val avService = clingDevice.findService(UDAServiceType("AVTransport")) ?: return null
        return DlnaController(device, svc, avService)
    }
}

/** Escape ký tự đặc biệt cho XML DIDL-Lite */
private fun xmlEscape(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

/** Metadata DIDL-Lite cho video */
private fun buildDidl(url: String, title: String, mime: String): String {
    val safeTitle = xmlEscape(if (title.isBlank()) "Bubu Cast" else title)
    val safeUrl = xmlEscape(url)
    val mimeType = if (mime.isBlank()) "video/mp4" else mime
    return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="0" restricted="1"><dc:title>$safeTitle</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:$mimeType:*">$safeUrl</res></item></DIDL-Lite>"""
}

private fun guessMime(url: String): String {
    val u = url.lowercase()
    return when {
        u.contains(".m3u8") -> "application/x-mpegURL"
        u.endsWith(".webm") -> "video/webm"
        u.endsWith(".mkv") -> "video/x-matroska"
        else -> "video/mp4"
    }
}

/** Điều khiển phát DLNA qua AVTransport */
class DlnaController(
    override val device: Device,
    private val upnpService: AndroidUpnpService,
    private val avService: ClingService<*, *>
) : DeviceController {

    override suspend fun playUrl(
        url: String,
        title: String,
        headers: Map<String, String>
    ): Result<Unit> {
        // DLNA không hỗ trợ custom header — chỉ gửi URL thuần.
        // Nếu URL cần cookie/header thì renderer sẽ từ chối (giới hạn giao thức).
        val mime = guessMime(url)
        val didl = buildDidl(url, title, mime)
        val setUri = CompletableDeferred<Result<Unit>>()
        upnpService.controlPoint.execute(
            object : SetAVTransportURI(avService, url, didl) {
                override fun success(invocation: ActionInvocation<*>) {
                    if (!setUri.isCompleted) setUri.complete(Result.success(Unit))
                }

                override fun failure(
                    invocation: ActionInvocation<*>,
                    operation: UpnpResponse,
                    defaultMsg: String
                ) {
                    if (!setUri.isCompleted) setUri.complete(Result.failure(Exception(defaultMsg)))
                }
            }
        )
        val setResult = setUri.await()
        if (setResult.isFailure) return setResult
        // Set URI xong -> Play
        return play()
    }

    override suspend fun play(): Result<Unit> {
        val d = CompletableDeferred<Result<Unit>>()
        upnpService.controlPoint.execute(object : Play(avService) {
            override fun success(invocation: ActionInvocation<*>) {
                if (!d.isCompleted) d.complete(Result.success(Unit))
            }

            override fun failure(
                invocation: ActionInvocation<*>,
                operation: UpnpResponse,
                defaultMsg: String
            ) {
                if (!d.isCompleted) d.complete(Result.failure(Exception(defaultMsg)))
            }
        })
        return d.await()
    }

    override suspend fun pause(): Result<Unit> {
        val d = CompletableDeferred<Result<Unit>>()
        upnpService.controlPoint.execute(object : Pause(avService) {
            override fun success(invocation: ActionInvocation<*>) {
                if (!d.isCompleted) d.complete(Result.success(Unit))
            }

            override fun failure(
                invocation: ActionInvocation<*>,
                operation: UpnpResponse,
                defaultMsg: String
            ) {
                if (!d.isCompleted) d.complete(Result.failure(Exception(defaultMsg)))
            }
        })
        return d.await()
    }

    override suspend fun stop(): Result<Unit> {
        val d = CompletableDeferred<Result<Unit>>()
        upnpService.controlPoint.execute(object : Stop(avService) {
            override fun success(invocation: ActionInvocation<*>) {
                if (!d.isCompleted) d.complete(Result.success(Unit))
            }

            override fun failure(
                invocation: ActionInvocation<*>,
                operation: UpnpResponse,
                defaultMsg: String
            ) {
                if (!d.isCompleted) d.complete(Result.failure(Exception(defaultMsg)))
            }
        })
        return d.await()
    }

    override suspend fun seekTo(seconds: Long): Result<Unit> {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val relTime = "%02d:%02d:%02d".format(h, m, s)
        val d = CompletableDeferred<Result<Unit>>()
        upnpService.controlPoint.execute(object : Seek(avService, SeekMode.REL_TIME, relTime) {
            override fun success(invocation: ActionInvocation<*>) {
                if (!d.isCompleted) d.complete(Result.success(Unit))
            }

            override fun failure(
                invocation: ActionInvocation<*>,
                operation: UpnpResponse,
                defaultMsg: String
            ) {
                if (!d.isCompleted) d.complete(Result.failure(Exception(defaultMsg)))
            }
        })
        return d.await()
    }

    override suspend fun getState(): PlaybackState {
        // Đọc trạng thái cần GetTransportInfo — để đơn giản trả về IDLE
        // (có thể mở rộng sau, không báo sai trạng thái)
        return PlaybackState.IDLE
    }

    override fun disconnect() {
        // DLNA không có khái niệm session — stop là đủ
    }
}
