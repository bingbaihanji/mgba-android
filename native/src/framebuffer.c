#include "framebuffer.h"

#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#define POOL_SLOT_COUNT 3

enum SlotState {
    SLOT_FREE = 0,
    SLOT_WRITING = 1,
    SLOT_READY = 2,
    SLOT_READING = 3
};

typedef struct FrameSlot {
    _Atomic int state;
    uint8_t *data;
    size_t capacity;
    unsigned width;
    unsigned height;
    enum retro_pixel_format format;
    uint64_t seq;
} FrameSlot;

struct FramebufferPool {
    FrameSlot slots[POOL_SLOT_COUNT];
    unsigned max_width;
    unsigned max_height;
    _Atomic uint64_t next_seq;
    int reading_index; /* only touched by the reader thread */
    unsigned long long written;
    unsigned long long dropped;
};

static unsigned bytes_per_pixel(enum retro_pixel_format format) {
    switch (format) {
    case RETRO_PIXEL_FORMAT_XRGB8888:
        return 4;
    case RETRO_PIXEL_FORMAT_0RGB1555:
    case RETRO_PIXEL_FORMAT_RGB565:
    default:
        return 2;
    }
}

FramebufferPool *framebuffer_pool_create(unsigned max_width,
                                         unsigned max_height) {
    FramebufferPool *pool;
    unsigned i;

    if (max_width == 0 || max_height == 0) {
        return NULL;
    }

    pool = (FramebufferPool *)calloc(1, sizeof(*pool));
    if (!pool) {
        return NULL;
    }

    pool->max_width = max_width;
    pool->max_height = max_height;
    pool->reading_index = -1;
    atomic_store(&pool->next_seq, 0);

    for (i = 0; i < POOL_SLOT_COUNT; ++i) {
        size_t capacity = (size_t)max_width * max_height * 4;
        pool->slots[i].data = (uint8_t *)malloc(capacity);
        if (!pool->slots[i].data) {
            framebuffer_pool_destroy(pool);
            return NULL;
        }
        pool->slots[i].capacity = capacity;
        atomic_store(&pool->slots[i].state, SLOT_FREE);
    }

    return pool;
}

void framebuffer_pool_destroy(FramebufferPool *pool) {
    unsigned i;
    if (!pool) {
        return;
    }
    for (i = 0; i < POOL_SLOT_COUNT; ++i) {
        free(pool->slots[i].data);
    }
    free(pool);
}

void framebuffer_pool_write(FramebufferPool *pool, const void *data,
                            unsigned width, unsigned height, size_t pitch,
                            enum retro_pixel_format format) {
    unsigned i;
    FrameSlot *slot = NULL;
    unsigned bpp;
    unsigned row_bytes;
    size_t needed;
    const uint8_t *src;
    uint8_t *dst;
    unsigned y;

    if (!pool || !data) {
        return; /* NULL means "repeat last frame" */
    }
    if (width == 0 || height == 0 || width > pool->max_width ||
        height > pool->max_height) {
        ++pool->dropped;
        return;
    }

    bpp = bytes_per_pixel(format);
    row_bytes = width * bpp;
    needed = (size_t)row_bytes * height;
    if (needed > pool->slots[0].capacity) {
        ++pool->dropped;
        return;
    }

    /* Find a FREE slot. If none is available the reader is behind: drop. */
    for (i = 0; i < POOL_SLOT_COUNT; ++i) {
        int expected = SLOT_FREE;
        if (atomic_compare_exchange_strong(&pool->slots[i].state, &expected,
                                           SLOT_WRITING)) {
            slot = &pool->slots[i];
            break;
        }
    }
    if (!slot) {
        ++pool->dropped;
        return;
    }

    src = (const uint8_t *)data;
    dst = slot->data;

    if ((size_t)row_bytes == pitch) {
        memcpy(dst, src, needed);
    } else {
        for (y = 0; y < height; ++y) {
            memcpy(dst + (size_t)y * row_bytes, src + (size_t)y * pitch,
                   row_bytes);
        }
    }

    slot->width = width;
    slot->height = height;
    slot->format = format;
    slot->seq = atomic_fetch_add(&pool->next_seq, 1) + 1;

    /* Release: the pixel copy above must be visible before READY. */
    atomic_store_explicit(&slot->state, SLOT_READY, memory_order_release);
    ++pool->written;
}

bool framebuffer_pool_acquire(FramebufferPool *pool, const uint8_t **out_data,
                              unsigned *out_width, unsigned *out_height,
                              enum retro_pixel_format *out_format,
                              uint64_t *out_seq) {
    int best = -1;
    uint64_t best_seq = 0;
    unsigned i;
    FrameSlot *slot;

    if (!pool || !out_data) {
        return false;
    }

    /* Pick the READY slot with the highest sequence number. */
    for (i = 0; i < POOL_SLOT_COUNT; ++i) {
        if (atomic_load_explicit(&pool->slots[i].state,
                                 memory_order_acquire) == SLOT_READY) {
            if (best < 0 || pool->slots[i].seq > best_seq) {
                best = (int)i;
                best_seq = pool->slots[i].seq;
            }
        }
    }

    if (best >= 0) {
        /* Release the old READING slot, then take the new one. */
        if (pool->reading_index >= 0 && pool->reading_index != best) {
            int old = pool->reading_index;
            atomic_store_explicit(&pool->slots[old].state, SLOT_FREE,
                                  memory_order_release);
        }
        atomic_store_explicit(&pool->slots[best].state, SLOT_READING,
                              memory_order_release);
        pool->reading_index = best;
    }

    if (pool->reading_index < 0) {
        return false; /* no frame has ever been published */
    }

    slot = &pool->slots[pool->reading_index];
    *out_data = slot->data;
    if (out_width) {
        *out_width = slot->width;
    }
    if (out_height) {
        *out_height = slot->height;
    }
    if (out_format) {
        *out_format = slot->format;
    }
    if (out_seq) {
        *out_seq = slot->seq;
    }
    return true;
}

void framebuffer_pool_release(FramebufferPool *pool) {
    /*
     * Release is handled at the next acquire (which frees the previous
     * READING slot). Nothing to do here -- the slot stays READING so
     * duplicate frames keep the last image available.
     */
    (void)pool;
}

unsigned long long framebuffer_pool_written(const FramebufferPool *pool) {
    return pool ? pool->written : 0;
}

unsigned long long framebuffer_pool_dropped(const FramebufferPool *pool) {
    return pool ? pool->dropped : 0;
}
