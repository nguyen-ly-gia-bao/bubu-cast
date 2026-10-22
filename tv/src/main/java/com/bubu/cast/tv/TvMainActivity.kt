package com.bubu.cast.tv

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

/**
 * Màn hình chờ của Bubu Cast TV Receiver (giống Web Video Caster).
 *
 * Hiển thị:
 *  - 📺 Bubu Cast TV
 *  - IP:port lớn màu xanh lá (vd 192.168.2.146:8080)
 *  - Mã kết nối 6 số lớn (vd 482916) — sinh ngẫu nhiên khi mở app
 *  - Hướng dẫn kết nối
 *
 * Khi nhận lệnh /play: ẩn màn hình chờ, hiện PlayerView phát video.
 */
class TvMainActivity : Activity() {

    private lateinit var server: TvReceiverServer
    private lateinit var tvPlayer: TvPlayer

    // Views màn hình chờ
    private lateinit var waitingLayout: LinearLayout
    private lateinit var ipText: TextView
    private lateinit var codeText: TextView
    private lateinit var statusText: TextView

    // Views phát video
    private lateinit var playerView: PlayerView
    private lateinit var videoTitleText: TextView
    private lateinit var errorText: TextView

    /** Mã kết nối 6 số — sinh ngẫu nhiên mỗi lần mở app */
    private val pairingCode: String = Random.nextInt(100_000, 999_999).toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen + giữ màn hình sáng
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F1419"))
        }

        // --- Màn hình chờ ---
        waitingLayout = buildWaitingLayout()
        root.addView(
            waitingLayout,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // --- PlayerView (ẩn ban đầu) ---
        val playerContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = android.view.View.GONE
        }
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Tự động focus để nhận phím remote
            isFocusable = true
            isFocusableInTouchMode = true
        }
        playerContainer.addView(playerView)

        // Tiêu đề video đang phát (góc trên)
        videoTitleText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(24, 16, 24, 16)
            visibility = android.view.View.GONE
        }
        playerContainer.addView(
            videoTitleText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        // Thông báo lỗi
        errorText = TextView(this).apply {
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            visibility = android.view.View.GONE
        }
        playerContainer.addView(
            errorText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        root.addView(
            playerContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // Lưu reference để toggle
        playerContainer.tag = "player_container"

        setContentView(root)

        // --- Khởi tạo player ---
        tvPlayer = TvPlayer(this, playerView).apply {
            listener = object : TvPlayer.Listener {
                override fun onError(message: String) {
                    runOnUiThread { showError(message) }
                }

                override fun onStateChanged(playing: Boolean) {
                    // Ẩn lỗi khi phát lại thành công
                    if (playing) {
                        runOnUiThread { errorText.visibility = android.view.View.GONE }
                    }
                }

                override fun onVideoStarted(title: String) {
                    runOnUiThread {
                        showPlayer()
                        if (title.isNotBlank()) {
                            videoTitleText.text = "🎬 $title"
                            videoTitleText.visibility = android.view.View.VISIBLE
                            // Ẩn tiêu đề sau 5s
                            videoTitleText.postDelayed({
                                videoTitleText.visibility = android.view.View.GONE
                            }, 5000)
                        }
                    }
                }
            }
        }

        // --- Khởi động HTTP server ---
        server = TvReceiverServer(
            pairingCode = pairingCode,
            onPlay = { url, title, headers, subtitles ->
                tvPlayer.play(url, title, headers, subtitles)
            },
            onControl = { action, position ->
                when (action) {
                    "play" -> tvPlayer.play()
                    "pause" -> tvPlayer.pause()
                    "stop" -> {
                        tvPlayer.stop()
                        runOnUiThread { showWaiting() }
                    }
                    "seek" -> position?.let { tvPlayer.seekTo(it) }
                }
            },
            onGetStatus = {
                JSONObject()
                    .put("state", tvPlayer.getStateString())
                    .put("position", tvPlayer.getPositionSec())
                    .put("duration", tvPlayer.getDurationSec())
                    .put("title", tvPlayer.currentTitle)
            }
        )
        server.start()

        // Hiển thị IP + mã
        updateNetworkInfo()
    }

    /** Dựng layout màn hình chờ */
    private fun buildWaitingLayout(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }

        fun addText(
            text: String,
            size: Float,
            color: Int = Color.WHITE,
            bold: Boolean = false,
            paddingTop: Int = 16
        ): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, paddingTop, 0, 0)
                layout.addView(
                    this,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        addText("📺 Bubu Cast TV", 42f, bold = true, paddingTop = 0)

        // IP:port lớn màu xanh lá
        ipText = addText("", 36f, Color.parseColor("#69F0AE"), bold = true, paddingTop = 48)

        // Mã kết nối 6 số lớn
        codeText = addText("", 32f, Color.parseColor("#9D4EDD"), bold = true, paddingTop = 32)

        statusText = addText(
            "Đang chờ điện thoại kết nối...",
            22f,
            Color.parseColor("#90A4AE"),
            paddingTop = 48
        )

        addText("1. Mở Bubu Cast trên điện thoại", 20f, paddingTop = 40)
        addText("2. Bấm 📺 → nhập mã hoặc quét tìm TV", 20f)

        return layout
    }

    /** Cập nhật IP và mã lên màn hình */
    private fun updateNetworkInfo() {
        val ip = getWifiIp() ?: "Không có WiFi"
        ipText.text = "🌐 $ip:${TvReceiverServer.PORT}"
        codeText.text = "Mã kết nối: $pairingCode"
    }

    /** Lấy IP WiFi của TV/máy chiếu */
    private fun getWifiIp(): String? {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(net) ?: return null
            val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val hasEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!hasWifi && !hasEthernet) return null
        } catch (_: Exception) {
            // Bỏ qua kiểm tra, thử lấy IP trực tiếp
        }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("169.254.")) return ip
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    /** Hiện màn hình phát video */
    private fun showPlayer() {
        waitingLayout.visibility = android.view.View.GONE
        findViewWithTag<FrameLayout>("player_container")?.visibility = android.view.View.VISIBLE
        errorText.visibility = android.view.View.GONE
        playerView.requestFocus()
    }

    /** Về màn hình chờ */
    private fun showWaiting() {
        findViewWithTag<FrameLayout>("player_container")?.visibility = android.view.View.GONE
        waitingLayout.visibility = android.view.View.VISIBLE
        updateNetworkInfo()
    }

    private fun <T : android.view.View> findViewWithTag(tag: String): T? {
        @Suppress("UNCHECKED_CAST")
        return findViewById<FrameLayout>(android.R.id.content)
            .findViewWithTag<T>(tag)
    }

    /** Hiện lỗi rõ ràng (KHÔNG tự mở WebView) */
    private fun showError(message: String) {
        showPlayer()
        errorText.text = "❌ $message"
        errorText.visibility = android.view.View.VISIBLE
    }

    // --- Điều khiển bằng remote ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Nếu đang phát video: chuyển phím cho player
        val playerVisible =
            findViewWithTag<FrameLayout>("player_container")?.visibility ==
                android.view.View.VISIBLE
        if (playerVisible && tvPlayer.handleKey(keyCode)) {
            return true
        }
        // Nút Back khi đang phát -> về màn hình chờ
        if (keyCode == KeyEvent.KEYCODE_BACK && playerVisible) {
            tvPlayer.stop()
            showWaiting()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        server.release()
        tvPlayer.release()
        super.onDestroy()
    }

    // Giữ server chạy khi màn hình tắt/mở (không stop)
    override fun onPause() {
        super.onPause()
        // Không dừng server — TV vẫn nhận lệnh khi màn hình chờ
    }
}
