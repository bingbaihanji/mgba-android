package com.mgba.bingbaihanji

import android.app.Application
import com.mgba.bingbaihanji.core.cheat.CheatStore
import com.mgba.bingbaihanji.core.emulation.CoreOptionStore
import com.mgba.bingbaihanji.core.input.GamepadLayoutStore
import com.mgba.bingbaihanji.core.input.KeyMapStore
import com.mgba.bingbaihanji.core.settings.SettingsStore
import com.mgba.bingbaihanji.core.video.ShaderStore
import com.mgba.bingbaihanji.platform.android.cheat.SharedPreferencesCheatStore
import com.mgba.bingbaihanji.platform.android.emulation.SharedPreferencesCoreOptionStore
import com.mgba.bingbaihanji.platform.android.input.SharedPreferencesGamepadLayoutStore
import com.mgba.bingbaihanji.platform.android.input.SharedPreferencesKeyMapStore
import com.mgba.bingbaihanji.platform.android.settings.SharedPreferencesSettingsStore
import com.mgba.bingbaihanji.platform.android.video.SharedPreferencesShaderStore

/**
 * 应用级组合根：创建并持有跨 Activity 共享的单例依赖。
 *
 * 放在 [Application] 中可以保证任何 Activity 启动前完成创建，
 * 避免全局 `lateinit` 在冷启动路径上未初始化的问题。
 */
class MgbaApplication : Application() {

    /** 应用设置存储，整个进程共享同一份 StateFlow。 */
    val settings: SettingsStore by lazy { SharedPreferencesSettingsStore(this) }

    /** 虚拟按键布局存储，游戏页与布局编辑器共享。 */
    val gamepadLayoutStore: GamepadLayoutStore by lazy {
        SharedPreferencesGamepadLayoutStore(this)
    }

    /** 视频着色器配置，包含内置预设或自定义 GLSL 源码。 */
    val shaderStore: ShaderStore by lazy { SharedPreferencesShaderStore(this) }

    /** 键盘/手柄按键映射配置。 */
    val keyMapStore: KeyMapStore by lazy { SharedPreferencesKeyMapStore(this) }

    /** Libretro 核心选项的持久化值。 */
    val coreOptionStore: CoreOptionStore by lazy {
        SharedPreferencesCoreOptionStore(this)
    }

    /** 按 ROM 保存的金手指列表。 */
    val cheatStore: CheatStore by lazy { SharedPreferencesCheatStore(this) }
}
