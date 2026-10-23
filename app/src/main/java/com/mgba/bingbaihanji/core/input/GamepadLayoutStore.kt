package com.mgba.bingbaihanji.core.input

import kotlinx.coroutines.flow.StateFlow

/**
 * 虚拟按键布局的持久化与观察接口。
 *
 * Android 实现使用 SharedPreferences；桌面端可以换成配置文件。
 * UI 通过 [layout] 的 StateFlow 自动刷新，不需要手动重绘。
 */
interface GamepadLayoutStore {

    /** 当前生效的布局；保存后立即发射新值。 */
    val layout: StateFlow<GamepadLayout>

    /** 保存布局；实现方负责裁剪并确保菜单控件存在。 */
    fun save(layout: GamepadLayout)

    /** 恢复默认布局并持久化。 */
    fun reset()
}
