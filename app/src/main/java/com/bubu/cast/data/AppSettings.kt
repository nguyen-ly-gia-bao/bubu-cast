package com.bubu.cast.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// DataStore dùng chung cho toàn app
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bubu_settings")

/**
 * Cài đặt ứng dụng lưu bằng DataStore Preferences.
 * Không lưu thông tin cá nhân, chỉ lưu tùy chọn người dùng.
 */
class AppSettings(private val context: Context) {

    companion object {
        private val KEY_ADBLOCK = booleanPreferencesKey("adblock_enabled")
        private val KEY_HOMEPAGE = stringPreferencesKey("homepage")
        private val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
        private val KEY_JS_ENABLED = booleanPreferencesKey("js_enabled")
        private val KEY_ROUTE_THROUGH_PHONE = booleanPreferencesKey("route_through_phone")

        const val DEFAULT_HOMEPAGE = "https://www.google.com"

        /** Key cho tiến trình xem video (hash URL -> "positionMs/durationMs") */
        private fun progressKey(url: String) =
            stringPreferencesKey("video_progress_${url.hashCode()}")
    }

    /** Bật/tắt chặn quảng cáo (mặc định bật) */
    val adblockEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_ADBLOCK] ?: true }

    suspend fun setAdblockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ADBLOCK] = enabled }
    }

    /** Trang chủ trình duyệt */
    val homepage: Flow<String> = context.dataStore.data
        .map { it[KEY_HOMEPAGE] ?: DEFAULT_HOMEPAGE }

    suspend fun setHomepage(url: String) {
        val fixed = if (url.startsWith("http")) url else "https://$url"
        context.dataStore.edit { it[KEY_HOMEPAGE] = fixed }
    }

    /** Chế độ tối (mặc định tối) */
    val darkTheme: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_DARK_THEME] ?: true }

    suspend fun setDarkTheme(dark: Boolean) {
        context.dataStore.edit { it[KEY_DARK_THEME] = dark }
    }

    /** Bật/tắt JavaScript trong WebView */
    val jsEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_JS_ENABLED] ?: true }

    suspend fun setJsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_JS_ENABLED] = enabled }
    }

    /**
     * Route video through phone: phone chạy proxy server,
     * TV phát qua URL local (giải quyết host chặn header/cookie).
     * Mặc định TẮT (gửi URL gốc trực tiếp, nhanh hơn).
     */
    val routeThroughPhone: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_ROUTE_THROUGH_PHONE] ?: false }

    suspend fun setRouteThroughPhone(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ROUTE_THROUGH_PHONE] = enabled }
    }

    /** Lấy tiến trình đã xem của 1 video (positionMs) */
    fun getVideoProgress(url: String): Flow<Long> = context.dataStore.data
        .map { prefs ->
            prefs[progressKey(url)]
                ?.substringBefore("/")
                ?.toLongOrNull() ?: 0L
        }

    /** Lấy thời lượng đã biết của 1 video (durationMs) */
    fun getVideoDuration(url: String): Flow<Long> = context.dataStore.data
        .map { prefs ->
            prefs[progressKey(url)]
                ?.substringAfter("/", "")
                ?.toLongOrNull() ?: 0L
        }

    /** Lưu tiến trình xem video */
    suspend fun saveVideoProgress(url: String, positionMs: Long, durationMs: Long) {
        // Chỉ lưu khi xem được ít nhất 5s và chưa xem hết (cách cuối 10s)
        if (positionMs < 5000) return
        if (durationMs > 0 && positionMs > durationMs - 10_000) {
            // Xem gần hết -> xóa tiến trình
            context.dataStore.edit { it.remove(progressKey(url)) }
            return
        }
        context.dataStore.edit {
            it[progressKey(url)] = "$positionMs/$durationMs"
        }
    }
}
