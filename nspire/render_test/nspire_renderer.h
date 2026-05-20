#pragma once

#include <stdint.h>
#include "fixed.h"

#define NSPIRE_FB_W 320
#define NSPIRE_FB_H 240

// Pack 8-bit-per-channel RGB into a single 16-bit RGB565 value.
static inline uint16_t nspire_rgb565(uint8_t r, uint8_t g, uint8_t b) {
    return (uint16_t) (((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
}

typedef struct {
    uint16_t* fb;
    int32_t fbW, fbH;
    // Scissor rect in framebuffer pixel coords. Inclusive-exclusive [x, x+w).
    int32_t scissorX, scissorY, scissorW, scissorH;
} NspireRenderer;

void NspireRenderer_init(NspireRenderer* r, uint16_t* fb, int32_t w, int32_t h);
void NspireRenderer_clear(NspireRenderer* r, uint16_t color);

// Fast path: axis-aligned 8bpp paletted blit with alpha-test on palette index 0.
// `indices` is a tightly packed w*h byte array; `palette` is 256 RGB565 entries
// with entry [0] reserved as the transparent slot. `destX`/`destY` may be negative
// or place the sprite partially off-screen; the function clips to the scissor.
void NspireRenderer_drawSpriteAxisAligned8(
    NspireRenderer* r,
    const uint8_t* indices, int32_t spriteW, int32_t spriteH,
    const uint16_t* palette,
    int32_t destX, int32_t destY
);
