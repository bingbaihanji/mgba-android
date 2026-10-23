package com.mgba.bingbaihanji.emulator

/**
 * 原始 JNI 入口。业务代码应使用
 * [com.mgba.bingbaihanji.platform.android.emulation.JniEmulatorCore]，
 * 它负责持有句柄并把 Native 返回码映射为 Kotlin 结果。
 *
 * 注意：本对象的包名不能修改。C 侧的符号名为
 * `Java_com_mgba_bingbaihanji_emulator_NativeEmulator_*`，
 * 移动包会导致 JNI 链接失败。
 */
internal object NativeEmulator {
    init {
        System.loadLibrary("mgba_frontend")
    }

    external fun nativeCreate(): Long

    external fun nativeDestroy(handle: Long)

    external fun nativeInitialize(
        handle: Long,
        coreLibraryPath: String,
        systemDirectory: String,
    ): Int

    external fun nativeLoadGame(handle: Long, romPath: String): Int

    external fun nativeRunOnce(handle: Long): Int

    external fun nativeUnloadGame(handle: Long): Int

    external fun nativeShutdown(handle: Long): Int

    external fun nativeLastError(handle: Long): String?

    external fun nativeCoreName(handle: Long): String?

    external fun nativeCoreVersion(handle: Long): String?

    external fun nativeState(handle: Long): Int

    external fun nativeFrameCount(handle: Long): Long

    external fun nativeAudioFrameCount(handle: Long): Long

    external fun nativePixelFormat(handle: Long): Int

    external fun nativeAcquireFrame(handle: Long, outMeta: LongArray): java.nio.ByteBuffer?

    external fun nativeReleaseFrame(handle: Long)

    external fun nativeFramesDropped(handle: Long): Long

    external fun nativeReadAudio(handle: Long, buffer: java.nio.ByteBuffer, maxFrames: Int): Int

    external fun nativeAudioQueued(handle: Long): Int

    external fun nativeAudioUnderruns(handle: Long): Long

    external fun nativeAudioOverflows(handle: Long): Long

    external fun nativeResetAudioStats(handle: Long)

    external fun nativeGetAvInfo(handle: Long, outInfo: DoubleArray)

    external fun nativeInputPress(handle: Long, buttonId: Int, sourceId: Int)

    external fun nativeInputRelease(handle: Long, buttonId: Int, sourceId: Int)

    external fun nativeInputReleaseSource(handle: Long, sourceId: Int)

    external fun nativeInputReset(handle: Long)

    external fun nativeCheatReset(handle: Long)

    external fun nativeCheatSet(
        handle: Long,
        index: Int,
        enabled: Boolean,
        code: String,
    ): Boolean

    external fun nativeSramSize(handle: Long): Int

    external fun nativeSaveSram(handle: Long, buffer: java.nio.ByteBuffer): Boolean

    external fun nativeLoadSram(handle: Long, buffer: java.nio.ByteBuffer, size: Int): Boolean

    external fun nativeRtcSize(handle: Long): Int

    external fun nativeSaveRtc(handle: Long, buffer: java.nio.ByteBuffer): Boolean

    external fun nativeLoadRtc(handle: Long, buffer: java.nio.ByteBuffer, size: Int): Boolean

    external fun nativeSerializeSize(handle: Long): Long

    external fun nativeSerialize(handle: Long, buffer: java.nio.ByteBuffer): Boolean

    external fun nativeUnserialize(handle: Long, buffer: java.nio.ByteBuffer, size: Int): Boolean

    external fun nativeOptionCount(handle: Long): Int

    external fun nativeOptionKey(handle: Long, index: Int): String?

    external fun nativeOptionValues(handle: Long, index: Int): String?

    external fun nativeOptionCurrent(handle: Long, index: Int): Int

    external fun nativeSetOption(handle: Long, index: Int, valueIndex: Int): Boolean
}
