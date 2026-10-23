package com.mgba.bingbaihanji.platform.android.storage

import android.util.Log
import com.mgba.bingbaihanji.core.storage.SaveStorage
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 基于应用私有目录的 [SaveStorage] 实现。
 *
 * 目录布局与重构前完全一致：
 * - 即时存档：`files/states/{romId}.slot{N}.state`
 * - SRAM：`files/saves/{sha256}.srm`
 * - RTC：`files/saves/{sha256}.rtc`
 *
 * 写入采用 tmp → fsync → rename 的原子流程，崩溃时不会破坏上一份有效存档。
 */
class FileSaveStorage(private val filesDir: File) : SaveStorage {

    /**
     * 串行化所有写盘操作。
     *
     * 生命周期暂停时的异步自动存档与 onDestroy 的同步存档可能同时发生；
     * 两者使用相同的 `.tmp` 文件名，必须避免并发写入互相覆盖。
     */
    private val writeLock = ReentrantLock()

    private fun statesDir(): File =
        File(filesDir, "states").also { it.mkdirs() }

    private fun savesDir(): File =
        File(filesDir, "saves").also { it.mkdirs() }

    private fun stateFile(romId: String, slot: Int): File =
        File(statesDir(), "${romId.ifEmpty { "unknown" }}.slot$slot.state")

    private fun batteryFile(romId: String): File =
        File(savesDir(), "$romId.srm")

    private fun rtcFile(romId: String): File =
        File(savesDir(), "$romId.rtc")

    override fun readSavestate(romId: String, slot: Int): ByteArray? {
        val file = stateFile(romId, slot)
        return if (file.exists()) file.readBytes() else null
    }

    override fun writeSavestate(romId: String, slot: Int, data: ByteArray): Boolean =
        atomicWrite(stateFile(romId, slot), data)

    override fun deleteSavestate(romId: String, slot: Int) {
        stateFile(romId, slot).delete()
    }

    override fun savestateTimestamp(romId: String, slot: Int): Long? {
        val file = stateFile(romId, slot)
        return if (file.exists()) file.lastModified() else null
    }

    override fun readBatterySave(romId: String): ByteArray? {
        val file = batteryFile(romId)
        return if (file.exists()) file.readBytes() else null
    }

    override fun writeBatterySave(romId: String, data: ByteArray) {
        atomicWrite(batteryFile(romId), data)
    }

    override fun deleteBatterySave(romId: String) {
        batteryFile(romId).delete()
    }

    override fun readRtc(romId: String): ByteArray? {
        val file = rtcFile(romId)
        return if (file.exists()) file.readBytes() else null
    }

    override fun writeRtc(romId: String, data: ByteArray) {
        atomicWrite(rtcFile(romId), data)
    }

    override fun deleteRtc(romId: String) {
        rtcFile(romId).delete()
    }

    /** 写入临时文件、刷盘后重命名；失败时保留旧文件并返回 false。 */
    private fun atomicWrite(target: File, data: ByteArray): Boolean = writeLock.withLock {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.outputStream().use { out ->
                out.write(data)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // 某些文件系统不支持覆盖式重命名，退化为复制后删除。
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Atomic write failed for ${target.name}", e)
            tmp.delete()
            false
        }
    }

    companion object {
        private const val TAG = "FileSaveStorage"
    }
}
