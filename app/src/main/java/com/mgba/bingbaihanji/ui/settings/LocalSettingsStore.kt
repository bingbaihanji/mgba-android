package com.mgba.bingbaihanji.ui.settings

import androidx.compose.runtime.staticCompositionLocalOf
import com.mgba.bingbaihanji.core.settings.SettingsStore

/**
 * 由平台层在 Compose 树根部提供的设置存储。
 *
 * 这样设置页面只依赖 core 的 [SettingsStore] 接口，不直接引用
 * Android 的 SharedPreferences 实现。桌面端可以提供另一个实现。
 */
val LocalSettingsStore = staticCompositionLocalOf<SettingsStore> {
    error("LocalSettingsStore was not provided")
}
