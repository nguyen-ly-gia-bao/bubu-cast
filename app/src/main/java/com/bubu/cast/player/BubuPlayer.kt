package com.bubu.cast.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Phụ đề ngoài */
data class SubtitleTrack(
    val url: String,
    val language: String = "vi",
    /** "vtt" hoặc "srt" */
    val format: String = "vtt",
    val label: String = "Tiếng Việt"
)

/** Một mức chất lượng video (để user chọn) */
data class QualityOption(
    val trackGroupIndex: Int,
    val trackIndex: Int,
    val label: String, // vd "1080p", "720p", "Tự động"
    val bitrate: Long = 0L
)

/**
 * Wrapper quanh Media3 ExoPlayer.
 * Hỗ trợ MP4 / HLS / WebM, custom HTTP header, phụ đề, chọn chất lượng.
 * Lifecycle: gọi [release] khi không dùng nữa.
 */
@OptIn(UnstableApi::class)
class BubuPlayer(context: Context) {

    private val appContext = context.applicationContext

    // DataSource với header tùy chỉnh (User-Agent, Referer, Cookie...)
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("BubuCast/1.0 (Android)")
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)
        .setAllowCrossProtocolRedirects(true)

    private val trackSelector = DefaultTrackSelector(appContext)

    private val _player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setTrackSelector(trackSelector)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(appContext)
                .setDataSourceFactory(httpFactory)
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Buffer vừa phải cho máy yếu
                .setBufferDurationsMs(
                    15_000, // min buffer
                    30_000, // max buffer
                    2_500,  // buffer for playback
                    5_000   // buffer for playback after rebuffer
                )
                .build()
        )
        .build()

    /** ExoPlayer gốc (để gắn vào PlayerView khi cần UI) */
    val exoPlayer: ExoPlayer get() = _player

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                _durationMs.value = _player.duration.coerceAtLeast(0L)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _error.value = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    "Không kết nối được tới nguồn video"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    "Hết thời gian tải video"
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                    "Định dạng video không được hỗ trợ"
                else -> "Không phát được video (${error.errorCodeName})"
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            // Cập nhật vị trí (UI tự poll hoặc dùng callback này)
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_IS_PLAYING_CHANGED)
            ) {
                _positionMs.value = player.currentPosition.coerceAtLeast(0L)
            }
        }
    }

    init {
        _player.addListener(listener)
    }

    /**
     * Đặt HTTP header mặc định cho mọi request (gọi trước playUrl).
     * Dùng để giả lập trình duyệt khi host yêu cầu Referer/Cookie.
     */
    fun setHeaders(headers: Map<String, String>) {
        if (headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(headers)
        }
    }

    /** Phát 1 URL video (tự nhận diện HLS/DASH/MP4) */
    fun playUrl(
        url: String,
        title: String = "",
        subtitles: List<SubtitleTrack> = emptyList()
    ) {
        _error.value = null
        val builder = MediaItem.Builder()
            .setUri(Uri.parse(url))

        // Gắn MIME nếu nhận diện được (giúp chọn đúng MediaSource)
        val mime = guessMime(url)
        if (mime != null) builder.setMimeType(mime)

        // Phụ đề ngoài
        if (subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                subtitles.map { sub ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                        .setMimeType(
                            if (sub.format.lowercase() == "srt") MimeTypes.APPLICATION_SUBRIP
                            else MimeTypes.TEXT_VTT
                        )
                        .setLanguage(sub.language)
                        .setLabel(sub.label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                }
            )
        }

        _player.setMediaItem(builder.build())
        _player.prepare()
        _player.play()
    }

    fun play() = _player.play()
    fun pause() = _player.pause()

    /** Tua tới vị trí (ms) */
    fun seekTo(positionMs: Long) {
        _player.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** Lấy danh sách chất lượng có thể chọn (từ video track) */
    fun getQualityOptions(): List<QualityOption> {
        val options = mutableListOf(
            QualityOption(-1, -1, "Tự động") // để ExoPlayer tự chọn
        )
        val tracks = _player.currentTracks
        for (group in tracks.groups) {
            // Chỉ quan tâm track video
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val height = format.height
                val label = when {
                    height >= 1080 -> "1080p"
                    height >= 720 -> "720p"
                    height >= 480 -> "480p"
                    height > 0 -> "${height}p"
                    else -> "${format.bitrate / 1000} kbps"
                }
                // Tránh trùng label
                if (options.none { it.label == label }) {
                    options.add(
                        QualityOption(
                            trackGroupIndex = tracks.groups.indexOf(group),
                            trackIndex = i,
                            label = label,
                            bitrate = format.bitrate.toLong()
                        )
                    )
                }
            }
        }
        // Sắp xếp theo bitrate giảm dần (sau "Tự động")
        return listOf(options.first()) +
            options.drop(1).sortedByDescending { it.bitrate }
    }

    /** Chọn chất lượng (dùng QualityOption từ getQualityOptions) */
    fun selectQuality(option: QualityOption) {
        val params = _player.trackSelectionParameters.buildUpon()
        if (option.trackGroupIndex < 0) {
            // Tự động: xóa override
            params.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {
            val group = _player.currentTracks.groups
                .getOrNull(option.trackGroupIndex) ?: return
            params.setOverrideForType(
                androidx.media3.exoplayer.trackselection.TrackSelectionOverride(
                    group.mediaTrackGroup,
                    option.trackIndex
                )
            )
        }
        _player.trackSelectionParameters = params.build()
    }

    /** Bật/tắt phụ đề (theo language, "" = tắt hết) */
    fun selectSubtitle(language: String) {
        val params = _player.trackSelectionParameters.buildUpon()
        if (language.isEmpty()) {
            params.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        } else {
            params.setPreferredTextLanguage(language)
        }
        _player.trackSelectionParameters = params.build()
    }

    /** Cập nhật vị trí hiện tại (gọi từ UI theo chu kỳ) */
    fun refreshPosition() {
        _positionMs.value = _player.currentPosition.coerceAtLeast(0L)
        if (_player.duration > 0) {
            _durationMs.value = _player.duration
        }
    }

    fun stop() {
        _player.stop()
        _isPlaying.value = false
    }

    /** Giải phóng — BẮT BUỘC gọi khi Activity/ViewModel bị hủy */
    fun release() {
        _player.removeListener(listener)
        _player.release()
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
            else -> null // để ExoPlayer tự đoán
        }
    }
}
