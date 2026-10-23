package com.mgba.bingbaihanji.platform.android.storage

import android.util.Log
import com.mgba.bingbaihanji.core.storage.SaveStorage
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 基于文件的 [SaveStorage] 实现：每个 ROM 使用一个独立子目录。
 *
 * 默认根目录为 `/storage/emulated/0/bingbaihanji/mgba/`，结构如下：
 * - `<ROM 名称>/.mgba-rom`：标识文件，保存完整 SHA-256，用于定位与同名消歧
 * - `<ROM 名称>/game.sav`：电池存档（SRAM，即游戏内存档）
 * - `<ROM 名称>/game.rtc`：RTC 数据
 * - `<ROM 名称>/slot{0..10}.state`：即时存档
 *
 * 文件夹由 [prepareRom] 在 ROM 加载后创建。存档按 ROM 的 SHA-256 定位，
 * 同一 ROM 的即时存档、电池存档与 RTC 都落在同一个文件夹里。
 *
 * 写入采用 tmp → fsync → rename 的原子流程，崩溃时不会破坏上一份有效存档。
 */
class FileSaveStorage(
    private val rootDirProvider: () -> File,
    /** 旧版存档目录（应用私有 filesDir），用于首次迁移 `states/` 与 `saves/`。 */
    private val legacyFilesDir: File? = null,
    /**
     * 未授权时使用的回退根目录。
     *
     * 授权后 [prepareRom] 会把回退目录里同一 ROM 的文件夹整体迁移到公共目录，
     * 避免「先游玩、后授权」时看起来丢了存档。
     */
    private val fallbackRootProvider: (() -> File)? = null,
) : SaveStorage {

    /**
     * 串行化所有写盘操作。
     *
     * 生命周期暂停时的异步自动存档与 onDestroy 的同步存档可能同时发生；
     * 两者使用相同的 `.tmp` 文件名，必须避免并发写入互相覆盖。
     */
    private val writeLock = ReentrantLock()

    /** 串行化文件夹的创建与迁移，避免同一 ROM 并发创建出两个目录。 */
    private val folderLock = Any()

    private fun rootDir(): File = rootDirProvider().also { it.mkdirs() }

    override fun prepareRom(romId: String, sha256: String, displayName: String) {
        val key = sha256.ifEmpty { romId }
        if (key.isEmpty()) return

        synchronized(folderLock) {
            val existing = findFolder(key)
                ?: findFolder(romId)
                ?: adoptFallbackFolder(key)
                ?: adoptFallbackFolder(romId)
            val dir = existing ?: createFolder(key, displayName)
            migrateLegacy(dir, romId, key)
        }
    }

    override fun romDirectory(romId: String): File? = findFolder(romId)

    override fun readSavestate(romId: String, slot: Int): ByteArray? {
        val file = stateFile(romId, slot) ?: return null
        return if (file.isFile) file.readBytes() else null
    }

    override fun writeSavestate(romId: String, slot: Int, data: ByteArray): Boolean {
        val dir = ensureFolder(romId) ?: return false
        return atomicWrite(File(dir, stateFileName(slot)), data)
    }

    override fun deleteSavestate(romId: String, slot: Int) {
        stateFile(romId, slot)?.delete()
    }

    override fun savestateTimestamp(romId: String, slot: Int): Long? {
        val file = stateFile(romId, slot) ?: return null
        return if (file.isFile) file.lastModified() else null
    }

    override fun readBatterySave(romId: String): ByteArray? {
        val file = File(folderFor(romId) ?: return null, BATTERY_FILE_NAME)
        return if (file.isFile) file.readBytes() else null
    }

    override fun writeBatterySave(romId: String, data: ByteArray) {
        val dir = ensureFolder(romId) ?: return
        atomicWrite(File(dir, BATTERY_FILE_NAME), data)
    }

    override fun deleteBatterySave(romId: String) {
        folderFor(romId)?.let { File(it, BATTERY_FILE_NAME).delete() }
    }

    override fun readRtc(romId: String): ByteArray? {
        val file = File(folderFor(romId) ?: return null, RTC_FILE_NAME)
        return if (file.isFile) file.readBytes() else null
    }

    override fun writeRtc(romId: String, data: ByteArray) {
        val dir = ensureFolder(romId) ?: return
        atomicWrite(File(dir, RTC_FILE_NAME), data)
    }

    override fun deleteRtc(romId: String) {
        folderFor(romId)?.let { File(it, RTC_FILE_NAME).delete() }
    }

    override fun deleteRomData(romId: String) {
        val dir = findFolder(romId) ?: return
        synchronized(folderLock) {
            dir.deleteRecursively()
        }
    }

    /** 按 ROM 标识查找已存在的存档文件夹；找不到返回 null。 */
    private fun folderFor(romId: String): File? = findFolder(romId)

    private fun findFolder(key: String): File? {
        if (key.isEmpty()) return null
        return findFolderIn(rootDir(), key)
    }

    private fun findFolderIn(root: File, key: String): File? {
        val dirs = root.listFiles() ?: return null
        return dirs.firstOrNull { dir -> dir.isDirectory && markerMatches(dir, key) }
    }

    /**
     * 在回退目录中查找该 ROM 的文件夹，找到后移动到当前根目录。
     *
     * 用于用户先拒绝权限、游玩一段时间后才授权的场景。
     */
    private fun adoptFallbackFolder(key: String): File? {
        val provider = fallbackRootProvider ?: return null
        if (key.isEmpty()) return null

        val current = rootDir()
        val fallback = provider()
        if (!fallback.isDirectory) return null
        val sameRoot = try {
            fallback.canonicalPath == current.canonicalPath
        } catch (_: IOException) {
            fallback.absolutePath == current.absolutePath
        }
        if (sameRoot) return null

        val source = findFolderIn(fallback, key) ?: return null
        val target = uniqueFolder(current, source.name, key)
        return try {
            if (source.renameTo(target)) {
                target
            } else if (source.copyRecursively(target, overwrite = false)) {
                source.deleteRecursively()
                target
            } else {
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "Cannot adopt fallback saves: ${e.message}")
            null
        }
    }

    /** 在当前根目录中生成一个不冲突的文件夹路径。 */
    private fun uniqueFolder(root: File, preferredName: String, key: String): File {
        var candidate = File(root, preferredName)
        var attempt = 0
        while (candidate.exists() && !isFolderFor(candidate, key)) {
            attempt++
            val suffix = "-${key.take(SHORT_ID_LENGTH)}" +
                    if (attempt > 1) "-$attempt" else ""
            candidate = File(root, preferredName + suffix)
        }
        return candidate
    }

    /**
     * 标识文件是否属于 [key]。
     *
     * 即时存档使用 SHA-256 前 16 位，电池存档使用完整 SHA-256，
     * 两者都是同一 ROM 的标识，只要一方是另一方的前缀即视为匹配。
     */
    private fun markerMatches(dir: File, key: String): Boolean {
        val marker = File(dir, MARKER_FILE_NAME)
        if (!marker.isFile) return false
        val id = try {
            marker.readText(Charsets.UTF_8).trim()
        } catch (_: IOException) {
            return false
        }
        return id.isNotEmpty() && (id == key || id.startsWith(key) || key.startsWith(id))
    }

    /** 找到已有文件夹，没有则用 [key] 作为名称兜底创建。 */
    private fun ensureFolder(key: String): File? {
        if (key.isEmpty()) return null
        findFolder(key)?.let { return it }
        synchronized(folderLock) {
            findFolder(key)?.let { return it }
            return createFolder(key, key)
        }
    }

    /**
     * 按 ROM 名称创建文件夹。
     *
     * 同名 ROM 会在名称后追加哈希前缀，避免不同游戏共用同一个存档目录。
     */
    private fun createFolder(fullId: String, displayName: String): File {
        val root = rootDir()
        val base = sanitizeFolderName(displayName.ifBlank { DEFAULT_FOLDER_NAME })
        var candidate = File(root, base)
        var attempt = 0
        while (candidate.exists() && !isFolderFor(candidate, fullId)) {
            attempt++
            val suffix = "-${fullId.take(SHORT_ID_LENGTH)}" +
                    if (attempt > 1) "-$attempt" else ""
            candidate = File(root, base + suffix)
        }

        if (!candidate.exists() && !candidate.mkdirs()) {
            Log.w(TAG, "Cannot create save directory: ${candidate.absolutePath}")
        }
        try {
            File(candidate, MARKER_FILE_NAME).writeText(fullId, Charsets.UTF_8)
        } catch (e: IOException) {
            Log.w(TAG, "Cannot write save marker: ${e.message}")
        }
        return candidate
    }

    private fun isFolderFor(dir: File, key: String): Boolean =
        dir.isDirectory && markerMatches(dir, key)

    /** 去掉文件系统不允许的字符，并限制长度。 */
    private fun sanitizeFolderName(raw: String): String {
        val builder = StringBuilder()
        for (ch in raw) {
            builder.append(
                if (ch.isISOControl() || ch in ILLEGAL_CHARS) '_' else ch
            )
        }
        // Windows 共享盘与 FAT 系文件系统不接受结尾的点和空格。
        val cleaned = builder.toString().trim().trim('.', ' ').trim()
        return cleaned.ifEmpty { DEFAULT_FOLDER_NAME }.take(MAX_FOLDER_NAME_LENGTH)
    }

    private fun stateFile(romId: String, slot: Int): File? =
        folderFor(romId)?.let { File(it, stateFileName(slot)) }

    private fun stateFileName(slot: Int): String = "slot$slot.state"

    /**
     * 把旧版 `files/states` 与 `files/saves` 中的存档迁移到新目录。
     *
     * 目标文件已存在时不覆盖，保证迁移不会破坏新存档。
     */
    private fun migrateLegacy(dir: File, romId: String, fullId: String) {
        val legacyRoot = legacyFilesDir ?: return

        if (romId.isNotEmpty()) {
            val prefix = "$romId.slot"
            File(legacyRoot, LEGACY_STATES_DIR)
                .listFiles { file ->
                    file.isFile && file.name.startsWith(prefix) &&
                            file.name.endsWith(LEGACY_STATE_SUFFIX)
                }
                ?.forEach { source ->
                    val slot = source.name
                        .removePrefix(prefix)
                        .removeSuffix(LEGACY_STATE_SUFFIX)
                        .toIntOrNull() ?: return@forEach
                    moveIfAbsent(source, File(dir, stateFileName(slot)))
                }
        }

        val legacySaves = File(legacyRoot, LEGACY_SAVES_DIR)
        val ids = listOf(fullId, romId).filter { it.isNotEmpty() }.distinct()
        for (id in ids) {
            moveIfAbsent(File(legacySaves, "$id.srm"), File(dir, BATTERY_FILE_NAME))
            moveIfAbsent(File(legacySaves, "$id.rtc"), File(dir, RTC_FILE_NAME))
        }
    }

    private fun moveIfAbsent(source: File, target: File) {
        if (!source.isFile || target.exists()) return
        try {
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = false)
                source.delete()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Cannot migrate ${source.name}: ${e.message}")
        }
    }

    /** 写入临时文件、刷盘后重命名；失败时保留旧文件并返回 false。 */
    private fun atomicWrite(target: File, data: ByteArray): Boolean = writeLock.withLock {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.e(TAG, "Cannot create directory: ${parent.absolutePath}")
            return false
        }

        val tmp = File(parent, "${target.name}.tmp")
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

        /** 标识文件，隐藏文件名减少对用户的干扰。 */
        private const val MARKER_FILE_NAME = ".mgba-rom"

        /** 电池存档（游戏内存档）。 */
        private const val BATTERY_FILE_NAME = "game.sav"

        /** RTC 数据。 */
        private const val RTC_FILE_NAME = "game.rtc"

        /** 旧版即时存档目录名。 */
        private const val LEGACY_STATES_DIR = "states"

        /** 旧版电池存档目录名。 */
        private const val LEGACY_SAVES_DIR = "saves"

        private const val LEGACY_STATE_SUFFIX = ".state"

        private const val DEFAULT_FOLDER_NAME = "ROM"

        private const val MAX_FOLDER_NAME_LENGTH = 64

        private const val SHORT_ID_LENGTH = 8

        private const val ILLEGAL_CHARS = "\\/:*?\"<>|"
    }
}
