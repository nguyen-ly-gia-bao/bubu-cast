package com.bubu.cast.browser

import android.content.Context
import android.net.Uri
import java.util.Locale

/**
 * Chặn quảng cáo 5 lớp (port từ Q10 Browser v1.6 sang Kotlin).
 *
 * Lớp 1 (network): [isBlocked] — chặn request theo rule EasyList.
 * Lớp 3 (cosmetic): [getGenericCss] + [getDomainCss] — ẩn banner bằng CSS.
 * Lớp 2, 4, 5 (popup / text tài trợ / download) nằm trong [BubuWebView].
 *
 * Rule đọc từ assets/adblock_rules.txt, định dạng rút gọn của EasyList:
 *   ||domain^          -> chặn mọi request tới domain
 *   @@||domain^        -> ngoại lệ (không chặn)
 *   ##selector         -> CSS ẩn chung
 *   domain##selector   -> CSS ẩn theo domain
 */
class AdBlocker {

    // Loại request (dùng cho rule có $script, $image...)
    companion object {
        const val T_OTHER = 0
        const val T_SCRIPT = 1
        const val T_IMAGE = 2
        const val T_STYLESHEET = 4
        const val T_MEDIA = 8
        const val T_FRAME = 16
        const val T_XHR = 32
        const val T_FONT = 64
    }

    private val blockDomains = HashSet<String>()
    private val allowDomains = HashSet<String>()
    private val genericCss = StringBuilder()
    private val domainCss = HashMap<String, StringBuilder>()

    @Volatile private var loaded = false

    /** Nạp rule từ assets. Gọi 1 lần khi khởi động (nên gọi trên thread nền). */
    fun load(context: Context) {
        if (loaded) return
        try {
            context.assets.open("adblock_rules.txt").bufferedReader().useLines { lines ->
                lines.forEach { parseRule(it.trim()) }
            }
        } catch (_: Exception) {
            // Không có file rule -> dùng rule mặc định nhúng sẵn
            loadBuiltIn()
        }
        loaded = true
    }

    private fun parseRule(raw: String) {
        if (raw.isEmpty() || raw.startsWith("!")) return // comment
        when {
            raw.startsWith("@@") -> {
                // Ngoại lệ
                val d = extractDomain(raw.removePrefix("@@"))
                if (d != null) allowDomains.add(d)
            }
            raw.startsWith("||") -> {
                val d = extractDomain(raw)
                if (d != null) blockDomains.add(d)
            }
            raw.contains("##") -> {
                // Cosmetic
                val parts = raw.split("##", limit = 2)
                val selector = parts[1].trim()
                if (selector.isEmpty()) return
                val css = "$selector { display: none !important; }\n"
                val domains = parts[0].trim()
                if (domains.isEmpty()) {
                    genericCss.append(css)
                } else {
                    domains.split(",").forEach { d ->
                        val host = d.trim().lowercase(Locale.US)
                        if (host.isNotEmpty()) {
                            domainCss.getOrPut(host) { StringBuilder() }.append(css)
                        }
                    }
                }
            }
        }
    }

    /** Tách domain từ rule dạng ||domain^... */
    private fun extractDomain(rule: String): String? {
        var s = rule.removePrefix("||")
        // Cắt tại ký tự phân tách đầu tiên
        val cut = s.indexOfFirst { it == '^' || it == '/' || it == '?' || it == '$' }
        if (cut >= 0) s = s.substring(0, cut)
        s = s.lowercase(Locale.US).trim()
        return s.ifEmpty { null }
    }

    /** Đoán loại request từ đuôi URL — QUAN TRỌNG: không truyền type=0 bừa. */
    fun guessType(url: String): Int {
        val path = try {
            Uri.parse(url).path?.lowercase(Locale.US) ?: ""
        } catch (_: Exception) { "" }
        return when {
            path.endsWith(".js") -> T_SCRIPT
            path.endsWith(".css") -> T_STYLESHEET
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                path.endsWith(".ico") -> T_IMAGE
            path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".ts") ||
                path.endsWith(".webm") || path.endsWith(".mp3") -> T_MEDIA
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") -> T_FONT
            else -> T_OTHER
        }
    }

    /**
     * Lớp 1: kiểm tra URL có bị chặn không.
     * Không chặn request cùng domain với trang (first-party) để tránh vỡ trang.
     */
    fun isBlocked(url: String, pageUrl: String, type: Int): Boolean {
        if (!loaded) return false
        // Không bao giờ chặn media — tránh làm vỡ video
        if (type == T_MEDIA) return false
        val host = try {
            Uri.parse(url).host?.lowercase(Locale.US) ?: return false
        } catch (_: Exception) { return false }
        val pageHost = try {
            Uri.parse(pageUrl).host?.lowercase(Locale.US) ?: ""
        } catch (_: Exception) { "" }

        // Ngoại lệ luôn thắng
        if (matchesDomain(host, allowDomains)) return false
        if (!matchesDomain(host, blockDomains)) return false

        // Chỉ chặn third-party (khác domain trang đang xem)
        if (pageHost.isNotEmpty() && (host == pageHost || host.endsWith(".$pageHost"))) {
            return false
        }
        return true
    }

    private fun matchesDomain(host: String, set: HashSet<String>): Boolean {
        if (set.contains(host)) return true
        // Kiểm tra subdomain: ads.example.com khớp rule ||example.com^
        var dot = host.indexOf('.')
        while (dot >= 0) {
            if (set.contains(host.substring(dot + 1))) return true
            dot = host.indexOf('.', dot + 1)
        }
        return false
    }

    /** Lớp 3a: CSS ẩn chung cho mọi trang */
    fun getGenericCss(): String = genericCss.toString()

    /** Lớp 3b: CSS ẩn riêng theo domain */
    fun getDomainCss(host: String): String {
        val h = host.lowercase(Locale.US)
        val sb = StringBuilder()
        domainCss[h]?.let { sb.append(it) }
        // Thử parent domain
        var dot = h.indexOf('.')
        while (dot >= 0) {
            domainCss[h.substring(dot + 1)]?.let { sb.append(it) }
            dot = h.indexOf('.', dot + 1)
        }
        return sb.toString()
    }

    /** Đếm selector CSS đã inject (để badge hiển thị đúng số) */
    fun countSelectors(host: String): Int {
        var n = 0
        if (genericCss.isNotEmpty()) n += genericCss.count { it == '}' }
        n += getDomainCss(host).count { it == '}' }
        return n
    }

    /** Rule mặc định khi chưa có file assets (đủ dùng cơ bản) */
    private fun loadBuiltIn() {
        listOf(
            "||doubleclick.net^",
            "||googlesyndication.com^",
            "||googleadservices.com^",
            "||ads.yahoo.com^",
            "||adservice.google.com^",
            "||pagead2.googlesyndication.com^",
            "||ads.tiktok.com^",
            "||analytics.tiktok.com^",
            "||facebook.net^",
            "##.ad-banner",
            "##.adsbygoogle",
            "##[id^='div-gpt-ad']",
            "##.popup-ad"
        ).forEach { parseRule(it) }
    }
}
