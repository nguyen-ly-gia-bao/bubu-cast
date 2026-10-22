package com.bubu.cast.discovery

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface chung cho mọi giao thức tìm thiết bị.
 * Mỗi module (DLNA / Cast / Web) triển khai độc lập.
 * KHÔNG hiển thị thiết bị giả — chỉ trả về thiết bị tìm thấy thật.
 */
interface DeviceDiscovery {

    /** Loại giao thức của module này */
    val type: DeviceType

    /** Danh sách thiết bị đang thấy (reactive) */
    val devices: StateFlow<List<Device>>

    /** Đang quét hay không */
    val isScanning: StateFlow<Boolean>

    /** Bắt đầu quét mạng */
    suspend fun startScan()

    /** Dừng quét */
    fun stopScan()

    /** Giải phóng tài nguyên */
    fun release()
}

/**
 * Điều khiển phát trên thiết bị đã kết nối.
 * Triển khai cho từng giao thức (DLNA dùng AVTransport, Web dùng HTTP...).
 */
interface DeviceController {

    val device: Device

    /** Gửi URL video tới thiết bị và phát. Headers chỉ dùng khi giao thức hỗ trợ. */
    suspend fun playUrl(
        url: String,
        title: String = "",
        headers: Map<String, String> = emptyMap()
    ): Result<Unit>

    suspend fun play(): Result<Unit>
    suspend fun pause(): Result<Unit>
    suspend fun stop(): Result<Unit>

    /** Tua tới vị trí (giây) */
    suspend fun seekTo(seconds: Long): Result<Unit>

    /** Trạng thái phát hiện tại (nếu giao thức hỗ trợ đọc) */
    suspend fun getState(): PlaybackState

    /** Ngắt kết nối */
    fun disconnect()
}
