package com.mgba.bingbaihanji.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 设置分类。
 *
 * 分类按“视频 / 音频 / 输入 / ROM 信息 / 系统信息 / 其他 / 关于”组织：
 * 原先独立的“调试”页被拆开，帧率与变速状态浮层并入视频，
 * 设备与运行数据放入系统信息。
 */
enum class SettingsCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    Video("视频", "帧率、着色器、画面比例与调试浮层", Icons.Default.PlayArrow),
    Audio("音频", "声音开关、音量与采样率", Icons.Default.ThumbUp),
    Input("输入", "虚拟按键、透明度与按键映射", Icons.Default.Person),
    RomInfo("ROM 信息", "当前游戏文件、校验值与存档位置", Icons.AutoMirrored.Filled.List),
    SystemInfo("系统信息", "应用、核心、设备与屏幕数据", Icons.Default.Phone),
    Misc("其他", "游戏速度、存档与启动行为", Icons.Default.Settings),
    About("关于", "版本、开源地址与联系方式", Icons.Default.Info),
}

/** 设置分类首页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeScreen(
    onBack: () -> Unit,
    onCategory: (SettingsCategory) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            items(SettingsCategory.entries) { cat ->
                SettingsCategoryCard(cat) { onCategory(cat) }
            }
        }
    }
}

@Composable
private fun SettingsCategoryCard(
    category: SettingsCategory,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                category.icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(category.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    category.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
