package com.bubu.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bubu.cast.discovery.PlaybackState
import kotlinx.coroutines.launch

/** Định dạng ms -> "12:34" */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

/**
 * Thanh điều khiển phát trên thiết bị nhận:
 * play/pause, tua ±10s, thanh tiến trình, thời lượng,
 * chọn thiết bị, ngắt kết nối.
 */
@Composable
fun PlayerControlBar(
    viewModel: CastViewModel,
    onSelectDevice: () -> Unit
) {
    val connection by viewModel.connection.collectAsState()
    val scope = rememberCoroutineScope()

    val device = (connection as? ConnectionState.Connected)?.device
    var positionSec by remember { mutableFloatStateOf(0f) }
    var durationSec by remember { mutableFloatStateOf(0f) }
    var state by remember { mutableStateOf(PlaybackState.IDLE) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Poll trạng thái (đơn giản, 2s/lần khi đang kết nối)
    androidx.compose.runtime.LaunchedEffect(device) {
        while (device != null) {
            runCatching {
                state = viewModel.controller?.getState() ?: PlaybackState.IDLE
            }
            kotlinx.coroutines.delay(2000)
        }
    }

    fun runOp(op: suspend () -> Result<Unit>) {
        val ctrl = viewModel.controller ?: run {
            message = "Chưa kết nối thiết bị"
            return
        }
        busy = true
        message = null
        scope.launch {
            val r = runCatching { op() }.getOrElse { Result.failure(it) }
            busy = false
            r.onFailure { message = it.message }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Dòng 1: tên thiết bị + nút đổi/ngắt
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    device?.let { "Đang chiếu: ${it.name}" } ?: "Chưa kết nối",
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onSelectDevice) {
                    Text("Chọn thiết bị", fontSize = 12.sp)
                }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = { viewModel.disconnect() },
                    enabled = device != null
                ) {
                    Text("Ngắt", fontSize = 12.sp)
                }
            }

            if (device != null) {
                // Dòng 2: thanh tiến trình
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(formatDuration((positionSec * 1000).toLong()), fontSize = 12.sp)
                    Slider(
                        value = positionSec,
                        onValueChange = { positionSec = it },
                        onValueChangeFinished = {
                            val ctrl = viewModel.controller ?: return@Slider
                            scope.launch {
                                runCatching { ctrl.seekTo(positionSec.toLong()) }
                            }
                        },
                        valueRange = 0f..durationSec.coerceAtLeast(1f),
                        modifier = Modifier.weight(1f)
                    )
                    Text(formatDuration((durationSec * 1000).toLong()), fontSize = 12.sp)
                }

                // Dòng 3: nút điều khiển
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        runOp {
                            val c = viewModel.controller!!
                            c.seekTo(((positionSec - 10).coerceAtLeast(0f)).toLong())
                        }
                    }) {
                        Icon(Icons.Filled.Replay10, contentDescription = "Lùi 10s")
                    }
                    IconButton(onClick = {
                        runOp {
                            val c = viewModel.controller!!
                            if (state == PlaybackState.PLAYING) c.pause() else c.play()
                        }
                    }) {
                        Icon(
                            if (state == PlaybackState.PLAYING) Icons.Filled.Pause
                            else Icons.Filled.PlayArrow,
                            contentDescription = "Phát/Tạm dừng"
                        )
                    }
                    IconButton(onClick = {
                        runOp {
                            val c = viewModel.controller!!
                            c.seekTo((positionSec + 10).toLong())
                        }
                    }) {
                        Icon(Icons.Filled.Forward10, contentDescription = "Tiến 10s")
                    }
                    IconButton(onClick = {
                        runOp { viewModel.controller!!.stop() }
                    }) {
                        Icon(Icons.Filled.Stop, contentDescription = "Dừng")
                    }
                }

                message?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
