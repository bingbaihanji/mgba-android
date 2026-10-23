package com.mgba.bingbaihanji.core.cheat

/**
 * 按 ROM 保存金手指列表。
 *
 * [romId] 与存档使用同一标识：ROM SHA-256 前 16 位；路径加载时为空，
 * 实现方应把空字符串映射到统一的 fallback 键。
 */
interface CheatStore {

    fun load(romId: String): List<Cheat>

    fun save(romId: String, cheats: List<Cheat>)
}
