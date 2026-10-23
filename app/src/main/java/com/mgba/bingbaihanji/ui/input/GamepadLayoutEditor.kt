package com.mgba.bingbaihanji.ui.input

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mgba.bingbaihanji.core.input.GamepadControl
import com.mgba.bingbaihanji.core.input.GamepadControlType
import com.mgba.bingbaihanji.core.input.GamepadLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class EditMode { None, Move, Resize }

/**
 * 虚拟按键布局编辑器。
 *
 * 交互与 My Boy 的 LayoutEditor 一致：
 * - 点按控件选中，拖动控件移动；
 * - 选中后拖动角点缩放（保持控件比例）；
 * - 左下角菜单用于添加/删除/重置/保存/放弃；
 * - 返回键打开同一菜单。
 *
 * 坐标以“屏幕宽度”为基准归一化，因此换分辨率后控件形状不会被拉伸。
 */
@Composable
fun GamepadLayoutEditor(
    initialLayout: GamepadLayout,
    onSave: (GamepadLayout) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    backRequest: Int = 0,
) {
    var working by remember(initialLayout) { mutableStateOf(initialLayout.sanitized()) }
    var selectedType by remember { mutableStateOf<GamepadControlType?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }

    // pointerInput 只启动一次；通过 rememberUpdatedState 在每次手势开始时
    // 读取最新的布局和选中项。若把 working/selectedType 作为 key，
    // 每次拖动更新状态都会取消并重启手势，导致拖动中断。
    val currentWorking by rememberUpdatedState(working)
    val currentSelectedType by rememberUpdatedState(selectedType)

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        Toast.makeText(
            context,
            "点按选中，拖动移动，拖动角点缩放；左下角菜单可保存",
            Toast.LENGTH_LONG,
        ).show()
    }

    // 物理返回键由 Activity 转成计数；先关闭最上层的弹窗，否则打开选项菜单。
    LaunchedEffect(backRequest) {
        if (backRequest <= 0) return@LaunchedEffect
        when {
            showAdd -> showAdd = false
            showReset -> showReset = false
            showOptions -> showOptions = false
            else -> showOptions = true
        }
    }

    // 手势返回（OnBackPressedDispatcher）作为物理返回键的补充。
    BackHandler(enabled = !showOptions && !showAdd && !showReset) {
        showOptions = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val grid = 8.dp.toPx()
                    val minHalfPx = 16.dp.toPx()
                    val handleHitRadius = 24.dp.toPx()

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val viewWidth = size.width.toFloat()
                        val viewHeight = size.height.toFloat()

                        val selected = currentSelectedType?.let { currentWorking.control(it) }
                        val handle = selected?.let {
                            handlePosition(it, viewWidth, viewHeight)
                        }
                        val onHandle = handle != null &&
                                (down.position - handle).getDistance() <= handleHitRadius

                        var mode = EditMode.None
                        var startControl: GamepadControl? = null

                        if (onHandle) {
                            mode = EditMode.Resize
                            startControl = selected
                        } else {
                            val hit = currentWorking.hitTest(
                                x = down.position.x,
                                y = down.position.y,
                                viewWidth = viewWidth,
                                viewHeight = viewHeight,
                            )
                            if (hit != null) {
                                selectedType = hit.control.type
                                startControl = hit.control
                                mode = EditMode.Move
                            } else {
                                selectedType = null
                            }
                        }

                        if (mode == EditMode.None || startControl == null) {
                            return@awaitEachGesture
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUp()) break
                            if (!change.positionChanged()) continue

                            val updated = when (mode) {
                                EditMode.Move -> moveControl(
                                    control = startControl,
                                    pointer = change.position,
                                    startPointer = down.position,
                                    viewWidth = viewWidth,
                                    viewHeight = viewHeight,
                                    grid = grid,
                                )

                                EditMode.Resize -> resizeControl(
                                    control = startControl,
                                    pointer = change.position,
                                    viewWidth = viewWidth,
                                    viewHeight = viewHeight,
                                    minHalfPx = minHalfPx,
                                )

                                EditMode.None -> startControl
                            }

                            working = working.with(updated)
                            selectedType = updated.type
                            change.consume()
                        }
                    }
                }
        ) {
            val viewWidth = size.width
            val viewHeight = size.height

            for (control in working.controls) {
                drawGamepadControl(
                    type = control.type,
                    bounds = control.bounds(viewWidth, viewHeight),
                    pressedButtons = emptySet(),
                    menuPressed = false,
                    baseAlpha = 0.3f,
                )
            }

            val selected = selectedType?.let { working.control(it) }
            if (selected != null) {
                val bounds = selected.bounds(viewWidth, viewHeight)
                drawRoundRect(
                    color = Color(0xFF80D8FF),
                    topLeft = Offset(bounds.left, bounds.top),
                    size = Size(bounds.width, bounds.height),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(2.dp.toPx()),
                )

                val handle = handlePosition(selected, viewWidth, viewHeight)
                drawCircle(Color(0xFFFFC107), 10.dp.toPx(), handle)
                drawCircle(
                    color = Color.Black.copy(alpha = 0.6f),
                    radius = 10.dp.toPx(),
                    center = handle,
                    style = Stroke(2.dp.toPx()),
                )
            }
        }

        FloatingActionButton(
            onClick = { showOptions = true },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(16.dp),
            containerColor = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
        ) {
            Icon(Icons.Default.Menu, contentDescription = "布局菜单")
        }

        if (showOptions) {
            LayoutOptionsDialog(
                hasSelection = selectedType != null,
                canDelete = selectedType != null && selectedType != GamepadControlType.MENU,
                onDismiss = { showOptions = false },
                onAdd = {
                    showOptions = false
                    showAdd = true
                },
                onDelete = {
                    selectedType?.let { type ->
                        if (type != GamepadControlType.MENU) {
                            working = working.without(type)
                            selectedType = null
                        }
                    }
                    showOptions = false
                },
                onReset = {
                    showOptions = false
                    showReset = true
                },
                onSave = { onSave(working.sanitized()) },
                onCancel = onCancel,
            )
        }

        if (showAdd) {
            AddControlDialog(
                layout = working,
                onDismiss = { showAdd = false },
                onAdd = { type ->
                    working = working.with(GamepadLayout.defaultControlFor(type))
                    selectedType = type
                    showAdd = false
                },
            )
        }

        if (showReset) {
            AlertDialog(
                onDismissRequest = { showReset = false },
                title = { Text("重置布局") },
                text = { Text("恢复默认布局？当前修改会被覆盖，点击“保存并退出”后生效。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            working = GamepadLayout.DEFAULT
                            selectedType = null
                            showReset = false
                        }
                    ) {
                        Text("重置")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showReset = false }) {
                        Text("取消")
                    }
                },
            )
        }
    }
}

@Composable
private fun LayoutOptionsDialog(
    hasSelection: Boolean,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("布局编辑") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "点按控件选中，拖动移动，拖动选中框的角点缩放。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        canDelete -> "已选中控件，可以删除。"
                        hasSelection -> "菜单控件用于打开游戏菜单，不能删除。"
                        else -> "先点按一个控件再删除。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                TextButton(onClick = onAdd) { Text("添加控件") }
                TextButton(onClick = onDelete, enabled = canDelete) { Text("删除选中控件") }
                TextButton(onClick = onReset) { Text("重置为默认布局") }
                TextButton(onClick = onSave) { Text("保存并退出") }
                TextButton(onClick = onCancel) { Text("放弃更改") }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun AddControlDialog(
    layout: GamepadLayout,
    onDismiss: () -> Unit,
    onAdd: (GamepadControlType) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加控件") },
        text = {
            // 始终列出全部控件类型：已存在的类型也可点选，
            // 效果是把它移回默认位置并选中，方便“找回”被拖出屏幕的控件。
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for (type in GamepadControlType.entries) {
                    val added = layout.control(type) != null
                    TextButton(onClick = { onAdd(type) }) {
                        Text(
                            if (added) {
                                "${controlLabel(type)}（已添加 · 点击重新放置）"
                            } else {
                                controlLabel(type)
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun controlLabel(type: GamepadControlType): String = when (type) {
    GamepadControlType.DPAD -> "方向轮盘（方向键）"
    GamepadControlType.AB -> "A / B（左右分半）"
    GamepadControlType.A -> "A"
    GamepadControlType.B -> "B"
    GamepadControlType.L -> "L"
    GamepadControlType.R -> "R"
    GamepadControlType.START -> "Start"
    GamepadControlType.SELECT -> "Select"
    GamepadControlType.START_SELECT -> "Select / Start（左右分半）"
    GamepadControlType.A_B -> "A + B"
    GamepadControlType.L_R -> "L + R"
    GamepadControlType.L_A -> "L + A"
    GamepadControlType.L_B -> "L + B"
    GamepadControlType.R_A -> "R + A"
    GamepadControlType.R_B -> "R + B"
    GamepadControlType.MENU -> "更多（菜单）"
}

/** 选中框的缩放角点：与控件中心同一象限、离屏幕中心最远的角。 */
private fun handlePosition(
    control: GamepadControl,
    viewWidth: Float,
    viewHeight: Float,
): Offset {
    val bounds = control.bounds(viewWidth, viewHeight)
    val x = if (control.centerX <= 0.5f) bounds.left else bounds.right
    val y = if (control.centerY <= 0.5f) bounds.top else bounds.bottom
    return Offset(x, y)
}

private fun moveControl(
    control: GamepadControl,
    pointer: Offset,
    startPointer: Offset,
    viewWidth: Float,
    viewHeight: Float,
    grid: Float,
): GamepadControl {
    val halfWidthPx = control.halfWidth * viewWidth
    val halfHeightPx = control.halfHeight * viewWidth

    var centerPxX = control.centerX * viewWidth + (pointer.x - startPointer.x)
    var centerPxY = control.centerY * viewHeight + (pointer.y - startPointer.y)

    centerPxX = clampCenter(snap(centerPxX, grid), halfWidthPx, viewWidth)
    centerPxY = clampCenter(snap(centerPxY, grid), halfHeightPx, viewHeight)

    return control.copy(
        centerX = centerPxX / viewWidth,
        centerY = centerPxY / viewHeight,
    )
}

private fun resizeControl(
    control: GamepadControl,
    pointer: Offset,
    viewWidth: Float,
    viewHeight: Float,
    minHalfPx: Float,
): GamepadControl {
    val bounds = control.bounds(viewWidth, viewHeight)
    val leftSide = control.centerX <= 0.5f
    val topSide = control.centerY <= 0.5f

    val fixedX = if (leftSide) bounds.right else bounds.left
    val fixedY = if (topSide) bounds.bottom else bounds.top
    val aspect = (control.halfWidth / control.halfHeight).coerceAtLeast(0.01f)

    var halfWidthPx = if (leftSide) {
        (fixedX - pointer.x) / 2f
    } else {
        (pointer.x - fixedX) / 2f
    }
    halfWidthPx = max(halfWidthPx, minHalfPx)
    var halfHeightPx = max(halfWidthPx / aspect, minHalfPx)

    val availableWidth = if (leftSide) fixedX else viewWidth - fixedX
    val availableHeight = if (topSide) fixedY else viewHeight - fixedY
    val maxHalfWidth = availableWidth / 2f
    val maxHalfHeight = availableHeight / 2f

    val fitScale = min(
        1f,
        min(
            if (halfWidthPx > 0f) maxHalfWidth / halfWidthPx else 1f,
            if (halfHeightPx > 0f) maxHalfHeight / halfHeightPx else 1f,
        ),
    ).coerceAtLeast(0f)

    halfWidthPx = max(halfWidthPx * fitScale, minHalfPx)
    halfHeightPx = max(halfHeightPx * fitScale, minHalfPx)

    val centerPxX = if (leftSide) fixedX - halfWidthPx else fixedX + halfWidthPx
    val centerPxY = if (topSide) fixedY - halfHeightPx else fixedY + halfHeightPx

    return control.copy(
        centerX = (centerPxX / viewWidth).coerceIn(0f, 1f),
        centerY = (centerPxY / viewHeight).coerceIn(0f, 1f),
        halfWidth = (halfWidthPx / viewWidth)
            .coerceIn(GamepadLayout.MIN_HALF, GamepadLayout.MAX_HALF),
        halfHeight = (halfHeightPx / viewWidth)
            .coerceIn(GamepadLayout.MIN_HALF, GamepadLayout.MAX_HALF),
    )
}

private fun clampCenter(value: Float, halfSize: Float, viewSize: Float): Float {
    val minValue = halfSize
    val maxValue = viewSize - halfSize
    return if (maxValue <= minValue) viewSize / 2f else value.coerceIn(minValue, maxValue)
}

private fun snap(value: Float, grid: Float): Float =
    if (grid <= 0f) value else (value / grid).roundToInt() * grid
