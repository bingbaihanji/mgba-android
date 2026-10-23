package com.mgba.bingbaihanji.ui.input

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.mgba.bingbaihanji.core.emulation.GbaButton
import com.mgba.bingbaihanji.core.input.ControlBounds
import com.mgba.bingbaihanji.core.input.GamepadControlType
import kotlin.math.max
import kotlin.math.min

/**
 * 绘制单个虚拟控件。游戏内虚拟手柄与布局编辑器共用同一套绘制逻辑。
 *
 * @param baseAlpha 未按下时的填充透明度
 * @param showOutline 是否绘制描边（编辑器也会用它表示控件边界）
 */
internal fun DrawScope.drawGamepadControl(
    type: GamepadControlType,
    bounds: ControlBounds,
    pressedButtons: Set<Int>,
    menuPressed: Boolean,
    baseAlpha: Float,
    showOutline: Boolean = true,
) {
    val strokeWidth = 2.dp.toPx()
    val safeBaseAlpha = baseAlpha.coerceIn(0f, 1f)

    when (type) {
        GamepadControlType.DPAD ->
            drawDpad(bounds, pressedButtons, safeBaseAlpha, strokeWidth, showOutline)

        GamepadControlType.AB -> drawSplitPill(
            bounds = bounds,
            leftLabel = "B",
            rightLabel = "A",
            leftPressed = GbaButton.B in pressedButtons,
            rightPressed = GbaButton.A in pressedButtons,
            baseAlpha = safeBaseAlpha,
            strokeWidth = strokeWidth,
            showOutline = showOutline,
        )

        GamepadControlType.START_SELECT -> drawSplitPill(
            bounds = bounds,
            leftLabel = "SEL",
            rightLabel = "STA",
            leftPressed = GbaButton.SELECT in pressedButtons,
            rightPressed = GbaButton.START in pressedButtons,
            baseAlpha = safeBaseAlpha,
            strokeWidth = strokeWidth,
            showOutline = showOutline,
        )

        GamepadControlType.MENU -> drawMenu(bounds, menuPressed, safeBaseAlpha, strokeWidth)

        else -> {
            val fixed = type.fixedButtons
            val pressed = fixed.isNotEmpty() && fixed.all { it in pressedButtons }
            if (fixed.size <= 1) {
                drawCircleButton(
                    bounds = bounds,
                    label = labelFor(type),
                    pressed = pressed,
                    baseAlpha = safeBaseAlpha,
                    strokeWidth = strokeWidth,
                    showOutline = showOutline,
                )
            } else {
                drawPillButton(
                    bounds = bounds,
                    label = labelFor(type),
                    pressed = pressed,
                    baseAlpha = safeBaseAlpha,
                    strokeWidth = strokeWidth,
                    showOutline = showOutline,
                )
            }
        }
    }
}

private fun DrawScope.drawDpad(
    bounds: ControlBounds,
    pressedButtons: Set<Int>,
    baseAlpha: Float,
    strokeWidth: Float,
    showOutline: Boolean,
) {
    val radius = min(bounds.width, bounds.height) / 2f
    val center = Offset(bounds.centerX, bounds.centerY)

    drawCircle(Color.White.copy(alpha = baseAlpha), radius, center)
    if (showOutline) {
        drawCircle(
            Color.White.copy(alpha = 0.3f),
            radius,
            center,
            style = Stroke(strokeWidth),
        )
    }

    val inset = radius * 0.14f
    val inner = radius * 0.42f
    val far = radius * 0.78f

    fun directionPath(points: List<Offset>): Path = Path().apply {
        moveTo(points[0].x, points[0].y)
        lineTo(points[1].x, points[1].y)
        lineTo(points[2].x, points[2].y)
        close()
    }

    val up = directionPath(
        listOf(
            Offset(center.x, center.y - far),
            Offset(center.x - inner, center.y - inset),
            Offset(center.x + inner, center.y - inset),
        )
    )
    val down = directionPath(
        listOf(
            Offset(center.x, center.y + far),
            Offset(center.x - inner, center.y + inset),
            Offset(center.x + inner, center.y + inset),
        )
    )
    val left = directionPath(
        listOf(
            Offset(center.x - far, center.y),
            Offset(center.x - inset, center.y - inner),
            Offset(center.x - inset, center.y + inner),
        )
    )
    val right = directionPath(
        listOf(
            Offset(center.x + far, center.y),
            Offset(center.x + inset, center.y - inner),
            Offset(center.x + inset, center.y + inner),
        )
    )

    drawPath(
        up,
        Color.White.copy(alpha = if (GbaButton.UP in pressedButtons) 0.7f else 0.28f),
    )
    drawPath(
        down,
        Color.White.copy(alpha = if (GbaButton.DOWN in pressedButtons) 0.7f else 0.28f),
    )
    drawPath(
        left,
        Color.White.copy(alpha = if (GbaButton.LEFT in pressedButtons) 0.7f else 0.28f),
    )
    drawPath(
        right,
        Color.White.copy(alpha = if (GbaButton.RIGHT in pressedButtons) 0.7f else 0.28f),
    )

    drawCircle(Color.White.copy(alpha = baseAlpha + 0.05f), radius * 0.1f, center)
}

private fun DrawScope.drawCircleButton(
    bounds: ControlBounds,
    label: String,
    pressed: Boolean,
    baseAlpha: Float,
    strokeWidth: Float,
    showOutline: Boolean,
) {
    val radius = min(bounds.width, bounds.height) / 2f
    val center = Offset(bounds.centerX, bounds.centerY)
    val fillAlpha = if (pressed) {
        max(baseAlpha + 0.25f, 0.42f).coerceAtMost(1f)
    } else {
        baseAlpha
    }

    drawCircle(Color.White.copy(alpha = fillAlpha), radius, center)
    if (showOutline) {
        drawCircle(
            Color.White.copy(alpha = if (pressed) 0.65f else 0.3f),
            radius,
            center,
            style = Stroke(strokeWidth),
        )
    }
    drawCenteredLabel(
        text = label,
        centerX = center.x,
        centerY = center.y,
        textSize = if (label.length > 1) radius * 0.55f else radius * 0.9f,
        alpha = if (pressed) 0.95f else 0.72f,
    )
}

private fun DrawScope.drawPillButton(
    bounds: ControlBounds,
    label: String,
    pressed: Boolean,
    baseAlpha: Float,
    strokeWidth: Float,
    showOutline: Boolean,
) {
    val topLeft = Offset(bounds.left, bounds.top)
    val size = Size(bounds.width, bounds.height)
    val corner = CornerRadius(bounds.height / 2f)
    val fillAlpha = if (pressed) {
        max(baseAlpha + 0.25f, 0.42f).coerceAtMost(1f)
    } else {
        baseAlpha
    }

    drawRoundRect(
        color = Color.White.copy(alpha = fillAlpha),
        topLeft = topLeft,
        size = size,
        cornerRadius = corner,
    )
    if (showOutline) {
        drawRoundRect(
            color = Color.White.copy(alpha = if (pressed) 0.65f else 0.3f),
            topLeft = topLeft,
            size = size,
            cornerRadius = corner,
            style = Stroke(strokeWidth),
        )
    }
    drawCenteredLabel(
        text = label,
        centerX = bounds.centerX,
        centerY = bounds.centerY,
        textSize = min(bounds.height * 0.55f, bounds.width * 0.2f),
        alpha = if (pressed) 0.95f else 0.72f,
    )
}

private fun DrawScope.drawSplitPill(
    bounds: ControlBounds,
    leftLabel: String,
    rightLabel: String,
    leftPressed: Boolean,
    rightPressed: Boolean,
    baseAlpha: Float,
    strokeWidth: Float,
    showOutline: Boolean,
) {
    val topLeft = Offset(bounds.left, bounds.top)
    val size = Size(bounds.width, bounds.height)
    val corner = CornerRadius(bounds.height / 2f)
    val pressedAlpha = max(baseAlpha + 0.25f, 0.42f).coerceAtMost(1f)

    drawRoundRect(
        color = Color.White.copy(alpha = baseAlpha),
        topLeft = topLeft,
        size = size,
        cornerRadius = corner,
    )

    if (leftPressed) {
        clipRect(bounds.left, bounds.top, bounds.centerX, bounds.bottom) {
            drawRoundRect(
                color = Color.White.copy(alpha = pressedAlpha),
                topLeft = topLeft,
                size = size,
                cornerRadius = corner,
            )
        }
    }
    if (rightPressed) {
        clipRect(bounds.centerX, bounds.top, bounds.right, bounds.bottom) {
            drawRoundRect(
                color = Color.White.copy(alpha = pressedAlpha),
                topLeft = topLeft,
                size = size,
                cornerRadius = corner,
            )
        }
    }

    drawLine(
        color = Color.White.copy(alpha = 0.32f),
        start = Offset(bounds.centerX, bounds.top + bounds.height * 0.18f),
        end = Offset(bounds.centerX, bounds.bottom - bounds.height * 0.18f),
        strokeWidth = strokeWidth,
    )

    if (showOutline) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.3f),
            topLeft = topLeft,
            size = size,
            cornerRadius = corner,
            style = Stroke(strokeWidth),
        )
    }

    val textSize = min(bounds.height * 0.5f, bounds.width * 0.14f)
    drawCenteredLabel(
        text = leftLabel,
        centerX = bounds.left + bounds.width * 0.25f,
        centerY = bounds.centerY,
        textSize = textSize,
        alpha = if (leftPressed) 0.95f else 0.72f,
    )
    drawCenteredLabel(
        text = rightLabel,
        centerX = bounds.left + bounds.width * 0.75f,
        centerY = bounds.centerY,
        textSize = textSize,
        alpha = if (rightPressed) 0.95f else 0.72f,
    )
}

private fun DrawScope.drawMenu(
    bounds: ControlBounds,
    menuPressed: Boolean,
    baseAlpha: Float,
    strokeWidth: Float,
) {
    val radius = min(bounds.width, bounds.height) / 2f
    val center = Offset(bounds.centerX, bounds.centerY)
    val fillAlpha = if (menuPressed) 0.6f else max(baseAlpha, 0.35f).coerceAtMost(1f)

    drawCircle(Color.White.copy(alpha = fillAlpha), radius, center)
    drawCircle(
        Color.White.copy(alpha = 0.5f),
        radius,
        center,
        style = Stroke(strokeWidth),
    )

    val lineWidth = radius * 0.9f
    val gap = radius * 0.3f
    val lineStroke = radius * 0.12f
    for (index in -1..1) {
        val y = center.y + index * gap
        drawLine(
            color = Color.White.copy(alpha = 0.8f),
            start = Offset(center.x - lineWidth / 2f, y),
            end = Offset(center.x + lineWidth / 2f, y),
            strokeWidth = lineStroke,
        )
    }
}

private fun DrawScope.drawCenteredLabel(
    text: String,
    centerX: Float,
    centerY: Float,
    textSize: Float,
    alpha: Float,
) {
    if (text.isEmpty() || textSize <= 0f) return

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        this.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        textAlign = Paint.Align.CENTER
        this.textSize = textSize
        typeface = Typeface.DEFAULT_BOLD
    }
    val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
    drawContext.canvas.nativeCanvas.drawText(text, centerX, baseline, paint)
}

private fun labelFor(type: GamepadControlType): String = when (type) {
    GamepadControlType.A -> "A"
    GamepadControlType.B -> "B"
    GamepadControlType.L -> "L"
    GamepadControlType.R -> "R"
    GamepadControlType.START -> "START"
    GamepadControlType.SELECT -> "SELECT"
    GamepadControlType.A_B -> "A+B"
    GamepadControlType.L_R -> "L+R"
    GamepadControlType.L_A -> "L+A"
    GamepadControlType.L_B -> "L+B"
    GamepadControlType.R_A -> "R+A"
    GamepadControlType.R_B -> "R+B"
    else -> ""
}
