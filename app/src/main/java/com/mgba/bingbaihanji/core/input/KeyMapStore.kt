package com.mgba.bingbaihanji.core.input

import kotlinx.coroutines.flow.StateFlow

/**
 * 按键映射配置的持久化与观察接口。
 *
 * 与图形/着色器配置一样，Android 实现使用 SharedPreferences；
 * 桌面端可以换成自己的配置文件。
 */
interface KeyMapStore {

    /** 当前全部配置及选中项。 */
    val state: StateFlow<KeyMapState>

    /** 选择一套配置。 */
    fun selectProfile(id: String)

    /** 新建配置（默认使用内置按键），返回新配置 id。 */
    fun addProfile(name: String): String

    /** 重命名配置。 */
    fun renameProfile(id: String, name: String)

    /** 删除配置；至少保留一套。 */
    fun deleteProfile(id: String)

    /**
     * 把 [keyCode] 绑定到 [action]。
     * 同一个键会先从其他动作移除，保证一个键只触发一个动作。
     */
    fun bind(profileId: String, action: InputAction, keyCode: Int)

    /** 清除某个动作的全部按键。 */
    fun clear(profileId: String, action: InputAction)

    /** 把配置恢复为内置默认按键。 */
    fun resetProfile(profileId: String)
}
