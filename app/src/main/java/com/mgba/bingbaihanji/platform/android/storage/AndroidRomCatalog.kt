package com.mgba.bingbaihanji.platform.android.storage

import android.content.Context
import com.mgba.bingbaihanji.core.storage.RomCatalog
import com.mgba.bingbaihanji.core.storage.RomMetadataParser
import com.mgba.bingbaihanji.core.storage.RomSummary
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * [RomCatalog] 的 Android 实现：扫描 `files/rom-cache` 下的 `.gba` 文件。
 *
 * 展示名优先级：
 * 1. 导入时保存的 `{sha256}.name`（原始 SAF 文件名）；
 * 2. ROM 头部标题（GBA 0xA0 / GB 0x134）；
 * 3. `未知游戏 {哈希前 8 位}`。
 */
class AndroidRomCatalog(context: Context) : RomCatalog {

    private val appContext = context.applicationContext

    override fun listRoms(): List<RomSummary> {
        val dir = AndroidRomRepository.romCacheDir(appContext)
        return dir.listFiles { file ->
            file.isFile && file.name.endsWith(".gba") && !file.name.startsWith("tmp-")
        }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val id = file.nameWithoutExtension
                RomSummary(
                    id = id,
                    displayName = resolveDisplayName(file, id),
                    path = file.absolutePath,
                    sizeBytes = file.length(),
                )
            }
            ?: emptyList()
    }

    override fun delete(id: String): Boolean {
        // id 来自文件名，仍显式拒绝路径分隔符，避免任何形式的目录穿越。
        if (id.isEmpty() || id.contains('/') || id.contains('\\')) return false

        val dir = AndroidRomRepository.romCacheDir(appContext)
        File(dir, "$id.name").delete()
        val rom = File(dir, "$id.gba")
        return !rom.exists() || rom.delete()
    }

    override fun rename(id: String, displayName: String) {
        val name = displayName.trim()
        val nameFile = File(AndroidRomRepository.romCacheDir(appContext), "$id.name")
        try {
            if (name.isEmpty()) {
                nameFile.delete()
            } else {
                nameFile.writeText(name, Charsets.UTF_8)
            }
        } catch (_: IOException) {
            // 名称只是展示元数据，写入失败不影响 ROM 本身。
        }
    }

    private fun resolveDisplayName(file: File, id: String): String =
        readPersistedName(file)
            ?: readHeaderTitle(file)
            ?: "未知游戏 ${id.take(8)}"

    private fun readPersistedName(file: File): String? {
        val nameFile = File(file.parentFile, "${file.nameWithoutExtension}.name")
        if (!nameFile.isFile) return null
        return try {
            cleanDisplayName(nameFile.readText(Charsets.UTF_8))
        } catch (_: IOException) {
            null
        }
    }

    private fun readHeaderTitle(file: File): String? {
        return try {
            val header = ByteArray(HEADER_SIZE)
            var read = 0
            file.inputStream().use { input ->
                while (read < header.size) {
                    val count = input.read(header, read, header.size - read)
                    if (count < 0) break
                    read += count
                }
            }
            if (read < MIN_HEADER_BYTES) null else RomMetadataParser.title(header)
        } catch (_: IOException) {
            null
        }
    }

    /**
     * 去掉已知 ROM 扩展名和首尾空白。
     * 不直接按最后一个点截断，避免把 “Dr. Mario” 这类名称截坏。
     */
    private fun cleanDisplayName(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.equals("unknown.gba", ignoreCase = true)) return null

        val lower = trimmed.lowercase(Locale.ROOT)
        val suffix = KNOWN_EXTENSIONS.firstOrNull { lower.endsWith(it) }
        val cleaned = if (suffix != null) {
            trimmed.dropLast(suffix.length).trim()
        } else {
            trimmed
        }
        return cleaned.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** 足够覆盖 GBA 的 0xA0 标题和 GB/GBC 的 0x134 标题。 */
        private const val HEADER_SIZE = 0x150
        private const val MIN_HEADER_BYTES = 0x144

        private val KNOWN_EXTENSIONS = listOf(".gba", ".gbc", ".gb", ".agb", ".zip")
    }
}
