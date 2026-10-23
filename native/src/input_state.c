#include "input_state.h"

#include <stdatomic.h>
#include <stdlib.h>

struct InputState {
    _Atomic uint32_t sources[INPUT_BUTTON_COUNT];
};

InputState *input_state_create(void) {
    return (InputState *)calloc(1, sizeof(InputState));
}

void input_state_destroy(InputState *state) {
    free(state);
}

void input_state_press(InputState *state, unsigned button_id,
                       unsigned source_id) {
    if (!state || button_id >= INPUT_BUTTON_COUNT || source_id >= 32) {
        return;
    }
    atomic_fetch_or(&state->sources[button_id], 1u << source_id);
}

void input_state_release(InputState *state, unsigned button_id,
                         unsigned source_id) {
    if (!state || button_id >= INPUT_BUTTON_COUNT || source_id >= 32) {
        return;
    }
    atomic_fetch_and(&state->sources[button_id], ~(1u << source_id));
}

void input_state_release_source(InputState *state, unsigned source_id) {
    unsigned i;
    if (!state || source_id >= 32) {
        return;
    }
    for (i = 0; i < INPUT_BUTTON_COUNT; ++i) {
        atomic_fetch_and(&state->sources[i], ~(1u << source_id));
    }
}

void input_state_reset(InputState *state) {
    unsigned i;
    if (!state) {
        return;
    }
    for (i = 0; i < INPUT_BUTTON_COUNT; ++i) {
        atomic_store(&state->sources[i], 0);
    }
}

bool input_state_is_pressed(const InputState *state, unsigned button_id) {
    if (!state || button_id >= INPUT_BUTTON_COUNT) {
        return false;
    }
    return atomic_load(&state->sources[button_id]) != 0;
}

uint32_t input_state_sources(const InputState *state, unsigned button_id) {
    if (!state || button_id >= INPUT_BUTTON_COUNT) {
        return 0;
    }
    return atomic_load(&state->sources[button_id]);
}
