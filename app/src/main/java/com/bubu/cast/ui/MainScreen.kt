package com.bubu.cast.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bubu.cast.data.AppSettings
import com.bubu.cast.data.BubuDatabase
import com.bubu.cast.ui.theme.NeonPurple

/** Các tab chính */
enum class MainTab(
    val title: String,
    val icon: ImageVector
) {
    BROWSER("Trình duyệt", Icons.Filled.Language),
    VIDEO("Video", Icons.Filled.PlayCircle),
    PLAYLIST("Playlist", Icons.Filled.PlaylistPlay),
    HISTORY("Lịch sử", Icons.Filled.History),
    BOOKMARK("Dấu trang", Icons.Filled.Bookmark),
    SETTINGS("Cài đặt", Icons.Filled.Settings)
}

/**
 * Màn hình chính: logo + trạng thái kết nối + tabs.
 */
@Composable
fun MainScreen(
    viewModel: CastViewModel,
    appSettings: AppSettings,
    database: BubuDatabase
) {
    var currentTab by remember { mutableStateOf(MainTab.BROWSER) }
    val connection by viewModel.connection.collectAsState()
    var showDevicePicker by remember { mutableStateOf(false) }

    // Đồng bộ cài đặt route-through-phone khi khởi động
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.syncRouteSetting(appSettings)
    }

    Scaffold(
        topBar = {
            // Header: logo + trạng thái kết nối
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📺 Bubu Cast",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonPurple
                )
                Spacer(Modifier.width(12.dp))
                // Trạng thái kết nối (thật — chỉ hiện khi đã kết nối thành công)
                val connText = when (val c = connection) {
                    is ConnectionState.Connected -> "📺✓ ${c.device.name}"
                    is ConnectionState.Connecting -> "Đang kết nối…"
                    else -> "Chưa kết nối"
                }
                Text(
                    text = connText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                // Nút mở dialog tìm thiết bị
                androidx.compose.material3.OutlinedButton(
                    onClick = { showDevicePicker = true }
                ) {
                    Icon(
                        Icons.Filled.Cast,
                        contentDescription = "Tìm thiết bị",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Thiết bị")
                }
            }
        },
        bottomBar = {
            NavigationBar {
                MainTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(tab.icon, contentDescription = tab.title)
                        },
                        label = { Text(tab.title, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (currentTab) {
                MainTab.BROWSER -> BrowserScreen(
                    viewModel = viewModel,
                    appSettings = appSettings,
                    database = database
                )
                MainTab.VIDEO -> VideoScreen(
                    viewModel = viewModel
                )
                MainTab.PLAYLIST -> PlaylistScreen(
                    viewModel = viewModel
                )
                MainTab.HISTORY -> HistoryScreen(
                    database = database,
                    showBookmarks = false
                )
                MainTab.BOOKMARK -> HistoryScreen(
                    database = database,
                    showBookmarks = true
                )
                MainTab.SETTINGS -> SettingsScreen(
                    appSettings = appSettings,
                    database = database
                )
            }
        }
    }

    // Dialog chọn thiết bị (dùng chung cho mọi tab)
    if (showDevicePicker) {
        DevicePickerDialog(
            viewModel = viewModel,
            onDismiss = { showDevicePicker = false },
            onCastStarted = {}
        )
    }
}
