package com.mgba.bingbaihanji.core.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * 设置读写接口。Android 实现使用 SharedPreferences，桌面端可以换成
 * Preferences API 或 JSON 文件，核心逻辑无需改动。
 *
 * 线程约定：[settings] 可被任意线程收集；[update] 应在主线程调用。
 */
interface SettingsStore {

    /** 当前设置的只读数据流，修改后立即发射新值。 */
    val settings: StateFlow<AppSettings>

    /** 以纯函数方式更新设置并持久化。 */
    fun update(transform: (AppSettings) -> AppSettings)
}
