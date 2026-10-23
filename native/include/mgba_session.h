/*
 * Minimal Libretro frontend session API for the mGBA Android app.
 *
 * This header is the seam between the JNI bridge and the core wrapper.
 * All functions must be called from a single emulation thread once the
 * session has been initialized.
 */
#ifndef MGBA_SESSION_H
#define MGBA_SESSION_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include <libretro.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum MgbaResult {
    MGBA_OK = 0,
    MGBA_ERR_INVALID_ARGUMENT = 1,
    MGBA_ERR_INVALID_STATE = 2,
    MGBA_ERR_DLOPEN = 3,
    MGBA_ERR_DLSYM = 4,
    MGBA_ERR_CORE_INIT = 5,
    MGBA_ERR_CORE_LOAD_GAME = 6,
    MGBA_ERR_CORE_RUN = 7,
    MGBA_ERR_OUT_OF_MEMORY = 8,
    MGBA_ERR_INVALID_CORE_API = 9
} MgbaResult;

typedef enum MgbaState {
    MGBA_STATE_UNINITIALIZED = 0,
    MGBA_STATE_INITIALIZED = 1,
    MGBA_STATE_GAME_LOADED = 2,
    MGBA_STATE_RUNNING = 3,
    MGBA_STATE_ERROR = 4
} MgbaState;

/*
 * Resolved Libretro entry points. handle is owned by mgba_core_open() and
 * must be released with mgba_core_close(); a session initialized with this
 * struct must not outlive it.
 */
typedef struct MgbaCoreApi {
    void *handle;
    unsigned (*api_version)(void);
    void (*set_environment)(retro_environment_t);
    void (*set_video_refresh)(retro_video_refresh_t);
    void (*set_audio_sample)(retro_audio_sample_t);
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
    void (*set_input_poll)(retro_input_poll_t);
    void (*set_input_state)(retro_input_state_t);
    void (*init)(void);
    void (*deinit)(void);
    void (*get_system_info)(struct retro_system_info *);
    void (*get_system_av_info)(struct retro_system_av_info *);
    bool (*load_game)(const struct retro_game_info *);
    void (*unload_game)(void);
    void (*run)(void);
    void (*reset)(void);
    size_t (*serialize_size)(void);
    bool (*serialize)(void *, size_t);
    bool (*unserialize)(const void *, size_t);
    void *(*get_memory_data)(unsigned);
    size_t (*get_memory_size)(unsigned);
    /* Optional Libretro cheat API; older minimal cores may not export it. */
    void (*cheat_reset)(void);
    void (*cheat_set)(unsigned index, bool enabled, const char *code);
} MgbaCoreApi;

/*
 * Load the Libretro core shared library and resolve every entry point.
 * On failure, error receives a human-readable message and out_api is zeroed.
 */
MgbaResult mgba_core_open(const char *library_path, MgbaCoreApi *out_api,
                          char *error, size_t error_size);

/* Release the dynamic library. Safe to call with a zeroed or closed API. */
void mgba_core_close(MgbaCoreApi *api);

/* Opaque session. Create with mgba_session_create(), free with destroy. */
typedef struct MgbaSession MgbaSession;

MgbaSession *mgba_session_create(void);
void mgba_session_destroy(MgbaSession *session);

MgbaState mgba_session_state(const MgbaSession *session);
const char *mgba_session_last_error(const MgbaSession *session);
const char *mgba_session_core_name(const MgbaSession *session);
const char *mgba_session_core_version(const MgbaSession *session);
unsigned mgba_session_get_pixel_format(const MgbaSession *session);
unsigned long long mgba_session_frame_count(const MgbaSession *session);
unsigned long long mgba_session_audio_frame_count(const MgbaSession *session);

/*
 * AV information is only valid after a successful load_game; the mGBA core
 * dereferences its game instance from retro_get_system_av_info().
 */
bool mgba_session_get_av_info(const MgbaSession *session,
                              struct retro_system_av_info *out_info);

/* Open a core library and initialize it with the frontend callbacks. */
MgbaResult mgba_session_initialize(MgbaSession *session,
                                   const char *core_library_path,
                                   const char *system_directory);

/*
 * Initialize from an already resolved API. The session copies the struct,
 * but does not take ownership of api->handle.
 */
MgbaResult mgba_session_initialize_with_api(MgbaSession *session,
                                            const MgbaCoreApi *api,
                                            const char *system_directory);

MgbaResult mgba_session_load_game(MgbaSession *session, const char *rom_path);
MgbaResult mgba_session_run_once(MgbaSession *session);
MgbaResult mgba_session_unload_game(MgbaSession *session);
MgbaResult mgba_session_shutdown(MgbaSession *session);

/*
 * Frame access for the render thread. acquire returns the pixel data pointer
 * of the latest complete frame (valid until the next acquire). The pointer is
 * owned by the session's framebuffer pool and must not be freed.
 */
bool mgba_session_acquire_frame(MgbaSession *session, const uint8_t **out_data,
                                unsigned *out_width, unsigned *out_height,
                                unsigned *out_format, unsigned long long *out_seq);
void mgba_session_release_frame(MgbaSession *session);
unsigned long long mgba_session_frames_dropped(const MgbaSession *session);

/*
 * Audio access for the playback thread. read_audio fills `out` with up to
 * max_frames interleaved stereo int16 frames and returns the count (0 when
 * the ring is empty). Safe to call from any thread.
 */
size_t mgba_session_read_audio(MgbaSession *session, int16_t *out,
                               size_t max_frames);
size_t mgba_session_audio_queued(const MgbaSession *session);
unsigned long long mgba_session_audio_underruns(const MgbaSession *session);
unsigned long long mgba_session_audio_overflows(const MgbaSession *session);
void mgba_session_reset_audio_stats(MgbaSession *session);

/*
 * Input state. Multiple sources (virtual, keyboard, gamepad) can hold the
 * same button; the button is pressed while any source holds it.
 */
void mgba_session_input_press(MgbaSession *session, unsigned button_id,
                              unsigned source_id);
void mgba_session_input_release(MgbaSession *session, unsigned button_id,
                                unsigned source_id);
void mgba_session_input_release_source(MgbaSession *session,
                                       unsigned source_id);
void mgba_session_input_reset(MgbaSession *session);

/*
 * Cheat codes. The Libretro cheat API is stateful: reset clears all cheats,
 * then each enabled code is added with cheat_set. Only valid after load_game
 * and must be called from the emulation thread.
 */
void mgba_session_cheat_reset(MgbaSession *session);
bool mgba_session_cheat_set(MgbaSession *session, unsigned index, bool enabled,
                            const char *code);

/*
 * SRAM / RTC access. Must be called from the emulation thread after
 * load_game. save_* copies core memory into the caller's buffer (caller
 * allocates at least *_size bytes); load_* writes caller data into core
 * memory.
 */
size_t mgba_session_sram_size(const MgbaSession *session);
bool mgba_session_save_sram(MgbaSession *session, void *out, size_t capacity);
bool mgba_session_load_sram(MgbaSession *session, const void *data, size_t size);

size_t mgba_session_rtc_size(const MgbaSession *session);
bool mgba_session_save_rtc(MgbaSession *session, void *out, size_t capacity);
bool mgba_session_load_rtc(MgbaSession *session, const void *data, size_t size);

/*
 * Savestate (serialize/unserialize). Must be called from the emulation
 * thread after load_game. serialize_size returns 0 when serialization is
 * unsupported.
 */
size_t mgba_session_serialize_size(MgbaSession *session);
bool mgba_session_serialize(MgbaSession *session, void *out, size_t capacity);
bool mgba_session_unserialize(MgbaSession *session, const void *data,
                              size_t size);

/*
 * Core options (v0 SET_VARIABLES path). option_count returns the number of
 * options the core registered; option_info fills key/values/current for
 * one option; set_option changes the current value index and flags the
 * core that an update is pending.
 */
int mgba_session_option_count(const MgbaSession *session);
bool mgba_session_option_info(const MgbaSession *session, int index,
                              const char **out_key, const char **out_values,
                              int *out_value_count, int *out_current);
bool mgba_session_option_value(const MgbaSession *session, int index,
                               int value_index, const char **out_value);
bool mgba_session_set_option(MgbaSession *session, int index, int value_index);

#ifdef __cplusplus
}
#endif

#endif /* MGBA_SESSION_H */
