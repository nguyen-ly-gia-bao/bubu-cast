package com.bubu.cast

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.bubu.cast.data.AppSettings
import com.bubu.cast.data.BubuDatabase
import com.bubu.cast.ui.CastViewModel
import com.bubu.cast.ui.MainScreen
import com.bubu.cast.ui.theme.BubuCastTheme

/**
 * Activity chính của Bubu Cast (điện thoại).
 * - Khởi tạo Compose, theme, ViewModel
 * - Giữ màn hình sáng khi đang cast (FLAG_KEEP_SCREEN_ON)
 */
class MainActivity : ComponentActivity() {

    // Database + Settings tạo 1 lần
    private val database: BubuDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            BubuDatabase::class.java,
            "bubu-cast.db"
        ).build()
    }

    private val appSettings: AppSettings by lazy {
        AppSettings(applicationContext)
    }

    // CastViewModel cần Application -> dùng factory
    private val castViewModel: CastViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CastViewModel(application) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val darkTheme by appSettings.darkTheme.collectAsState(initial = true)

            // Giữ màn hình sáng khi đang kết nối cast
            val connection by castViewModel.connection.collectAsState()
            val isCasting = connection is com.bubu.cast.ui.ConnectionState.Connected
            androidx.compose.runtime.LaunchedEffect(isCasting) {
                if (isCasting) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            BubuCastTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        viewModel = castViewModel,
                        appSettings = appSettings,
                        database = database
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // ViewModel tự release (onCleared) — không cần làm gì thêm
        super.onDestroy()
    }
}
