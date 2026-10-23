package com.mgba.bingbaihanji.core.emulation

/**
 * Libretro 核心注册的单个选项。
 *
 * @property values 以 `|` 分隔后的可选值列表
 * @property currentIndex 当前值在 [values] 中的下标
 */
data class CoreOption(
    val index: Int,
    val key: String,
    val values: List<String>,
    val currentIndex: Int,
) {
    /** 当前值的文本形式。 */
    val current: String
        get() = values.getOrNull(currentIndex) ?: values.firstOrNull() ?: ""
}

/**
 * 读取会话已经加载的全部核心选项。
 *
 * 调用前必须确保 ROM 已加载，否则核心尚未注册选项，返回空列表。
 */
fun EmulatorCore.loadCoreOptions(): List<CoreOption> {
    val count = optionCount
    return (0 until count).mapNotNull { i ->
        val key = optionKey(i) ?: return@mapNotNull null
        val values = optionValues(i)?.split('|') ?: emptyList()
        CoreOption(
            index = i,
            key = key,
            values = values,
            currentIndex = optionCurrent(i),
        )
    }
}
