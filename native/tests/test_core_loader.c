#include "mgba_session.h"

#include <string.h>

#include "test_common.h"

#ifndef FAKE_CORE_PATH
#error "FAKE_CORE_PATH must be defined by CMake"
#endif

#ifndef PARTIAL_CORE_PATH
#error "PARTIAL_CORE_PATH must be defined by CMake"
#endif

static void test_loads_fake_core(void) {
    MgbaCoreApi api;
    char error[256] = {0};
    MgbaResult result =
        mgba_core_open(FAKE_CORE_PATH, &api, error, sizeof(error));
    CHECK_EQ_INT(result, MGBA_OK);
    CHECK(api.handle != NULL);
    CHECK(api.api_version != NULL);
    CHECK_EQ_INT(api.api_version(), RETRO_API_VERSION);

    struct retro_system_info info;
    memset(&info, 0, sizeof(info));
    api.get_system_info(&info);
    CHECK_STR_EQ(info.library_name, "FakeCore");
    CHECK_STR_EQ(info.library_version, "1.0-test");

    mgba_core_close(&api);
    CHECK(api.handle == NULL);
    mgba_core_close(&api);
}

static void test_missing_library(void) {
    MgbaCoreApi api;
    char error[256] = {0};
    MgbaResult result = mgba_core_open("this-library-does-not-exist.so", &api,
                                       error, sizeof(error));
    CHECK_EQ_INT(result, MGBA_ERR_DLOPEN);
    CHECK_NON_EMPTY(error);
    CHECK(api.handle == NULL);
}

static void test_missing_symbol(void) {
    MgbaCoreApi api;
    char error[256] = {0};
    MgbaResult result =
        mgba_core_open(PARTIAL_CORE_PATH, &api, error, sizeof(error));
    CHECK_EQ_INT(result, MGBA_ERR_DLSYM);
    CHECK_NON_EMPTY(error);
    CHECK(strstr(error, "retro_set_environment") != NULL);
    CHECK(api.handle == NULL);
}

static void test_invalid_arguments(void) {
    MgbaCoreApi api;
    char error[256] = {0};
    CHECK_EQ_INT(mgba_core_open(NULL, &api, error, sizeof(error)),
                 MGBA_ERR_INVALID_ARGUMENT);
    CHECK_EQ_INT(mgba_core_open(FAKE_CORE_PATH, NULL, error, sizeof(error)),
                 MGBA_ERR_INVALID_ARGUMENT);
    mgba_core_close(NULL);
}

static void test_session_end_to_end(void) {
    MgbaSession *session = mgba_session_create();
    CHECK(session != NULL);
    CHECK_EQ_INT(mgba_session_initialize(session, FAKE_CORE_PATH, "/system"),
                 MGBA_OK);
    CHECK_STR_EQ(mgba_session_core_name(session), "FakeCore");
    CHECK_STR_EQ(mgba_session_core_version(session), "1.0-test");
    CHECK_EQ_INT(mgba_session_load_game(session, "game.gba"), MGBA_OK);
    CHECK_EQ_INT(mgba_session_run_once(session), MGBA_OK);
    CHECK_EQ_INT(mgba_session_frame_count(session), 1);
    CHECK_EQ_INT(mgba_session_audio_frame_count(session), 0);
    CHECK_EQ_INT(mgba_session_unload_game(session), MGBA_OK);
    CHECK_EQ_INT(mgba_session_shutdown(session), MGBA_OK);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_UNINITIALIZED);
    mgba_session_destroy(session);
}

static void test_session_initialize_failure(void) {
    MgbaSession *session = mgba_session_create();
    CHECK(session != NULL);
    CHECK_EQ_INT(mgba_session_initialize(session, "missing-core.so", ""),
                 MGBA_ERR_DLOPEN);
    CHECK_EQ_INT(mgba_session_state(session), MGBA_STATE_ERROR);
    CHECK_NON_EMPTY(mgba_session_last_error(session));
    mgba_session_destroy(session);
}

int main(void) {
    test_loads_fake_core();
    test_missing_library();
    test_missing_symbol();
    test_invalid_arguments();
    test_session_end_to_end();
    test_session_initialize_failure();
    return test_summary("test_core_loader");
}
