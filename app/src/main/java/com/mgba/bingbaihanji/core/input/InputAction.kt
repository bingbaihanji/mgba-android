package com.mgba.bingbaihanji.core.input

import com.mgba.bingbaihanji.core.emulation.GbaButton

/**
 * 按键映射中可以触发的模拟器快捷键。
 *
 * 这些动作不对应 GBA 按键，由平台层（GameActivity）执行。
 */
enum class InputShortcut {
    FAST_FORWARD,
    OPEN_MENU,
    SAVE_STATE,
    LOAD_STATE,
}

/**
 * 可映射的输入动作。
 *
 * - [gbaButtons] 非空：按下时同时按住这些 GBA 按键；
 * - [shortcut] 非空：按下时触发平台层动作；
 * - 两者都为空：不应出现（保留给未来的特殊动作）。
 *
 * 组合键（如 A+B）通过 [gbaButtons] 同时按下多个按键实现。
 */
enum class InputAction(
    val id: String,
    val gbaButtons: Set<Int> = emptySet(),
    val shortcut: InputShortcut? = null,
) {
    UP("up", setOf(GbaButton.UP)),
    DOWN("down", setOf(GbaButton.DOWN)),
    LEFT("left", setOf(GbaButton.LEFT)),
    RIGHT("right", setOf(GbaButton.RIGHT)),
    SELECT("select", setOf(GbaButton.SELECT)),
    START("start", setOf(GbaButton.START)),
    A("a", setOf(GbaButton.A)),
    B("b", setOf(GbaButton.B)),
    L("tl", setOf(GbaButton.L)),
    R("tr", setOf(GbaButton.R)),

    UP_LEFT("up+left", setOf(GbaButton.UP, GbaButton.LEFT)),
    UP_RIGHT("up+right", setOf(GbaButton.UP, GbaButton.RIGHT)),
    DOWN_LEFT("down+left", setOf(GbaButton.DOWN, GbaButton.LEFT)),
    DOWN_RIGHT("down+right", setOf(GbaButton.DOWN, GbaButton.RIGHT)),
    A_B("a+b", setOf(GbaButton.A, GbaButton.B)),
    L_R("tl+tr", setOf(GbaButton.L, GbaButton.R)),
    L_A("tl+a", setOf(GbaButton.L, GbaButton.A)),
    L_B("tl+b", setOf(GbaButton.L, GbaButton.B)),
    R_A("tr+a", setOf(GbaButton.R, GbaButton.A)),
    R_B("tr+b", setOf(GbaButton.R, GbaButton.B)),

    FAST_FORWARD("ff", shortcut = InputShortcut.FAST_FORWARD),
    OPEN_MENU("menu", shortcut = InputShortcut.OPEN_MENU),
    SAVE_STATE("save", shortcut = InputShortcut.SAVE_STATE),
    LOAD_STATE("load", shortcut = InputShortcut.LOAD_STATE);

    companion object {
        fun fromId(id: String): InputAction? =
            entries.firstOrNull { it.id == id }

        /** 设置页分区：基本按键。 */
        val basic: List<InputAction> = listOf(
            UP, DOWN, LEFT, RIGHT, A, B, L, R, START, SELECT,
        )

        /** 设置页分区：组合键。 */
        val combos: List<InputAction> = listOf(
            UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT,
            A_B, L_R, L_A, L_B, R_A, R_B,
        )

        /** 设置页分区：模拟器快捷键。 */
        val shortcuts: List<InputAction> = listOf(
            FAST_FORWARD, OPEN_MENU, SAVE_STATE, LOAD_STATE,
        )
    }
}
