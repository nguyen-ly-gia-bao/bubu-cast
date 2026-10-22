package com.bubu.cast.discovery

/** Loại thiết bị nhận */
enum class DeviceType {
    DLNA,   // Smart TV / thiết bị UPnP MediaRenderer
    CAST,   // Chromecast / Google Cast
    WEB     // Bubu Cast TV Receiver (HTTP nội bộ)
}

/**
 * Thiết bị nhận video trong mạng nội bộ.
 * @param extra chứa thông tin riêng từng loại (UDN cho DLNA, ...).
 * @param code mã kết nối 6 số (chỉ dùng cho WEB receiver, DLNA/Cast để trống).
 */
data class Device(
    val id: String,
    val name: String,
    val ip: String,
    val type: DeviceType,
    val extra: String = "",
    val code: String = ""
)

/** Trạng thái phát trên thiết bị nhận */
enum class PlaybackState {
    IDLE, BUFFERING, PLAYING, PAUSED, STOPPED, ERROR
}
