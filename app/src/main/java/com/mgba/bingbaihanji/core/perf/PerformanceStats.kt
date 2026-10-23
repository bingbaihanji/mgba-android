package com.mgba.bingbaihanji.core.perf

import java.util.concurrent.atomic.AtomicLong

/**
 * 运行性能计数，供调试浮层采样。
 *
 * 只做无锁累加，不主动计算速率；采样方按固定间隔读取两次计数并求差值，
 * 因此关闭浮层时几乎没有额外开销。
 */
class PerformanceStats {

    private val emulatedFrames = AtomicLong()
    private val renderedFrames = AtomicLong()
    private val emulatedNanos = AtomicLong()

    /** 最近一帧的游戏画面尺寸，由渲染线程在新帧上传时写入。 */
    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    /** 记录核心输出的游戏画面尺寸。 */
    fun recordFrameSize(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height
    }

    /** 当前游戏画面尺寸；尚未拿到首帧时为 0×0。 */
    fun frameSize(): FrameSize = FrameSize(frameWidth, frameHeight)

    /** 记录一次仿真帧推进；[durationNanos] 是该帧在核心内的耗时。 */
    fun recordEmulatedFrame(durationNanos: Long) {
        emulatedFrames.incrementAndGet()
        emulatedNanos.addAndGet(durationNanos)
    }

    /** 记录一次实际绘制上屏。 */
    fun recordRenderedFrame() {
        renderedFrames.incrementAndGet()
    }

    /** 当前累计值，由采样方自行计算差分。 */
    fun counters(): Counters = Counters(
        emulatedFrames = emulatedFrames.get(),
        renderedFrames = renderedFrames.get(),
        emulatedNanos = emulatedNanos.get(),
    )

    data class Counters(
        val emulatedFrames: Long,
        val renderedFrames: Long,
        val emulatedNanos: Long,
    )

    data class FrameSize(val width: Int, val height: Int)
}
