package com.mgba.bingbaihanji.platform.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.mgba.bingbaihanji.core.audio.AudioSink
import java.nio.ByteBuffer

/**
 * 基于 Android [AudioTrack] 的 [AudioSink] 实现。
 *
 * 只负责设备生命周期与阻塞写入；读取环形缓冲区、休眠等消费逻辑由
 * 平台无关的 AudioPump 负责。
 */
class AudioTrackSink(sampleRate: Int) : AudioSink {

    private val sampleRateHz: Int = sampleRate.coerceAtLeast(8000)

    private var audioTrack: AudioTrack? = null

    @Volatile
    private var closed = false

    /** 音量 0..100；设置时同步到播放器。 */
    @Volatile
    override var volume: Int = 100
        set(value) {
            field = value.coerceIn(0, 100)
            // setVolume() 作用于所有声道，等价于原先的 setStereoVolume(l, r)。
            audioTrack?.setVolume(field / 100f)
        }

    override fun start(): Boolean {
        if (closed) return false

        // 上一次播放若因写入失败而停止，这里先释放旧设备再重建。
        audioTrack?.let { track ->
            try {
                track.stop()
            } catch (_: IllegalStateException) {
            }
            track.release()
            audioTrack = null
        }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "AudioTrack.getMinBufferSize failed: $minBuf")
            return false
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize (state=${track.state})")
            track.release()
            return false
        }

        audioTrack = track
        // 应用在 start() 之前设置的音量；否则设备会一直使用默认音量。
        track.setVolume(volume / 100f)
        track.play()
        return true
    }

    override fun pause() {
        audioTrack?.let { track ->
            try {
                track.pause()
                track.flush()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioTrack pause failed", e)
            }
        }
    }

    override fun resume() {
        if (closed) return
        try {
            audioTrack?.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack play failed", e)
        }
    }

    override fun write(buffer: ByteBuffer, byteCount: Int) {
        audioTrack?.write(buffer, byteCount, AudioTrack.WRITE_BLOCKING)
    }

    override fun close() {
        if (closed) return
        closed = true

        audioTrack?.let { track ->
            try {
                track.stop()
            } catch (_: IllegalStateException) {
            }
            track.release()
        }
        audioTrack = null
    }

    companion object {
        private const val TAG = "AudioTrackSink"
    }
}
