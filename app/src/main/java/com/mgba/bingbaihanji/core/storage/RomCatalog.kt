package com.mgba.bingbaihanji.core.storage

/**
 * 游戏列表中的一项。
 *
 * @property id 稳定标识（Android 实现使用缓存文件名中的 SHA-256）
 * @property displayName 展示名称
 * @property path 可供仿真核心加载的本地路径
 * @property sizeBytes 文件大小（字节）
 */
data class RomSummary(
    val id: String,
    val displayName: String,
    val path: String,
    val sizeBytes: Long,
)

/**
 * 可用 ROM 列表的提供者。
 *
 * Android 实现扫描应用私有缓存目录；桌面端可以实现为扫描任意文件夹。
 * 实现会执行文件 I/O，调用方应在后台线程调用 [listRoms]。
 */
interface RomCatalog {

    /** 列出当前可用的 ROM。 */
    fun listRoms(): List<RomSummary>

    /**
     * 修改 ROM 展示名；传空字符串表示清除自定义名称、恢复自动名称。
     * 实现方负责持久化。
     */
    fun rename(id: String, displayName: String)

    /**
     * 删除 ROM 副本。返回 true 表示文件已删除或原本就不存在。
     *
     * 只负责 ROM 本身；关联存档与金手指由 [RomLibrary] 编排。
     */
    fun delete(id: String): Boolean

    companion object {
        /**
         * 存档与金手指键使用的标识长度。
         *
         * ROM 的完整标识是 SHA-256；即时存档与金手指只取其前 16 位以缩短文件名。
         * 电池存档/RTC 使用完整 SHA-256。
         */
        const val ROM_ID_LENGTH = 16
    }
}
