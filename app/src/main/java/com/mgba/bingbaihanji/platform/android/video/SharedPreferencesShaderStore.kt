package com.mgba.bingbaihanji.platform.android.video

import android.content.Context
import android.content.SharedPreferences
import com.mgba.bingbaihanji.core.video.ShaderConfig
import com.mgba.bingbaihanji.core.video.ShaderPreset
import com.mgba.bingbaihanji.core.video.ShaderStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ShaderStore] 的 Android 实现。
 *
 * 使用独立的偏好文件保存源码，避免与普通设置混在一起。
 */
class SharedPreferencesShaderStore(context: Context) : ShaderStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    override val config: StateFlow<ShaderConfig> = _config.asStateFlow()

    override fun save(config: ShaderConfig) {
        _config.value = config
        prefs.edit()
            .putString(KEY_PRESET, config.preset.id)
            .putString(KEY_SOURCE, config.source)
            .putString(KEY_NAME, config.name)
            .apply()
    }

    override fun reset() {
        save(ShaderConfig())
    }

    private fun load(): ShaderConfig = ShaderConfig(
        preset = ShaderPreset.fromId(
            prefs.getString(KEY_PRESET, ShaderPreset.Off.id)!!
        ),
        source = prefs.getString(KEY_SOURCE, "") ?: "",
        name = prefs.getString(KEY_NAME, "") ?: "",
    )

    companion object {
        private const val PREFS_NAME = "shader_settings"
        private const val KEY_PRESET = "preset"
        private const val KEY_SOURCE = "source"
        private const val KEY_NAME = "name"
    }
}
