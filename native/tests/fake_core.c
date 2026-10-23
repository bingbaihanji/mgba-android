/*
 * Fake Libretro core used by the host loader/session integration tests.
 * It exports the real retro_* ABI and records nothing; assertions about
 * session behaviour live in test_session.c, which injects its own API.
 */
#include <stdbool.h>
#include <stddef.h>
#include <string.h>

#include <libretro.h>

static retro_environment_t g_environment;
static retro_video_refresh_t g_video;
static retro_audio_sample_t g_audio_sample;
static retro_audio_sample_batch_t g_audio_batch;

unsigned retro_api_version(void) {
    return RETRO_API_VERSION;
}

void retro_set_environment(retro_environment_t cb) {
    g_environment = cb;
}

void retro_set_video_refresh(retro_video_refresh_t cb) {
    g_video = cb;
}

void retro_set_audio_sample(retro_audio_sample_t cb) {
    g_audio_sample = cb;
}

void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb) {
    g_audio_batch = cb;
}

void retro_set_input_poll(retro_input_poll_t cb) {
    (void)cb;
}

void retro_set_input_state(retro_input_state_t cb) {
    (void)cb;
}

void retro_init(void) {
    enum retro_pixel_format format = RETRO_PIXEL_FORMAT_RGB565;
    if (g_environment) {
        g_environment(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &format);
    }
}

void retro_deinit(void) {
}

void retro_get_system_info(struct retro_system_info *info) {
    memset(info, 0, sizeof(*info));
    info->library_name = "FakeCore";
    info->library_version = "1.0-test";
    info->valid_extensions = "gba";
    info->need_fullpath = false;
}

void retro_get_system_av_info(struct retro_system_av_info *info) {
    memset(info, 0, sizeof(*info));
    info->geometry.base_width = 240;
    info->geometry.base_height = 160;
    info->geometry.max_width = 256;
    info->geometry.max_height = 224;
    info->geometry.aspect_ratio = 240.0f / 160.0f;
    info->timing.fps = 60.0f;
    info->timing.sample_rate = 65536.0;
}

bool retro_load_game(const struct retro_game_info *game) {
    return game != NULL && game->path != NULL;
}

void retro_unload_game(void) {
}

void retro_run(void) {
    if (g_video) {
        g_video(NULL, 240, 160, 512);
    }
    if (g_audio_batch) {
        g_audio_batch(NULL, 0);
    }
}

void retro_reset(void) {
}

size_t retro_serialize_size(void) {
    return 0;
}

bool retro_serialize(void *data, size_t size) {
    (void)data;
    (void)size;
    return false;
}

bool retro_unserialize(const void *data, size_t size) {
    (void)data;
    (void)size;
    return false;
}

void *retro_get_memory_data(unsigned id) {
    (void)id;
    return NULL;
}

size_t retro_get_memory_size(unsigned id) {
    (void)id;
    return 0;
}

void retro_cheat_reset(void) {
}

void retro_cheat_set(unsigned index, bool enabled, const char *code) {
    (void)index;
    (void)enabled;
    (void)code;
}

void retro_set_controller_port_device(unsigned port, unsigned device) {
    (void)port;
    (void)device;
}

bool retro_load_game_special(unsigned game_type,
                             const struct retro_game_info *info,
                             size_t num_info) {
    (void)game_type;
    (void)info;
    (void)num_info;
    return false;
}

unsigned retro_get_region(void) {
    return RETRO_REGION_NTSC;
}
