package com.bubu.cast.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.bubu.cast.browser.AdBlocker
import com.bubu.cast.browser.BrowserTabManager
import com.bubu.cast.browser.DetectedVideo
import com.bubu.cast.browser.VideoProbe
import com.bubu.cast.browser.isVideoUrl
import com.bubu.cast.browser.normalizeUrl
import com.bubu.cast.data.AppSettings
import com.bubu.cast.data.Bookmark
import com.bubu.cast.data.BubuDatabase
import com.bubu.cast.data.HistoryEntry
import com.bubu.cast.ui.theme.CastGreen
import com.bubu.cast.ui.theme.NeonPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Màn hình trình duyệt multi-tab (giống Web Video Caster).
 *
 * - Top App Bar: ☰ | [địa chỉ] | ↻ | 📺 | ❺ | ⋮
 * - Thanh trạng thái: 🛡 badge | 🎬 badge
 * - Mỗi tab là WebView độc lập (không lẫn nội dung)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: CastViewModel,
    appSettings: AppSettings,
    database: BubuDatabase
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val homepage by appSettings.homepage.collectAsState(initial = AppSettings.DEFAULT_HOMEPAGE)
    val adblockEnabled by appSettings.adblockEnabled.collectAsState(initial = true)
    val jsEnabled by appSettings.jsEnabled.collectAsState(initial = true)
    val routeThroughPhone by viewModel.routeThroughPhone.collectAsState()
    val connection by viewModel.connection.collectAsState()

    val adBlocker = remember {
        AdBlocker().also { blocker ->
            Thread { blocker.load(context) }.start()
        }
    }

    // Video phát hiện (từ mọi tab, không trùng URL)
    val detectedVideos = remember { mutableStateListOf<DetectedVideo>() }
    // Metadata đã probe (url -> DetectedVideo có metadata)
    val videoMetadata = remember { mutableStateOf<Map<String, DetectedVideo>>(emptyMap()) }

    var tabVersion by remember { mutableStateOf(0) } // force recompose khi tab đổi
    var blockedCount by remember { mutableStateOf(0) }

    // Dialog/sheet states
    var showUrlDialog by remember { mutableStateOf(false) }
    var showTabManager by remember { mutableStateOf(false) }
    var showVideoSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var pendingCastVideo by remember { mutableStateOf<DetectedVideo?>(null) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // TabManager: tạo 1 lần
    val tabManager = remember {
        BrowserTabManager(
            context = context,
            adBlocker = adBlocker,
            adblockEnabled = { adblockEnabled },
            jsEnabled = { jsEnabled },
            onVideoFound = { video ->
                // Thêm nếu chưa có (theo URL)
                if (detectedVideos.none { it.url == video.url }) {
                    detectedVideos.add(video)
                }
            },
            onAdBlocked = { blockedCount++ },
            onTabUpdated = { tabVersion++ }
        )
    }

    // Mở tab đầu tiên + đồng bộ route setting
    LaunchedEffect(Unit) {
        viewModel.syncRouteSetting(appSettings)
        if (tabManager.tabs.isEmpty()) {
            tabManager.newTab(homepage)
        }
    }

    // Hủy TabManager khi rời màn hình
    DisposableEffect(Unit) {
        onDispose { tabManager.destroy() }
    }

    val currentTab = tabManager.tabs.getOrNull(tabManager.currentIndex)
    val isConnected = connection is ConnectionState.Connected

    // Lưu lịch sử khi đổi tab / trang mới
    LaunchedEffect(currentTab?.url) {
        val url = currentTab?.url ?: return@LaunchedEffect
        val title = currentTab?.title ?: ""
        if (url.isNotBlank() && url != "about:blank" && !url.startsWith("about:")) {
            withContext(Dispatchers.IO) {
                runCatching {
                    database.historyDao().insert(HistoryEntry(url = url, title = title.ifBlank { url }))
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                BrowserDrawer(
                    database = database,
                    onOpenUrl = { url ->
                        scope.launch {
                            drawerState.close()
                            tabManager.loadUrl(url)
                        }
                    },
                    onOpenSettings = {
                        // Chuyển sang tab Cài đặt — xử lý ở MainScreen
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ===== TOP APP BAR =====
            BrowserTopBar(
                addressText = currentTab?.let {
                    it.title.ifBlank { it.url }.ifBlank { "Tab mới" }
                } ?: "Tab mới",
                onAddressClick = { showUrlDialog = true },
                onReload = { tabManager.reload() },
                isConnected = isConnected,
                onCastClick = { showDevicePicker = true },
                tabCount = tabManager.tabs.size,
                onTabCountClick = { showTabManager = true },
                onMoreClick = { showMoreMenu = true },
                onMenuClick = { scope.launch { drawerState.open() } }
            )

            // Progress bar (chỉ tab active)
            val progress = currentTab?.progress ?: 0
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = NeonPurple
                )
            }

            // ===== THANH TRẠNG THÁI =====
            BrowserStatusBar(
                blockedCount = blockedCount,
                adblockEnabled = adblockEnabled,
                onAdblockToggle = {
                    scope.launch {
                        appSettings.setAdblockEnabled(!adblockEnabled)
                        if (!adblockEnabled) blockedCount = 0
                        tabManager.reload()
                    }
                },
                videoCount = detectedVideos.size,
                onVideoClick = { showVideoSheet = true }
            )

            // ===== WEBVIEW CONTAINER (multi-tab) =====
            // Chỉ 1 WebView VISIBLE tại 1 thời điểm (do TabManager quản lý)
            AndroidView(
                factory = { tabManager.container },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }

    // ===== DIALOG NHẬP URL =====
    if (showUrlDialog) {
        UrlInputDialog(
            database = database,
            initialText = currentTab?.url ?: "",
            onGo = { url ->
                showUrlDialog = false
                val normalized = normalizeUrl(url)
                if (normalized.isNotBlank()) {
                    // Xóa video cũ khi chuyển trang
                    detectedVideos.clear()
                    videoMetadata.value = emptyMap()
                    tabManager.loadUrl(normalized)
                }
            },
            onDismiss = { showUrlDialog = false }
        )
    }

    // ===== QUẢN LÝ TAB =====
    if (showTabManager) {
        TabManagerDialog(
            tabManager = tabManager,
            onDismiss = { showTabManager = false },
            onNewTab = {
                tabManager.newTab(homepage)
                showTabManager = false
            }
        )
    }

    // ===== BOTTOM SHEET VIDEO =====
    if (showVideoSheet) {
        VideoListBottomSheet(
            videos = detectedVideos.toList(),
            metadata = videoMetadata.value,
            routeThroughPhone = routeThroughPhone,
            onRouteToggle = { enabled ->
                viewModel.setRouteThroughPhone(enabled, appSettings)
            },
            onProbeVideo = { video ->
                // Probe metadata trong nền
                scope.launch(Dispatchers.IO) {
                    val headers = tabManager.getCurrentHeaders()
                    val result = VideoProbe.probe(video.url, headers)
                    val updated = video.copy(
                        mimeType = result.mimeType,
                        sizeBytes = result.sizeBytes
                    )
                    withContext(Dispatchers.Main) {
                        videoMetadata.value = videoMetadata.value + (video.url to updated)
                        // Cập nhật trong danh sách
                        val idx = detectedVideos.indexOfFirst { it.url == video.url }
                        if (idx >= 0) detectedVideos[idx] = updated
                    }
                }
            },
            onCastVideo = { video ->
                showVideoSheet = false
                pendingCastVideo = video
                showDevicePicker = true
            },
            onPlayOnPhone = { video ->
                // Phát trên điện thoại — xử lý ở ngoài (callback)
                showVideoSheet = false
            },
            onDismiss = { showVideoSheet = false },
            appSettings = appSettings
        )
    }

    // ===== DIALOG CHỌN THIẾT BỊ (cast ngay) =====
    if (showDevicePicker) {
        val video = pendingCastVideo
        DevicePickerDialog(
            viewModel = viewModel,
            onDismiss = {
                showDevicePicker = false
                pendingCastVideo = null
            },
            pendingVideoUrl = video?.url,
            pendingVideoTitle = video?.pageTitle ?: "",
            pendingHeaders = tabManager.getCurrentHeaders(),
            onCastStarted = {}
        )
    }

    // ===== MENU ⋮ =====
    if (showMoreMenu) {
        MoreMenuDialog(
            onDismiss = { showMoreMenu = false },
            onShare = {
                showMoreMenu = false
                currentTab?.url?.let { url ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(Intent.createChooser(intent, "Chia sẻ"))
                }
            },
            onDesktopMode = {
                showMoreMenu = false
                // Bật/tắt desktop mode bằng User-Agent
                currentTab?.webView?.settings?.let { settings ->
                    val ua = settings.userAgentString ?: ""
                    settings.userAgentString = if (ua.contains("Mobile")) {
                        ua.replace("Mobile", "eliboM")
                            .replace("Android", "X11; Linux x86_64")
                    } else {
                        // Reset về mặc định bằng cách reload WebView mới
                        null
                    }
                    // Đơn giản: reload để áp dụng
                    tabManager.reload()
                }
            },
            onClearData = {
                showMoreMenu = false
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        database.historyDao().clear()
                    }
                }
                currentTab?.webView?.clearHistory()
            },
            onHome = {
                showMoreMenu = false
                detectedVideos.clear()
                tabManager.loadUrl(homepage)
            }
        )
    }
}

/** Top App Bar: ☰ | [địa chỉ] | ↻ | 📺 | ❺ | ⋮ */
@Composable
private fun BrowserTopBar(
    addressText: String,
    onAddressClick: () -> Unit,
    onReload: () -> Unit,
    isConnected: Boolean,
    onCastClick: () -> Unit,
    tabCount: Int,
    onTabCountClick: () -> Unit,
    onMoreClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1F24))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onMenuClick, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = Color.White)
        }
        // Ô địa chỉ (bấm vào mở dialog, không phải EditText trực tiếp)
        Box(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF0F1419), MaterialTheme.shapes.small)
                .clickable(onClick = onAddressClick)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = addressText,
                color = Color(0xFFB0BEC5),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onReload, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = "Tải lại", tint = Color.White)
        }
        // Nút Cast: xanh lá khi đã kết nối, xám khi chưa
        IconButton(onClick = onCastClick, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Cast,
                contentDescription = "Cast",
                tint = if (isConnected) CastGreen else Color(0xFF78909C)
            )
        }
        // Số tab
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(onClick = onTabCountClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$tabCount",
                color = NeonPurple,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier
                    .background(Color(0xFF0F1419), MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        IconButton(onClick = onMoreClick, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Thêm", tint = Color.White)
        }
    }
}

/** Thanh trạng thái: 🛡 badge | 🎬 badge */
@Composable
private fun BrowserStatusBar(
    blockedCount: Int,
    adblockEnabled: Boolean,
    onAdblockToggle: () -> Unit,
    videoCount: Int,
    onVideoClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1F24))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 🛡 Chặn QC (bấm để bật/tắt)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onAdblockToggle)
        ) {
            BadgedBox(
                badge = {
                    if (blockedCount > 0) Badge { Text("$blockedCount") }
                }
            ) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = "Chặn quảng cáo",
                    tint = if (adblockEnabled) NeonPurple else Color(0xFF546E7A),
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "$blockedCount đã chặn",
                fontSize = 12.sp,
                color = Color(0xFFB0BEC5)
            )
        }
        // 🎬 Video (bấm để mở danh sách)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onVideoClick)
        ) {
            BadgedBox(
                badge = {
                    if (videoCount > 0) Badge { Text("$videoCount") }
                }
            ) {
                Icon(
                    Icons.Filled.Videocam,
                    contentDescription = "Video",
                    tint = if (videoCount > 0) NeonPurple else Color(0xFF546E7A),
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "$videoCount video",
                fontSize = 12.sp,
                color = Color(0xFFB0BEC5)
            )
        }
        Spacer(Modifier.weight(1f))
        if (!adblockEnabled) {
            Text("QC: TẮT", fontSize = 11.sp, color = Color(0xFFFFD740))
        }
    }
}

/** Dialog nhập URL (tự focus + hiện bàn phím, gợi ý lịch sử/dấu trang) */
@Composable
private fun UrlInputDialog(
    database: BubuDatabase,
    initialText: String,
    onGo: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(initialText) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val recentHistory by database.historyDao().observeAll()
        .collectAsState(initial = emptyList())
    val bookmarks by database.bookmarkDao().observeAll()
        .collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Nhập địa chỉ web", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onGo(input) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Hủy") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onGo(input) }) { Text("Đi tới") }
                }
                // Gợi ý: lịch sử gần đây
                val suggestions = (recentHistory.take(3).map { it.url to it.title } +
                    bookmarks.take(3).map { it.url to it.title })
                    .distinctBy { it.first }
                    .take(5)
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Gợi ý:", fontSize = 13.sp, color = Color.Gray)
                    suggestions.forEach { (url, title) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onGo(url) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.Gray
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                title.ifBlank { url },
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Dialog quản lý tab (dạng lưới) */
@Composable
private fun TabManagerDialog(
    tabManager: BrowserTabManager,
    onDismiss: () -> Unit,
    onNewTab: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Tab (${tabManager.tabs.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Đóng")
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.heightIn(max = 400.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tabManager.tabs.toList(), key = { it.id }) { tab ->
                        val index = tabManager.tabs.indexOf(tab)
                        val isActive = index == tabManager.currentIndex
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.clickable {
                                tabManager.switchTo(index)
                                onDismiss()
                            }
                        ) {
                            Box(modifier = Modifier.padding(8.dp)) {
                                Column {
                                    // Thumbnail: dùng icon thay vì chụp WebView (nhẹ)
                                    Icon(
                                        Icons.Filled.Language,
                                        contentDescription = null,
                                        tint = NeonPurple,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        tab.title.ifBlank { "Tab mới" },
                                        fontSize = 13.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        tab.url,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = Color.Gray
                                    )
                                }
                                // Nút đóng tab
                                IconButton(
                                    onClick = { tabManager.closeTab(index) },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Đóng tab",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                    // Nút + Tab mới
                    item {
                        Card(
                            modifier = Modifier.clickable(onClick = onNewTab)
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = null,
                                        tint = NeonPurple,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Text("Tab mới", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Drawer menu: lịch sử, dấu trang, cài đặt */
@Composable
private fun BrowserDrawer(
    database: BubuDatabase,
    onOpenUrl: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val bookmarks by database.bookmarkDao().observeAll()
        .collectAsState(initial = emptyList())

    Column(modifier = Modifier.padding(16.dp)) {
        Text("📺 Bubu Cast", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = NeonPurple)
        Spacer(Modifier.height(16.dp))
        NavigationDrawerItem(
            label = { Text("Trang chủ") },
            selected = false,
            onClick = { },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) }
        )
        Text(
            "Dấu trang",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        if (bookmarks.isEmpty()) {
            Text("Chưa có dấu trang", fontSize = 13.sp, color = Color.Gray)
        } else {
            bookmarks.take(10).forEach { bm ->
                NavigationDrawerItem(
                    label = {
                        Text(
                            bm.title.ifBlank { bm.url },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp
                        )
                    },
                    selected = false,
                    onClick = { onOpenUrl(bm.url) },
                    icon = { Icon(Icons.Filled.Star, contentDescription = null) }
                )
            }
        }
    }
}

/** Menu ⋮ : chia sẻ, desktop mode, xóa dữ liệu */
@Composable
private fun MoreMenuDialog(
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDesktopMode: () -> Unit,
    onClearData: () -> Unit,
    onHome: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(8.dp)) {
                DropdownMenuItem(
                    text = { Text("Chia sẻ trang") },
                    onClick = onShare,
                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Chế độ desktop") },
                    onClick = onDesktopMode,
                    leadingIcon = { Icon(Icons.Filled.DesktopWindows, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Xóa dữ liệu duyệt web") },
                    onClick = onClearData,
                    leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Về trang chủ") },
                    onClick = onHome,
                    leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null) }
                )
            }
        }
    }
}
