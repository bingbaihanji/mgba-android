package com.mgba.bingbaihanji.platform.android.emulation

import com.mgba.bingbaihanji.core.cheat.CheatCodeParser
import com.mgba.bingbaihanji.core.emulation.AvInfo
import com.mgba.bingbaihanji.core.emulation.EmulatorCore
import com.mgba.bingbaihanji.core.emulation.EmulatorError
import com.mgba.bingbaihanji.core.emulation.EmulatorException
import com.mgba.bingbaihanji.core.emulation.EmulatorState
import com.mgba.bingbaihanji.core.emulation.FrameData
import com.mgba.bingbaihanji.emulator.NativeEmulator
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 基于 JNI + Libretro 核心的 [EmulatorCore] 实现。
 *
 * 线程模型：[runOnce] 直接在调用线程（帧循环线程）执行，其余核心调用
 * 提交到专用 `mgba-emulation` 线程并同步等待结果；所有调用共用
 * [coreLock]，任何时刻只有一个线程进入原生核心。
 * [acquireFrame]/[releaseFrame] 供 GL 线程使用，[readAudio] 供音频线程使用，
 * 输入方法由 Native 原子状态保证线程安全。
 *
 * 说明：JNI 入口 [NativeEmulator] 的包名被 C 符号
 * `Java_com_mgba_bingbaihanji_emulator_NativeEmulator_*` 固定，不能移动。
 */
class JniEmulatorCore(
    private val coreLibraryPath: String,
    private val systemDirectory: String,
) : EmulatorCore {

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mgba-emulation")
        }

    /**
     * 串行化所有原生调用。
     *
     * [runOnce] 在帧循环线程上直接进入核心（省掉每帧一次提交等待），
     * 其它操作仍经 `mgba-emulation` 线程提交，两者用同一把锁互斥。
     */
    private val coreLock = ReentrantLock()

    @Volatile
    private var handle: Long

    @Volatile
    private var closed = false

    init {
        handle = emulationCall { NativeEmulator.nativeCreate() }
        if (handle == 0L) {
            executor.shutdown()
            error("Failed to allocate the native emulator session")
        }
    }

    override val state: EmulatorState
        get() = EmulatorState.fromValue(
            read(EmulatorState.UNINITIALIZED.value) { NativeEmulator.nativeState(it) },
        )

    override val coreName: String?
        get() = read(null) { NativeEmulator.nativeCoreName(it) }

    override val coreVersion: String?
        get() = read(null) { NativeEmulator.nativeCoreVersion(it) }

    override val lastError: String?
        get() = read(null) { NativeEmulator.nativeLastError(it) }

    override val frameCount: Long
        get() = read(0L) { NativeEmulator.nativeFrameCount(it) }

    override val audioFrameCount: Long
        get() = read(0L) { NativeEmulator.nativeAudioFrameCount(it) }

    override val pixelFormat: Int
        get() = read(0) { NativeEmulator.nativePixelFormat(it) }

    private val frameMeta = LongArray(4)

    override fun acquireFrame(): FrameData? {
        if (closed) return null
        val h = handle
        if (h == 0L) return null
        val buffer = NativeEmulator.nativeAcquireFrame(h, frameMeta) ?: return null
        return FrameData(
            width = frameMeta[0].toInt(),
            height = frameMeta[1].toInt(),
            format = frameMeta[2].toInt(),
            sequence = frameMeta[3],
            buffer = buffer,
        )
    }

    override fun releaseFrame() {
        if (closed) return
        val h = handle
        if (h != 0L) NativeEmulator.nativeReleaseFrame(h)
    }

    override val framesDropped: Long
        get() = read(0L) { NativeEmulator.nativeFramesDropped(it) }

    private val avInfoArray = DoubleArray(2)

    override val avInfo: AvInfo
        get() {
            val h = handle
            if (h == 0L || closed) return AvInfo(0.0, 0.0)
            NativeEmulator.nativeGetAvInfo(h, avInfoArray)
            return AvInfo(avInfoArray[0], avInfoArray[1])
        }

    override fun readAudio(buffer: ByteBuffer, maxFrames: Int): Int {
        if (closed) return 0
        val h = handle
        return if (h == 0L) 0 else NativeEmulator.nativeReadAudio(h, buffer, maxFrames)
    }

    override val audioQueued: Int
        get() = read(0) { NativeEmulator.nativeAudioQueued(it) }

    override val audioUnderruns: Long
        get() = read(0L) { NativeEmulator.nativeAudioUnderruns(it) }

    override val audioOverflows: Long
        get() = read(0L) { NativeEmulator.nativeAudioOverflows(it) }

    override fun resetAudioStats() {
        if (closed) return
        val h = handle
        if (h != 0L) NativeEmulator.nativeResetAudioStats(h)
    }

    override fun inputPress(buttonId: Int, sourceId: Int) {
        if (closed) return
        val h = handle
        if (h != 0L) NativeEmulator.nativeInputPress(h, buttonId, sourceId)
    }

    override fun inputRelease(buttonId: Int, sourceId: Int) {
        if (closed) return
        val h = handle
        if (h != 0L) NativeEmulator.nativeInputRelease(h, buttonId, sourceId)
    }

    override fun inputReleaseSource(sourceId: Int) {
        if (closed) return
        val h = handle
        if (h != 0L) NativeEmulator.nativeInputReleaseSource(h, sourceId)
    }

    override fun inputReset() {
        if (closed) return
        val h = handle
        if (h != 0L) NativeEmulator.nativeInputReset(h)
    }

    override fun cheatReset() {
        if (closed) return
        try {
            emulationCall {
                val h = handle
                if (h != 0L) NativeEmulator.nativeCheatReset(h)
            }
        } catch (_: IllegalStateException) {
            // 会话已关闭。
        }
    }

    override fun cheatSet(index: Int, enabled: Boolean, code: String): Boolean {
        if (closed) return false
        // libretro 桥接按 13/17 字符切分代码行，补足 Raw 短行的长度。
        val line = CheatCodeParser.toLibretroCode(code)
        return try {
            emulationCall {
                val h = handle
                h != 0L && NativeEmulator.nativeCheatSet(h, index, enabled, line)
            }
        } catch (_: IllegalStateException) {
            false
        }
    }

    override val sramSize: Int
        get() = read(0) { NativeEmulator.nativeSramSize(it) }

    override fun saveSram(buffer: ByteBuffer): Boolean {
        if (closed) return false
        return try {
            emulationCall {
                val h = handle
                h != 0L && NativeEmulator.nativeSaveSram(h, buffer)
            }
        } catch (_: IllegalStateException) {
            false
        }
    }

    override fun loadSram(buffer: ByteBuffer, size: Int): Boolean {
        if (closed) return false
        return try {
            emulationCall {
                val h = handle
                h != 0L && NativeEmulator.nativeLoadSram(h, buffer, size)
            }
        } catch (_: IllegalStateException) {
            false
        }
    }

    override val rtcSize: Int
        get() = read(0) { NativeEmulator.nativeRtcSize(it) }

    override fun saveRtc(buffer: ByteBuffer): Boolean {
        if (closed) return false
        return try {
            emulationCall {
                val h = handle
                h != 0L && NativeEmulator.nativeSaveRtc(h, buffer)
            }
        } catch (_: IllegalStateException) {
            false
        }
    }

    override fun loadRtc(buffer: ByteBuffer, size: Int): Boolean {
        if (closed) return false
        return try {
            emulationCall {
                val h = handle
                h != 0L && NativeEmulator.nativeLoadRtc(h, buffer, size)
            }
        } catch (_: IllegalStateException) {
            false
        }
    }

    override val serializeSize: Long
        get() = read(0L) { NativeEmulator.nativeSerializeSize(it) }

    override fun serialize(buffer: ByteBuffer): Boolean {
        if (closed) return false
        return try {
            emulationCall {
                val h = handle
                h != 0L && NativeEmulator.nativeSerialize(h, buffer)
            }
        } catch (_: IllegalStateException) {
            false
        }
    }

    override fun unserialize(buffer: ByteBuffer, size: Int): Boolean {
        if (closed) return false
        return try {
            emulationCall {
                val h = handle
                h != 0L && NativeEmulator.nativeUnserialize(h, buffer, size)
            }
        } catch (_: IllegalStateException) {
            false
        }
    }

    override val optionCount: Int
        get() = read(0) { NativeEmulator.nativeOptionCount(it) }

    override fun optionKey(index: Int): String? =
        read(null) { NativeEmulator.nativeOptionKey(it, index) }

    override fun optionValues(index: Int): String? =
        read(null) { NativeEmulator.nativeOptionValues(it, index) }

    override fun optionCurrent(index: Int): Int =
        read(-1) { NativeEmulator.nativeOptionCurrent(it, index) }

    override fun setOption(index: Int, valueIndex: Int): Boolean {
        if (closed) return false
        return try {
            emulationCall {
                val h = handle
                h != 0L && NativeEmulator.nativeSetOption(h, index, valueIndex)
            }
        } catch (_: IllegalStateException) {
            false
        }
    }

    override fun initialize(): Result<Unit> =
        mutate { NativeEmulator.nativeInitialize(it, coreLibraryPath, systemDirectory) }

    override fun loadGame(romPath: String): Result<Unit> =
        mutate { NativeEmulator.nativeLoadGame(it, romPath) }

    override fun runOnce(): Result<Unit> {
        if (closed) return closedFailure()
        // 每帧直接在当前线程调用：少一次线程切换与 Callable/FutureTask 分配，
        // 帧间隔更稳定；与其它核心调用通过 [coreLock] 互斥。
        coreLock.lock()
        try {
            val current = handle
            if (closed || current == 0L) return closedFailure()
            return toResult(current, NativeEmulator.nativeRunOnce(current))
        } finally {
            coreLock.unlock()
        }
    }

    override fun unloadGame(): Result<Unit> =
        mutate { NativeEmulator.nativeUnloadGame(it) }

    override fun shutdown(): Result<Unit> =
        mutate { NativeEmulator.nativeShutdown(it) }

    override fun close() {
        synchronized(this) {
            if (closed) {
                return
            }
            closed = true
            try {
                emulationCall {
                    val current = handle
                    if (current != 0L) {
                        NativeEmulator.nativeDestroy(current)
                        handle = 0L
                    }
                }
            } catch (_: IllegalStateException) {
                // 执行器已关闭，没有需要销毁的资源。
            } finally {
                executor.shutdown()
            }
        }
    }

    private fun <T> emulationCall(block: () -> T): T {
        return try {
            executor.submit(Callable { coreLock.withLock { block() } }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: RejectedExecutionException) {
            throw IllegalStateException("Emulator session is closed", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for the emulation thread", e)
        }
    }

    private fun <T> read(default: T, block: (Long) -> T): T {
        if (closed) {
            return default
        }
        return try {
            emulationCall {
                val current = handle
                if (current == 0L) default else block(current)
            }
        } catch (_: IllegalStateException) {
            default
        }
    }

    private fun mutate(block: (Long) -> Int): Result<Unit> {
        if (closed) {
            return closedFailure()
        }
        return try {
            emulationCall {
                val current = handle
                if (current == 0L) {
                    closedFailure()
                } else {
                    toResult(current, block(current))
                }
            }
        } catch (_: IllegalStateException) {
            closedFailure()
        }
    }

    private fun toResult(handle: Long, code: Int): Result<Unit> {
        if (code == 0) {
            return Result.success(Unit)
        }
        val error = EmulatorError.fromCode(code)
        val message = NativeEmulator.nativeLastError(handle)
            ?: "Native emulator error ${error.name.lowercase()} ($code)"
        return Result.failure(EmulatorException(error, message))
    }

    private fun closedFailure(): Result<Unit> =
        Result.failure(EmulatorException(EmulatorError.INVALID_STATE, "Emulator session is closed"))
}
