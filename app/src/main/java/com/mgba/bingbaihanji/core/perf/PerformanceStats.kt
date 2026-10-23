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
}
