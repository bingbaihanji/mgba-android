package com.mgba.bingbaihanji.platform.android.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * ROM 文件管理：SAF 选择、私有缓存、内容标识。
 *
 * ROM 会被复制到 `files/rom-cache/`，保证仿真核心始终从稳定的本地路径加载；
 * 内容的 SHA-256 同时用作缓存文件名和存档键。
 *
 * 该模块依赖 Android 的 ContentResolver/DocumentsContract，因此属于平台层。
 */
object AndroidRomRepository {

    /** ROM 准备结果。 */
    data class RomEntry(
        val cachedFile: File,
        val sha256: String,
        val displayName: String,
    )

    fun romCacheDir(context: Context): File =
        File(context.filesDir, "rom-cache").also { it.mkdirs() }

    /**
     * 把 SAF URI 复制到私有缓存并计算 SHA-256。
     *
     * 支持直接选择 `.gba/.gb/.gbc` 或包含它们的 `.zip`：
     * 压缩包会流式提取第一个 ROM 条目，哈希按解压后的 ROM 计算，
     * 因此重复导入同一个压缩包仍然得到相同的标识与存档。
     *
     * 该函数执行阻塞 I/O，调用方应放在后台线程。
     * @throws IOException URI 无法读取、压缩包内没有 ROM 或复制失败。
     */
    @Throws(IOException::class)
    fun prepareRom(context: Context, uri: Uri): RomEntry {
        val resolver = context.contentResolver

        val displayName = queryDisplayName(resolver, uri) ?: "unknown.gba"

        // 单次流式读取，同时计算哈希并写缓存。
        val digest = MessageDigest.getInstance("SHA-256")
        val tmp = File(romCacheDir(context), "tmp-${System.nanoTime()}.gba")

        try {
            // ZIP 的内层文件名比压缩包名更能代表实际游戏。
            var innerRomName: String? = null

            resolver.openInputStream(uri)?.use { raw ->
                val input = BufferedInputStream(raw)
                // 先嗅探文件头，非 ZIP 时回退到整体复制。
                input.mark(ZIP_SIGNATURE_SIZE)
                val head = ByteArray(ZIP_SIGNATURE_SIZE)
                val headRead = readFully(input, head)
                input.reset()

                if (headRead == ZIP_SIGNATURE_SIZE && isZip(head)) {
                    innerRomName = extractFirstRom(input, tmp, digest)
                } else {
                    copyHashed(input, tmp, digest)
                }
            } ?: throw IOException("Cannot open URI: $uri")

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val cached = File(romCacheDir(context), "$hash.gba")

            if (!tmp.renameTo(cached)) {
                // 跨设备或目标已存在：退化为复制。
                tmp.copyTo(cached, overwrite = true)
                tmp.delete()
            }

            val effectiveName = innerRomName ?: displayName
            // 把原始文件名保存为 ROM 展示名的旁路文件。
            persistDisplayName(context, hash, effectiveName)

            return RomEntry(cachedFile = cached, sha256 = hash, displayName = effectiveName)
        } catch (e: Exception) {
            tmp.delete()
            throw IOException("Failed to cache ROM: ${e.message}", e)
        }
    }

    private fun isZip(head: ByteArray): Boolean =
        head[0] == 'P'.code.toByte() &&
                head[1] == 'K'.code.toByte() &&
                head[2] == 0x03.toByte() &&
                head[3] == 0x04.toByte()

    /**
     * 把 [input] 完整写入 [outputFile] 并更新 [digest]。
     * 超过 [MAX_ROM_BYTES] 或内容为空时抛出 [IOException]。
     */
    private fun copyHashed(input: InputStream, outputFile: File, digest: MessageDigest) {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        outputFile.outputStream().use { output ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > MAX_ROM_BYTES) {
                    throw IOException("ROM is larger than ${MAX_ROM_BYTES / (1024 * 1024)} MB")
                }
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        if (total == 0L) {
            throw IOException("ROM file is empty")
        }
    }

    /**
     * 从 ZIP 中提取第一个 GBA/GB 条目。
     *
     * @return 内层 ROM 的文件名（含扩展名）
     */
    private fun extractFirstRom(
        input: InputStream,
        outputFile: File,
        digest: MessageDigest,
    ): String {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue

                val name = entry.name.substringAfterLast('/')
                if (name.isEmpty()) continue
                val lower = name.lowercase(Locale.ROOT)
                // 跳过 macOS 打包产生的元数据条目。
                if (lower.startsWith("._") || lower.startsWith("__macosx")) continue
                if (ROM_EXTENSIONS.none { lower.endsWith(it) }) continue

                copyHashed(zip, outputFile, digest)
                return name
            }
        }
        throw IOException("压缩包内没有找到 GBA/GB ROM")
    }

    /** 读满 [buffer] 或到达流末尾，返回实际读取的字节数。 */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) break
            read += count
        }
        return read
    }

    /** 按 SHA-256 查找已缓存的 ROM。 */
    fun findCachedRom(context: Context, sha256: String): File? {
        val file = File(romCacheDir(context), "$sha256.gba")
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * 取得 ROM 的内容标识（完整 SHA-256）。
     *
     * 缓存目录里的文件本身以 SHA-256 命名，可直接复用，避免每次启动重新哈希；
     * 其他路径（例如外部直接传入的 ROM）流式计算一次。
     *
     * 该函数执行阻塞 I/O，调用方应放在后台线程。
     */
    @Throws(IOException::class)
    fun sha256For(context: Context, file: File): String {
        val cachedName = file.nameWithoutExtension
        val inCacheDir = file.parentFile?.canonicalPath == romCacheDir(context).canonicalPath
        if (inCacheDir && isSha256Name(cachedName)) {
            return cachedName
        }
        return computeSha256(file)
    }

    private fun isSha256Name(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    @Throws(IOException::class)
    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 保存 ROM 展示名到 `{sha256}.name`。
     * 写入失败只影响展示，不影响 ROM 导入本身。
     */
    private fun persistDisplayName(
        context: Context,
        sha256: String,
        displayName: String,
    ) {
        if (displayName.isBlank() ||
            displayName.equals("unknown.gba", ignoreCase = true)
        ) {
            return
        }
        try {
            val nameFile = File(romCacheDir(context), "$sha256.name")
            // 已存在说明用户重命名过，重复导入不应覆盖。
            if (nameFile.exists() && nameFile.length() > 0) return
            nameFile.writeText(displayName, Charsets.UTF_8)
        } catch (_: IOException) {
            // 展示名是可选元数据。
        }
    }

    private fun queryDisplayName(
        resolver: ContentResolver,
        uri: Uri,
    ): String? {
        return try {
            resolver.query(
                uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** GBA ROM 最大 32 MB，留一倍余量，同时作为解压上限防止压缩炸弹。 */
    private const val MAX_ROM_BYTES = 64L * 1024 * 1024

    private const val ZIP_SIGNATURE_SIZE = 4

    private val ROM_EXTENSIONS = listOf(".gba", ".gbc", ".gb", ".agb")
}
