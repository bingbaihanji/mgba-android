package com.mgba.bingbaihanji.core.storage

import com.mgba.bingbaihanji.core.cheat.CheatStore

/**
 * ROM 及其关联数据的删除编排。
 *
 * 平台层只负责触发删除；ROM 副本、即时存档、电池存档、RTC 与金手指
 * 使用哪些标识、按什么范围清理都集中在这里，避免 UI 重复实现。
 *
 * 线程约定：会执行文件 I/O，调用方应放在后台线程。
 */
class RomLibrary(
    private val catalog: RomCatalog,
    private val saveStorage: SaveStorage,
    private val cheatStore: CheatStore,
) {

    /**
     * 删除 [id] 对应的 ROM。
     *
     * [deleteSaves] 为 true 时同时清理该 ROM 的全部即时存档（含自动存档槽）、
     * 电池存档、RTC 与金手指；为 false 时只删 ROM 副本，重新导入同一文件
     * 仍可继续原来的进度。
     *
     * @return ROM 副本已删除或原本不存在时返回 true。
     */
    fun deleteRom(id: String, deleteSaves: Boolean): Boolean {
        if (deleteSaves) {
            val romId = id.take(RomCatalog.ROM_ID_LENGTH)
            for (slot in 0..SavestateRepository.AUTO_SAVE_SLOT) {
                saveStorage.deleteSavestate(romId, slot)
            }
            saveStorage.deleteBatterySave(id)
            saveStorage.deleteRtc(id)
            // 金手指以空列表覆盖，避免 CheatStore 再多一个删除方法。
            cheatStore.save(romId, emptyList())
        }
        return catalog.delete(id)
    }
}
