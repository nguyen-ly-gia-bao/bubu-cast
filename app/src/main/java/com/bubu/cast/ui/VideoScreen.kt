package com.bubu.cast.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.bubu.cast.player.BubuPlayer
import com.bubu.cast.ui.theme.NeonPurple

/** Video cục bộ đã chọn */
data class LocalVideo(
    val uri: Uri,
    val name: String
)

/**
 * Tab Video: chọn video từ bộ nhớ (SAF), phát trên điện thoại bằng
 * BubuPlayer (Media3), hoặc cast tới thiết bị (qua local HTTP server
 * nếu là file cục bộ — hiện tại chỉ cast được URL mạng).
 */
@Composable
fun VideoScreen(
    viewModel: CastViewModel
) {
    val context = LocalContext.current
    val videos = remember { mutableStateListOf<LocalVideo>() }
    var playingUri by remember { mutableStateOf<Uri?>(null) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var pendingCastUri by remember { mutableStateOf<Uri?>(null) }

    // BubuPlayer cho phát nội bộ — release khi rời màn hình
    val player = remember {
        BubuPlayer(context)
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // Chọn file video qua Storage Access Framework (không cần quyền toàn bộ)
    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            try {
                // Giữ quyền đọc lâu dài
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val name = uri.lastPathSegment?.substringAfterLast("/") ?: "Video"
            if (videos.none { it.uri == uri }) {
                videos.add(LocalVideo(uri, name))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Video của tôi", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Button(onClick = { pickVideo.launch(arrayOf("video/*")) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Thêm video")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Trình phát nội bộ
        if (playingUri != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player.exoPlayer
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (videos.isEmpty()) {
            Text(
                "Chưa có video nào.\nBấm \"Thêm video\" để chọn từ bộ nhớ.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(videos, key = { it.uri.toString() }) { video ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    video.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 14.sp
                                )
                                if (playingUri == video.uri) {
                                    Text(
                                        "Đang phát",
                                        fontSize = 12.sp,
                                        color = NeonPurple
                                    )
                                }
                            }
                            IconButton(onClick = {
                                playingUri = video.uri
                                player.playUrl(video.uri.toString())
                            }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Phát")
                            }
                            IconButton(onClick = {
                                // Cast video cục bộ: hiện tại chỉ hỗ trợ URL mạng.
                                // File cục bộ cần HTTP server nội bộ (chưa triển khai).
                                pendingCastUri = video.uri
                                showDevicePicker = true
                            }) {
                                Icon(
                                    Icons.Filled.Cast,
                                    contentDescription = "Chiếu",
                                    tint = NeonPurple
                                )
                            }
                            IconButton(onClick = {
                                videos.remove(video)
                                if (playingUri == video.uri) {
                                    playingUri = null
                                    player.stop()
                                }
                            }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Xóa",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Lưu ý: cast file cục bộ cần TV truy cập được file — " +
                "tính năng đang phát triển, hiện ưu tiên cast URL mạng.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Dialog cast — với file cục bộ, gửi content:// URI (chỉ Web Receiver cùng máy mới đọc được)
    if (showDevicePicker) {
        val uri = pendingCastUri
        DevicePickerDialog(
            viewModel = viewModel,
            onDismiss = {
                showDevicePicker = false
                pendingCastUri = null
            },
            // Nếu là file cục bộ, thử gửi URI (giới hạn: receiver phải đọc được)
            pendingVideoUrl = uri?.toString(),
            pendingVideoTitle = videos.find { it.uri == uri }?.name ?: "",
            onCastStarted = {}
        )
    }
}

/**
 * Tab Playlist: hàng đợi video sẽ cast.
 * Đơn giản: danh sách URL + nút cast từng mục / cast tất cả.
 */
@Composable
fun PlaylistScreen(
    viewModel: CastViewModel
) {
    val items = remember { mutableStateListOf<Pair<String, String>>() }
    var showDevicePicker by remember { mutableStateOf(false) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }

    var urlInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text("Playlist", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        // Thêm URL trực tiếp
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                placeholder = { Text("Dán URL video…", fontSize = 14.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val u = urlInput.trim()
                if (u.isNotBlank() && items.none { it.first == u }) {
                    val name = try {
                        android.net.Uri.parse(u).lastPathSegment ?: u
                    } catch (_: Exception) { u }
                    items.add(u to name)
                    urlInput = ""
                }
            }) {
                Text("Thêm")
            }
        }

        Spacer(Modifier.height(8.dp))

        if (items.isEmpty()) {
            Text(
                "Playlist trống.\nVideo phát hiện từ trình duyệt có thể thêm vào đây.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items, key = { it.first }) { (url, name) ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = {
                                pendingUrl = url
                                showDevicePicker = true
                            }) {
                                Icon(
                                    Icons.Filled.Cast,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Chiếu")
                            }
                            IconButton(onClick = { items.remove(url to name) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Xóa",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDevicePicker) {
        DevicePickerDialog(
            viewModel = viewModel,
            onDismiss = {
                showDevicePicker = false
                pendingUrl = null
            },
            pendingVideoUrl = pendingUrl,
            pendingVideoTitle = items.find { it.first == pendingUrl }?.second ?: "",
            onCastStarted = {}
        )
    }
}
