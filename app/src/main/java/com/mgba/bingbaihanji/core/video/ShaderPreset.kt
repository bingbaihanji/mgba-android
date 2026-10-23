package com.mgba.bingbaihanji.core.video

/**
 * 内置着色器预设。[Custom] 表示使用用户选择的 GLSL 片元着色器文件。
 */
enum class ShaderPreset(val id: String) {
    Off("off"),
    Lcd("lcd"),
    Scanlines("scanlines"),
    Dot("dot"),
    Custom("custom");

    companion object {
        fun fromId(id: String): ShaderPreset =
            entries.firstOrNull { it.id == id } ?: Off
    }
}
