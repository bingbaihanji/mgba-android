package com.mgba.bingbaihanji.ui.settings

import androidx.compose.runtime.staticCompositionLocalOf
import com.mgba.bingbaihanji.core.input.KeyMapStore

/**
 * 由平台层在 Compose 树根部提供的按键映射存储。
 */
val LocalKeyMapStore = staticCompositionLocalOf<KeyMapStore> {
    error("LocalKeyMapStore was not provided")
}
