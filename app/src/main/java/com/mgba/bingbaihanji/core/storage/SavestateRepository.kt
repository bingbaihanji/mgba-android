package com.mgba.bingbaihanji.core.storage

import com.mgba.bingbaihanji.core.emulation.EmulatorCore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.logging.Logger

/**
 * 即时存档业务逻辑：序列化核心状态、写入 MGST 文件、校验并恢复。
 *
 * 线程约定：会执行文件 I/O 与核心序列化调用，应在后台线程调用。
 */
class SavestateRepository(
    private val core: EmulatorCore,
    private val storage: SaveStorage,
) {

    /** 列出全部槽位；[romId] 为空时使用存储实现的默认标识。 */
    fun listSlots(romId: String): List<SlotStatus> =
        (0 until MAX_SLOTS).map { slot ->
            val timestamp = storage.savestateTimestamp(romId, slot)
            SlotStatus(slot, timestamp != null, timestamp)
        }

    /**
     * 把当前状态保存到 [slot]。
     * 成功返回 true；核心不支持序列化、序列化失败或写盘失败返回 false。
     */
    fun save(romId: String, slot: Int): Boolean {
        val size = core.serializeSize
        if (size <= 0L || size > SavestateFormat.MAX_STATE_SIZE) {
            LOG.warning("serializeSize=$size, cannot save slot $slot")
            return false
        }

        val dataBuffer = ByteBuffer.allocateDirect(size.toInt()).order(ByteOrder.nativeOrder())
        if (!core.serialize(dataBuffer)) {
            LOG.warning("serialize failed for slot $slot")
            return false
        }

        val coreName = core.coreName ?: "unknown"
        val coreVersion = core.coreVersion ?: "unknown"
        val data = ByteArray(size.toInt())
        dataBuffer.position(0)
        dataBuffer.get(data)

        val encoded = SavestateFormat.encode(coreName, coreVersion, romId, data)
        return storage.writeSavestate(romId, slot, encoded)
    }

    /**
     * 从 [slot] 读取并恢复状态。
     * 校验顺序：文件存在 → 头部合法 → ROM 匹配 → 数据长度 → 截断检查 → 核心接受。
     */
    fun load(romId: String, slot: Int): SavestateLoadResult {
        val bytes = try {
            storage.readSavestate(romId, slot)
        } catch (e: IOException) {
            LOG.warning("Cannot read state file: ${e.message}")
            return SavestateLoadResult.FileError(e.message ?: "read error")
        } ?: return SavestateLoadResult.SlotEmpty

        val parsed = SavestateFormat.parse(bytes)
            ?: return SavestateLoadResult.Corrupted("Invalid header")
        val info = parsed.info

        if (info.romSha256.isNotEmpty() && info.romSha256 != romId) {
            return SavestateLoadResult.RomMismatch(info.romSha256, romId)
        }
        if (info.dataSize <= 0L || info.dataSize > SavestateFormat.MAX_STATE_SIZE) {
            return SavestateLoadResult.Corrupted("Bad data size: ${info.dataSize}")
        }

        if (parsed.headerSize + info.dataSize > bytes.size) {
            return SavestateLoadResult.Corrupted("Truncated data")
        }

        val dataBuffer = ByteBuffer.allocateDirect(info.dataSize.toInt())
            .order(ByteOrder.nativeOrder())
        dataBuffer.put(bytes, parsed.headerSize, info.dataSize.toInt())
        dataBuffer.position(0)

        return if (core.unserialize(dataBuffer, info.dataSize.toInt())) {
            SavestateLoadResult.Success(info)
        } else {
            SavestateLoadResult.CoreRejected
        }
    }

    /** 删除槽位；不存在时静默忽略。 */
    fun delete(romId: String, slot: Int) {
        storage.deleteSavestate(romId, slot)
    }

    companion object {
        /** UI 展示的槽位数（0..9），自动存档使用紧随其后的槽位。 */
        const val MAX_SLOTS = 10

        /** 自动存档槽位，不对 UI 展示。 */
        const val AUTO_SAVE_SLOT = MAX_SLOTS

        private val LOG: Logger = Logger.getLogger("SavestateRepository")
    }
}
