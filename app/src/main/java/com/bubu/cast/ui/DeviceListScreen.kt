package com.bubu.cast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bubu.cast.discovery.Device
import com.bubu.cast.discovery.DeviceType
import com.bubu.cast.ui.theme.NeonPurple

/** Icon theo loại thiết bị (giống Web Video Caster) */
private fun deviceIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.DLNA -> Icons.Filled.Tv
    DeviceType.CAST -> Icons.Filled.Cast
    DeviceType.WEB -> Icons.Filled.Wifi
}

/** Tên loại giao thức hiển thị */
private fun deviceTypeLabel(type: DeviceType): String = when (type) {
    DeviceType.DLNA -> "DLNA"
    DeviceType.CAST -> "Chromecast"
    DeviceType.WEB -> "Web Receiver"
}

/**
 * Dialog "Chọn thiết bị để chiếu tới" — giống Web Video Caster.
 *
 * Bấm vào thiết bị -> kết nối và cast NGAY video đang chờ (nếu có).
 *
 * @param pendingVideoUrl URL video sẽ cast ngay sau khi kết nối (null = chỉ kết nối)
 * @param pendingVideoTitle tiêu đề video
 * @param pendingHeaders header gửi kèm (User-Agent, Referer, Cookie...)
 */
@Composable
fun DevicePickerDialog(
    viewModel: CastViewModel,
    onDismiss: () -> Unit,
    pendingVideoUrl: String? = null,
    pendingVideoTitle: String = "",
    pendingHeaders: Map<String, String> = emptyMap(),
    onCastStarted: (Device) -> Unit = {}
) {
    val devices by viewModel.allDevices.collectAsState()
    val scanning by viewModel.isScanningAny.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val enabledProtocols by viewModel.enabledProtocols.collectAsState()

    var codeInput by remember { mutableStateOf("") }
    var ipInput by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showProtocolFilter by remember { mutableStateOf(false) }
    var showTroubleshoot by remember { mutableStateOf(false) }
    var castError by remember { mutableStateOf<String?>(null) }

    // Tự quét khi mở dialog
    LaunchedEffect(Unit) {
        viewModel.startScan()
    }

    /** Kết nối xong -> cast ngay video đang chờ */
    fun handleConnected(device: Device) {
        if (pendingVideoUrl.isNullOrBlank()) {
            onCastStarted(device)
            onDismiss()
            return
        }
        viewModel.castVideo(
            url = pendingVideoUrl,
            title = pendingVideoTitle,
            headers = pendingHeaders
        ) { ok, err ->
            busy = false
            if (ok) {
                onCastStarted(device)
                onDismiss()
            } else {
                castError = "Đã kết nối nhưng gửi video thất bại: $err"
            }
        }
    }

    /** Bấm vào 1 thiết bị: kết nối + cast ngay */
    fun pickDevice(device: Device) {
        if (busy) return
        message = null
        castError = null
        busy = true
        viewModel.connect(device) { ok, err ->
            if (ok) {
                handleConnected(device)
                // busy giữ nguyên tới khi cast xong (trong handleConnected)
                if (pendingVideoUrl.isNullOrBlank()) busy = false
            } else {
                busy = false
                message = err
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // --- Tiêu đề ---
                Text(
                    text = "Chọn thiết bị để chiếu tới",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))

                // --- Dòng trạng thái quét + nút cài đặt giao thức ---
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    val protoNames = enabledProtocols
                        .sortedBy { it.ordinal }
                        .joinToString(", ") { deviceTypeLabel(it) }
                    Text(
                        text = if (scanning) "Đang quét cho: $protoNames"
                        else "Quét cho: $protoNames",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { showProtocolFilter = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Cấu hình loại thiết bị quét",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // --- Danh sách thiết bị (scroll) ---
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 240.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (devices.isEmpty() && !scanning) {
                        item {
                            Text(
                                "Không tìm thấy thiết bị nào trên mạng của bạn.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                    items(devices, key = { it.id }) { device ->
                        DeviceRowCompact(
                            device = device,
                            enabled = !busy,
                            onClick = { pickDevice(device) }
                        )
                    }
                }

                // Lỗi kết nối / cast
                (message ?: castError)?.let {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            it,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Đang kết nối…", fontSize = 13.sp)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // --- Nhập mã TV (6 số) ---
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Nhập mã TV:",
                        fontSize = 14.sp,
                        modifier = Modifier.width(92.dp)
                    )
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = {
                            codeInput = it.filter { ch -> ch.isDigit() }.take(6)
                        },
                        placeholder = { Text("482916", fontSize = 14.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            message = null
                            castError = null
                            if (codeInput.length != 6) {
                                message = "Mã phải gồm 6 chữ số"
                                return@Button
                            }
                            busy = true
                            viewModel.connectByCode(codeInput) { ok, err ->
                                if (ok) {
                                    val dev = (viewModel.connection.value
                                        as? ConnectionState.Connected)?.device
                                    if (dev != null) handleConnected(dev)
                                    else {
                                        busy = false
                                        message = "Lỗi không xác định"
                                    }
                                    if (pendingVideoUrl.isNullOrBlank()) busy = false
                                } else {
                                    busy = false
                                    message = err
                                }
                            }
                        },
                        enabled = !busy && codeInput.length == 6
                    ) {
                        Text("Kết nối")
                    }
                }

                Spacer(Modifier.height(8.dp))

                // --- Nhập IP thủ công ---
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Nhập IP:",
                        fontSize = 14.sp,
                        modifier = Modifier.width(92.dp)
                    )
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it.trim().take(21) },
                        placeholder = { Text("192.168.2.210", fontSize = 14.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            message = null
                            castError = null
                            if (ipInput.isBlank()) {
                                message = "Nhập địa chỉ IP của TV"
                                return@Button
                            }
                            busy = true
                            viewModel.connectByIp(ipInput) { ok, err ->
                                if (ok) {
                                    val dev = (viewModel.connection.value
                                        as? ConnectionState.Connected)?.device
                                    if (dev != null) handleConnected(dev)
                                    else {
                                        busy = false
                                        message = "Lỗi không xác định"
                                    }
                                    if (pendingVideoUrl.isNullOrBlank()) busy = false
                                } else {
                                    busy = false
                                    message = err
                                }
                            }
                        },
                        enabled = !busy && ipInput.isNotBlank()
                    ) {
                        Text("Kết nối")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // --- Link: Khắc phục sự cố (mở rộng) ---
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTroubleshoot = !showTroubleshoot }
                        .padding(vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Filled.HelpOutline,
                        contentDescription = null,
                        tint = NeonPurple,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Khắc phục sự cố",
                        color = NeonPurple,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (showTroubleshoot) Icons.Filled.ExpandLess
                        else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = NeonPurple
                    )
                }
                if (showTroubleshoot) {
                    Column(modifier = Modifier.padding(start = 26.dp, bottom = 4.dp)) {
                        listOf(
                            "Đảm bảo điện thoại và TV cùng mạng WiFi.",
                            "Khởi động lại WiFi trên cả 2 thiết bị rồi bấm QUÉT LẠI.",
                            "Mở app Bubu Cast TV trên TV/máy chiếu và xem mã 6 số.",
                            "Nhập IP thủ công nếu quét không tìm thấy."
                        ).forEach { tip ->
                            Text(
                                "• $tip",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }

                // --- Link: Cấu hình loại thiết bị quét ---
                TextButton(
                    onClick = { showProtocolFilter = true },
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Text("Cấu hình loại thiết bị quét", color = NeonPurple, fontSize = 14.sp)
                }

                Spacer(Modifier.height(4.dp))

                // --- 2 nút dưới cùng: QUÉT LẠI (trái) / ĐÓNG (phải) ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = {
                            message = null
                            castError = null
                            viewModel.startScan()
                        },
                        enabled = !scanning && !busy
                    ) {
                        Text("QUÉT LẠI")
                    }
                    Button(
                        onClick = { if (!busy) onDismiss() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("ĐÓNG")
                    }
                }
            }
        }
    }

    // Dialog con: chọn giao thức quét
    if (showProtocolFilter) {
        ProtocolFilterDialog(
            viewModel = viewModel,
            onDismiss = { showProtocolFilter = false }
        )
    }
}

/** Một dòng thiết bị: icon + tên (dòng 1) + Loại (IP) (dòng 2) */
@Composable
private fun DeviceRowCompact(
    device: Device,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = deviceIcon(device.type),
                contentDescription = null,
                tint = NeonPurple,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                // Dòng 2: Loại (IP) — vd "Chromecast (192.168.2.93)"
                val sub = buildString {
                    append(deviceTypeLabel(device.type))
                    if (device.ip.isNotBlank()) append(" (${device.ip})")
                    if (device.code.isNotBlank()) append(" • Mã: ${device.code}")
                }
                Text(
                    sub,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!enabled) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

/** Dialog chọn giao thức muốn quét: DLNA / Cast / Web Receiver */
@Composable
private fun ProtocolFilterDialog(
    viewModel: CastViewModel,
    onDismiss: () -> Unit
) {
    val enabled by viewModel.enabledProtocols.collectAsState()
    var selection by remember(enabled) { mutableStateOf(enabled) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Loại thiết bị quét",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                DeviceType.values().forEach { type ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selection = if (type in selection) selection - type
                                else selection + type
                            }
                            .padding(vertical = 6.dp)
                    ) {
                        Checkbox(
                            checked = type in selection,
                            onCheckedChange = {
                                selection = if (it) selection + type else selection - type
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            deviceIcon(type),
                            contentDescription = null,
                            tint = NeonPurple,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(deviceTypeLabel(type), fontSize = 15.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Hủy") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        viewModel.setProtocolsEnabled(selection)
                        viewModel.startScan()
                        onDismiss()
                    }) {
                        Text("Lưu & quét")
                    }
                }
            }
        }
    }
}

/** Trạng thái kết nối (dùng chung) */
@Composable
fun ConnectionStatusRow(viewModel: CastViewModel) {
    val connection by viewModel.connection.collectAsState()
    when (val c = connection) {
        is ConnectionState.Connected -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Đã kết nối: ${c.device.name}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        is ConnectionState.Connecting -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(6.dp))
                Text("Đang kết nối…", fontSize = 13.sp)
            }
        }
        else -> {
            Text(
                "Chưa kết nối thiết bị",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
