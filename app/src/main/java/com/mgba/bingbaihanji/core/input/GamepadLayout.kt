package com.mgba.bingbaihanji.core.input

import com.mgba.bingbaihanji.core.emulation.GbaButton

/**
 * 可放置的虚拟控件类型。
 *
 * [fixedButtons] 表示固定触发哪些 GBA 按键；DPAD、AB、START_SELECT、MENU
 * 需要根据触点位置动态计算，因此为空集。
 */
enum class GamepadControlType(
    val id: String,
    val fixedButtons: Set<Int> = emptySet(),
) {
    DPAD("dpad"),
    AB("ab"),
    A("a", setOf(GbaButton.A)),
    B("b", setOf(GbaButton.B)),
    L("tl", setOf(GbaButton.L)),
    R("tr", setOf(GbaButton.R)),
    START("start", setOf(GbaButton.START)),
    SELECT("select", setOf(GbaButton.SELECT)),
    START_SELECT("start_select"),
    A_B("a+b", setOf(GbaButton.A, GbaButton.B)),
    L_R("tl+tr", setOf(GbaButton.L, GbaButton.R)),
    L_A("tl+a", setOf(GbaButton.L, GbaButton.A)),
    L_B("tl+b", setOf(GbaButton.L, GbaButton.B)),
    R_A("tr+a", setOf(GbaButton.R, GbaButton.A)),
    R_B("tr+b", setOf(GbaButton.R, GbaButton.B)),
    MENU("menu");

    companion object {
        fun fromId(id: String): GamepadControlType? =
            entries.firstOrNull { it.id == id }
    }
}

/**
 * 一个可拖放控件的几何信息。
 *
 * 坐标约定：
 * - [centerX] / [centerY] 是相对屏幕宽/高的比例（0..1）；
 * - [halfWidth] / [halfHeight] 都以**屏幕宽度**为基准，
 *   这样在不同宽高比的屏幕上控件不会被拉伸变形。
 */
data class GamepadControl(
    val type: GamepadControlType,
    val centerX: Float,
    val centerY: Float,
    val halfWidth: Float,
    val halfHeight: Float,
) {
    /** 计算当前屏幕尺寸下的像素边界。 */
    fun bounds(viewWidth: Float, viewHeight: Float): ControlBounds {
        val halfW = halfWidth * viewWidth
        val halfH = halfHeight * viewWidth
        val cx = centerX * viewWidth
        val cy = centerY * viewHeight
        return ControlBounds(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    /**
     * 根据控件内的归一化坐标计算应触发的按键集合。
     * [normalizedX] / [normalizedY] 的取值范围通常是 -1..1。
     */
    fun buttonsAt(normalizedX: Float, normalizedY: Float): Set<Int> = when (type) {
        GamepadControlType.DPAD -> buildSet {
            if (normalizedX < -DIRECTION_THRESHOLD) add(GbaButton.LEFT)
            if (normalizedX > DIRECTION_THRESHOLD) add(GbaButton.RIGHT)
            if (normalizedY < -DIRECTION_THRESHOLD) add(GbaButton.UP)
            if (normalizedY > DIRECTION_THRESHOLD) add(GbaButton.DOWN)
        }

        // 左半 B，右半 A（与 My Boy 的 ab 控件一致）。
        GamepadControlType.AB ->
            if (normalizedX < 0f) setOf(GbaButton.B) else setOf(GbaButton.A)

        // 左半 Select，右半 Start。
        GamepadControlType.START_SELECT ->
            if (normalizedX < 0f) setOf(GbaButton.SELECT) else setOf(GbaButton.START)

        GamepadControlType.MENU -> emptySet()
        else -> type.fixedButtons
    }

    private companion object {
        const val DIRECTION_THRESHOLD = 0.33f
    }
}

/** 像素坐标系下的矩形边界。 */
data class ControlBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float, slop: Float = 0f): Boolean =
        x >= left - slop && x <= right + slop &&
                y >= top - slop && y <= bottom + slop

    fun normalizedX(x: Float): Float =
        if (width <= 0f) 0f else ((x - left) / width) * 2f - 1f

    fun normalizedY(y: Float): Float =
        if (height <= 0f) 0f else ((y - top) / height) * 2f - 1f
}

/** 命中结果：控件 + 触点在控件内的归一化坐标。 */
data class ControlHit(
    val control: GamepadControl,
    val normalizedX: Float,
    val normalizedY: Float,
) {
    fun buttons(): Set<Int> = control.buttonsAt(normalizedX, normalizedY)
}

/**
 * 一套完整的虚拟按键布局。
 *
 * 每种控件最多出现一次，菜单控件始终保留（否则用户无法再打开游戏菜单）。
 */
data class GamepadLayout(val controls: List<GamepadControl>) {

    fun control(type: GamepadControlType): GamepadControl? =
        controls.firstOrNull { it.type == type }

    /** 替换同类型控件；不存在时追加。保持原绘制顺序。 */
    fun with(control: GamepadControl): GamepadLayout {
        val index = controls.indexOfFirst { it.type == control.type }
        return if (index < 0) {
            GamepadLayout(controls + control)
        } else {
            GamepadLayout(
                controls.toMutableList().also { it[index] = control }
            )
        }
    }

    fun without(type: GamepadControlType): GamepadLayout =
        GamepadLayout(controls.filterNot { it.type == type })

    /**
     * 去重、裁剪到合法范围，并确保菜单控件存在。
     * 加载外部保存的数据时使用。
     */
    fun sanitized(): GamepadLayout {
        val seen = mutableSetOf<GamepadControlType>()
        val cleaned = controls.mapNotNull { control ->
            if (!seen.add(control.type)) return@mapNotNull null
            control.copy(
                centerX = control.centerX
                    .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
                centerY = control.centerY
                    .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
                halfWidth = control.halfWidth
                    .takeIf { it.isFinite() }?.coerceIn(MIN_HALF, MAX_HALF) ?: 0.06f,
                halfHeight = control.halfHeight
                    .takeIf { it.isFinite() }?.coerceIn(MIN_HALF, MAX_HALF) ?: 0.06f,
            )
        }
        val base = if (cleaned.isEmpty()) DEFAULT.controls else cleaned
        val withMenu = if (base.any { it.type == GamepadControlType.MENU }) {
            base
        } else {
            base + DEFAULT.control(GamepadControlType.MENU)!!
        }
        return GamepadLayout(withMenu)
    }

    /**
     * 命中测试。默认从后往前检查（后绘制的控件在上层）。
     *
     * [preferMenu] 为 true 时优先检查菜单控件，保证即使被其他控件覆盖，
     * 玩家仍能打开游戏菜单。
     */
    fun hitTest(
        x: Float,
        y: Float,
        viewWidth: Float,
        viewHeight: Float,
        slopPx: Float = 0f,
        preferMenu: Boolean = false,
    ): ControlHit? {
        val ordered: List<GamepadControl> = if (preferMenu) {
            val menu = controls.firstOrNull { it.type == GamepadControlType.MENU }
            if (menu == null) {
                controls.asReversed()
            } else {
                listOf(menu) + controls.asReversed().filter { it.type != GamepadControlType.MENU }
            }
        } else {
            controls.asReversed()
        }

        for (control in ordered) {
            val bounds = control.bounds(viewWidth, viewHeight)
            if (bounds.contains(x, y, slopPx)) {
                return ControlHit(
                    control = control,
                    normalizedX = bounds.normalizedX(x).coerceIn(-2f, 2f),
                    normalizedY = bounds.normalizedY(y).coerceIn(-2f, 2f),
                )
            }
        }
        return null
    }

    companion object {
        const val MIN_HALF = 0.02f
        const val MAX_HALF = 0.45f

        /**
         * 默认布局：沿用旧的圆形按键位置，并把顶部控件略微下移，
         * 确保控件和缩放手柄在常见横屏比例下完整可见。
         */
        val DEFAULT: GamepadLayout = GamepadLayout(
            listOf(
                GamepadControl(GamepadControlType.DPAD, 0.18f, 0.72f, 0.12f, 0.12f),
                GamepadControl(GamepadControlType.A, 0.88f, 0.62f, 0.08f, 0.08f),
                GamepadControl(GamepadControlType.B, 0.76f, 0.72f, 0.08f, 0.08f),
                GamepadControl(GamepadControlType.L, 0.08f, 0.15f, 0.06f, 0.06f),
                GamepadControl(GamepadControlType.R, 0.92f, 0.15f, 0.06f, 0.06f),
                GamepadControl(GamepadControlType.START, 0.58f, 0.85f, 0.05f, 0.05f),
                GamepadControl(GamepadControlType.SELECT, 0.42f, 0.85f, 0.05f, 0.05f),
                GamepadControl(GamepadControlType.MENU, 0.50f, 0.13f, 0.05f, 0.05f),
            )
        )

        /** 新增控件时的默认位置和尺寸。 */
        fun defaultControlFor(type: GamepadControlType): GamepadControl {
            DEFAULT.control(type)?.let { return it }
            return when (type) {
                GamepadControlType.AB ->
                    GamepadControl(type, 0.50f, 0.50f, 0.09f, 0.04f)

                GamepadControlType.START_SELECT ->
                    GamepadControl(type, 0.50f, 0.78f, 0.09f, 0.04f)

                GamepadControlType.A_B,
                GamepadControlType.L_R,
                GamepadControlType.L_A,
                GamepadControlType.L_B,
                GamepadControlType.R_A,
                GamepadControlType.R_B,
                -> GamepadControl(type, 0.50f, 0.50f, 0.075f, 0.035f)

                else -> GamepadControl(type, 0.50f, 0.50f, 0.06f, 0.06f)
            }
        }
    }
}
