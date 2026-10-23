/*
 * Intentionally incomplete Libretro library: exports only retro_api_version,
 * so the loader must fail at the next required symbol.
 */
#include <libretro.h>

unsigned retro_api_version(void) {
    return RETRO_API_VERSION;
}
