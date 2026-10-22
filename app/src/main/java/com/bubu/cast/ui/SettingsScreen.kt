package com.bubu.cast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bubu.cast.data.AppSettings
import com.bubu.cast.data.BubuDatabase
import com.bubu.cast.ui.theme.NeonPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Màn hình Cài đặt: chặn QC, trang chủ, theme, xóa dữ liệu.
 */
@Composable
fun SettingsScreen(
    appSettings: AppSettings,
    database: BubuDatabase
) {
    val scope = rememberCoroutineScope()
    val adblockEnabled by appSettings.adblockEnabled.collectAsState(initial = true)
    val homepage by appSettings.homepage.collectAsState(initial = AppSettings.DEFAULT_HOMEPAGE)
    val darkTheme by appSettings.darkTheme.collectAsState(initial = true)
    val jsEnabled by appSettings.jsEnabled.collectAsState(initial = true)

    var homepageInput by remember(homepage) { mutableStateOf(homepage) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Cài đặt", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // Chặn quảng cáo
        SettingRow(
            title = "Chặn quảng cáo",
            subtitle = "Chặn QC 5 lớp khi duyệt web",
            checked = adblockEnabled,
            onChecked = { scope.launch { appSettings.setAdblockEnabled(it) } }
        )
        HorizontalDivider()

        // JavaScript
        SettingRow(
            title = "JavaScript",
            subtitle = "Tắt nếu trang web quá nặng QC",
            checked = jsEnabled,
            onChecked = { scope.launch { appSettings.setJsEnabled(it) } }
        )
        HorizontalDivider()

        // Chế độ tối
        SettingRow(
            title = "Chế độ tối",
            subtitle = "Nền tối xanh đen + tím neon",
            checked = darkTheme,
            onChecked = { scope.launch { appSettings.setDarkTheme(it) } }
        )
        HorizontalDivider()

        // Trang chủ
        Spacer(Modifier.height(12.dp))
        Text("Trang chủ trình duyệt", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = homepageInput,
                onValueChange = { homepageInput = it },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    appSettings.setHomepage(homepageInput)
                    savedMessage = "Đã lưu trang chủ"
                }
            }) {
                Text("Lưu")
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // Xóa lịch sử
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showClearHistoryConfirm = true }
                .padding(vertical = 10.dp)
        ) {
            Icon(
                Icons.Filled.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Text("Xóa lịch sử duyệt web", fontSize = 15.sp)
        }

        // Xóa toàn bộ dữ liệu
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showClearConfirm = true }
                .padding(vertical = 10.dp)
        ) {
            Icon(
                Icons.Filled.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Xóa toàn bộ dữ liệu (lịch sử + dấu trang)",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.error
            )
        }

        savedMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = NeonPurple, fontSize = 13.sp)
        }

        Spacer(Modifier.weight(1f))

        // Phiên bản
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                "Bubu Cast 1.0 — mã nguồn mở, không thu thập dữ liệu",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text("Xóa lịch sử?") },
            text = { Text("Toàn bộ lịch sử duyệt web sẽ bị xóa.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearHistoryConfirm = false
                    scope.launch(Dispatchers.IO) {
                        runCatching { database.historyDao().clear() }
                    }
                    savedMessage = "Đã xóa lịch sử"
                }) { Text("Xóa") }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) {
                    Text("Hủy")
                }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Xóa toàn bộ dữ liệu?") },
            text = { Text("Lịch sử và dấu trang sẽ bị xóa vĩnh viễn.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                database.historyDao().clear()
                                database.bookmarkDao().clear()
                            }
                        }
                        savedMessage = "Đã xóa toàn bộ dữ liệu"
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Xóa hết") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Hủy")
                }
            }
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
