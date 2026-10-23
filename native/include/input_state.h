/*
 * Thread-safe input state aggregator.
 *
 * Multiple input sources (virtual gamepad, physical keyboard, gamepad) can
 * independently press and release buttons. Each button tracks a bitmask of
 * which sources are holding it; the button is pressed when any bit is set.
 * This prevents one source's keyUp from clearing a button another source
 * is still holding.
 */
#ifndef MGBA_INPUT_STATE_H
#define MGBA_INPUT_STATE_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Input source IDs. Up to 32 sources fit in the per-button bitmask.
 */
#define INPUT_SOURCE_VIRTUAL 0
#define INPUT_SOURCE_KEYBOARD 1
#define INPUT_SOURCE_GAMEPAD 2

/* Number of Libretro joypad buttons we track (0..15). */
#define INPUT_BUTTON_COUNT 16

typedef struct InputState InputState;

InputState *input_state_create(void);
void input_state_destroy(InputState *state);

/* source_id: INPUT_SOURCE_* (0-31). button_id: RETRO_DEVICE_ID_JOYPAD_*. */
void input_state_press(InputState *state, unsigned button_id,
                       unsigned source_id);
void input_state_release(InputState *state, unsigned button_id,
                         unsigned source_id);

/* Release all buttons held by one source (pause, disconnect, focus loss). */
void input_state_release_source(InputState *state, unsigned source_id);

/* Release every button from every source. */
void input_state_reset(InputState *state);

/* Returns true if any source is holding the button. */
bool input_state_is_pressed(const InputState *state, unsigned button_id);

/* Returns the bitmask of sources currently holding the button. */
uint32_t input_state_sources(const InputState *state, unsigned button_id);

#ifdef __cplusplus
}
#endif

#endif /* MGBA_INPUT_STATE_H */
