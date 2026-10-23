package com.mgba.bingbaihanji.core.video

import kotlinx.coroutines.flow.StateFlow

/**
 * 着色器配置的持久化与观察接口。
 *
 * 与 [com.mgba.bingbaihanji.core.settings.SettingsStore] 分开存储，
 * 避免自定义着色器源码较大时，每次调节音量/透明度都重写整份偏好文件。
 */
interface ShaderStore {

    /** 当前生效的着色器配置；保存后立即发射。 */
    val config: StateFlow<ShaderConfig>

    /** 保存配置并持久化。 */
    fun save(config: ShaderConfig)

    /** 恢复为关闭着色器。 */
    fun reset()
}
