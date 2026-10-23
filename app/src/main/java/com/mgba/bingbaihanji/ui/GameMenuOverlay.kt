package com.mgba.bingbaihanji.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mgba.bingbaihanji.core.settings.SpeedPresets
import kotlin.math.roundToInt

/**
 * 游戏内菜单内容（半透明遮罩 + 底部操作面板）。
 *
 * 显示状态由外部的 [visible] 控制，触发入口是虚拟手柄上的菜单按钮。
 */
@Composable
fun GameMenuOverlay(
    visible: Boolean,
    onSaveState: () -> Unit,
    onLoadState: () -> Unit,
    onSettings: () -> Unit,
    onEditLayout: () -> Unit,
    onCoreOptions: () -> Unit,
    onCheats: () -> Unit,
    /** 当前是否处于快进状态，用于在菜单里显示提示。 */
    fastForwarding: Boolean,
    /** 当前游戏速度倍率（0.2x–16x），由滑块调整并保存到设置。 */
    gameSpeed: Float,
    onGameSpeedChange: (Float) -> Unit,
    onFastForward: () -> Unit,
    onReset: () -> Unit,
    onExit: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim covers everything.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .pointerInput(Unit) {
                    detectTapGestures { onDismiss() }
                },
        )
        // Bottom sheet
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 9 个菜单项在大字体下会超过 380dp，留出滚动前的可视空间。
                .heightIn(max = 440.dp)
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                )
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            // 菜单已由游戏页暂停；具体动作由调用方负责关闭菜单/恢复仿真，
            // 这里不再统一调用 onDismiss，避免「恢复 → 再次暂停」的音频抖动。
            MenuRow(Icons.Default.Star, "保存进度") { onSaveState() }
            MenuRow(Icons.Default.PlayArrow, "读取进度") { onLoadState() }
            val speedLabel = SpeedPresets.label(gameSpeed)
            val slow = SpeedPresets.isSlow(gameSpeed)
            MenuRow(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                label = when {
                    !fastForwarding && slow -> "慢放 · $speedLabel"
                    !fastForwarding -> "快进 · $speedLabel"
                    slow -> "慢放中 · $speedLabel"
                    else -> "快进中 · $speedLabel"
                },
                highlighted = fastForwarding,
            ) { onFastForward() }
            GameSpeedSlider(
                speed = gameSpeed,
                onChange = onGameSpeedChange,
            )
            MenuRow(Icons.Default.Edit, "编辑布局") { onEditLayout() }
            MenuRow(Icons.Default.Build, "核心选项") { onCoreOptions() }
            MenuRow(Icons.AutoMirrored.Filled.List, "金手指") { onCheats() }
            MenuRow(Icons.Default.Settings, "设置") { onSettings() }
            MenuRow(Icons.Default.Refresh, "重置") { onReset() }
            MenuRow(Icons.Default.Close, "退出") { onExit() }
        }
    }
}

/**
 * 游戏速度滑块：在 [SpeedPresets.VALUES] 的常用档位之间拖动。
 *
 * 索引保存在本地状态里，只在档位真正变化时才回调，避免拖动过程中
 * 反复写设置、以及外部状态回写导致滑块跳动。
 */
@Composable
private fun GameSpeedSlider(speed: Float, onChange: (Float) -> Unit) {
    val presetIndex = SpeedPresets.nearestIndex(speed)
    var localIndex by remember(presetIndex) { mutableStateOf(presetIndex) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(40.dp))
            Text(
                text = "游戏速度",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = SpeedPresets.label(SpeedPresets.VALUES[localIndex]),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = localIndex.toFloat(),
            onValueChange = { value ->
                val index = value.roundToInt()
                    .coerceIn(SpeedPresets.VALUES.indices)
                if (index != localIndex) {
                    localIndex = index
                    onChange(SpeedPresets.VALUES[index])
                }
            },
            valueRange = 0f..(SpeedPresets.VALUES.size - 1).toFloat(),
            steps = SpeedPresets.VALUES.size - 2,
        )
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (highlighted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (highlighted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
