package com.mgba.bingbaihanji.core.input

/**
 * 平台键码到 [InputAction] 的映射策略。
 *
 * [keyCode] 使用平台自己的键码（Android 为 KeyEvent.keyCode）。
 * 未来桌面端可以实现自己的键码映射。
 */
fun interface KeyMapping {
    fun actionFor(keyCode: Int): InputAction?
}
