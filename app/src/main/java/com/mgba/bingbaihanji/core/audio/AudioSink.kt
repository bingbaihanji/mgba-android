package com.mgba.bingbaihanji.core.audio

import java.nio.ByteBuffer

/**
 * 音频输出设备。平台实现负责创建真正的播放器（Android 为 AudioTrack，
 * 桌面端可以是 OpenAL / JavaFX 音频链路）。
 *
 * 线程约定：[write] 由音频泵线程调用，可能阻塞；其余方法由控制线程调用。
 */
interface AudioSink : AutoCloseable {

    /** 音量 0..100。 */
    var volume: Int

    /**
     * 创建并启动播放设备。
     * 返回 false 表示设备初始化失败，调用方不应启动消费线程。
     */
    fun start(): Boolean

    /** 暂停播放并清空积压数据，避免恢复时播放旧声音。 */
    fun pause()

    /** 恢复播放。 */
    fun resume()

    /**
     * 阻塞写入 [byteCount] 字节的交错立体声 PCM 数据。
     * [buffer] 的位置和 limit 已由调用方设置好。
     */
    fun write(buffer: ByteBuffer, byteCount: Int)
}
