package com.mgba.bingbaihanji.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mgba.bingbaihanji.core.storage.SlotStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 即时存档槽位对话框：2 列网格展示 10 个槽位。
 * 点击保存/读取，长按删除。
 *
 * 槽位信息通过 [loadSlots] 回调获取，UI 只依赖 [SlotStatus] 数据模型，
 * 不依赖具体的存储服务或文件系统布局。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SaveSlotDialog(
    romId: String,
    loadSlots: (String) -> List<SlotStatus>,
    isSaveMode: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    var slots by remember { mutableStateOf(listOf<SlotStatus>()) }

    LaunchedEffect(romId) {
        slots = loadSlots(romId)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isSaveMode) "保存进度" else "读取进度",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(slots) { slot ->
                        SlotCard(
                            slot = slot,
                            onClick = { onSelect(slot.slot) },
                            onLongClick = { if (slot.exists) onDelete(slot.slot) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("取消")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlotCard(
    slot: SlotStatus,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val isEmpty = !slot.exists
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (isEmpty) null else onLongClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isEmpty)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "槽位 ${slot.slot}",
                style = MaterialTheme.typography.titleSmall,
                color = if (isEmpty) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isEmpty) "空" else formatTime(slot.timestamp ?: 0),
                style = MaterialTheme.typography.bodySmall,
                color = if (isEmpty) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "有存档"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
