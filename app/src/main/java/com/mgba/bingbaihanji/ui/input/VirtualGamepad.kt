package com.mgba.bingbaihanji.ui.input

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import com.mgba.bingbaihanji.core.input.GamepadControl
import com.mgba.bingbaihanji.core.input.GamepadControlType
import com.mgba.bingbaihanji.core.input.GamepadLayout
import com.mgba.bingbaihanji.core.input.InputDevice

/**
 * 一根手指当前捕获的控件。
 *
 * 与 My Boy 一致：手指按下时确定控件，移动过程中不再切换到其他控件，
 * 而是在该控件矩形内重新计算归一化位置（例如 D-pad 的方向、A/B 的左右半区）。
 */
private data class ActivePointer(
    val control: GamepadControl?,
    val isMenu: Boolean,
    val buttons: Set<Int>,
)

/**
 * 透明多点触控虚拟手柄。
 *
 * 控件的位置、尺寸和类型全部来自 [layout]；每个 pointerId 独立跟踪，
 * 同一按键被多根手指按住时通过引用计数保证不会提前松开。
 */
@Composable
fun VirtualGamepad(
    input: InputDevice,
    layout: GamepadLayout,
    modifier: Modifier = Modifier,
    /** 是否显示除菜单外的游戏按键；关闭时仍保留菜单按钮。 */
    showGameControls: Boolean = true,
    /** 未按下时的填充透明度，来自“按键透明度”设置。 */
    baseAlpha: Float = 0.15f,
    onMenu: () -> Unit = {},
) {
    val pressedCounts = remember { mutableStateMapOf<Int, Int>() }
    var menuPressed by remember { mutableStateOf(false) }

    fun pressButtons(buttons: Set<Int>) {
        for (button in buttons) {
            val count = pressedCounts[button] ?: 0
            if (count == 0) input.press(button)
            pressedCounts[button] = count + 1
        }
    }

    fun releaseButtons(buttons: Set<Int>) {
        for (button in buttons) {
            val count = pressedCounts[button] ?: 0
            if (count <= 1) {
                if (count == 1) input.release(button)
                pressedCounts.remove(button)
            } else {
                pressedCounts[button] = count - 1
            }
        }
    }

    // 读取 keys 会让 Compose 在按键状态变化时重组并重绘。
    val pressedButtons = pressedCounts.keys.toSet()

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .pointerInput(input, layout, showGameControls) {
                val hitSlop = 8.dp.toPx()
                awaitPointerEventScope {
                    val pointers = mutableMapOf<PointerId, ActivePointer>()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                when {
                                    change.changedToDown() -> {
                                        val hit = layout.hitTest(
                                            x = change.position.x,
                                            y = change.position.y,
                                            viewWidth = size.width.toFloat(),
                                            viewHeight = size.height.toFloat(),
                                            slopPx = hitSlop,
                                            preferMenu = true,
                                        )

                                        if (hit?.control?.type == GamepadControlType.MENU) {
                                            onMenu()
                                            menuPressed = true
                                            pointers[change.id] =
                                                ActivePointer(null, isMenu = true, buttons = emptySet())
                                        } else if (hit != null && showGameControls) {
                                            val buttons = hit.buttons()
                                            pressButtons(buttons)
                                            pointers[change.id] =
                                                ActivePointer(hit.control, isMenu = false, buttons = buttons)
                                        } else {
                                            pointers[change.id] =
                                                ActivePointer(null, isMenu = false, buttons = emptySet())
                                        }
                                    }

                                    change.changedToUp() -> {
                                        val state = pointers.remove(change.id)
                                        if (state != null) {
                                            releaseButtons(state.buttons)
                                            if (state.isMenu) menuPressed = false
                                        }
                                    }

                                    change.positionChanged() -> {
                                        val state = pointers[change.id] ?: continue
                                        if (state.isMenu) continue
                                        val control = state.control ?: continue

                                        val bounds = control.bounds(
                                            size.width.toFloat(),
                                            size.height.toFloat(),
                                        )
                                        val newButtons = control.buttonsAt(
                                            bounds.normalizedX(change.position.x),
                                            bounds.normalizedY(change.position.y),
                                        )
                                        if (newButtons != state.buttons) {
                                            releaseButtons(state.buttons)
                                            pressButtons(newButtons)
                                            pointers[change.id] = state.copy(buttons = newButtons)
                                        }
                                    }
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } finally {
                        // 组合项被移除（打开菜单/布局编辑器）时释放所有虚拟按键。
                        pointers.values.forEach { releaseButtons(it.buttons) }
                        pointers.clear()
                        menuPressed = false
                        pressedCounts.keys.toList().forEach { input.release(it) }
                        pressedCounts.clear()
                    }
                }
            }
    ) {
        val width = size.width
        val height = size.height
        for (control in layout.controls) {
            // 关闭虚拟按键时仍绘制菜单控件，避免无法打开游戏菜单。
            if (!showGameControls && control.type != GamepadControlType.MENU) continue
            drawGamepadControl(
                type = control.type,
                bounds = control.bounds(width, height),
                pressedButtons = pressedButtons,
                menuPressed = menuPressed,
                baseAlpha = baseAlpha,
            )
        }
    }
}
