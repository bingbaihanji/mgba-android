#include "mgba_session.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "framebuffer.h"
#include "audio_ring.h"
#include "input_state.h"

#ifdef __ANDROID__
#include <android/log.h>
#endif

/* Generous upper bound that covers any GB/GBA resolution. */
#define VIDEO_POOL_MAX_WIDTH 512
#define VIDEO_POOL_MAX_HEIGHT 512

/* ~250 ms of audio at 65536 Hz. Power of two for cheap wrapping. */
#define AUDIO_RING_CAPACITY 16384

#define MAX_CORE_OPTIONS 64
#define MAX_OPT_KEY 80
#define MAX_OPT_DESC 256
#define MAX_OPT_VALUES 16
#define MAX_OPT_VALUE_LEN 64

typedef struct CoreOption {
    char key[MAX_OPT_KEY];
    char values[MAX_OPT_VALUES][MAX_OPT_VALUE_LEN];
    int value_count;
    int current; /* index into values */
} CoreOption;

struct MgbaSession {
    MgbaCoreApi api;
    MgbaState state;
    bool has_api;
    bool initialized;
    bool owns_core;

    char error[256];
    char system_directory[512];
    char core_name[128];
    char core_version[128];

    enum retro_pixel_format pixel_format;
    struct retro_system_av_info av_info;
    bool has_av_info;

    unsigned long long video_frames;
    unsigned long long audio_frames;

    FramebufferPool *video_pool;
    AudioRing *audio_ring;
    InputState *input;

    CoreOption core_options[MAX_CORE_OPTIONS];
    int option_count;
    bool options_updated;
};

/*
 * Libretro callbacks have no userdata pointer, so exactly one session may be
 * active at a time. This is the same constraint the core itself has.
 */
static MgbaSession *g_active_session;

static void set_error(MgbaSession *session, const char *format, ...) {
    va_list args;
    if (!session) {
        return;
    }
    va_start(args, format);
    vsnprintf(session->error, sizeof(session->error), format, args);
    va_end(args);
}

static void clear_error(MgbaSession *session) {
    if (session) {
        session->error[0] = '\0';
    }
}

static void copy_string(char *destination, size_t size, const char *source) {
    size_t length;
    if (!destination || size == 0) {
        return;
    }
    if (!source) {
        destination[0] = '\0';
        return;
    }
    length = strlen(source);
    if (length >= size) {
        length = size - 1;
    }
    memcpy(destination, source, length);
    destination[length] = '\0';
}

static bool session_is_active(const MgbaSession *session) {
    return session && g_active_session == session;
}

static void session_video_refresh(const void *data, unsigned width,
                                  unsigned height, size_t pitch) {
    MgbaSession *session = g_active_session;
    if (!session) {
        return;
    }
    ++session->video_frames;
    if (session->video_pool) {
        framebuffer_pool_write(session->video_pool, data, width, height, pitch,
                               session->pixel_format);
    }
}

static void session_audio_sample(int16_t left, int16_t right) {
    MgbaSession *session = g_active_session;
    if (!session) {
        return;
    }
    ++session->audio_frames;
    if (session->audio_ring) {
        int16_t frame[2] = {left, right};
        audio_ring_write(session->audio_ring, frame, 1);
    }
}

static size_t session_audio_sample_batch(const int16_t *data, size_t frames) {
    MgbaSession *session = g_active_session;
    if (!session || frames == 0) {
        return 0;
    }
    if (data && session->audio_ring) {
        audio_ring_write(session->audio_ring, data, frames);
    }
    session->audio_frames += frames;
    return frames;
}

static void session_input_poll(void) {
}

static int16_t session_input_state(unsigned port, unsigned device,
                                   unsigned index, unsigned id) {
    MgbaSession *session = g_active_session;
    (void)port;
    (void)index;
    if (!session || !session->input) {
        return 0;
    }
    if (device == RETRO_DEVICE_JOYPAD) {
        return input_state_is_pressed(session->input, id) ? 1 : 0;
    }
    return 0;
}

static void session_log(enum retro_log_level level, const char *format, ...) {
    char message[256];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);

#ifdef __ANDROID__
    int priority;
    switch (level) {
    case RETRO_LOG_DEBUG:
        priority = ANDROID_LOG_DEBUG;
        break;
    case RETRO_LOG_WARN:
        priority = ANDROID_LOG_WARN;
        break;
    case RETRO_LOG_ERROR:
        priority = ANDROID_LOG_ERROR;
        break;
    case RETRO_LOG_INFO:
    default:
        priority = ANDROID_LOG_INFO;
        break;
    }
    __android_log_print(priority, "mgba-core", "%s", message);
#else
    const char *name;
    switch (level) {
    case RETRO_LOG_DEBUG:
        name = "debug";
        break;
    case RETRO_LOG_WARN:
        name = "warn";
        break;
    case RETRO_LOG_ERROR:
        name = "error";
        break;
    case RETRO_LOG_INFO:
    default:
        name = "info";
        break;
    }
    fprintf(stderr, "[mgba-core][%s] %s\n", name, message);
#endif
}

static bool session_environment(unsigned command, void *data) {
    MgbaSession *session = g_active_session;
    if (!session) {
        return false;
    }

    switch (command) {
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        if (!data) {
            return false;
        }
        session->pixel_format = *(const enum retro_pixel_format *)data;
        return true;

    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        if (!data || session->system_directory[0] == '\0') {
            return false;
        }
        *(const char **)data = session->system_directory;
        return true;

    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
        if (!data) {
            return false;
        }
        session->av_info = *(const struct retro_system_av_info *)data;
        session->has_av_info = true;
        return true;

    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        return true;

    case RETRO_ENVIRONMENT_SET_VARIABLES:
        if (!data) {
            return true; /* empty option list is valid */
        }
        /* Parse the core's option definitions into our store. */
        {
            const struct retro_variable *vars =
                (const struct retro_variable *)data;
            session->option_count = 0;
            while (vars->key && vars->value &&
                   session->option_count < MAX_CORE_OPTIONS) {
                CoreOption *opt =
                    &session->core_options[session->option_count];
                strncpy(opt->key, vars->key, MAX_OPT_KEY - 1);
                opt->key[MAX_OPT_KEY - 1] = '\0';

                /* value format: "Label;val1|val2|val3" */
                opt->value_count = 0;
                opt->current = 0;
                {
                    const char *sep = strchr(vars->value, ';');
                    const char *p = sep ? sep + 1 : vars->value;
                    while (*p && opt->value_count < MAX_OPT_VALUES) {
                        const char *bar = strchr(p, '|');
                        size_t len = bar ? (size_t)(bar - p) : strlen(p);
                        if (len >= MAX_OPT_VALUE_LEN) {
                            len = MAX_OPT_VALUE_LEN - 1;
                        }
                        memcpy(opt->values[opt->value_count], p, len);
                        opt->values[opt->value_count][len] = '\0';
                        ++opt->value_count;
                        if (!bar) {
                            break;
                        }
                        p = bar + 1;
                    }
                }
                ++session->option_count;
                ++vars;
            }
            session->options_updated = true;
        }
        return true;

    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
        /* Accept the newer interfaces but keep the v0 parse path. */
        return true;

    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
        if (!data) {
            return false;
        }
        /* 0 selects the v0 SET_VARIABLES path. */
        *(unsigned *)data = 0;
        return true;

    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        if (!data) {
            return false;
        }
        *(bool *)data = session->options_updated;
        session->options_updated = false;
        return true;

    case RETRO_ENVIRONMENT_GET_VARIABLE:
        if (!data) {
            return false;
        }
        {
            struct retro_variable *var = (struct retro_variable *)data;
            int i;
            for (i = 0; i < session->option_count; ++i) {
                if (strcmp(session->core_options[i].key, var->key) == 0) {
                    CoreOption *opt = &session->core_options[i];
                    var->value = opt->values[opt->current];
                    return true;
                }
            }
        }
        return false;

    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        if (!data) {
            return false;
        }
        ((struct retro_log_callback *)data)->log = session_log;
        return true;

    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
    case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
    case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
    case RETRO_ENVIRONMENT_GET_SENSOR_INTERFACE:
    case RETRO_ENVIRONMENT_GET_CAMERA_INTERFACE:
    default:
        return false;
    }
}

static MgbaResult fail_initialize(MgbaSession *session, MgbaResult result,
                                  const char *message) {
    if (!session) {
        return result;
    }
    set_error(session, "%s", message ? message : "initialization failed");
    if (session->initialized && session->api.deinit) {
        session->api.deinit();
    }
    if (g_active_session == session) {
        g_active_session = NULL;
    }
    session->has_api = false;
    session->initialized = false;
    session->owns_core = false;
    session->state = MGBA_STATE_ERROR;
    memset(&session->api, 0, sizeof(session->api));
    return result;
}

#define REQUIRE_API(field)                                                     \
    do {                                                                       \
        if (!api->field) {                                                     \
            return fail_initialize(session, MGBA_ERR_INVALID_CORE_API,         \
                                   "core API missing: " #field);               \
        }                                                                      \
    } while (0)

static void start_core(MgbaSession *session) {
    session->api.set_environment(session_environment);
    session->api.set_video_refresh(session_video_refresh);
    session->api.set_audio_sample(session_audio_sample);
    session->api.set_audio_sample_batch(session_audio_sample_batch);
    session->api.set_input_poll(session_input_poll);
    session->api.set_input_state(session_input_state);
    session->api.init();
    session->initialized = true;
}

MgbaSession *mgba_session_create(void) {
    MgbaSession *session = (MgbaSession *)calloc(1, sizeof(*session));
    if (!session) {
        return NULL;
    }
    session->state = MGBA_STATE_UNINITIALIZED;
    session->pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
    return session;
}

void mgba_session_destroy(MgbaSession *session) {
    if (!session) {
        return;
    }
    if (session->state != MGBA_STATE_UNINITIALIZED) {
        mgba_session_shutdown(session);
    }
    free(session);
}

MgbaState mgba_session_state(const MgbaSession *session) {
    return session ? session->state : MGBA_STATE_UNINITIALIZED;
}

const char *mgba_session_last_error(const MgbaSession *session) {
    return session ? session->error : NULL;
}

const char *mgba_session_core_name(const MgbaSession *session) {
    if (!session || session->core_name[0] == '\0') {
        return NULL;
    }
    return session->core_name;
}

const char *mgba_session_core_version(const MgbaSession *session) {
    if (!session || session->core_version[0] == '\0') {
        return NULL;
    }
    return session->core_version;
}

unsigned mgba_session_get_pixel_format(const MgbaSession *session) {
    return session ? (unsigned)session->pixel_format
                   : (unsigned)RETRO_PIXEL_FORMAT_0RGB1555;
}

unsigned long long mgba_session_frame_count(const MgbaSession *session) {
    return session ? session->video_frames : 0;
}

unsigned long long mgba_session_audio_frame_count(const MgbaSession *session) {
    return session ? session->audio_frames : 0;
}

bool mgba_session_get_av_info(const MgbaSession *session,
                              struct retro_system_av_info *out_info) {
    if (!session || !out_info || !session->has_av_info) {
        return false;
    }
    *out_info = session->av_info;
    return true;
}

MgbaResult mgba_session_initialize_with_api(MgbaSession *session,
                                            const MgbaCoreApi *api,
                                            const char *system_directory) {
    struct retro_system_info system_info;
    unsigned api_version;

    if (!session || !api) {
        return MGBA_ERR_INVALID_ARGUMENT;
    }
    if (session->state != MGBA_STATE_UNINITIALIZED &&
        session->state != MGBA_STATE_ERROR) {
        set_error(session, "session already initialized");
        return MGBA_ERR_INVALID_STATE;
    }
    if (g_active_session && !session_is_active(session)) {
        set_error(session, "another core session is active");
        return MGBA_ERR_INVALID_STATE;
    }

    REQUIRE_API(api_version);
    REQUIRE_API(set_environment);
    REQUIRE_API(set_video_refresh);
    REQUIRE_API(set_audio_sample);
    REQUIRE_API(set_audio_sample_batch);
    REQUIRE_API(set_input_poll);
    REQUIRE_API(set_input_state);
    REQUIRE_API(init);
    REQUIRE_API(deinit);
    REQUIRE_API(get_system_info);
    REQUIRE_API(get_system_av_info);
    REQUIRE_API(load_game);
    REQUIRE_API(unload_game);
    REQUIRE_API(run);

    api_version = api->api_version();
    if (api_version != RETRO_API_VERSION) {
        return fail_initialize(session, MGBA_ERR_INVALID_CORE_API,
                               "core API version mismatch");
    }

    session->api = *api;
    session->has_api = true;
    session->initialized = false;
    session->owns_core = false;
    session->state = MGBA_STATE_ERROR;
    session->video_frames = 0;
    session->audio_frames = 0;
    session->has_av_info = false;
    session->pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
    session->core_name[0] = '\0';
    session->core_version[0] = '\0';
    session->system_directory[0] = '\0';
    if (system_directory) {
        copy_string(session->system_directory, sizeof(session->system_directory),
                    system_directory);
    }
    clear_error(session);

    /* Create buffers before any callback can fire. */
    if (!session->video_pool) {
        session->video_pool = framebuffer_pool_create(VIDEO_POOL_MAX_WIDTH,
                                                      VIDEO_POOL_MAX_HEIGHT);
        if (!session->video_pool) {
            return fail_initialize(session, MGBA_ERR_OUT_OF_MEMORY,
                                   "failed to allocate video framebuffer pool");
        }
    }
    if (!session->audio_ring) {
        session->audio_ring = audio_ring_create(AUDIO_RING_CAPACITY);
        if (!session->audio_ring) {
            return fail_initialize(session, MGBA_ERR_OUT_OF_MEMORY,
                                   "failed to allocate audio ring buffer");
        }
    }
    if (!session->input) {
        session->input = input_state_create();
        if (!session->input) {
            return fail_initialize(session, MGBA_ERR_OUT_OF_MEMORY,
                                   "failed to allocate input state");
        }
    }

    g_active_session = session;
    start_core(session);

    memset(&system_info, 0, sizeof(system_info));
    session->api.get_system_info(&system_info);
    copy_string(session->core_name, sizeof(session->core_name),
                system_info.library_name);
    copy_string(session->core_version, sizeof(session->core_version),
                system_info.library_version);

    /*
     * Do not call retro_get_system_av_info() here: mGBA resolves its game
     * instance in retro_load_game(), so AV info is only valid afterwards.
     */
    memset(&session->av_info, 0, sizeof(session->av_info));
    session->has_av_info = false;

    session->state = MGBA_STATE_INITIALIZED;
    return MGBA_OK;
}

#undef REQUIRE_API

MgbaResult mgba_session_initialize(MgbaSession *session,
                                   const char *core_library_path,
                                   const char *system_directory) {
    MgbaCoreApi api;
    MgbaResult result;

    if (!session || !core_library_path) {
        return MGBA_ERR_INVALID_ARGUMENT;
    }
    if (session->state != MGBA_STATE_UNINITIALIZED &&
        session->state != MGBA_STATE_ERROR) {
        set_error(session, "session already initialized");
        return MGBA_ERR_INVALID_STATE;
    }
    if (g_active_session && !session_is_active(session)) {
        set_error(session, "another core session is active");
        return MGBA_ERR_INVALID_STATE;
    }

    result = mgba_core_open(core_library_path, &api, session->error,
                            sizeof(session->error));
    if (result != MGBA_OK) {
        session->state = MGBA_STATE_ERROR;
        return result;
    }

    result = mgba_session_initialize_with_api(session, &api, system_directory);
    if (result != MGBA_OK) {
        mgba_core_close(&api);
        return result;
    }

    session->owns_core = true;
    return MGBA_OK;
}

MgbaResult mgba_session_load_game(MgbaSession *session, const char *rom_path) {
    struct retro_game_info game_info;

    if (!session || !rom_path) {
        return MGBA_ERR_INVALID_ARGUMENT;
    }
    if (session->state != MGBA_STATE_INITIALIZED || !session->has_api) {
        set_error(session, "cannot load a game in the current state");
        return MGBA_ERR_INVALID_STATE;
    }

    /*
     * unload_game() performs a full retro_deinit() so the core frees its
     * frontend buffers before the next load; re-init lazily here.
     */
    if (!session->initialized) {
        start_core(session);
    }

    memset(&game_info, 0, sizeof(game_info));
    game_info.path = rom_path;
    game_info.data = NULL;
    game_info.size = 0;
    game_info.meta = NULL;

    if (!session->api.load_game(&game_info)) {
        set_error(session, "core rejected ROM: %s", rom_path);
        return MGBA_ERR_CORE_LOAD_GAME;
    }

    memset(&session->av_info, 0, sizeof(session->av_info));
    session->api.get_system_av_info(&session->av_info);
    session->has_av_info = true;

    clear_error(session);
    session->state = MGBA_STATE_GAME_LOADED;
    return MGBA_OK;
}

MgbaResult mgba_session_run_once(MgbaSession *session) {
    if (!session) {
        return MGBA_ERR_INVALID_ARGUMENT;
    }
    if (!session->has_api ||
        (session->state != MGBA_STATE_GAME_LOADED &&
         session->state != MGBA_STATE_RUNNING)) {
        set_error(session, "cannot run without a loaded game");
        return MGBA_ERR_INVALID_STATE;
    }

    session->api.run();
    clear_error(session);
    session->state = MGBA_STATE_RUNNING;
    return MGBA_OK;
}

MgbaResult mgba_session_unload_game(MgbaSession *session) {
    if (!session) {
        return MGBA_ERR_INVALID_ARGUMENT;
    }
    if (!session->has_api ||
        (session->state != MGBA_STATE_GAME_LOADED &&
         session->state != MGBA_STATE_RUNNING)) {
        set_error(session, "no game is loaded");
        return MGBA_ERR_INVALID_STATE;
    }

    session->api.unload_game();
    /*
     * retro_unload_game() frees the game core but not the frontend buffers
     * allocated by retro_load_game(). retro_deinit() owns those; doing a full
     * init/deinit cycle per game keeps reloads leak-free.
     */
    if (session->initialized && session->api.deinit) {
        session->api.deinit();
        session->initialized = false;
    }
    memset(&session->av_info, 0, sizeof(session->av_info));
    session->has_av_info = false;
    clear_error(session);
    session->state = MGBA_STATE_INITIALIZED;
    return MGBA_OK;
}

MgbaResult mgba_session_shutdown(MgbaSession *session) {
    if (!session) {
        return MGBA_ERR_INVALID_ARGUMENT;
    }
    if (session->state == MGBA_STATE_UNINITIALIZED) {
        return MGBA_OK;
    }

    if (session->has_api &&
        (session->state == MGBA_STATE_GAME_LOADED ||
         session->state == MGBA_STATE_RUNNING)) {
        session->api.unload_game();
    }
    if (session->initialized && session->api.deinit) {
        session->api.deinit();
    }
    if (session->owns_core) {
        mgba_core_close(&session->api);
    }
    if (g_active_session == session) {
        g_active_session = NULL;
    }

    memset(&session->api, 0, sizeof(session->api));
    session->has_api = false;
    session->initialized = false;
    session->owns_core = false;
    session->core_name[0] = '\0';
    session->core_version[0] = '\0';
    session->has_av_info = false;
    if (session->video_pool) {
        framebuffer_pool_destroy(session->video_pool);
        session->video_pool = NULL;
    }
    if (session->audio_ring) {
        audio_ring_destroy(session->audio_ring);
        session->audio_ring = NULL;
    }
    if (session->input) {
        input_state_destroy(session->input);
        session->input = NULL;
    }
    clear_error(session);
    session->state = MGBA_STATE_UNINITIALIZED;
    return MGBA_OK;
}

bool mgba_session_acquire_frame(MgbaSession *session, const uint8_t **out_data,
                                unsigned *out_width, unsigned *out_height,
                                unsigned *out_format,
                                unsigned long long *out_seq) {
    enum retro_pixel_format fmt;
    uint64_t seq;
    bool ok;

    if (!session || !session->video_pool || !out_data) {
        return false;
    }

    ok = framebuffer_pool_acquire(session->video_pool, out_data, out_width,
                                  out_height, &fmt, &seq);
    if (!ok) {
        return false;
    }
    if (out_format) {
        *out_format = (unsigned)fmt;
    }
    if (out_seq) {
        *out_seq = (unsigned long long)seq;
    }
    return true;
}

void mgba_session_release_frame(MgbaSession *session) {
    if (session && session->video_pool) {
        framebuffer_pool_release(session->video_pool);
    }
}

unsigned long long mgba_session_frames_dropped(const MgbaSession *session) {
    return session && session->video_pool
               ? framebuffer_pool_dropped(session->video_pool)
               : 0;
}

size_t mgba_session_read_audio(MgbaSession *session, int16_t *out,
                               size_t max_frames) {
    if (!session || !session->audio_ring || !out) {
        return 0;
    }
    return audio_ring_read(session->audio_ring, out, max_frames);
}

size_t mgba_session_audio_queued(const MgbaSession *session) {
    return session && session->audio_ring ? audio_ring_queued(session->audio_ring)
                                          : 0;
}

unsigned long long mgba_session_audio_underruns(const MgbaSession *session) {
    return session && session->audio_ring
               ? audio_ring_underruns(session->audio_ring)
               : 0;
}

unsigned long long mgba_session_audio_overflows(const MgbaSession *session) {
    return session && session->audio_ring
               ? audio_ring_overflows(session->audio_ring)
               : 0;
}

void mgba_session_reset_audio_stats(MgbaSession *session) {
    if (session && session->audio_ring) {
        audio_ring_reset_stats(session->audio_ring);
    }
}

void mgba_session_input_press(MgbaSession *session, unsigned button_id,
                              unsigned source_id) {
    if (session && session->input) {
        input_state_press(session->input, button_id, source_id);
    }
}

void mgba_session_input_release(MgbaSession *session, unsigned button_id,
                                unsigned source_id) {
    if (session && session->input) {
        input_state_release(session->input, button_id, source_id);
    }
}

void mgba_session_input_release_source(MgbaSession *session,
                                       unsigned source_id) {
    if (session && session->input) {
        input_state_release_source(session->input, source_id);
    }
}

void mgba_session_input_reset(MgbaSession *session) {
    if (session && session->input) {
        input_state_reset(session->input);
    }
}

void mgba_session_cheat_reset(MgbaSession *session) {
    if (!session || !session->has_api || !session->api.cheat_reset) {
        return;
    }
    if (session->state != MGBA_STATE_GAME_LOADED &&
        session->state != MGBA_STATE_RUNNING) {
        return;
    }
    session->api.cheat_reset();
}

bool mgba_session_cheat_set(MgbaSession *session, unsigned index,
                            bool enabled, const char *code) {
    if (!session || !session->has_api || !session->api.cheat_set || !code) {
        return false;
    }
    if (session->state != MGBA_STATE_GAME_LOADED &&
        session->state != MGBA_STATE_RUNNING) {
        return false;
    }
    session->api.cheat_set(index, enabled, code);
    return true;
}

size_t mgba_session_sram_size(const MgbaSession *session) {
    if (!session || !session->has_api ||
        (session->state != MGBA_STATE_GAME_LOADED &&
         session->state != MGBA_STATE_RUNNING)) {
        return 0;
    }
    return session->api.get_memory_size(RETRO_MEMORY_SAVE_RAM);
}

bool mgba_session_save_sram(MgbaSession *session, void *out, size_t capacity) {
    const void *src;
    size_t size;

    if (!session || !out || !session->has_api ||
        (session->state != MGBA_STATE_GAME_LOADED &&
         session->state != MGBA_STATE_RUNNING)) {
        return false;
    }
    src = session->api.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size = session->api.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!src || size == 0 || size > capacity) {
        return false;
    }
    memcpy(out, src, size);
    return true;
}

bool mgba_session_load_sram(MgbaSession *session, const void *data,
                            size_t size) {
    void *dst;
    size_t core_size;

    if (!session || !data || !session->has_api ||
        (session->state != MGBA_STATE_GAME_LOADED &&
         session->state != MGBA_STATE_RUNNING)) {
        return false;
    }
    dst = session->api.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    core_size = session->api.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!dst || core_size == 0) {
        return false;
    }
    if (size > core_size) {
        size = core_size;
    }
    memcpy(dst, data, size);
    /* Zero any remaining bytes if the file is shorter than the SRAM region. */
    if (size < core_size) {
        memset((uint8_t *)dst + size, 0, core_size - size);
    }
    return true;
}

size_t mgba_session_rtc_size(const MgbaSession *session) {
    if (!session || !session->has_api ||
        (session->state != MGBA_STATE_GAME_LOADED &&
         session->state != MGBA_STATE_RUNNING)) {
        return 0;
    }
    return session->api.get_memory_size(RETRO_MEMORY_RTC);
}

bool mgba_session_save_rtc(MgbaSession *session, void *out, size_t capacity) {
    const void *src;
    size_t size;

    if (!session || !out || !session->has_api ||
        (session->state != MGBA_STATE_GAME_LOADED &&
         session->state != MGBA_STATE_RUNNING)) {
        return false;
    }
    src = session->api.get_memory_data(RETRO_MEMORY_RTC);
    size = session->api.get_memory_size(RETRO_MEMORY_RTC);
    if (!src || size == 0 || size > capacity) {
        return false;
    }
    memcpy(out, src, size);
    return true;
}

bool mgba_session_load_rtc(MgbaSession *session, const void *data,
                           size_t size) {
    void *dst;
    size_t core_size;

    if (!session || !data || !session->has_api ||
        (session->state != MGBA_STATE_GAME_LOADED &&
         session->state != MGBA_STATE_RUNNING)) {
        return false;
    }
    dst = session->api.get_memory_data(RETRO_MEMORY_RTC);
    core_size = session->api.get_memory_size(RETRO_MEMORY_RTC);
    if (!dst || core_size == 0) {
        return false;
    }
    if (size > core_size) {
        size = core_size;
    }
    memcpy(dst, data, size);
    if (size < core_size) {
        memset((uint8_t *)dst + size, 0, core_size - size);
    }
    return true;
}

size_t mgba_session_serialize_size(MgbaSession *session) {
    if (!session || !session->has_api ||
        (session->state != MGBA_STATE_GAME_LOADED &&
         session->state != MGBA_STATE_RUNNING)) {
        return 0;
    }
    return session->api.serialize_size();
}

bool mgba_session_serialize(MgbaSession *session, void *out, size_t capacity) {
    size_t needed;

    if (!session || !out || !session->has_api ||
        (session->state != MGBA_STATE_GAME_LOADED &&
         session->state != MGBA_STATE_RUNNING)) {
        return false;
    }
    needed = session->api.serialize_size();
    if (needed == 0 || needed > capacity) {
        return false;
    }
    return session->api.serialize(out, needed);
}

bool mgba_session_unserialize(MgbaSession *session, const void *data,
                              size_t size) {
    if (!session || !data || size == 0 || !session->has_api ||
        (session->state != MGBA_STATE_GAME_LOADED &&
         session->state != MGBA_STATE_RUNNING)) {
        return false;
    }
    return session->api.unserialize(data, size);
}

int mgba_session_option_count(const MgbaSession *session) {
    return session ? session->option_count : 0;
}

bool mgba_session_option_info(const MgbaSession *session, int index,
                              const char **out_key, const char **out_values,
                              int *out_value_count, int *out_current) {
    const CoreOption *opt;
    if (!session || index < 0 || index >= session->option_count) {
        return false;
    }
    opt = &session->core_options[index];
    if (out_key) {
        *out_key = opt->key;
    }
    if (out_values) {
        *out_values = &opt->values[0][0];
    }
    if (out_value_count) {
        *out_value_count = opt->value_count;
    }
    if (out_current) {
        *out_current = opt->current;
    }
    return true;
}

bool mgba_session_option_value(const MgbaSession *session, int index,
                               int value_index, const char **out_value) {
    const CoreOption *opt;
    if (!session || index < 0 || index >= session->option_count) {
        return false;
    }
    opt = &session->core_options[index];
    if (value_index < 0 || value_index >= opt->value_count) {
        return false;
    }
    if (out_value) {
        *out_value = opt->values[value_index];
    }
    return true;
}

bool mgba_session_set_option(MgbaSession *session, int index, int value_index) {
    CoreOption *opt;
    if (!session || index < 0 || index >= session->option_count) {
        return false;
    }
    opt = &session->core_options[index];
    if (value_index < 0 || value_index >= opt->value_count) {
        return false;
    }
    opt->current = value_index;
    session->options_updated = true;
    return true;
}
