package com.mgba.bingbaihanji.core.emulation

/**
 * Libretro 核心选项的持久化。
 *
 * 选项按「核心名称 → 选项键 → 选项值」保存。游戏加载后由会话自动应用，
 * 这样下次启动时用户之前选过的核心参数仍然生效。
 *
 * 当前 app 只加载 mGBA 一个核心，但保留核心名维度以便未来扩展。
 */
interface CoreOptionStore {

    /** 读取某个核心已保存的选项值。 */
    fun values(coreName: String): Map<String, String>

    /** 保存单个选项值。 */
    fun set(coreName: String, key: String, value: String)

    /** 清除某个核心的全部已保存选项。 */
    fun reset(coreName: String)
}
