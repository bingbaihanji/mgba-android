package com.mgba.bingbaihanji.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mgba.bingbaihanji.core.emulation.CoreOption

/**
 * 展示 Libretro 核心选项的设置页。
 *
 * 选项列表只能在游戏加载后从核心读取；没有运行中的游戏时显示空状态提示。
 * 与 [SettingsHomeScreen] 区分开，避免两个同名 `SettingsScreen`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoreOptionsScreen(
    coreOptions: List<CoreOption>,
    onBack: () -> Unit,
    onOptionSelected: (CoreOption, Int) -> Unit,
) {
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("核心选项") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item {
                Text(
                    text = "部分选项需要重置游戏后生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (coreOptions.isEmpty()) {
                item {
                    Text(
                        text = "当前没有可用的核心选项。\n加载游戏后即可查看核心设置。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(coreOptions) { option ->
                    OptionCard(
                        option = option,
                        onSelect = { index -> onOptionSelected(option, index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionCard(option: CoreOption, onSelect: (Int) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = option.key.removePrefix("mgba_").replace('_', ' '),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            option.values.forEachIndexed { index, value ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (index == option.currentIndex) "● " else "○ ",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (index == option.currentIndex)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (index == option.currentIndex)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
