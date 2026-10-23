package com.mgba.bingbaihanji.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.mgba.bingbaihanji.core.storage.RomCatalog
import com.mgba.bingbaihanji.core.storage.RomSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 游戏列表页：展示 [RomCatalog] 提供的 ROM，并提供导入新 ROM 的入口。
 *
 * 页面只依赖 core 的 ROM 目录接口，不关心 ROM 存储在哪里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameListScreen(
    romCatalog: RomCatalog,
    onPlayRom: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onPickRom: () -> Unit,
    /** 删除 ROM；[deleteSaves] 为 true 时同时清理存档与金手指。 */
    onDeleteRom: suspend (RomSummary, Boolean) -> Unit,
) {
    var games by remember { mutableStateOf<List<RomSummary>>(emptyList()) }
    var refreshToken by remember { mutableStateOf(0) }
    var renaming by remember { mutableStateOf<RomSummary?>(null) }
    var deleting by remember { mutableStateOf<RomSummary?>(null) }

    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    val scope = rememberCoroutineScope()

    // 从游戏/设置页返回时刷新列表，新导入的 ROM 和名称立即可见。
    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshToken++
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(romCatalog, refreshToken) {
        // 目录扫描、头部读取和文件 stat 放到 IO 线程。
        games = withContext(Dispatchers.IO) { romCatalog.listRoms() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("mGBA-Android") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Button(
                onClick = onPickRom,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("打开 ROM 或 ZIP…")
            }

            if (games.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "还没有游戏",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "选择一个 ROM 开始游戏",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(games) { game ->
                        GameCard(
                            game = game,
                            onClick = { onPlayRom(game.path) },
                            onRename = { renaming = game },
                            onDelete = { deleting = game },
                        )
                    }
                }
            }
        }
    }

    val renameTarget = renaming
    if (renameTarget != null) {
        RenameRomDialog(
            initialName = renameTarget.displayName,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                romCatalog.rename(renameTarget.id, name)
                renaming = null
                refreshToken++
            },
        )
    }

    val deleteTarget = deleting
    if (deleteTarget != null) {
        DeleteRomDialog(
            game = deleteTarget,
            onDismiss = { deleting = null },
            onConfirm = { deleteSaves ->
                deleting = null
                scope.launch {
                    onDeleteRom(deleteTarget, deleteSaves)
                    refreshToken++
                }
            },
        )
    }
}

@Composable
private fun DeleteRomDialog(
    game: RomSummary,
    onDismiss: () -> Unit,
    onConfirm: (deleteSaves: Boolean) -> Unit,
) {
    var deleteSaves by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除游戏") },
        text = {
            Column {
                Text("将从设备删除“${game.displayName}”的 ROM 副本。")
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { deleteSaves = !deleteSaves },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = deleteSaves,
                        onCheckedChange = { deleteSaves = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "同时删除存档与金手指",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = if (deleteSaves) {
                        "存档、自动存档、RTC 与金手指都会被清除，无法恢复。"
                    } else {
                        "保留存档：重新导入同一 ROM 后仍可继续原来的进度。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteSaves) }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun RenameRomDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名 ROM") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("显示名称") },
                supportingText = { Text("留空则恢复自动名称") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun GameCard(
    game: RomSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = game.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${formatSize(game.sizeBytes)}  ·  ${game.id.take(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
