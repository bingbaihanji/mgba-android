#include "mgba_session.h"

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#if defined(_WIN32) && !defined(__CYGWIN__)
#include <windows.h>
typedef HMODULE MgbaLibraryHandle;

static MgbaLibraryHandle mgba_library_open(const char *path) {
    return LoadLibraryA(path);
}

static void *mgba_library_symbol(MgbaLibraryHandle handle, const char *name) {
    return (void *)(uintptr_t)GetProcAddress(handle, name);
}

static void mgba_library_close(MgbaLibraryHandle handle) {
    FreeLibrary(handle);
}

static void mgba_library_error(char *buffer, size_t size) {
    DWORD code = GetLastError();
    if (!buffer || size == 0) {
        return;
    }
    snprintf(buffer, size, "Win32 error %lu", (unsigned long)code);
}
#else
#include <dlfcn.h>
typedef void *MgbaLibraryHandle;

static MgbaLibraryHandle mgba_library_open(const char *path) {
    return dlopen(path, RTLD_NOW | RTLD_LOCAL);
}

static void *mgba_library_symbol(MgbaLibraryHandle handle, const char *name) {
    return dlsym(handle, name);
}

static void mgba_library_close(MgbaLibraryHandle handle) {
    dlclose(handle);
}

static void mgba_library_error(char *buffer, size_t size) {
    const char *message = dlerror();
    if (!buffer || size == 0) {
        return;
    }
    snprintf(buffer, size, "%s", message ? message : "unknown loader error");
}
#endif

static void set_error(char *error, size_t error_size, const char *format, ...) {
    va_list args;
    if (!error || error_size == 0) {
        return;
    }
    va_start(args, format);
    vsnprintf(error, error_size, format, args);
    va_end(args);
}

static bool load_symbol(MgbaLibraryHandle handle, void *target,
                        size_t target_size, const char *name, char *error,
                        size_t error_size) {
    void *symbol = mgba_library_symbol(handle, name);
    if (!symbol) {
        set_error(error, error_size, "missing symbol: %s", name);
        return false;
    }
    if (target_size != sizeof(symbol)) {
        set_error(error, error_size, "unsupported pointer size for: %s", name);
        return false;
    }
    memcpy(target, &symbol, sizeof(symbol));
    return true;
}

/*
 * Resolve a symbol that is allowed to be absent. The cheat API is part of
 * Libretro but minimal/test cores may omit it; leave the pointer NULL then.
 */
static void load_optional_symbol(MgbaLibraryHandle handle, void *target,
                                 size_t target_size, const char *name) {
    void *symbol = mgba_library_symbol(handle, name);
    if (symbol && target_size == sizeof(symbol)) {
        memcpy(target, &symbol, sizeof(symbol));
    }
}

#define LOAD_SYMBOL(field, name)                                              \
    do {                                                                       \
        if (!load_symbol(handle, &out_api->field, sizeof(out_api->field), name,\
                         error, error_size)) {                                 \
            mgba_library_close(handle);                                        \
            memset(out_api, 0, sizeof(*out_api));                              \
            return MGBA_ERR_DLSYM;                                             \
        }                                                                      \
    } while (0)

MgbaResult mgba_core_open(const char *library_path, MgbaCoreApi *out_api,
                          char *error, size_t error_size) {
    MgbaLibraryHandle handle;

    if (error && error_size) {
        error[0] = '\0';
    }
    if (!library_path || !out_api) {
        set_error(error, error_size, "invalid argument");
        return MGBA_ERR_INVALID_ARGUMENT;
    }
    memset(out_api, 0, sizeof(*out_api));

    handle = mgba_library_open(library_path);
    if (!handle) {
        char loader_error[256] = {0};
        mgba_library_error(loader_error, sizeof(loader_error));
        set_error(error, error_size, "dlopen failed for %s: %s", library_path,
                  loader_error);
        return MGBA_ERR_DLOPEN;
    }

    LOAD_SYMBOL(api_version, "retro_api_version");
    LOAD_SYMBOL(set_environment, "retro_set_environment");
    LOAD_SYMBOL(set_video_refresh, "retro_set_video_refresh");
    LOAD_SYMBOL(set_audio_sample, "retro_set_audio_sample");
    LOAD_SYMBOL(set_audio_sample_batch, "retro_set_audio_sample_batch");
    LOAD_SYMBOL(set_input_poll, "retro_set_input_poll");
    LOAD_SYMBOL(set_input_state, "retro_set_input_state");
    LOAD_SYMBOL(init, "retro_init");
    LOAD_SYMBOL(deinit, "retro_deinit");
    LOAD_SYMBOL(get_system_info, "retro_get_system_info");
    LOAD_SYMBOL(get_system_av_info, "retro_get_system_av_info");
    LOAD_SYMBOL(load_game, "retro_load_game");
    LOAD_SYMBOL(unload_game, "retro_unload_game");
    LOAD_SYMBOL(run, "retro_run");
    LOAD_SYMBOL(reset, "retro_reset");
    LOAD_SYMBOL(serialize_size, "retro_serialize_size");
    LOAD_SYMBOL(serialize, "retro_serialize");
    LOAD_SYMBOL(unserialize, "retro_unserialize");
    LOAD_SYMBOL(get_memory_data, "retro_get_memory_data");
    LOAD_SYMBOL(get_memory_size, "retro_get_memory_size");

    /* Optional: cheat_reset/cheat_set are exported by mGBA. */
    out_api->cheat_reset = NULL;
    out_api->cheat_set = NULL;
    load_optional_symbol(handle, &out_api->cheat_reset,
                         sizeof(out_api->cheat_reset), "retro_cheat_reset");
    load_optional_symbol(handle, &out_api->cheat_set,
                         sizeof(out_api->cheat_set), "retro_cheat_set");

    out_api->handle = handle;
    return MGBA_OK;
}

#undef LOAD_SYMBOL

void mgba_core_close(MgbaCoreApi *api) {
    if (!api) {
        return;
    }
    if (api->handle) {
        mgba_library_close((MgbaLibraryHandle)api->handle);
    }
    memset(api, 0, sizeof(*api));
}
