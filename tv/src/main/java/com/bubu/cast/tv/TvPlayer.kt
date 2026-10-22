package com.bubu.cast.tv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Trình phát video cho TV Receiver — dùng Media3 ExoPlayer (KHÔNG dùng VideoView).
 *
 * Hỗ trợ:
 *  - MP4 (H.264/AVC, H.265/HEVC)
 *  - HLS (.m3u8) — kể cả mã hóa AES-128
 *  - DASH (.mpd)
 *  - WebM (VP8/VP9)
 *  - MKV (H.264 + AAC)
 *  - Phụ đề: SRT, VTT
 *
 * Custom headers (User-Agent, Referer, Cookie) qua DefaultHttpDataSource.
 */
@OptIn(UnstableApi::class)
class TvPlayer(
    private val context: Context,
    private val playerView: PlayerView
) {

    /** Callback khi có lỗi hoặc trạng thái đổi (để Activity cập nhật UI) */
    var listener: Listener? = null

    interface Listener {
        fun onError(message: String)
        fun onStateChanged(playing: Boolean)
        fun onVideoStarted(title: String)
    }

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("BubuCastTV/1.0 (Android)")
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setAllowCrossProtocolRedirects(true)

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(httpFactory)
        )
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Tiêu đề video đang phát */
    var currentTitle: String = ""
        private set

    init {
        playerView.player = player
        // Controller luôn hiện khi chạm, tự ẩn sau 5s
        playerView.controllerShowTimeoutMs = 5000
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                mainHandler.post { listener?.onStateChanged(isPlaying) }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && player.playWhenReady) {
                    mainHandler.post { listener?.onVideoStarted(currentTitle) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                        "Không kết nối được tới nguồn video.\nKiểm tra mạng của TV."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Hết thời gian tải video.\nThử lại hoặc kiểm tra mạng."
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                        "Định dạng video không được hỗ trợ."
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED ->
                        "TV không giải mã được video này.\nThử video khác."
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                        "Không tìm thấy video (link hết hạn?).\nHãy cast lại từ điện thoại."
                    else ->
                        "Không phát được video.\n(${error.errorCodeName})"
                }
                mainHandler.post { listener?.onError(msg) }
            }
        })
    }

    /**
     * Phát 1 URL video.
     * @param headers header HTTP (User-Agent, Referer, Cookie từ điện thoại)
     * @param subtitles danh sách phụ đề ngoài (url + format)
     */
    fun play(
        url: String,
        title: String = "",
        headers: Map<String, String> = emptyMap(),
        subtitles: List<SubtitleInfo> = emptyList()
    ) {
        currentTitle = title
        // Cập nhật headers trước khi phát
        if (headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(headers)
        }

        val builder = MediaItem.Builder()
            .setUri(Uri.parse(url))

        guessMime(url)?.let { builder.setMimeType(it) }

        if (subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                subtitles.map { sub ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                        .setMimeType(
                            if (sub.format.equals("srt", ignoreCase = true))
                                MimeTypes.APPLICATION_SUBRIP
                            else MimeTypes.TEXT_VTT
                        )
                        .setLanguage(sub.language)
                        .setLabel(sub.label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                }
            )
        }

        mainHandler.post {
            player.setMediaItem(builder.build())
            player.prepare()
            player.play()
        }
    }

    fun play() {
        mainHandler.post { player.play() }
    }

    fun pause() {
        mainHandler.post { player.pause() }
    }

    fun stop() {
        mainHandler.post {
            player.stop()
            currentTitle = ""
        }
    }

    /** Tua tới vị trí (giây) */
    fun seekTo(seconds: Long) {
        mainHandler.post {
            player.seekTo((seconds * 1000).coerceAtLeast(0))
        }
    }

    /** Tua tương đối (±giây) */
    fun seekRelative(deltaSeconds: Long) {
        mainHandler.post {
            val target = player.currentPosition + deltaSeconds * 1000
            player.seekTo(target.coerceAtLeast(0))
        }
    }

    /** Đang phát không */
    fun isPlaying(): Boolean = player.isPlaying

    /** Trạng thái dạng chuỗi cho API /status */
    fun getStateString(): String = when {
        player.playerError != null -> "error"
        player.playbackState == Player.STATE_BUFFERING -> "buffering"
        player.isPlaying -> "playing"
        player.playbackState == Player.STATE_IDLE -> "idle"
        else -> "paused"
    }

    fun getPositionSec(): Long = (player.currentPosition / 1000).coerceAtLeast(0)

    fun getDurationSec(): Long {
        val d = player.duration
        return if (d > 0) d / 1000 else 0
    }

    /** Xử lý phím remote: OK/Play/Pause = play/pause, trái/phải = seek ∓10s */
    fun handleKey(keyCode: Int): Boolean {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (isPlaying()) pause() else play()
                true
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekRelative(-10)
                true
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekRelative(10)
                true
            }
            else -> false
        }
    }

    /** Giải phóng */
    fun release() {
        playerView.player = null
        player.release()
    }

    private fun guessMime(url: String): String? {
        val path = try {
            Uri.parse(url).path?.lowercase() ?: return null
        } catch (_: Exception) {
            return null
        }
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            else -> null
        }
    }
}

/** Thông tin phụ đề ngoài */
data class SubtitleInfo(
    val url: String,
    val format: String = "vtt", // "vtt" hoặc "srt"
    val language: String = "vi",
    val label: String = "Tiếng Việt"
)
