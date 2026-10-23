package com.mgba.bingbaihanji.platform.android.input

import android.content.Context
import android.content.SharedPreferences
import com.mgba.bingbaihanji.core.input.GamepadLayout
import com.mgba.bingbaihanji.core.input.GamepadLayoutCodec
import com.mgba.bingbaihanji.core.input.GamepadLayoutStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [GamepadLayoutStore] 的 Android 实现。
 *
 * 使用独立的偏好文件，避免和普通设置混在一起；首次读取失败时回退到默认布局。
 */
class SharedPreferencesGamepadLayoutStore(context: Context) : GamepadLayoutStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _layout = MutableStateFlow(load())
    override val layout: StateFlow<GamepadLayout> = _layout.asStateFlow()

    override fun save(layout: GamepadLayout) {
        val sanitized = layout.sanitized()
        _layout.value = sanitized
        prefs.edit()
            .putString(KEY_LAYOUT, GamepadLayoutCodec.encode(sanitized))
            .apply()
    }

    override fun reset() {
        save(GamepadLayout.DEFAULT)
    }

    private fun load(): GamepadLayout {
        val raw = prefs.getString(KEY_LAYOUT, null)
        val decoded = GamepadLayoutCodec.decode(raw)
        return (decoded ?: GamepadLayout.DEFAULT).sanitized()
    }

    companion object {
        private const val PREFS_NAME = "gamepad_layout"
        private const val KEY_LAYOUT = "layout_v1"
    }
}
