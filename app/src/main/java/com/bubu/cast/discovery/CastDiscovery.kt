package com.bubu.cast.discovery

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Tìm và điều khiển Chromecast / Google Cast qua Cast SDK chính thức.
 *
 * AN TOÀN: mọi gọi Cast SDK đều bọc try-catch. Thiết bị không có
 * Google Play Services (một số ROM Trung Quốc) -> module tự vô hiệu,
 * không crash app.
 */
class CastDiscovery(private val context: Context) : DeviceDiscovery {

    override val type = DeviceType.CAST

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    override val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** Cast SDK dùng được không (có GMS không) */
    val isAvailable: Boolean by lazy {
        try {
            val code = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context)
            code == ConnectionResult.SUCCESS
        } catch (_: Exception) {
            false
        }
    }

    private var castContext: CastContext? = null
    private var sessionListener: SessionManagerListener<Session>? = null

    /** Session Cast đang hoạt động (nếu có) */
    var currentSession: CastSession? = null
        private set

    override suspend fun startScan() {
        if (_isScanning.value) return
        if (!isAvailable) {
            // Không có GMS -> không quét, không báo thiết bị giả
            _isScanning.value = false
            return
        }
        _isScanning.value = true
        try {
            val cc = CastContext.getSharedInstance(context)
            castContext = cc

            // Lắng nghe session để biết khi nào kết nối tới thiết bị
            val listener = object : SessionManagerListener<Session> {
                override fun onSessionStarted(session: Session, sessionId: String) {
                    (session as? CastSession)?.let {
                        currentSession = it
                        onSessionDevice(it)
                    }
                }

                override fun onSessionResumed(session: Session, wasSuspended: Boolean) {
                    (session as? CastSession)?.let {
                        currentSession = it
                        onSessionDevice(it)
                    }
                }

                override fun onSessionEnded(session: Session, error: Int) {
                    if (currentSession == session) {
                        currentSession = null
                        scope.launch { _devices.value = emptyList() }
                    }
                }

                override fun onSessionStarting(session: Session) = Unit
                override fun onSessionStartFailed(session: Session, error: Int) = Unit
                override fun onSessionResuming(session: Session, sessionId: String) = Unit
                override fun onSessionResumeFailed(session: Session, error: Int) = Unit
                override fun onSessionSuspended(session: Session, reason: Int) = Unit
                override fun onSessionEnding(session: Session) = Unit
            }
            sessionListener?.let { cc.sessionManager.removeSessionManagerListener(it) }
            sessionListener = listener
            cc.sessionManager.addSessionManagerListener(listener)

            // Nếu đã có session từ trước (app mở lại)
            cc.sessionManager.currentCastSession?.let { sess ->
                currentSession = sess
                onSessionDevice(sess)
            }

            // Cast SDK tự discovery qua MediaRouter — không cần quét thủ công.
            // Hiển thị gợi ý: bấm nút Cast trên thanh media để chọn thiết bị.
            // (Danh sách route do hệ thống quản lý, ta chỉ theo dõi session.)
        } catch (_: Exception) {
            // GMS lỗi -> tắt lặng lẽ
        } finally {
            _isScanning.value = false
        }
    }

    /** Khi có session: đưa thiết bị vào danh sách */
    private fun onSessionDevice(session: CastSession) {
        try {
            val routeId = session.sessionId ?: return
            val name = try {
                session.castDevice?.friendlyName ?: "Chromecast"
            } catch (_: Exception) {
                "Chromecast"
            }
            scope.launch {
                _devices.value = listOf(
                    Device(
                        id = "cast-$routeId",
                        name = name,
                        ip = "",
                        type = DeviceType.CAST
                    )
                )
            }
        } catch (_: Exception) { }
    }

    override fun stopScan() {
        _isScanning.value = false
    }

    override fun release() {
        try {
            sessionListener?.let { castContext?.sessionManager?.removeSessionManagerListener(it) }
        } catch (_: Exception) { }
        sessionListener = null
        castContext = null
        currentSession = null
        scope.cancel()
    }

    /** Tạo controller nếu đang có session Cast */
    fun createController(): CastController? {
        val session = currentSession ?: return null
        val device = _devices.value.firstOrNull() ?: return null
        return CastController(device, session) {
            try {
                castContext?.sessionManager?.endCurrentSession(true)
            } catch (_: Exception) { }
        }
    }
}

/** Điều khiển phát qua Google Cast RemoteMediaClient */
class CastController(
    override val device: Device,
    private val session: CastSession,
    private val onDisconnect: () -> Unit = {}
) : DeviceController {

    private fun remoteClient() = session.remoteMediaClient

    override suspend fun playUrl(
        url: String,
        title: String,
        headers: Map<String, String>
    ): Result<Unit> = suspendCancellableCoroutine { cont ->
        try {
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, if (title.isBlank()) "Bubu Cast" else title)
            }
            // GMS Cast không hỗ trợ custom HTTP header cho media.
            // Nếu URL cần header/cookie thì receiver sẽ từ chối (giới hạn SDK).
            val mediaInfo = MediaInfo.Builder(url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(guessCastMime(url))
                .setMetadata(metadata)
                .build()
            val request = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .build()
            val client = remoteClient()
                ?: return@suspendCancellableCoroutine cont.resume(
                    Result.failure(Exception("Chưa kết nối Cast"))
                )
            val pending = client.load(request)
            pending.setResultCallback { result ->
                if (result.status.isSuccess) cont.resume(Result.success(Unit))
                else cont.resume(
                    Result.failure(Exception("Cast thất bại: ${result.status.statusMessage}"))
                )
            }
        } catch (e: Exception) {
            cont.resume(Result.failure(e))
        }
    }

    override suspend fun play(): Result<Unit> {
        return try {
            remoteClient()?.play()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun pause(): Result<Unit> {
        return try {
            remoteClient()?.pause()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun stop(): Result<Unit> {
        return try {
            remoteClient()?.stop()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun seekTo(seconds: Long): Result<Unit> {
        return try {
            remoteClient()?.seek(seconds * 1000L)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getState(): PlaybackState {
        return try {
            when (remoteClient()?.playerState) {
                RemoteMediaClient.PLAYER_STATE_PLAYING ->
                    PlaybackState.PLAYING
                RemoteMediaClient.PLAYER_STATE_PAUSED ->
                    PlaybackState.PAUSED
                RemoteMediaClient.PLAYER_STATE_BUFFERING ->
                    PlaybackState.BUFFERING
                RemoteMediaClient.PLAYER_STATE_IDLE ->
                    PlaybackState.IDLE
                else -> PlaybackState.IDLE
            }
        } catch (_: Exception) {
            PlaybackState.IDLE
        }
    }

    override fun disconnect() {
        try {
            onDisconnect()
        } catch (_: Exception) { }
    }

    private fun guessCastMime(url: String): String {
        val u = url.lowercase()
        // Dùng Uri để tách path chính xác hơn
        val path = try {
            Uri.parse(url).path?.lowercase() ?: u
        } catch (_: Exception) { u }
        return when {
            path.endsWith(".m3u8") -> "application/x-mpegurl"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mkv") -> "video/x-matroska"
            else -> "video/mp4"
        }
    }
}
