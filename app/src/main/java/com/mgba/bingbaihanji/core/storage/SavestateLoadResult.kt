package com.mgba.bingbaihanji.core.storage

/**
 * 即时存档加载结果。
 *
 * 独立于 [SavestateRepository] 定义，UI 只需依赖这个结果类型，
 * 不需要依赖具体的存储服务实现。
 */
sealed interface SavestateLoadResult {

    /** 加载成功；[info] 是存档文件头中的元数据。 */
    data class Success(val info: SavestateFormat.Info) : SavestateLoadResult

    /** 槽位为空（文件不存在）。 */
    data object SlotEmpty : SavestateLoadResult

    /** 存档属于另一个 ROM。 */
    data class RomMismatch(
        val savedSha: String,
        val currentSha: String,
    ) : SavestateLoadResult

    /** 文件头或数据不合法。 */
    data class Corrupted(val reason: String) : SavestateLoadResult

    /** 读取文件失败。 */
    data class FileError(val reason: String) : SavestateLoadResult

    /** 核心拒绝恢复该状态。 */
    data object CoreRejected : SavestateLoadResult
}
