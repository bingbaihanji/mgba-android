package com.mgba.bingbaihanji.ui.settings

/**
 * 设置页展示的当前 ROM 快照。
 *
 * 由游戏页在打开设置时传入；从主界面的设置入口进入时为 null，
 * 此时“ROM 信息”页会提示先启动游戏。
 */
data class RomInfoUi(
    /** ROM 展示名（不含扩展名）。 */
    val title: String,
    /** 核心实际加载的本地路径。 */
    val path: String,
    /** 文件大小（字节）；读取失败时为 0。 */
    val sizeBytes: Long,
    /** 完整 SHA-256，用作存档与金手指的内容标识。 */
    val sha256: String,
    /** 存档文件名使用的前 16 位 SHA-256。 */
    val romId: String,
    /** 核心名称，加载失败时可能为 null。 */
    val coreName: String?,
    /** 核心版本。 */
    val coreVersion: String?,
    /** 核心帧率（GBA 约 59.73）。 */
    val fps: Double,
    /** 核心输出采样率（GBA 通常为 32768 Hz）。 */
    val sampleRate: Int,
)
