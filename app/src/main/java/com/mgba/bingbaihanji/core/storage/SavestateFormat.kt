package com.mgba.bingbaihanji.core.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * MGST 即时存档二进制格式。
 *
 * 文件布局与重构前完全一致：
 * ```
 * [4]   magic "MGST"
 * [4]   格式版本（1）
 * [8]   时间戳（Unix 毫秒）
 * [4+N] 核心名称（长度前缀 + UTF-8）
 * [4+N] 核心版本
 * [4+N] ROM 标识
 * [8]   序列化数据长度
 * [N]   序列化数据
 * ```
 */
object SavestateFormat {

    private const val MAGIC = "MGST"
    private const val FORMAT_VERSION = 1

    /** 单个即时存档的大小上限（64 MB），防止损坏文件导致超大分配。 */
    const val MAX_STATE_SIZE = 64L * 1024 * 1024

    /** 从文件中解析出的元数据。 */
    data class Info(
        val timestamp: Long,
        val coreName: String,
        val coreVersion: String,
        val romSha256: String,
        val dataSize: Long,
    )

    /** 解析结果：元数据 + 头部实际占用的字节数。 */
    data class Parsed(
        val info: Info,
        val headerSize: Int,
    )

    /**
     * 把元数据和序列化数据编码为完整的 MGST 字节数组。
     * 时间戳取当前系统时间。
     */
    fun encode(
        coreName: String,
        coreVersion: String,
        romSha256: String,
        data: ByteArray,
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.writeInt(FORMAT_VERSION)
            out.writeLong(System.currentTimeMillis())
            writeString(out, coreName)
            writeString(out, coreVersion)
            writeString(out, romSha256)
            out.writeLong(data.size.toLong())
            out.write(data)
        }
        return bos.toByteArray()
    }

    /** 解析文件头；格式不合法时返回 null。 */
    fun parse(bytes: ByteArray): Parsed? {
        if (bytes.size < 32) return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val magic = ByteArray(4)
                input.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != MAGIC) return null

                val version = input.readInt()
                if (version != FORMAT_VERSION) return null

                val timestamp = input.readLong()
                val coreName = readString(input) ?: return null
                val coreVersion = readString(input) ?: return null
                val romSha256 = readString(input) ?: return null
                val dataSize = input.readLong()

                Parsed(
                    info = Info(timestamp, coreName, coreVersion, romSha256, dataSize),
                    headerSize = headerSize(coreName, coreVersion, romSha256),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun headerSize(
        coreName: String,
        coreVersion: String,
        romSha256: String,
    ): Int {
        // magic(4) + version(4) + timestamp(8) + 3 个长度前缀字符串 + dataSize(8)
        return 4 + 4 + 8 +
                (4 + coreName.toByteArray(Charsets.UTF_8).size) +
                (4 + coreVersion.toByteArray(Charsets.UTF_8).size) +
                (4 + romSha256.toByteArray(Charsets.UTF_8).size) +
                8
    }

    private fun writeString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String? {
        val len = input.readInt()
        if (len < 0 || len > 1024) return null
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
