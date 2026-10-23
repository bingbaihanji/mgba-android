/*
 * Lock-free SPSC ring buffer for PCM audio.
 *
 * The producer (emulation thread, from audio callbacks) writes interleaved
 * stereo int16 frames. The consumer (audio thread) reads them into AudioTrack.
 * Monotonically increasing indices avoid the ABA problem; buffer position
 * is `index % capacity`.
 */
#ifndef MGBA_AUDIO_RING_H
#define MGBA_AUDIO_RING_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct AudioRing AudioRing;

/*
 * Create a ring that holds up to `capacity_frames` stereo sample frames
 * (each frame is two int16 samples: left, right).
 */
AudioRing *audio_ring_create(size_t capacity_frames);
void audio_ring_destroy(AudioRing *ring);

/*
 * Write interleaved stereo frames. Returns the number of frames actually
 * written (may be less than requested when the ring is full). The excess
 * is counted as overflow.
 */
size_t audio_ring_write(AudioRing *ring, const int16_t *data, size_t frames);

/*
 * Read up to `max_frames` stereo frames into `out`. Returns the number of
 * frames actually read (0 when the ring is empty).
 */
size_t audio_ring_read(AudioRing *ring, int16_t *out, size_t max_frames);

/* Frames currently queued (written but not yet read). */
size_t audio_ring_queued(const AudioRing *ring);

/* Free space in frames. */
size_t audio_ring_free(const AudioRing *ring);

/* Statistics. */
unsigned long long audio_ring_total_written(const AudioRing *ring);
unsigned long long audio_ring_total_read(const AudioRing *ring);
unsigned long long audio_ring_overflows(const AudioRing *ring);
unsigned long long audio_ring_underruns(const AudioRing *ring);

/* Reset underrun/overflow counters (e.g. on resume). */
void audio_ring_reset_stats(AudioRing *ring);

#ifdef __cplusplus
}
#endif

#endif /* MGBA_AUDIO_RING_H */
