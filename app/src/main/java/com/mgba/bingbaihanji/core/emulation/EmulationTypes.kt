package com.mgba.bingbaihanji.core.emulation

import java.nio.ByteBuffer

/**
 * 仿真器状态，数值与 native/include/mgba_session.h 中的 MgbaState 一一对应。
 */
enum class EmulatorState(val value: Int) {
    UNINITIALIZED(0),
    INITIALIZED(1),
    GAME_LOADED(2),
    RUNNING(3),
    ERROR(4);

    companion object {
        fun fromValue(value: Int): EmulatorState =
            entries.firstOrNull { it.value == value } ?: ERROR
    }
}

/**
 * 仿真器错误码，数值与 native/include/mgba_session.h 中的 MgbaResult 一一对应。
 */
enum class EmulatorError(val code: Int) {
    INVALID_ARGUMENT(1),
    INVALID_STATE(2),
    DLOPEN(3),
    DLSYM(4),
    CORE_INIT(5),
    CORE_LOAD_GAME(6),
    CORE_RUN(7),
    OUT_OF_MEMORY(8),
    INVALID_CORE_API(9),
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): EmulatorError =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * 仿真核心返回的错误。[error] 保留结构化错误码，便于调用方区分失败阶段。
 */
class EmulatorException(
    val error: EmulatorError,
    message: String,
) : RuntimeException(message)

/**
 * 一帧视频数据。
 *
 * 跨线程约定：[buffer] 由 Native 帧池持有，仅在本次 [FrameSource.acquireFrame]
 * 到下一次 acquire / release 之间有效，禁止在渲染线程之外读取。
 */
class FrameData(
    val width: Int,
    val height: Int,
    /** Libretro 像素格式：2 = RGB565，1 = XRGB8888。 */
    val format: Int,
    /** 帧序号，渲染线程用它跳过重复帧。 */
    val sequence: Long,
    val buffer: ByteBuffer,
)

/**
 * 核心上报的音视频时序信息，只在 loadGame 成功后有效。
 */
class AvInfo(
    val fps: Double,
    val sampleRate: Double,
)

/**
 * Libretro 手柄按键编号（RETRO_DEVICE_ID_JOYPAD_*）。
 */
object GbaButton {
    const val B = 0
    const val Y = 1
    const val SELECT = 2
    const val START = 3
    const val UP = 4
    const val DOWN = 5
    const val LEFT = 6
    const val RIGHT = 7
    const val A = 8
    const val X = 9
    const val L = 10
    const val R = 11
}

/**
 * 输入源编号。同一按键可以被多个来源同时按住，Native 层按来源位图合并，
 * 只有所有来源都松开时按键才真正松开。
 */
const val INPUT_SOURCE_VIRTUAL = 0
const val INPUT_SOURCE_KEYBOARD = 1
const val INPUT_SOURCE_GAMEPAD = 2
