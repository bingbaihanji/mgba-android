package com.mgba.bingbaihanji.core.storage

import com.mgba.bingbaihanji.core.emulation.EmulatorCore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.logging.Logger

/**
 * 电池存档（SRAM）与 RTC 的持久化逻辑。
 *
 * 线程约定：会执行文件 I/O 与核心内存拷贝，应在后台线程调用。
 */
class BatterySaveRepository(
    private val core: EmulatorCore,
    private val storage: SaveStorage,
) {

    /** 依次保存 SRAM 与 RTC。 */
    fun save(romId: String) {
        saveSram(romId)
        saveRtc(romId)
    }

    /** 依次加载 SRAM 与 RTC；文件不存在时静默跳过。 */
    fun load(romId: String) {
        loadSram(romId)
        loadRtc(romId)
    }

    fun saveSram(romId: String) {
        val size = core.sramSize
        if (size <= 0) return

        val buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        if (!core.saveSram(buffer)) {
            LOG.warning("saveSram failed for $romId")
            return
        }

        val bytes = ByteArray(size)
        buffer.position(0)
        buffer.get(bytes)
        storage.writeBatterySave(romId, bytes)
    }

    fun loadSram(romId: String) {
        val bytes = storage.readBatterySave(romId) ?: return
        if (bytes.isEmpty()) return

        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.position(0)
        core.loadSram(buffer, bytes.size)
    }

    fun saveRtc(romId: String) {
        val size = core.rtcSize
        if (size <= 0) return

        val buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        if (!core.saveRtc(buffer)) {
            LOG.warning("saveRtc failed for $romId")
            return
        }

        val bytes = ByteArray(size)
        buffer.position(0)
        buffer.get(bytes)
        storage.writeRtc(romId, bytes)
    }

    fun loadRtc(romId: String) {
        val bytes = storage.readRtc(romId) ?: return
        if (bytes.isEmpty()) return

        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.position(0)
        core.loadRtc(buffer, bytes.size)
    }

    companion object {
        private val LOG: Logger = Logger.getLogger("BatterySaveRepository")
    }
}
