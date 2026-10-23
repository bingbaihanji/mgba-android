package com.mgba.bingbaihanji.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import com.mgba.bingbaihanji.ui.settings.*

/**
 * 设置导航容器：先显示分类首页，再进入对应子页面。
 *
 * [romInfo] 由游戏页提供当前 ROM 的快照；从主界面进入设置时为 null。
 */
@Composable
fun SettingsNav(
    onBack: () -> Unit,
    romInfo: RomInfoUi? = null,
) {
    var category by remember { mutableStateOf<SettingsCategory?>(null) }

    // 返回键先退回分类首页，再交给调用方（游戏页 → 返回游戏）。
    BackHandler {
        if (category != null) {
            category = null
        } else {
            onBack()
        }
    }

    when (category) {
        null -> SettingsHomeScreen(
            onBack = onBack,
            onCategory = { category = it },
        )

        SettingsCategory.Video -> VideoSettingsScreen(
            onBack = { category = null },
            romInfo = romInfo,
        )
        SettingsCategory.Audio -> AudioSettingsScreen(
            onBack = { category = null },
            romInfo = romInfo,
        )
        SettingsCategory.Input -> InputSettingsScreen(onBack = { category = null })
        SettingsCategory.RomInfo -> RomInfoScreen(
            onBack = { category = null },
            romInfo = romInfo,
        )
        SettingsCategory.SystemInfo -> SystemInfoScreen(
            onBack = { category = null },
            romInfo = romInfo,
        )
        SettingsCategory.Misc -> MiscSettingsScreen(onBack = { category = null })
        SettingsCategory.About -> AboutScreen(onBack = { category = null })
    }
}
