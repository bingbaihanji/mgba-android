package com.mgba.bingbaihanji.core.input

/**
 * 一套命名按键配置。
 *
 * [bindings] 是「动作 → 平台键码集合」：
 * - 一个动作可以绑定多个键（例如 A 同时支持手柄 A、空格、回车）；
 * - 一个键只属于一个动作（保存时会从其他动作移除）；
 * - 一个组合键动作对应多个 GBA 按键。
 *
 * [id] 是内部稳定标识，[name] 是用户可见名称。
 */
data class KeyMapProfile(
    val id: String,
    val name: String,
    val bindings: Map<InputAction, Set<Int>> = emptyMap(),
) : KeyMapping {

    override fun actionFor(keyCode: Int): InputAction? =
        bindings.entries.firstOrNull { (_, keys) -> keyCode in keys }?.key

    /** 某个动作当前绑定的键码。 */
    fun keysFor(action: InputAction): Set<Int> =
        bindings[action] ?: emptySet()
}

/**
 * 按键配置的完整状态。
 */
data class KeyMapState(
    val profiles: List<KeyMapProfile>,
    val selectedProfileId: String,
) {
    /** 当前选中的配置；找不到时回退到第一套。 */
    val selectedProfile: KeyMapProfile
        get() = profiles.firstOrNull { it.id == selectedProfileId }
            ?: profiles.first()
}
