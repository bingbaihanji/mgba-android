package com.mgba.bingbaihanji.platform.android.input

import android.view.KeyEvent
import com.mgba.bingbaihanji.core.input.InputAction

/**
 * 内置默认键位，保持与重构前的键盘/手柄映射一致：
 * 手柄 A/B/L/R/Start/Select、方向键，以及键盘的空格、回车、Q/W 等。
 *
 * 组合键与快速存档/读档默认不绑定，由用户在设置页自行配置。
 */
object KeyBindingDefaults {

    fun bindings(): Map<InputAction, Set<Int>> = mapOf(
        InputAction.UP to setOf(KeyEvent.KEYCODE_DPAD_UP),
        InputAction.DOWN to setOf(KeyEvent.KEYCODE_DPAD_DOWN),
        InputAction.LEFT to setOf(KeyEvent.KEYCODE_DPAD_LEFT),
        InputAction.RIGHT to setOf(KeyEvent.KEYCODE_DPAD_RIGHT),

        InputAction.A to setOf(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_SPACE,
        ),
        InputAction.B to setOf(
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_Y,
        ),
        InputAction.L to setOf(
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_Q,
        ),
        InputAction.R to setOf(
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_W,
        ),
        InputAction.START to setOf(
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_ENTER,
        ),
        InputAction.SELECT to setOf(
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BACK,
        ),

        InputAction.FAST_FORWARD to setOf(KeyEvent.KEYCODE_F),
        InputAction.OPEN_MENU to setOf(KeyEvent.KEYCODE_MENU),
    )
}
