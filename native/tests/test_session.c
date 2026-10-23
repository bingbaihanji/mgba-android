#include "mgba_session.h"

#include <string.h>

#include "test_common.h"

static int g_init_calls;
static int g_deinit_calls;
static int g_load_calls;
static int g_unload_calls;
static int g_run_calls;
static int g_av_info_calls;
static char g_loaded_path[512];

static retro_environment_t g_environment;
static retro_video_refresh_t g_video;
static retro_audio_sample_t g_audio_sample;
static retro_audio_sample_batch_t g_audio_batch;
static retro_input_poll_t g_input_poll;
static retro_input_state_t g_input_state;

static void reset_fakes(void) {
    g_init_calls = 0;
    g_deinit_calls = 0;
    g_load_calls = 0;
    g_unload_calls = 0;
    g_run_calls = 0;
    g_av_info_calls = 0;
    memset(g_loaded_path, 0, sizeof(g_loaded_path));
    g_environment = NULL;
    g_video = NULL;
    g_audio_sample = NULL;
    g_audio_batch = NULL;
    g_input_poll = NULL;
    g_input_state = NULL;
}

static unsigned fake_api_version(void) {
    return RETRO_API_VERSION;
}

static unsigned fake_bad_api_version(void) {
    return RETRO_API_VERSION + 1;
}

static void fake_set_environment(retro_environment_t cb) {
    g_environment = cb;
}

static void fake_set_video_refresh(retro_video_refresh_t cb) {
    g_video = cb;
}

static void fake_set_audio_sample(retro_audio_sample_t cb) {
    g_audio_sample = cb;
}

static void fake_set_audio_sample_batch(retro_audio_sample_batch_t cb) {
    g_audio_batch = cb;
}

static void fake_set_input_poll(retro_input_poll_t cb) {
    g_input_poll = cb;
}

static void fake_set_input_state(retro_input_state_t cb) {
    g_input_state = cb;
}

static void fake_init(void) {
    ++g_init_calls;
    enum retro_pixel_format format = RETRO_PIXEL_FORMAT_RGB565;
    if (g_environment) {
        g_environment(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &format);
    }
}

static void fake_deinit(void) {
    ++g_deinit_calls;
}

static void fake_get_system_info(struct retro_system_info *info) {
    memset(info, 0, sizeof(*info));
    info->library_name = "FakeCore";
    info->library_version = "1.0-test";
    info->valid_extensions = "gba";
    info->need_fullpath = false;
}

static void fake_get_system_av_info(struct retro_system_av_info *info) {
    ++g_av_info_calls;
    memset(info, 0, sizeof(*info));
    info->geometry.base_width = 240;
    info->geometry.base_height = 160;
    info->geometry.max_width = 256;
    info->geometry.max_height = 224;
    info->geometry.aspect_ratio = 240.0f / 160.0f;
    info->timing.fps = 60.0f;
    info->timing.sample_rate = 65536.0;
}

static bool fake_load_game(const struct retro_game_info *game) {
    ++g_load_calls;
    if (!game || !game->path) {
        return false;
    }
    snprintf(g_loaded_path, sizeof(g_loaded_path), "%s", game->path);
    return strcmp(game->path, "bad.gba") != 0;
}

static void fake_unload_game(void) {
    ++g_unload_calls;
}

static void fake_run(void) {
    ++g_run_calls;
}

static void fake_reset(void) {
}

static size_t fake_serialize_size(void) {
    return 0;
}

static bool fake_serialize(void *data, size_t size) {
    (void)data;
    (void)size;
    return false;
}

static bool fake_unserialize(const void *data, size_t size) {
    (void)data;
    (void)size;
    return false;
}

static void *fake_get_memory_data(unsigned id) {
    (void)id;
    return NULL;
}

static size_t fake_get_memory_size(unsigned id) {
    (void)id;
    return 0;
}

static MgbaCoreApi make_api(void) {
    MgbaCoreApi api;
    memset(&api, 0, sizeof(api));
    api.api_version = fake_api_version;
    api.set_environment = fake_set_environment;
    api.set_video_refresh = fake_set_video_refresh;
    api.set_audio_sample = fake_set_audio_sample;
    api.set_audio_sample_batch = fake_set_audio_sample_batch;
    api.set_input_poll = fake_set_input_poll;
    api.set_input_state = fake_set_input_state;
    api.init = fake_init;
    api.deinit = fake_deinit;
    api.get_system_info = fake_get_system_info;
    api.get_system_av_info = fake_get_system_av_info;
    api.load_game = fake_load_game;
    api.unload_game = fake_unload_game;
    api.run = fake_run;
    api.reset = fake_reset;
    api.serialize_size = fake_serialize_size;
    api.serialize = fake_serialize;
    api.unserialize = fake_unserialize;
    api.get_memory_data = fake_get_memory_data;
    api.get_memory_size = fake_get_memory_size;
    return api;
}

static void test_full_lifecycle(void) {
    reset_fakes();
    MgbaSession *session = mgba_session_create();
    CHECK(session != NULL);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_UNINITIALIZED);
    CHECK(mgba_session_core_name(session) == NULL);
    CHECK(mgba_session_core_version(session) == NULL);

    MgbaCoreApi api = make_api();
    CHECK_EQ_INT(mgba_session_initialize_with_api(session, &api, "/system"),
                 MGBA_OK);
    CHECK_EQ_INT(g_init_calls, 1);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_INITIALIZED);
    CHECK_STR_EQ(mgba_session_core_name(session), "FakeCore");
    CHECK_STR_EQ(mgba_session_core_version(session), "1.0-test");
    CHECK_EQ_INT(mgba_session_get_pixel_format(session),
                 RETRO_PIXEL_FORMAT_RGB565);
    CHECK_EQ_INT(g_av_info_calls, 0);

    CHECK_EQ_INT(mgba_session_load_game(session, "game.gba"), MGBA_OK);
    CHECK_EQ_INT(g_load_calls, 1);
    CHECK_STR_EQ(g_loaded_path, "game.gba");
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_GAME_LOADED);
    CHECK_EQ_INT(g_av_info_calls, 1);

    struct retro_system_av_info av_info;
    memset(&av_info, 0, sizeof(av_info));
    CHECK(mgba_session_get_av_info(session, &av_info));
    CHECK_EQ_INT(av_info.geometry.base_width, 240);
    CHECK_EQ_INT(av_info.geometry.base_height, 160);
    CHECK_EQ_INT(av_info.geometry.max_width, 256);
    CHECK_EQ_INT(av_info.geometry.max_height, 224);
    CHECK(av_info.timing.sample_rate == 65536.0);

    CHECK_EQ_INT(mgba_session_run_once(session), MGBA_OK);
    CHECK_EQ_INT(g_run_calls, 1);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_RUNNING);

    CHECK_EQ_INT(mgba_session_unload_game(session), MGBA_OK);
    CHECK_EQ_INT(g_unload_calls, 1);
    CHECK_EQ_INT(g_deinit_calls, 1);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_INITIALIZED);
    CHECK(!mgba_session_get_av_info(session, &av_info));

    CHECK_EQ_INT(mgba_session_shutdown(session), MGBA_OK);
    CHECK_EQ_INT(g_deinit_calls, 1);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_UNINITIALIZED);

    CHECK_EQ_INT(mgba_session_shutdown(session), MGBA_OK);
    CHECK_EQ_INT(g_deinit_calls, 1);

    mgba_session_destroy(session);
    CHECK_EQ_INT(g_deinit_calls, 1);
}

static void test_initialize_twice_is_invalid(void) {
    reset_fakes();
    MgbaSession *session = mgba_session_create();
    MgbaCoreApi api = make_api();
    CHECK_EQ_INT(mgba_session_initialize_with_api(session, &api, ""), MGBA_OK);
    CHECK_EQ_INT(mgba_session_initialize_with_api(session, &api, ""),
                 MGBA_ERR_INVALID_STATE);
    CHECK_EQ_INT(g_init_calls, 1);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_INITIALIZED);
    mgba_session_destroy(session);
}

static void test_run_before_load_is_invalid(void) {
    reset_fakes();
    MgbaSession *session = mgba_session_create();
    MgbaCoreApi api = make_api();
    CHECK_EQ_INT(mgba_session_initialize_with_api(session, &api, ""), MGBA_OK);
    CHECK_EQ_INT(mgba_session_run_once(session), MGBA_ERR_INVALID_STATE);
    CHECK_EQ_INT(g_run_calls, 0);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_INITIALIZED);
    mgba_session_destroy(session);
}

static void test_load_failure_keeps_session_usable(void) {
    reset_fakes();
    MgbaSession *session = mgba_session_create();
    MgbaCoreApi api = make_api();
    CHECK_EQ_INT(mgba_session_initialize_with_api(session, &api, ""), MGBA_OK);
    CHECK_EQ_INT(mgba_session_load_game(session, "bad.gba"),
                 MGBA_ERR_CORE_LOAD_GAME);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_INITIALIZED);
    CHECK_EQ_INT(g_av_info_calls, 0);
    CHECK_NON_EMPTY(mgba_session_last_error(session));
    CHECK_EQ_INT(mgba_session_unload_game(session), MGBA_ERR_INVALID_STATE);
    CHECK_EQ_INT(mgba_session_load_game(session, "good.gba"), MGBA_OK);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_GAME_LOADED);
    CHECK(mgba_session_last_error(session)[0] == '\0');
    mgba_session_destroy(session);
}

static void test_reload_after_unload_reinitializes_core(void) {
    reset_fakes();
    MgbaSession *session = mgba_session_create();
    MgbaCoreApi api = make_api();
    CHECK_EQ_INT(mgba_session_initialize_with_api(session, &api, ""), MGBA_OK);
    CHECK_EQ_INT(g_init_calls, 1);
    CHECK_EQ_INT(mgba_session_load_game(session, "one.gba"), MGBA_OK);

    CHECK_EQ_INT(mgba_session_unload_game(session), MGBA_OK);
    CHECK_EQ_INT(g_unload_calls, 1);
    CHECK_EQ_INT(g_deinit_calls, 1);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_INITIALIZED);

    CHECK_EQ_INT(mgba_session_load_game(session, "two.gba"), MGBA_OK);
    CHECK_EQ_INT(g_init_calls, 2);
    CHECK_STR_EQ(g_loaded_path, "two.gba");
    CHECK_EQ_INT(g_av_info_calls, 2);

    CHECK_EQ_INT(mgba_session_run_once(session), MGBA_OK);
    CHECK_EQ_INT(mgba_session_shutdown(session), MGBA_OK);
    CHECK_EQ_INT(g_unload_calls, 2);
    CHECK_EQ_INT(g_deinit_calls, 2);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_UNINITIALIZED);
    mgba_session_destroy(session);
}

static void test_shutdown_while_running(void) {
    reset_fakes();
    MgbaSession *session = mgba_session_create();
    MgbaCoreApi api = make_api();
    CHECK_EQ_INT(mgba_session_initialize_with_api(session, &api, ""), MGBA_OK);
    CHECK_EQ_INT(mgba_session_load_game(session, "game.gba"), MGBA_OK);
    CHECK_EQ_INT(mgba_session_run_once(session), MGBA_OK);

    CHECK_EQ_INT(mgba_session_shutdown(session), MGBA_OK);
    CHECK_EQ_INT(g_unload_calls, 1);
    CHECK_EQ_INT(g_deinit_calls, 1);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_UNINITIALIZED);
    mgba_session_destroy(session);
}

static void test_second_session_is_rejected(void) {
    reset_fakes();
    MgbaSession *first = mgba_session_create();
    MgbaSession *second = mgba_session_create();
    MgbaCoreApi api = make_api();

    CHECK_EQ_INT(mgba_session_initialize_with_api(first, &api, ""), MGBA_OK);
    CHECK_EQ_INT(mgba_session_initialize_with_api(second, &api, ""),
                 MGBA_ERR_INVALID_STATE);
    CHECK_EQ_INT(mgba_session_state(second), MGBA_STATE_UNINITIALIZED);
    CHECK_EQ_INT(mgba_session_state(first), MGBA_STATE_INITIALIZED);

    CHECK_EQ_INT(mgba_session_shutdown(first), MGBA_OK);
    CHECK_EQ_INT(mgba_session_initialize_with_api(second, &api, ""), MGBA_OK);
    CHECK_EQ_INT(mgba_session_shutdown(second), MGBA_OK);
    mgba_session_destroy(first);
    mgba_session_destroy(second);
}

static void test_path_initialize_on_live_session_is_invalid(void) {
    reset_fakes();
    MgbaSession *session = mgba_session_create();
    MgbaCoreApi api = make_api();
    CHECK_EQ_INT(mgba_session_initialize_with_api(session, &api, ""), MGBA_OK);

    CHECK_EQ_INT(mgba_session_initialize(session, "missing-core.so", ""),
                 MGBA_ERR_INVALID_STATE);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_INITIALIZED);
    CHECK_EQ_INT(g_init_calls, 1);

    CHECK_EQ_INT(mgba_session_shutdown(session), MGBA_OK);
    mgba_session_destroy(session);
}

static void test_unload_without_game_is_invalid(void) {
    reset_fakes();
    MgbaSession *session = mgba_session_create();
    MgbaCoreApi api = make_api();
    CHECK_EQ_INT(mgba_session_initialize_with_api(session, &api, ""), MGBA_OK);
    CHECK_EQ_INT(mgba_session_unload_game(session), MGBA_ERR_INVALID_STATE);
    CHECK_EQ_INT(g_unload_calls, 0);
    mgba_session_destroy(session);
}

static void test_invalid_api_enters_error_and_can_recover(void) {
    reset_fakes();
    MgbaSession *session = mgba_session_create();

    MgbaCoreApi bad = make_api();
    bad.init = NULL;
    CHECK_EQ_INT(mgba_session_initialize_with_api(session, &bad, ""),
                 MGBA_ERR_INVALID_CORE_API);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_ERROR);
    CHECK_NON_EMPTY(mgba_session_last_error(session));

    MgbaCoreApi wrong_version = make_api();
    wrong_version.api_version = fake_bad_api_version;
    CHECK_EQ_INT(mgba_session_initialize_with_api(session, &wrong_version, ""),
                 MGBA_ERR_INVALID_CORE_API);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_ERROR);

    MgbaCoreApi good = make_api();
    CHECK_EQ_INT(mgba_session_initialize_with_api(session, &good, ""), MGBA_OK);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_INITIALIZED);
    CHECK_EQ_INT(g_init_calls, 1);
    mgba_session_destroy(session);
}

static void test_invalid_arguments(void) {
    reset_fakes();
    MgbaCoreApi api = make_api();
    CHECK_EQ_INT(mgba_session_initialize_with_api(NULL, &api, ""),
                 MGBA_ERR_INVALID_ARGUMENT);
    MgbaSession *session = mgba_session_create();
    CHECK_EQ_INT(mgba_session_initialize_with_api(session, NULL, ""),
                 MGBA_ERR_INVALID_ARGUMENT);
    CHECK_EQ_INT(mgba_session_load_game(NULL, "game.gba"),
                 MGBA_ERR_INVALID_ARGUMENT);
    CHECK_EQ_INT(mgba_session_run_once(NULL), MGBA_ERR_INVALID_ARGUMENT);
    CHECK_EQ_INT(mgba_session_load_game(session, NULL),
                 MGBA_ERR_INVALID_ARGUMENT);
    mgba_session_destroy(session);
    mgba_session_destroy(NULL);
}

static void test_environment_contract(void) {
    reset_fakes();
    MgbaSession *session = mgba_session_create();
    MgbaCoreApi api = make_api();
    CHECK_EQ_INT(mgba_session_initialize_with_api(session, &api, "/system"),
                 MGBA_OK);
    CHECK(g_environment != NULL);

    const char *directory = NULL;
    CHECK(g_environment(RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY, &directory));
    CHECK_STR_EQ(directory, "/system");

    struct retro_variable variable;
    memset(&variable, 0, sizeof(variable));
    variable.key = "mgba_use_bios";
    CHECK(!g_environment(RETRO_ENVIRONMENT_GET_VARIABLE, &variable));

    unsigned version = 99;
    CHECK(g_environment(RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION, &version));
    CHECK_EQ_INT(version, 0);

    CHECK(g_environment(RETRO_ENVIRONMENT_SET_VARIABLES, NULL));
    CHECK(!g_environment(RETRO_ENVIRONMENT_GET_INPUT_BITMASKS, NULL));
    CHECK(!g_environment(0x7FFFFFFFu, NULL));

    bool updated = true;
    CHECK(g_environment(RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE, &updated));
    CHECK(!updated);

    struct retro_log_callback log_callback;
    memset(&log_callback, 0, sizeof(log_callback));
    CHECK(g_environment(RETRO_ENVIRONMENT_GET_LOG_INTERFACE, &log_callback));
    CHECK(log_callback.log != NULL);
    if (log_callback.log) {
        log_callback.log(RETRO_LOG_INFO, "environment log test %d", 1);
    }

    mgba_session_destroy(session);
}

static void test_callbacks_and_stats(void) {
    reset_fakes();
    MgbaSession *session = mgba_session_create();
    MgbaCoreApi api = make_api();
    CHECK_EQ_INT(mgba_session_initialize_with_api(session, &api, ""), MGBA_OK);
    CHECK(g_video != NULL);
    CHECK(g_audio_sample != NULL);
    CHECK(g_audio_batch != NULL);
    CHECK(g_input_poll != NULL);
    CHECK(g_input_state != NULL);

    g_video(NULL, 240, 160, 512);
    g_video(NULL, 240, 160, 512);
    size_t consumed = g_audio_batch(NULL, 7);
    CHECK_EQ_INT(consumed, 7);

    CHECK_EQ_INT(mgba_session_frame_count(session), 2);
    CHECK_EQ_INT(mgba_session_audio_frame_count(session), 7);
    mgba_session_destroy(session);
}

int main(void) {
    test_full_lifecycle();
    test_initialize_twice_is_invalid();
    test_run_before_load_is_invalid();
    test_load_failure_keeps_session_usable();
    test_unload_without_game_is_invalid();
    test_invalid_api_enters_error_and_can_recover();
    test_invalid_arguments();
    test_environment_contract();
    test_callbacks_and_stats();
    test_reload_after_unload_reinitializes_core();
    test_shutdown_while_running();
    test_second_session_is_rejected();
    test_path_initialize_on_live_session_is_invalid();
    return test_summary("test_session");
}
