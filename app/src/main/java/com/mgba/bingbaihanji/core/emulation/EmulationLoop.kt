package com.mgba.bingbaihanji.core.emulation

import com.mgba.bingbaihanji.core.perf.PerformanceStats
import java.util.concurrent.locks.LockSupport
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 帧循环管理器，负责按核心上报的帧率驱动 [EmulatorCore.runOnce]。
 *
 * 快进实现：每帧的实际等待时间 = 1e9 / (fps × 速度倍率)。
 *
 * 帧循环运行在独立的 `mgba-frame-loop` 线程上（而不是共享的
 * Dispatchers.IO），避免文件 I/O 或协程调度影响帧间隔；线程优先级略微
 * 提高，降低系统繁忙时的唤醒延迟。
 */
class EmulationLoop(
    private val core: EmulatorCore,
    /** 帧耗时与绘制计数，供调试浮层采样。 */
    private val stats: PerformanceStats,
    /** 每成功推进一帧后的回调，平台层可在这里请求一次渲染。 */
    private val onFrameProduced: () -> Unit = {},
) {

    /** 一次运行期间的存活标记；重新 start 会创建新实例，旧线程不会串台。 */
    private class RunToken {
        @Volatile
        var running = true
    }

    /** 速度倍率：1.0 为正常速度，4.0 为四倍速。 */
    @Volatile
    private var speedMultiplier = 1.0f

    /** 核心帧率，仅在 [start] 时更新。 */
    @Volatile
    private var baseFps = 60.0

    /** 当前帧率与速度下每帧的目标间隔（纳秒），避免在循环里重复做浮点除法。 */
    @Volatile
    private var frameNanos = 0L

    @Volatile
    private var token: RunToken? = null

    private var loopThread: Thread? = null

    /** 帧循环当前是否在运行。 */
    val isRunning: Boolean get() = token?.running == true

    /**
     * 以 [fps] 启动帧循环。重复调用会先停止旧循环。
     */
    fun start(fps: Double) {
        stop()
        baseFps = if (fps > 0) fps else 60.0
        recomputeFrameNanos()

        val runToken = RunToken()
        token = runToken
        loopThread = Thread({ runLoop(runToken) }, "mgba-frame-loop").apply {
            // 略高于普通线程，降低被后台任务挤占导致的帧间隔抖动。
            priority = Thread.NORM_PRIORITY + 2
            isDaemon = true
            start()
        }
    }

    private fun runLoop(runToken: RunToken) {
        var nextFrame = System.nanoTime()
        while (runToken.running) {
            val start = System.nanoTime()
            val result = core.runOnce()
            val elapsed = System.nanoTime() - start

            if (result.isFailure) {
                LOG.log(Level.SEVERE, "runOnce failed", result.exceptionOrNull())
                // 出错后把状态置为停止，允许后续 resume() 重新启动。
                runToken.running = false
                break
            }

            stats.recordEmulatedFrame(elapsed)
            // 尽早通知平台层：新帧已经可以呈现。
            onFrameProduced()

            nextFrame += frameNanos

            val now = System.nanoTime()
            val sleepNanos = nextFrame - now
            if (sleepNanos > SPIN_THRESHOLD_NANOS) {
                // 先休眠到截止前 SPIN_THRESHOLD_NANOS，再自旋收尾。
                // parkNanos 的唤醒误差通常在数百微秒，直接睡满容易
                // 偶尔错过 vsync；最后的短自旋能把帧间隔压到稳定。
                LockSupport.parkNanos(sleepNanos - SPIN_THRESHOLD_NANOS)
                while (runToken.running && System.nanoTime() < nextFrame) {
                    // 忙等剩余不足 1ms 的时间。
                }
            } else if (sleepNanos > 0) {
                // 等待时间本来就短（快进时每帧只有一两毫秒），
                // 自旋没有意义还会占满 CPU，直接休眠。
                LockSupport.parkNanos(sleepNanos)
            } else {
                // 落后于预定节奏时重新对齐，避免积压后疯狂追帧。
                nextFrame = now
            }
        }
    }

    /** 设置速度倍率（1.0 = 正常，4.0 = 四倍快进）。 */
    fun setSpeed(multiplier: Float) {
        speedMultiplier = multiplier.coerceAtLeast(0.1f)
        recomputeFrameNanos()
        LOG.fine("speed set to ${speedMultiplier}x")
    }

    /** 按当前帧率与速度倍率重算每帧间隔。 */
    private fun recomputeFrameNanos() {
        frameNanos = (1_000_000_000.0 / (baseFps * speedMultiplier))
            .toLong()
            .coerceAtLeast(1_000_000L)
    }

    /** 暂停并终止当前循环。 */
    fun pause() {
        stop()
    }

    /** 在循环未运行时以 [fps] 重新启动。 */
    fun resume(fps: Double) {
        if (!isRunning) start(fps)
    }

    /** 停止并终止当前循环。 */
    fun stop() {
        token?.running = false
        token = null
        loopThread?.interrupt()
        loopThread = null
    }

    /**
     * 重置当前游戏：卸载并重新加载同一个 ROM。
     * 调用方负责在成功后重新 [start] 循环。
     */
    fun reset(romPath: String): Boolean {
        stop()
        core.unloadGame()
        val load = core.loadGame(romPath)
        return load.isSuccess
    }

    companion object {
        /** 睡醒后用于收尾自旋的时间窗口，避免 parkNanos 抖动错过 vsync。 */
        private const val SPIN_THRESHOLD_NANOS = 1_000_000L

        private val LOG: Logger = Logger.getLogger("EmulationLoop")
    }
}
