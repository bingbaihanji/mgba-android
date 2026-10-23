package com.mgba.bingbaihanji.core.emulation

import java.nio.ByteBuffer

/**
 * 视频帧来源。渲染线程通过它拉取最新画面。
 *
 * 线程约定：只在渲染线程调用。acquire 返回的帧缓冲区在下一次
 * acquire / release 之前有效，用完必须调用 [releaseFrame]。
 */
interface FrameSource {
    /** 获取最新完整帧；尚无画面时返回 null。 */
    fun acquireFrame(): FrameData?

    /** 释放上一次 [acquireFrame] 拿到的帧。 */
    fun releaseFrame()
}

/**
 * 音频采样来源。播放线程通过它从环形缓冲区取交错立体声 int16 数据。
 *
 * 线程约定：只在音频播放线程调用。
 */
interface AudioSource {
    /**
     * 读取最多 [maxFrames] 帧到 [buffer]（必须是直接缓冲区）。
     * 返回实际读取帧数，缓冲区为空时返回 0。
     */
    fun readAudio(buffer: ByteBuffer, maxFrames: Int): Int
}

/**
 * 输入接收端。任意线程均可调用，Native 层用原子位图保证并发安全。
 */
interface InputSink {
    /** 按下 [buttonId]；[sourceId] 用于区分虚拟按键、键盘、手柄。 */
    fun inputPress(buttonId: Int, sourceId: Int)

    /** 松开 [buttonId] 的指定来源。 */
    fun inputRelease(buttonId: Int, sourceId: Int)

    /** 释放某个来源按住的全部按键。 */
    fun inputReleaseSource(sourceId: Int)

    /** 清空全部输入状态。 */
    fun inputReset()

    /** 清空核心中的全部金手指（Libretro retro_cheat_reset）。 */
    fun cheatReset()

    /**
     * 添加一条启用的金手指代码（Libretro retro_cheat_set）。
     *
     * 核心忽略 index/enabled；调用方应先 [cheatReset] 再只添加启用项。
     * [code] 为一行规范化代码，例如 `820051F4 0000FFFF`。
     */
    fun cheatSet(index: Int, enabled: Boolean, code: String): Boolean
}

/**
 * 仿真核心的平台无关契约。
 *
 * 实现负责线程归属：本接口的实现通常把所有核心调用串行化到一条专用
 * 仿真线程上，调用方无需自行加锁。除 [acquireFrame]、[releaseFrame]、
 * [readAudio] 与输入方法外，其余方法都应视为阻塞调用。
 *
 * 未来扩展：桌面端可以复用该接口，只需提供另一个 JNI/FFI 绑定实现；
 * 渲染、音频、输入模块不需要改动。
 */
interface EmulatorCore : FrameSource, AudioSource, InputSink, AutoCloseable {

    /** 当前状态。 */
    val state: EmulatorState

    /** 核心名称；未初始化时为 null。 */
    val coreName: String?

    /** 核心版本；未初始化时为 null。 */
    val coreVersion: String?

    /** 最近一次失败的可读描述。 */
    val lastError: String?

    /** 已渲染帧数。 */
    val frameCount: Long

    /** 已产生的音频帧数。 */
    val audioFrameCount: Long

    /** Libretro 像素格式；2 表示 RGB565。 */
    val pixelFormat: Int

    /** 因帧池无空闲槽位而丢弃的帧数。 */
    val framesDropped: Long

    /** 核心上报的帧率与采样率；loadGame 之后有效。 */
    val avInfo: AvInfo

    /** 音频环形缓冲区中待播放的帧数。 */
    val audioQueued: Int

    /** 音频欠载次数。 */
    val audioUnderruns: Long

    /** 音频溢出次数。 */
    val audioOverflows: Long

    /** 清零音频统计。 */
    fun resetAudioStats()

    /** 电池存档大小；无存档时返回 0。 */
    val sramSize: Int

    /** 把核心 SRAM 复制到 [buffer]，容量不足时返回 false。 */
    fun saveSram(buffer: ByteBuffer): Boolean

    /** 用 [buffer] 的前 [size] 字节覆盖核心 SRAM。 */
    fun loadSram(buffer: ByteBuffer, size: Int): Boolean

    /** RTC 数据大小；不存在时返回 0。 */
    val rtcSize: Int

    /** 把核心 RTC 数据复制到 [buffer]。 */
    fun saveRtc(buffer: ByteBuffer): Boolean

    /** 用 [buffer] 的前 [size] 字节覆盖核心 RTC。 */
    fun loadRtc(buffer: ByteBuffer, size: Int): Boolean

    /** 即时存档序列化大小；不支持时返回 0。 */
    val serializeSize: Long

    /** 把当前状态序列化到 [buffer]。 */
    fun serialize(buffer: ByteBuffer): Boolean

    /** 从 [buffer] 的前 [size] 字节恢复状态。 */
    fun unserialize(buffer: ByteBuffer, size: Int): Boolean

    /** 核心注册的选项数量。 */
    val optionCount: Int

    /** 第 [index] 个选项的键。 */
    fun optionKey(index: Int): String?

    /** 第 [index] 个选项的可选值，以 `|` 分隔。 */
    fun optionValues(index: Int): String?

    /** 第 [index] 个选项的当前值下标。 */
    fun optionCurrent(index: Int): Int

    /** 修改第 [index] 个选项的当前值。 */
    fun setOption(index: Int, valueIndex: Int): Boolean

    /** 加载并初始化核心动态库。 */
    fun initialize(): Result<Unit>

    /** 加载 ROM。 */
    fun loadGame(romPath: String): Result<Unit>

    /** 运行一帧。 */
    fun runOnce(): Result<Unit>

    /** 卸载当前 ROM。 */
    fun unloadGame(): Result<Unit>

    /** 反初始化核心。 */
    fun shutdown(): Result<Unit>
}
