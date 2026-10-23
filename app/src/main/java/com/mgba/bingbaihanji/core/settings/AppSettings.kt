package com.mgba.bingbaihanji.core.settings

/**
 * 屏幕方向。取值字符串写入持久化配置，不能随意修改。
 */
enum class ScreenOrientation(val value: String) {
    Sensor("sensor"),
    Landscape("landscape"),
    Portrait("portrait");

    companion object {
        fun fromValue(v: String): ScreenOrientation =
            entries.firstOrNull { it.value == v } ?: Sensor
    }
}

/**
 * 应用设置的平台无关快照。字段、默认值与重构前完全一致。
 */
data class AppSettings(
    val stretchToFit: Boolean = false,
    val screenOrientation: ScreenOrientation = ScreenOrientation.Sensor,
    /** 屏幕刷新率（Hz）；0 表示跟随系统。 */
    val displayFps: Int = 0,
    val linearFiltering: Boolean = true,
    val enableSound: Boolean = true,
    val soundVolume: Int = 100,
    val enableVKeypad: Boolean = true,
    val vkeypadOpacity: Float = 0.3f,
    /** 游戏速度倍率；1.0 为正常，低于 1 慢放，高于 1 快进。 */
    val gameSpeed: Float = SpeedPresets.DEFAULT_SPEED,
    val autoSaveOnExit: Boolean = true,
    val confirmOnClose: Boolean = true,
    val fullScreenMode: Boolean = true,
    val fullScreenImmersive: Boolean = true,
    /** 调试：在游戏画面左上角显示实时帧率。 */
    val showFps: Boolean = false,
    /** 调试：在帧率浮层中显示当前变速状态（与帧率使用不同颜色）。 */
    val showSpeedStatus: Boolean = false,
)
