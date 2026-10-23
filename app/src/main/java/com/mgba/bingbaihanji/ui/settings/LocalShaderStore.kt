package com.mgba.bingbaihanji.ui.settings

import androidx.compose.runtime.staticCompositionLocalOf
import com.mgba.bingbaihanji.core.video.ShaderStore

/**
 * 由平台层在 Compose 树根部提供的着色器配置存储。
 *
 * 与 [LocalSettingsStore] 分开，避免自定义着色器源码较大时混入普通设置。
 */
val LocalShaderStore = staticCompositionLocalOf<ShaderStore> {
    error("LocalShaderStore was not provided")
}
