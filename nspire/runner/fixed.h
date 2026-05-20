#pragma once

// Q16.16 fixed-point helpers for the Nspire renderer.
// The ARM926EJ-S has no FPU; software float on the slow path is ~20-50 cycles
// per op. A single-instruction smull-based fixed multiply is one cycle, so the
// renderer's inner loops use these types throughout.

#include <stdint.h>

typedef int32_t fx16;  // Q16.16: 1 sign bit, 15 integer bits, 16 fraction bits

#define FX_ONE        ((fx16) 0x00010000)
#define FX_HALF       ((fx16) 0x00008000)
#define FX_FRAC_BITS  16

static inline fx16 fx_from_int(int32_t i) { return (fx16) (i << FX_FRAC_BITS); }
static inline int32_t fx_to_int(fx16 f)   { return f >> FX_FRAC_BITS; }
static inline int32_t fx_floor(fx16 f)    { return f >> FX_FRAC_BITS; }
static inline int32_t fx_ceil(fx16 f)     { return (f + (FX_ONE - 1)) >> FX_FRAC_BITS; }

// Multiply: (a * b) >> 16, using a 64-bit intermediate to avoid overflow.
// On ARM9 GCC emits this as a single smull + extraction.
static inline fx16 fx_mul(fx16 a, fx16 b) {
    return (fx16) (((int64_t) a * (int64_t) b) >> FX_FRAC_BITS);
}

// Divide: (a << 16) / b, 64-bit intermediate.
static inline fx16 fx_div(fx16 a, fx16 b) {
    return (fx16) (((int64_t) a << FX_FRAC_BITS) / b);
}
