package com.mgba.bingbaihanji.core.audio

import com.mgba.bingbaihanji.core.emulation.AudioSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 音频泵：从 [AudioSource]（仿真实例的环形缓冲区）读取采样，阻塞写入
 * [AudioSink]。仿真线程只负责生产，永远不会被播放阻塞。
 *
 * 线程模型：
 * - [start] 创建一条名为 `mgba-audio` 的守护线程；
 * - 该线程循环执行「读取 → 写入」，环形缓冲区为空时休眠 2ms；
 * - [pause] 只暂停设备并丢弃积压采样，消费线程保持存活；
 * - [resume] 恢复设备；如果此前从未启动，则按需创建线程；
 * - [close] 终止线程并释放设备。
 */
class AudioPump(
    private val source: AudioSource,
    private val sink: AudioSink,
) : AutoCloseable {

    private val bytesPerFrame = 4 // 立体声 int16
    private val readFrames = 1024

    private val pcmBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(readFrames * bytesPerFrame)
        .order(ByteOrder.nativeOrder())

    /** 消费线程是否应继续运行。 */
    @Volatile
    private var running = false

    /** 设备是否处于暂停状态；暂停期间线程只负责丢弃积压。 */
    @Volatile
    private var paused = false

    @Volatile
    private var closed = false

    private var audioThread: Thread? = null

    /** 透传到输出设备的音量，范围 0..100。 */
    var volume: Int
        get() = sink.volume
        set(value) {
            sink.volume = value
        }

    /** 创建输出设备并启动消费线程；设备初始化失败时不创建线程。 */
    fun start() {
        if (closed || running) return

        if (!sink.start()) {
            LOG.severe("Audio sink failed to start")
            return
        }

        running = true
        paused = false
        audioThread = Thread({ playbackLoop() }, "mgba-audio").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * 暂停设备。消费线程保持存活，并持续丢弃新产生的采样，
     * 避免恢复时播放暂停期间的旧声音。
     */
    fun pause() {
        if (closed) return
        paused = true
        sink.pause()
    }

    /**
     * 恢复设备。若此前从未成功启动（例如启动时关闭了声音），
     * 这里会按需创建设备和消费线程。
     */
    fun resume() {
        if (closed) return
        if (!running) {
            start()
            return
        }
        paused = false
        sink.resume()
    }

    override fun close() {
        if (closed) return
        closed = true
        running = false
        paused = false

        audioThread?.let { thread ->
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        audioThread = null
        sink.close()
    }

    private fun playbackLoop() {
        while (running && !closed) {
            if (paused) {
                discardBacklog()
                sleepQuietly()
                continue
            }

            pcmBuffer.position(0)
            val frames = source.readAudio(pcmBuffer, readFrames)
            if (frames > 0) {
                val bytes = frames * bytesPerFrame
                pcmBuffer.position(0)
                pcmBuffer.limit(bytes)
                try {
                    sink.write(pcmBuffer, bytes)
                } catch (e: Exception) {
                    LOG.log(Level.SEVERE, "Audio sink write failed", e)
                    running = false
                    break
                }
            } else {
                sleepQuietly()
            }
        }
    }

    /**
     * 暂停期间丢弃环形缓冲区中的积压采样。
     * 每轮最多丢弃 4 批，既能让消费速度高于生产速度，又不会空转。
     */
    private fun discardBacklog() {
        repeat(4) {
            pcmBuffer.position(0)
            if (source.readAudio(pcmBuffer, readFrames) <= 0) return
        }
    }

    private fun sleepQuietly() {
        try {
            Thread.sleep(2)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            running = false
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger("AudioPump")
    }
}
