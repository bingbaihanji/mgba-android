package com.mgba.bingbaihanji.core.storage

import java.io.IOException

/**
 * 单个存档槽位的状态，供 UI 展示。
 *
 * @property slot 槽位编号
 * @property exists 文件是否存在
 * @property timestamp 最后修改时间（毫秒）；槽位为空时为 null
 */
data class SlotStatus(
    val slot: Int,
    val exists: Boolean,
    val timestamp: Long?,
)

/**
 * 存档字节存储。只负责「按 ROM 标识读写字节」，不关心 MGST 格式或 SRAM 语义，
 * 便于桌面端换成普通文件、云同步或测试用内存实现。
 *
 * 参数 [romId] 对即时存档是 SHA-256 前 16 位（路径加载时为空字符串），
 * 对 SRAM/RTC 是完整 SHA-256。
 */
interface SaveStorage {

    /**
     * 读取即时存档原始字节。
     * 文件不存在返回 null；读取失败抛出 [IOException]。
     */
    @Throws(IOException::class)
    fun readSavestate(romId: String, slot: Int): ByteArray?

    /** 原子写入即时存档；失败返回 false。 */
    fun writeSavestate(romId: String, slot: Int, data: ByteArray): Boolean

    /** 删除即时存档；文件不存在时静默忽略。 */
    fun deleteSavestate(romId: String, slot: Int)

    /** 即时存档的最后修改时间；文件不存在返回 null。 */
    fun savestateTimestamp(romId: String, slot: Int): Long?

    /**
     * 读取电池存档（SRAM）。
     * 文件不存在返回 null；读取失败抛出 [IOException]。
     */
    @Throws(IOException::class)
    fun readBatterySave(romId: String): ByteArray?

    /** 原子写入电池存档；失败时静默忽略（保留上一份有效存档）。 */
    fun writeBatterySave(romId: String, data: ByteArray)

    /** 删除电池存档；文件不存在时静默忽略。 */
    fun deleteBatterySave(romId: String)

    /**
     * 读取 RTC 数据。
     * 文件不存在返回 null；读取失败抛出 [IOException]。
     */
    @Throws(IOException::class)
    fun readRtc(romId: String): ByteArray?

    /** 原子写入 RTC 数据；失败时静默忽略。 */
    fun writeRtc(romId: String, data: ByteArray)

    /** 删除 RTC 数据；文件不存在时静默忽略。 */
    fun deleteRtc(romId: String)
}
