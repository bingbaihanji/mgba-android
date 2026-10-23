#include <jni.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>

#include "mgba_session.h"

/* RETRO_PIXEL_FORMAT_XRGB8888 = 1, RGB565 = 2, 0RGB1555 = 0. */
static unsigned pixel_format_bpp(unsigned format) {
    return format == RETRO_PIXEL_FORMAT_XRGB8888 ? 4 : 2;
}

static MgbaSession *session_from_handle(jlong handle) {
    return (MgbaSession *)(intptr_t)handle;
}

static jstring new_string(JNIEnv *env, const char *text) {
    return text ? (*env)->NewStringUTF(env, text) : NULL;
}

JNIEXPORT jlong JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeCreate(JNIEnv *env,
                                                               jobject thiz) {
    (void)env;
    (void)thiz;
    return (jlong)(intptr_t)mgba_session_create();
}

JNIEXPORT void JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeDestroy(JNIEnv *env,
                                                                jobject thiz,
                                                                jlong handle) {
    (void)env;
    (void)thiz;
    mgba_session_destroy(session_from_handle(handle));
}

JNIEXPORT jint JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeInitialize(
    JNIEnv *env, jobject thiz, jlong handle, jstring core_library_path,
    jstring system_directory) {
    MgbaSession *session = session_from_handle(handle);
    const char *core_path;
    const char *system_dir = NULL;
    MgbaResult result;

    (void)thiz;
    if (!session || !core_library_path) {
        return MGBA_ERR_INVALID_ARGUMENT;
    }

    core_path = (*env)->GetStringUTFChars(env, core_library_path, NULL);
    if (!core_path) {
        return MGBA_ERR_INVALID_ARGUMENT;
    }
    if (system_directory) {
        system_dir = (*env)->GetStringUTFChars(env, system_directory, NULL);
        if (!system_dir) {
            (*env)->ReleaseStringUTFChars(env, core_library_path, core_path);
            return MGBA_ERR_INVALID_ARGUMENT;
        }
    }

    result = mgba_session_initialize(session, core_path, system_dir);

    if (system_dir) {
        (*env)->ReleaseStringUTFChars(env, system_directory, system_dir);
    }
    (*env)->ReleaseStringUTFChars(env, core_library_path, core_path);
    return (jint)result;
}

JNIEXPORT jint JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeLoadGame(
    JNIEnv *env, jobject thiz, jlong handle, jstring rom_path) {
    MgbaSession *session = session_from_handle(handle);
    const char *path;
    MgbaResult result;

    (void)thiz;
    if (!session || !rom_path) {
        return MGBA_ERR_INVALID_ARGUMENT;
    }

    path = (*env)->GetStringUTFChars(env, rom_path, NULL);
    if (!path) {
        return MGBA_ERR_INVALID_ARGUMENT;
    }
    result = mgba_session_load_game(session, path);
    (*env)->ReleaseStringUTFChars(env, rom_path, path);
    return (jint)result;
}

JNIEXPORT jint JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeRunOnce(JNIEnv *env,
                                                                jobject thiz,
                                                                jlong handle) {
    (void)env;
    (void)thiz;
    return (jint)mgba_session_run_once(session_from_handle(handle));
}

JNIEXPORT jint JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeUnloadGame(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return (jint)mgba_session_unload_game(session_from_handle(handle));
}

JNIEXPORT jint JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeShutdown(JNIEnv *env,
                                                                 jobject thiz,
                                                                 jlong handle) {
    (void)env;
    (void)thiz;
    return (jint)mgba_session_shutdown(session_from_handle(handle));
}

JNIEXPORT jstring JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeLastError(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    return new_string(env, mgba_session_last_error(session_from_handle(handle)));
}

JNIEXPORT jstring JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeCoreName(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    return new_string(env, mgba_session_core_name(session_from_handle(handle)));
}

JNIEXPORT jstring JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeCoreVersion(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    return new_string(env,
                      mgba_session_core_version(session_from_handle(handle)));
}

JNIEXPORT jint JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeState(JNIEnv *env,
                                                              jobject thiz,
                                                              jlong handle) {
    (void)env;
    (void)thiz;
    return (jint)mgba_session_state(session_from_handle(handle));
}

JNIEXPORT jlong JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeFrameCount(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return (jlong)mgba_session_frame_count(session_from_handle(handle));
}

JNIEXPORT jlong JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeAudioFrameCount(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return (jlong)mgba_session_audio_frame_count(session_from_handle(handle));
}

JNIEXPORT jint JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativePixelFormat(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return (jint)mgba_session_get_pixel_format(session_from_handle(handle));
}

/*
 * Acquire the latest video frame for rendering.
 *
 * out_meta is a 4-element long array filled with [width, height, format, seq].
 * Returns a direct ByteBuffer wrapping the pixel data, or NULL when no frame
 * is available. The buffer is only valid until nativeReleaseFrame() or the
 * session is destroyed -- the caller must stop rendering before close().
 */
JNIEXPORT jobject JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeAcquireFrame(
    JNIEnv *env, jobject thiz, jlong handle, jlongArray out_meta) {
    MgbaSession *session = session_from_handle(handle);
    const uint8_t *data = NULL;
    unsigned width = 0, height = 0, format = 0;
    unsigned long long seq = 0;
    jlong meta[4];
    size_t size;

    (void)thiz;
    if (!session || !out_meta) {
        return NULL;
    }
    if ((*env)->GetArrayLength(env, out_meta) < 4) {
        return NULL;
    }

    if (!mgba_session_acquire_frame(session, &data, &width, &height, &format,
                                    &seq)) {
        return NULL;
    }

    size = (size_t)width * height * pixel_format_bpp(format);
    meta[0] = (jlong)width;
    meta[1] = (jlong)height;
    meta[2] = (jlong)format;
    meta[3] = (jlong)seq;
    (*env)->SetLongArrayRegion(env, out_meta, 0, 4, meta);

    return (*env)->NewDirectByteBuffer(env, (void *)(uintptr_t)data,
                                       (jlong)size);
}

JNIEXPORT void JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeReleaseFrame(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    mgba_session_release_frame(session_from_handle(handle));
}

JNIEXPORT jlong JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeFramesDropped(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return (jlong)mgba_session_frames_dropped(session_from_handle(handle));
}

/*
 * Read interleaved stereo PCM frames from the audio ring buffer.
 * `buffer` is a direct ByteBuffer whose capacity is at least max_frames * 4.
 * Returns the number of frames actually read (0 when empty).
 */
JNIEXPORT jint JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeReadAudio(
    JNIEnv *env, jobject thiz, jlong handle, jobject buffer, jint max_frames) {
    MgbaSession *session = session_from_handle(handle);
    void *addr;
    jlong capacity;
    size_t frames_read;

    (void)thiz;
    if (!session || !buffer || max_frames <= 0) {
        return 0;
    }

    addr = (*env)->GetDirectBufferAddress(env, buffer);
    capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    if (!addr || capacity < (jlong)max_frames * 4) {
        return 0;
    }

    frames_read = mgba_session_read_audio(session, (int16_t *)addr,
                                          (size_t)max_frames);
    return (jint)frames_read;
}

JNIEXPORT jint JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeAudioQueued(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return (jint)mgba_session_audio_queued(session_from_handle(handle));
}

JNIEXPORT jlong JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeAudioUnderruns(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return (jlong)mgba_session_audio_underruns(session_from_handle(handle));
}

JNIEXPORT jlong JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeAudioOverflows(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return (jlong)mgba_session_audio_overflows(session_from_handle(handle));
}

JNIEXPORT void JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeResetAudioStats(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    mgba_session_reset_audio_stats(session_from_handle(handle));
}

/* Returns [fps, sample_rate] as a 2-element double array from AV info. */
JNIEXPORT void JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeGetAvInfo(
    JNIEnv *env, jobject thiz, jlong handle, jdoubleArray out_info) {
    MgbaSession *session = session_from_handle(handle);
    struct retro_system_av_info av_info;
    jdouble values[2];

    (void)thiz;
    if (!session || !out_info ||
        (*env)->GetArrayLength(env, out_info) < 2) {
        return;
    }
    if (!mgba_session_get_av_info(session, &av_info)) {
        values[0] = 0.0;
        values[1] = 0.0;
    } else {
        values[0] = av_info.timing.fps;
        values[1] = av_info.timing.sample_rate;
    }
    (*env)->SetDoubleArrayRegion(env, out_info, 0, 2, values);
}

JNIEXPORT void JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeInputPress(
    JNIEnv *env, jobject thiz, jlong handle, jint button_id, jint source_id) {
    (void)env;
    (void)thiz;
    mgba_session_input_press(session_from_handle(handle), (unsigned)button_id,
                             (unsigned)source_id);
}

JNIEXPORT void JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeInputRelease(
    JNIEnv *env, jobject thiz, jlong handle, jint button_id, jint source_id) {
    (void)env;
    (void)thiz;
    mgba_session_input_release(session_from_handle(handle),
                               (unsigned)button_id, (unsigned)source_id);
}

JNIEXPORT void JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeInputReleaseSource(
    JNIEnv *env, jobject thiz, jlong handle, jint source_id) {
    (void)env;
    (void)thiz;
    mgba_session_input_release_source(session_from_handle(handle),
                                      (unsigned)source_id);
}

JNIEXPORT void JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeInputReset(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    mgba_session_input_reset(session_from_handle(handle));
}

JNIEXPORT void JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeCheatReset(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    mgba_session_cheat_reset(session_from_handle(handle));
}

JNIEXPORT jboolean JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeCheatSet(
    JNIEnv *env, jobject thiz, jlong handle, jint index, jboolean enabled,
    jstring code) {
    MgbaSession *session = session_from_handle(handle);
    const char *text;
    jboolean result;

    (void)thiz;
    if (!session || !code) {
        return JNI_FALSE;
    }
    text = (*env)->GetStringUTFChars(env, code, NULL);
    if (!text) {
        return JNI_FALSE;
    }
    result = mgba_session_cheat_set(session, (unsigned)index,
                                    enabled == JNI_TRUE, text)
                 ? JNI_TRUE
                 : JNI_FALSE;
    (*env)->ReleaseStringUTFChars(env, code, text);
    return result;
}

/* SRAM size in bytes (0 when no game is loaded or no SRAM). */
JNIEXPORT jint JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeSramSize(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return (jint)mgba_session_sram_size(session_from_handle(handle));
}

/*
 * Copy SRAM into `buffer` (a direct ByteBuffer with capacity >= sram_size).
 * Returns true on success.
 */
JNIEXPORT jboolean JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeSaveSram(
    JNIEnv *env, jobject thiz, jlong handle, jobject buffer) {
    MgbaSession *session = session_from_handle(handle);
    void *addr;
    jlong capacity;

    (void)thiz;
    if (!session || !buffer) {
        return JNI_FALSE;
    }
    addr = (*env)->GetDirectBufferAddress(env, buffer);
    capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    if (!addr || capacity <= 0) {
        return JNI_FALSE;
    }
    return mgba_session_save_sram(session, addr, (size_t)capacity)
               ? JNI_TRUE
               : JNI_FALSE;
}

/* Load SRAM from a direct ByteBuffer. */
JNIEXPORT jboolean JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeLoadSram(
    JNIEnv *env, jobject thiz, jlong handle, jobject buffer, jint size) {
    MgbaSession *session = session_from_handle(handle);
    void *addr;

    (void)thiz;
    if (!session || !buffer || size <= 0) {
        return JNI_FALSE;
    }
    addr = (*env)->GetDirectBufferAddress(env, buffer);
    if (!addr) {
        return JNI_FALSE;
    }
    return mgba_session_load_sram(session, addr, (size_t)size)
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeRtcSize(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return (jint)mgba_session_rtc_size(session_from_handle(handle));
}

JNIEXPORT jboolean JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeSaveRtc(
    JNIEnv *env, jobject thiz, jlong handle, jobject buffer) {
    MgbaSession *session = session_from_handle(handle);
    void *addr;
    jlong capacity;

    (void)thiz;
    if (!session || !buffer) {
        return JNI_FALSE;
    }
    addr = (*env)->GetDirectBufferAddress(env, buffer);
    capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    if (!addr || capacity <= 0) {
        return JNI_FALSE;
    }
    return mgba_session_save_rtc(session, addr, (size_t)capacity)
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeLoadRtc(
    JNIEnv *env, jobject thiz, jlong handle, jobject buffer, jint size) {
    MgbaSession *session = session_from_handle(handle);
    void *addr;

    (void)thiz;
    if (!session || !buffer || size <= 0) {
        return JNI_FALSE;
    }
    addr = (*env)->GetDirectBufferAddress(env, buffer);
    if (!addr) {
        return JNI_FALSE;
    }
    return mgba_session_load_rtc(session, addr, (size_t)size)
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeSerializeSize(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return (jlong)mgba_session_serialize_size(session_from_handle(handle));
}

JNIEXPORT jboolean JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeSerialize(
    JNIEnv *env, jobject thiz, jlong handle, jobject buffer) {
    MgbaSession *session = session_from_handle(handle);
    void *addr;
    jlong capacity;

    (void)thiz;
    if (!session || !buffer) {
        return JNI_FALSE;
    }
    addr = (*env)->GetDirectBufferAddress(env, buffer);
    capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    if (!addr || capacity <= 0) {
        return JNI_FALSE;
    }
    return mgba_session_serialize(session, addr, (size_t)capacity)
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeUnserialize(
    JNIEnv *env, jobject thiz, jlong handle, jobject buffer, jint size) {
    MgbaSession *session = session_from_handle(handle);
    void *addr;

    (void)thiz;
    if (!session || !buffer || size <= 0) {
        return JNI_FALSE;
    }
    addr = (*env)->GetDirectBufferAddress(env, buffer);
    if (!addr) {
        return JNI_FALSE;
    }
    return mgba_session_unserialize(session, addr, (size_t)size)
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeOptionCount(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    return (jint)mgba_session_option_count(session_from_handle(handle));
}

/*
 * out_info is a 3-element int array: [value_count, current, reserved].
 * out_key receives the option key; out_values receives a pipe-separated
 * string of possible values (for simple display).
 */
JNIEXPORT jstring JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeOptionKey(
    JNIEnv *env, jobject thiz, jlong handle, jint index) {
    MgbaSession *session = session_from_handle(handle);
    const char *key = NULL;

    (void)thiz;
    if (!session || !mgba_session_option_info(session, index, &key, NULL, NULL,
                                              NULL)) {
        return NULL;
    }
    return key ? (*env)->NewStringUTF(env, key) : NULL;
}

JNIEXPORT jstring JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeOptionValues(
    JNIEnv *env, jobject thiz, jlong handle, jint index) {
    MgbaSession *session = session_from_handle(handle);
    const char *values = NULL;
    int count = 0;
    char joined[16 * 64]; /* enough for 16 values of 64 chars */
    size_t pos = 0;

    (void)thiz;
    if (!session ||
        !mgba_session_option_info(session, index, NULL, &values, &count,
                                  NULL)) {
        return NULL;
    }
    /* Join values with '|' for easy display in Kotlin. */
    joined[0] = '\0';
    {
        int i;
        for (i = 0; i < count; ++i) {
            const char *v = NULL;
            size_t len;
            if (!mgba_session_option_value(session, index, i, &v) || !v) {
                continue;
            }
            len = strlen(v);
            if (pos + len + 2 >= sizeof(joined)) {
                break;
            }
            if (i > 0) {
                joined[pos++] = '|';
            }
            memcpy(joined + pos, v, len);
            pos += len;
            joined[pos] = '\0';
        }
    }
    return (*env)->NewStringUTF(env, joined);
}

JNIEXPORT jint JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeOptionCurrent(
    JNIEnv *env, jobject thiz, jlong handle, jint index) {
    MgbaSession *session = session_from_handle(handle);
    int current = -1;

    (void)thiz;
    if (!session ||
        !mgba_session_option_info(session, index, NULL, NULL, NULL, &current)) {
        return -1;
    }
    return current;
}

JNIEXPORT jboolean JNICALL
Java_com_mgba_bingbaihanji_emulator_NativeEmulator_nativeSetOption(
    JNIEnv *env, jobject thiz, jlong handle, jint index, jint value_index) {
    (void)env;
    (void)thiz;
    return mgba_session_set_option(session_from_handle(handle), index,
                                   value_index)
               ? JNI_TRUE
               : JNI_FALSE;
}
