package com.mgba.bingbaihanji.platform.android.settings

import android.content.Context
import android.content.SharedPreferences
import com.mgba.bingbaihanji.core.settings.AppSettings
import com.mgba.bingbaihanji.core.settings.ScreenOrientation
import com.mgba.bingbaihanji.core.settings.SettingsStore
import com.mgba.bingbaihanji.core.settings.SpeedPresets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [SettingsStore] 的 Android 实现，使用 SharedPreferences 持久化。
 *
 * 偏好文件名与键名与重构前完全一致，升级后不会丢失已有设置。
 * 由 [com.mgba.bingbaihanji.MgbaApplication] 创建并持有，
 * 不再需要显式的 `init()` 调用。
 */
class SharedPreferencesSettingsStore(context: Context) : SettingsStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override fun update(transform: (AppSettings) -> AppSettings) {
        val new = transform(_settings.value)
        _settings.value = new
        save(new)
    }

    private fun load(): AppSettings = AppSettings(
        stretchToFit = prefs.getBoolean("stretchToFit", false),
        screenOrientation = ScreenOrientation.fromValue(
            prefs.getString("screenOrientation", "sensor")!!
        ),
        displayFps = prefs.getInt("displayFps", 0),
        linearFiltering = prefs.getBoolean("linearFiltering", true),
        enableSound = prefs.getBoolean("enableSound", true),
        soundVolume = prefs.getInt("soundVolume", 100),
        enableVKeypad = prefs.getBoolean("enableVKeypad", true),
        vkeypadOpacity = prefs.getFloat("vkeypadOpacity", 0.3f),
        // 旧版本把倍率存成整数（4 = 4x），这里做一次兼容读取并吸附到常用档位。
        gameSpeed = SpeedPresets.snap(
            prefs.getFloat(
                "gameSpeed",
                prefs.getInt("fastForwardSpeed", 4).toFloat(),
            )
        ),
        autoSaveOnExit = prefs.getBoolean("autoSaveOnExit", true),
        confirmOnClose = prefs.getBoolean("confirmOnClose", true),
        fullScreenMode = prefs.getBoolean("fullScreenMode", true),
        fullScreenImmersive = prefs.getBoolean("fullScreenImmersive", true),
        showFps = prefs.getBoolean("showFps", false),
        showSpeedStatus = prefs.getBoolean("showSpeedStatus", false),
    )

    private fun save(s: AppSettings) {
        prefs.edit()
            .putBoolean("stretchToFit", s.stretchToFit)
            .putString("screenOrientation", s.screenOrientation.value)
            .putInt("displayFps", s.displayFps)
            .putBoolean("linearFiltering", s.linearFiltering)
            .putBoolean("enableSound", s.enableSound)
            .putInt("soundVolume", s.soundVolume)
            .putBoolean("enableVKeypad", s.enableVKeypad)
            .putFloat("vkeypadOpacity", s.vkeypadOpacity)
            .putFloat("gameSpeed", s.gameSpeed)
            .putBoolean("autoSaveOnExit", s.autoSaveOnExit)
            .putBoolean("confirmOnClose", s.confirmOnClose)
            .putBoolean("fullScreenMode", s.fullScreenMode)
            .putBoolean("fullScreenImmersive", s.fullScreenImmersive)
            .putBoolean("showFps", s.showFps)
            .putBoolean("showSpeedStatus", s.showSpeedStatus)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
    }
}
