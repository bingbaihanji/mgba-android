/*
 * Triple-buffered frame pool for the Libretro video callback.
 *
 * The writer (emulation thread, inside video_refresh_cb) copies pixel data
 * into a free slot and publishes it. The reader (GL render thread) picks up
 * the latest complete frame and holds it while uploading to a GL texture.
 *
 * Single-producer / single-consumer. No mutexes on the hot path.
 */
#ifndef MGBA_FRAMEBUFFER_H
#define MGBA_FRAMEBUFFER_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include <libretro.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct FramebufferPool FramebufferPool;

/*
 * Create a pool whose slots can hold frames up to max_width x max_height
 * at 4 bytes per pixel (the largest Libretro pixel format). The pool is
 * safe for one writer thread and one reader thread.
 */
FramebufferPool *framebuffer_pool_create(unsigned max_width,
                                         unsigned max_height);

void framebuffer_pool_destroy(FramebufferPool *pool);

/*
 * Publish a frame. Called from the emulation thread (video_refresh_cb).
 * data may be NULL meaning "repeat the last frame" -- the pool is untouched.
 * Rows are compacted so that the destination pitch is always width * bpp.
 * The frame is dropped (and counted) when no free slot is available.
 */
void framebuffer_pool_write(FramebufferPool *pool, const void *data,
                            unsigned width, unsigned height, size_t pitch,
                            enum retro_pixel_format format);

/*
 * Acquire the latest complete frame for reading. Called from the render
 * thread. Returns true and fills the out-parameters when a frame is
 * available. The returned pointer stays valid until framebuffer_pool_release().
 *
 * If no new READY frame exists the previously held frame is returned again,
 * which handles duplicate (data == NULL) video callbacks naturally.
 */
bool framebuffer_pool_acquire(FramebufferPool *pool, const uint8_t **out_data,
                              unsigned *out_width, unsigned *out_height,
                              enum retro_pixel_format *out_format,
                              uint64_t *out_seq);

/* Release the frame obtained from framebuffer_pool_acquire(). */
void framebuffer_pool_release(FramebufferPool *pool);

/* Statistics. */
unsigned long long framebuffer_pool_written(const FramebufferPool *pool);
unsigned long long framebuffer_pool_dropped(const FramebufferPool *pool);

#ifdef __cplusplus
}
#endif

#endif /* MGBA_FRAMEBUFFER_H */
