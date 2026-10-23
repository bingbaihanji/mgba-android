package com.mgba.bingbaihanji.core.storage

/**
 * 从 ROM 头部解析游戏标题，作为 SAF 文件名缺失时的兜底显示名。
 *
 * - GBA：标题在 0xA0，长度 12，Nintendo logo 位于 0x04；
 * - GB/GBC：标题在 0x134，长度 16，Nintendo logo 位于 0x104。
 *
 * 只提取可打印 ASCII；遇到非 ASCII（例如日文标题）或空字节就停止，
 * 解析失败返回 null，由调用方继续使用其他兜底名称。
 */
object RomMetadataParser {

    private const val GBA_TITLE_OFFSET = 0xA0
    private const val GBA_TITLE_LENGTH = 12
    private const val GB_TITLE_OFFSET = 0x134
    private const val GB_TITLE_LENGTH = 16

    fun title(header: ByteArray): String? {
        // 先通过 Nintendo logo 判断机型，避免把 GBA 的头字段误当成 GB 标题。
        if (isGbaHeader(header)) {
            return decode(header, GBA_TITLE_OFFSET, GBA_TITLE_LENGTH)
        }
        if (isGbHeader(header)) {
            return decode(header, GB_TITLE_OFFSET, GB_TITLE_LENGTH)
        }
        // logo 不完整/被修改过的 ROM：再尝试 GBA 固定字节和两个标题偏移。
        if (header.size > 0xB2 && (header[0xB2].toInt() and 0xFF) == 0x96) {
            return decode(header, GBA_TITLE_OFFSET, GBA_TITLE_LENGTH)
        }
        return decode(header, GB_TITLE_OFFSET, GB_TITLE_LENGTH)
            ?: decode(header, GBA_TITLE_OFFSET, GBA_TITLE_LENGTH)
    }

    private fun isGbaHeader(header: ByteArray): Boolean =
        header.size > 0x07 &&
                (header[0x04].toInt() and 0xFF) == 0x24 &&
                (header[0x05].toInt() and 0xFF) == 0xFF &&
                (header[0x06].toInt() and 0xFF) == 0xAE &&
                (header[0x07].toInt() and 0xFF) == 0x51

    private fun isGbHeader(header: ByteArray): Boolean =
        header.size > 0x107 &&
                (header[0x104].toInt() and 0xFF) == 0xCE &&
                (header[0x105].toInt() and 0xFF) == 0xED &&
                (header[0x106].toInt() and 0xFF) == 0x66 &&
                (header[0x107].toInt() and 0xFF) == 0x66

    private fun decode(bytes: ByteArray, offset: Int, length: Int): String? {
        if (offset >= bytes.size) return null

        val end = minOf(offset + length, bytes.size)
        val builder = StringBuilder(length)

        for (index in offset until end) {
            val value = bytes[index].toInt() and 0xFF
            if (value == 0) break
            if (value in 0x20..0x7E) {
                builder.append(value.toChar())
            } else {
                // 已经读到部分 ASCII 时，把后续的非 ASCII 视为标题结束。
                if (builder.isNotEmpty()) break
                return null
            }
        }

        val title = builder.toString().trim()
        return title.takeIf { it.length >= 2 }
    }
}
