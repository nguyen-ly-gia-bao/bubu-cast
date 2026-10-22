package com.bubu.cast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bubu.cast.data.BubuDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tab Lịch sử / Dấu trang.
 * @param showBookmarks false = lịch sử, true = dấu trang
 * @param onOpenUrl callback khi bấm vào 1 mục (để mở trong trình duyệt)
 */
@Composable
fun HistoryScreen(
    database: BubuDatabase,
    showBookmarks: Boolean,
    onOpenUrl: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    if (!showBookmarks) {
        val history by database.historyDao().observeAll()
            .collectAsState(initial = emptyList())
        HistoryList(
            title = "Lịch sử duyệt web",
            emptyText = "Chưa có lịch sử.",
            items = history.map { Triple(it.url, it.title, it.timestamp) },
            onOpen = onOpenUrl,
            onDelete = { url ->
                scope.launch(Dispatchers.IO) {
                    runCatching { database.historyDao().deleteByUrl(url) }
                }
            }
        )
    } else {
        val bookmarks by database.bookmarkDao().observeAll()
            .collectAsState(initial = emptyList())
        HistoryList(
            title = "Dấu trang",
            emptyText = "Chưa có dấu trang nào.\nBấm icon ⭐ trong trình duyệt để lưu.",
            items = bookmarks.map { Triple(it.url, it.title, it.timestamp) },
            onOpen = onOpenUrl,
            onDelete = { url ->
                scope.launch(Dispatchers.IO) {
                    runCatching { database.bookmarkDao().deleteByUrl(url) }
                }
            },
            icon = Icons.Filled.Star
        )
    }
}

@Composable
private fun HistoryList(
    title: String,
    emptyText: String,
    items: List<Triple<String, String, Long>>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.History
) {
    val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        if (items.isEmpty()) {
            Text(
                emptyText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items, key = { it.first + it.third }) { (url, itemTitle, timestamp) ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(url) }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    itemTitle.ifBlank { url },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    url,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    dateFormat.format(Date(timestamp)),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onDelete(url) }) {
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
}
