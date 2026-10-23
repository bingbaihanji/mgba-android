package com.mgba.bingbaihanji.core.cheat

/**
 * 金手指类型，与 My Boy 的类型保持一致。
 *
 * - [Auto] 仅用于编辑时的“自动检测”，不会保存到金手指条目；
 * - [GameShark] 对应 GameShark v1/v2 与 Action Replay（v3）；
 * - [CodeBreaker] 对应 CodeBreaker；
 * - [Raw] 直接按地址写入内存，值宽度由十六进制位数决定；
 * - [Mixed] 表示同一条金手指里混用了多种代码格式。
 */
enum class CheatType(val id: String) {
    Auto("auto"),
    GameShark("gs"),
    CodeBreaker("cb"),
    Raw("raw"),
    Mixed("mixed");

    companion object {
        fun fromId(id: String): CheatType =
            entries.firstOrNull { it.id == id } ?: Auto
    }
}

/**
 * 一条金手指。
 *
 * [codes] 保存规范化后的代码行，每行形如：
 * - `820051F4 0000FFFF`（GameShark / Action Replay，8+8 位）
 * - `820051F4 0063`（CodeBreaker，8+4 位）
 * - `030050C4:0063`（Raw，8 位地址 + 2/4/8 位数值）
 *
 * 应用时每行调用一次 `retro_cheat_set`。
 */
data class Cheat(
    val id: String,
    val name: String,
    val type: CheatType,
    val codes: List<String>,
    val enabled: Boolean = true,
)
