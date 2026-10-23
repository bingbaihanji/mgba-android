package com.mgba.bingbaihanji.core.video

/**
 * 当前着色器配置。
 *
 * @property preset 内置预设或 [ShaderPreset.Custom]
 * @property source 自定义 GLSL 片元着色器源码；内置预设时可以为空
 * @property name 自定义文件的显示名，用于设置页展示
 */
data class ShaderConfig(
    val preset: ShaderPreset = ShaderPreset.Off,
    val source: String = "",
    val name: String = "",
)

/** 设置页与调试浮层共用的显示名称。 */
fun ShaderConfig.displayName(): String = when (preset) {
    ShaderPreset.Off -> "关闭"
    ShaderPreset.Lcd -> "LCD 网格"
    ShaderPreset.Scanlines -> "扫描线"
    ShaderPreset.Dot -> "点阵"
    ShaderPreset.Custom -> name.ifBlank { "自定义 GLSL" }
}
