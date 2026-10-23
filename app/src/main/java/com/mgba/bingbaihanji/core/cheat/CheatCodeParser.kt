package com.mgba.bingbaihanji.core.cheat

/**
 * 金手指代码解析与规范化。
 *
 * 支持 GBA 的三种格式（与 My Boy 的「自动检测 / GameShark / CodeBreaker / Raw」一致）：
 * - GameShark / Action Replay：8 位地址 + 8 位数值（共 16 位十六进制）
 * - CodeBreaker：8 位地址 + 4 位数值（共 12 位十六进制）
 * - Raw：8 位地址 + 2/4/8 位数值，表示按地址直接写入内存；
 *   数值位数就是写入宽度（1/2/4 字节）
 *
 * 输入可以是多行，每行一条代码。十六进制数字可以用空格、`+`、`-` 分隔，
 * GameShark / CodeBreaker 也接受连续写法；Raw 额外接受 `地址:数值`
 * （冒号必须紧跟在 8 位地址之后）。
 *
 * 输出统一规范化：
 * - GameShark：`AAAAAAAA VVVVVVVV`
 * - CodeBreaker：`AAAAAAAA VVVV`
 * - Raw：`AAAAAAAA:VV` / `AAAAAAAA:VVVV` / `AAAAAAAA:VVVVVVVV`
 *
 * Raw 统一使用冒号，是为了让 mGBA 走 VBA 直写解析路径。若保留空格写法，
 * `8+2` / `8+4` 会被核心的自动检测当成 CodeBreaker 的运算码，写出错误结果。
 */
object CheatCodeParser {

    private val HEX = Regex("[0-9A-Fa-f]+")
    private val WHITESPACE = Regex("\\s+")
    private val COLON_SPACING = Regex("\\s*:\\s*")

    /** 地址固定 8 位十六进制。 */
    private const val ADDRESS_DIGITS = 8

    /** Raw 数值允许的位数，分别对应 1/2/4 字节写入。 */
    private val RAW_VALUE_DIGITS = setOf(2, 4, 8)

    /** Raw 连续写法：8+2、8+4、8+8。 */
    private val RAW_LENGTHS = setOf(10, 12, 16)

    sealed interface ParseResult {
        data class Success(
            val codes: List<String>,
            val type: CheatType,
        ) : ParseResult

        data class Failure(val message: String) : ParseResult
    }

    fun parse(
        input: String,
        requestedType: CheatType = CheatType.Auto,
    ): ParseResult {
        val lines = input.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (lines.isEmpty()) {
            return ParseResult.Failure("请输入金手指代码")
        }

        val normalized = mutableListOf<String>()
        var hasGameShark = false
        var hasCodeBreaker = false
        var hasRaw = false

        for ((index, line) in lines.withIndex()) {
            val parsed = when (requestedType) {
                CheatType.Auto, CheatType.Mixed -> autoDetect(line)
                CheatType.GameShark ->
                    parseFixed(line, ADDRESS_DIGITS, 8)?.let { it to CheatType.GameShark }

                CheatType.CodeBreaker ->
                    parseFixed(line, ADDRESS_DIGITS, 4)?.let { it to CheatType.CodeBreaker }

                CheatType.Raw -> parseRaw(line)?.let { it to CheatType.Raw }
            } ?: return ParseResult.Failure(failureMessage(requestedType, index + 1))

            normalized.add(parsed.first)
            when (parsed.second) {
                CheatType.GameShark -> hasGameShark = true
                CheatType.CodeBreaker -> hasCodeBreaker = true
                CheatType.Raw -> hasRaw = true
                else -> Unit
            }
        }

        val detectedType = when {
            listOf(hasGameShark, hasCodeBreaker, hasRaw).count { it } > 1 -> CheatType.Mixed
            hasRaw -> CheatType.Raw
            hasCodeBreaker -> CheatType.CodeBreaker
            else -> CheatType.GameShark
        }
        return ParseResult.Success(normalized, detectedType)
    }

    /**
     * 把规范化后的代码行转换为 libretro `retro_cheat_set` 可接受的形式。
     *
     * mGBA 的 libretro 桥接按 13 或 17 个字符切分单行代码（8+4 / 8+8），
     * 短于 13 字符的行（Raw 1 字节写入，如 `030050C4:FF`）会被静默丢弃。
     * 补足空格后 VBA 直写解析器仍能正常读取地址与数值。
     */
    fun toLibretroCode(code: String): String =
        if (code.length < LIBRETRO_SHORTEST_LINE) {
            code.padEnd(LIBRETRO_SHORTEST_LINE, ' ')
        } else {
            code
        }

    /** 自动检测单行类型；规则与 My Boy 的 m4992H 一致。 */
    private fun autoDetect(line: String): Pair<String, CheatType>? {
        // 「地址:数值」是 Raw 的显式写法，必须在按长度归并之前识别，
        // 否则 8+4 会被误判成 CodeBreaker。
        parseRawColon(compactColon(line))?.let { return it to CheatType.Raw }

        val hex = normalizeHex(line) ?: return null
        return when (hex.length) {
            // 8+8：GameShark / Action Replay
            16 -> "${hex.take(ADDRESS_DIGITS)} ${hex.drop(ADDRESS_DIGITS)}" to CheatType.GameShark
            // 8+4：CodeBreaker
            12 -> "${hex.take(ADDRESS_DIGITS)} ${hex.drop(ADDRESS_DIGITS)}" to CheatType.CodeBreaker
            // 8+2：Raw 1 字节写入；只有连续写法能自动识别，
            // 8+8 的 Raw 与 GameShark 无法区分，需要手动选择 Raw。
            10 -> rawCode(hex.take(ADDRESS_DIGITS), hex.drop(ADDRESS_DIGITS))
                ?.let { it to CheatType.Raw }

            else -> null
        }
    }

    /** 解析固定宽度格式：地址 8 位 + 数值 [valueDigits] 位。 */
    private fun parseFixed(line: String, addressDigits: Int, valueDigits: Int): String? {
        val hex = normalizeHex(line) ?: return null
        if (hex.length != addressDigits + valueDigits) return null
        return "${hex.take(addressDigits)} ${hex.drop(addressDigits)}"
    }

    /** 解析 Raw 行，返回 `AAAAAAAA:V...` 规范化结果。 */
    private fun parseRaw(line: String): String? {
        parseRawColon(compactColon(line))?.let { return it }

        val tokens = line.split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .map { it.removePrefix("0x").removePrefix("0X") }
        return when {
            tokens.size == 2 &&
                    tokens[0].length == ADDRESS_DIGITS &&
                    tokens[1].length in RAW_VALUE_DIGITS ->
                rawCode(tokens[0], tokens[1])

            tokens.size == 1 && tokens[0].length in RAW_LENGTHS ->
                rawCode(
                    tokens[0].take(ADDRESS_DIGITS),
                    tokens[0].drop(ADDRESS_DIGITS),
                )

            else -> null
        }
    }

    /** 去掉冒号两侧的空白，允许 `030050C4 : 0063` 这种写法。 */
    private fun compactColon(line: String): String =
        line.replace(COLON_SPACING, ":")

    /** 解析 `AAAAAAAA:VV` 形式，冒号必须紧跟在 8 位地址之后。 */
    private fun parseRawColon(line: String): String? {
        val colon = line.indexOf(':')
        if (colon < 0) return null

        val address = line.substring(0, colon)
        val value = line.substring(colon + 1)
        if (address.length != ADDRESS_DIGITS || value.length !in RAW_VALUE_DIGITS) {
            return null
        }
        return rawCode(address, value)
    }

    private fun rawCode(address: String, value: String): String? {
        if (!HEX.matches(address) || !HEX.matches(value)) return null
        return "${address.uppercase()}:${value.uppercase()}"
    }

    /** 去掉常见分隔符与前缀，返回连续的十六进制串。 */
    private fun normalizeHex(line: String): String? {
        val joined = line
            .replace('+', ' ')
            .replace(':', ' ')
            .replace('-', ' ')
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .map { it.removePrefix("0x").removePrefix("0X") }
            .joinToString("")
            .uppercase()

        return if (joined.isNotEmpty() && HEX.matches(joined)) joined else null
    }

    private fun failureMessage(type: CheatType, lineNumber: Int): String = when (type) {
        CheatType.GameShark ->
            "第 $lineNumber 行不是 GameShark / Action Replay 代码（需要 8+8 位）"

        CheatType.CodeBreaker ->
            "第 $lineNumber 行不是 CodeBreaker 代码（需要 8+4 位）"

        CheatType.Raw ->
            "第 $lineNumber 行不是 Raw 代码：地址 8 位，数值 2/4/8 位，" +
                    "例如 030050C4:0063 或 030050C4 0063"

        else ->
            "第 $lineNumber 行格式无法识别：支持 8+8（GameShark）、" +
                    "8+4（CodeBreaker）或 8+2/4/8（Raw）"
    }

    /** libretro 单行最短长度：8 位地址 + 空格 + 4 位数值。 */
    private const val LIBRETRO_SHORTEST_LINE = 13
}
