#include "audio_ring.h"

#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

struct AudioRing {
    int16_t *data; /* interleaved L, R */
    size_t capacity; /* in frames */
    _Atomic size_t write_pos; /* monotonically increasing */
    _Atomic size_t read_pos;  /* monotonically increasing */
    _Atomic unsigned long long total_written;
    _Atomic unsigned long long total_read;
    _Atomic unsigned long long overflows;
    _Atomic unsigned long long underruns;
};

AudioRing *audio_ring_create(size_t capacity_frames) {
    AudioRing *ring;
    if (capacity_frames == 0) {
        return NULL;
    }
    ring = (AudioRing *)calloc(1, sizeof(*ring));
    if (!ring) {
        return NULL;
    }
    ring->data = (int16_t *)calloc(capacity_frames * 2, sizeof(int16_t));
    if (!ring->data) {
        free(ring);
        return NULL;
    }
    ring->capacity = capacity_frames;
    return ring;
}

void audio_ring_destroy(AudioRing *ring) {
    if (!ring) {
        return;
    }
    free(ring->data);
    free(ring);
}

size_t audio_ring_write(AudioRing *ring, const int16_t *data, size_t frames) {
    size_t w, r, free_space, to_write, first_chunk;

    if (!ring || !data || frames == 0) {
        return 0;
    }

    w = atomic_load_explicit(&ring->write_pos, memory_order_relaxed);
    r = atomic_load_explicit(&ring->read_pos, memory_order_acquire);
    free_space = ring->capacity - (w - r);

    to_write = frames < free_space ? frames : free_space;
    if (to_write < frames) {
        atomic_fetch_add_explicit(&ring->overflows, 1, memory_order_relaxed);
    }
    if (to_write == 0) {
        return 0;
    }

    first_chunk = ring->capacity - (w % ring->capacity);
    if (first_chunk > to_write) {
        first_chunk = to_write;
    }
    memcpy(ring->data + (w % ring->capacity) * 2, data,
           first_chunk * 2 * sizeof(int16_t));
    if (to_write > first_chunk) {
        memcpy(ring->data, data + first_chunk * 2,
               (to_write - first_chunk) * 2 * sizeof(int16_t));
    }

    /* Release: the copies above must be visible before the index update. */
    atomic_store_explicit(&ring->write_pos, w + to_write, memory_order_release);
    atomic_fetch_add_explicit(&ring->total_written, to_write,
                              memory_order_relaxed);
    return to_write;
}

size_t audio_ring_read(AudioRing *ring, int16_t *out, size_t max_frames) {
    size_t w, r, available, to_read, first_chunk;

    if (!ring || !out || max_frames == 0) {
        return 0;
    }

    r = atomic_load_explicit(&ring->read_pos, memory_order_relaxed);
    w = atomic_load_explicit(&ring->write_pos, memory_order_acquire);
    available = w - r;

    to_read = max_frames < available ? max_frames : available;
    if (to_read == 0) {
        atomic_fetch_add_explicit(&ring->underruns, 1, memory_order_relaxed);
        return 0;
    }

    first_chunk = ring->capacity - (r % ring->capacity);
    if (first_chunk > to_read) {
        first_chunk = to_read;
    }
    memcpy(out, ring->data + (r % ring->capacity) * 2,
           first_chunk * 2 * sizeof(int16_t));
    if (to_read > first_chunk) {
        memcpy(out + first_chunk * 2, ring->data,
               (to_read - first_chunk) * 2 * sizeof(int16_t));
    }

    atomic_store_explicit(&ring->read_pos, r + to_read, memory_order_release);
    atomic_fetch_add_explicit(&ring->total_read, to_read, memory_order_relaxed);
    return to_read;
}

size_t audio_ring_queued(const AudioRing *ring) {
    if (!ring) {
        return 0;
    }
    return atomic_load(&ring->write_pos) - atomic_load(&ring->read_pos);
}

size_t audio_ring_free(const AudioRing *ring) {
    if (!ring) {
        return 0;
    }
    return ring->capacity - audio_ring_queued(ring);
}

unsigned long long audio_ring_total_written(const AudioRing *ring) {
    return ring ? atomic_load(&ring->total_written) : 0;
}

unsigned long long audio_ring_total_read(const AudioRing *ring) {
    return ring ? atomic_load(&ring->total_read) : 0;
}

unsigned long long audio_ring_overflows(const AudioRing *ring) {
    return ring ? atomic_load(&ring->overflows) : 0;
}

unsigned long long audio_ring_underruns(const AudioRing *ring) {
    return ring ? atomic_load(&ring->underruns) : 0;
}

void audio_ring_reset_stats(AudioRing *ring) {
    if (!ring) {
        return;
    }
    atomic_store(&ring->overflows, 0);
    atomic_store(&ring->underruns, 0);
}
