package com.bubu.cast.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bubu.cast.browser.DetectedVideo
import com.bubu.cast.data.AppSettings
import com.bubu.cast.ui.theme.NeonPurple
import com.bubu.cast.ui.theme.SuccessGreen

/**
 * Bottom sheet danh sách video phát hiện được (giống Web Video Caster).
 *
 * - Checkbox "Route video through phone" ở đầu
 * - Mỗi video: thumbnail/icon, tiêu đề, URL rút gọn, định dạng (xanh lá),
 *   tiến trình đã xem, nút ⋮, bấm vào item -> cast ngay
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoListBottomSheet(
    videos: List<DetectedVideo>,
    metadata: Map<String, DetectedVideo>,
    routeThroughPhone: Boolean,
    onRouteToggle: (Boolean) -> Unit,
    onProbeVideo: (DetectedVideo) -> Unit,
    onCastVideo: (DetectedVideo) -> Unit,
    onPlayOnPhone: (DetectedVideo) -> Unit,
    onDismiss: () -> Unit,
    appSettings: AppSettings
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Probe metadata cho video chưa có (khi mở sheet)
    LaunchedEffect(videos) {
        videos.forEach { video ->
            if (!metadata.containsKey(video.url)) {
                onProbeVideo(video)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Tiêu đề
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "🎬 Video tìm thấy (${videos.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Đóng")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Checkbox QUAN TRỌNG: Route video through phone
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRouteToggle(!routeThroughPhone) }
                    .padding(vertical = 4.dp)
            ) {
                Checkbox(
                    checked = routeThroughPhone,
                    onCheckedChange = onRouteToggle
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Route video through phone",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        "Phone tải video rồi serve lại cho TV — " +
                            "dùng khi web chặn thiếu header/cookie",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Danh sách video
            if (videos.isEmpty()) {
                Text(
                    "Chưa phát hiện video nào.\nMở trang web có video để tự động tìm.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(400.dp)
                ) {
                    items(videos, key = { it.url }) { video ->
                        // Dùng metadata đã probe nếu có
                        val displayVideo = metadata[video.url] ?: video
                        VideoItemRow(
                            video = displayVideo,
                            appSettings = appSettings,
                            onCast = { onCastVideo(displayVideo) },
                            onPlayOnPhone = { onPlayOnPhone(displayVideo) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Một dòng video trong danh sách */
@Composable
private fun VideoItemRow(
    video: DetectedVideo,
    appSettings: AppSettings,
    onCast: () -> Unit,
    onPlayOnPhone: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    // Tiến trình đã xem (từ DataStore)
    val progressMs by appSettings.getVideoProgress(video.url)
        .collectAsState(initial = 0L)
    val durationMs by appSettings.getVideoDuration(video.url)
        .collectAsState(initial = 0L)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCast) // Bấm vào item -> cast ngay
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail: icon clapperboard màu cam (nhẹ, không tải ảnh)
            Icon(
                Icons.Filled.Movie,
                contentDescription = null,
                tint = Color(0xFFFF9800),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // Tiêu đề
                Text(
                    video.pageTitle.ifBlank { video.fileName }.ifBlank { "Video" },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // URL rút gọn
                Text(
                    shortenUrl(video.url),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Định dạng (xanh lá): "mp4 (35,9MB)" hoặc "m3u8 (HLS)"
                Text(
                    video.formatInfo(),
                    fontSize = 12.sp,
                    color = SuccessGreen,
                    fontWeight = FontWeight.Medium
                )
                // Tiến trình đã xem
                if (progressMs > 0 && durationMs > 0) {
                    Text(
                        "Played ${formatDuration(progressMs)} of ${formatDuration(durationMs)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Nút ⋮ menu
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Tùy chọn")
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Phát trên điện thoại") },
                    onClick = {
                        showMenu = false
                        onPlayOnPhone()
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Copy link") },
                    onClick = {
                        showMenu = false
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText("video_url", video.url)
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Thêm vào playlist") },
                    onClick = {
                        showMenu = false
                        // TODO: thêm vào playlist (qua callback nếu cần)
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.PlaylistAdd, contentDescription = null)
                    }
                )
            }
            // Nút Cast riêng
            IconButton(onClick = onCast) {
                Icon(
                    Icons.Filled.Cast,
                    contentDescription = "Chiếu",
                    tint = NeonPurple,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/** Rút gọn URL: domain + ... */
private fun shortenUrl(url: String): String {
    return try {
        val uri = android.net.Uri.parse(url)
        val host = uri.host ?: return url
        val path = uri.path ?: ""
        val short = if (path.length > 30) path.take(30) + "…" else path
        "https://$host$short"
    } catch (_: Exception) {
        url.take(50)
    }
}
