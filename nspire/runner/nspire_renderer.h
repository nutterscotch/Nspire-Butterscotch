#pragma once

#include <stdint.h>
#include <stdbool.h>
#include "fixed.h"

#define NSPIRE_FB_W 320
#define NSPIRE_FB_H 240

static inline uint16_t nspire_rgb565(uint8_t r, uint8_t g, uint8_t b) {
    return (uint16_t) (((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
}

typedef struct {
    uint16_t* fb;
    int32_t fbW, fbH;
    int32_t scissorX, scissorY, scissorW, scissorH;
} NspireRenderer;

// Per-frame draw accounting for the diagnostic HUD. Reset by the engine
// renderer's beginFrame. px is a coarse cost proxy (triangle fb bbox area /
// sprite atlas-crop area), not an exact pixel count — good enough to see
// which path owns the frame.
typedef struct {
    uint32_t triCalls, sprCalls;
    uint32_t triPx, sprPx;
} NspireDrawStats;
extern NspireDrawStats gNspireDrawStats;

void NspireRenderer_init(NspireRenderer* r, uint16_t* fb, int32_t w, int32_t h);
void NspireRenderer_clear(NspireRenderer* r, uint16_t color);
void NspireRenderer_fillRect(NspireRenderer* r, int32_t x, int32_t y, int32_t w, int32_t h, uint16_t color);

// Fast packed-RGB565 alpha blend. a is 0..32 (5-bit). Returns fg over bg.
static inline uint16_t nspire_blend565(uint16_t fg, uint16_t bg, uint32_t a) {
    uint32_t f = (fg | ((uint32_t) fg << 16)) & 0x07E0F81Fu;
    uint32_t b = (bg | ((uint32_t) bg << 16)) & 0x07E0F81Fu;
    uint32_t r = ((f * a + b * (32u - a)) >> 5) & 0x07E0F81Fu;
    return (uint16_t) ((r & 0xFFFFu) | (r >> 16));
}

// Alpha-blended solid rect. alpha is 0..255. Used for fade-to-black/white overlays.
void NspireRenderer_blendRect(NspireRenderer* r, int32_t x, int32_t y, int32_t w, int32_t h,
                              uint16_t color, uint8_t alpha);

// Fast path, 8bpp paletted, axis-aligned, alpha-test on palette index 0.
// Source is a sub-rect of a larger atlas indexed in row-major order with `atlasStride`
// bytes per row. destX/destY may be negative or place the sprite off-screen.
void NspireRenderer_drawSpritePart8(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY
);

// 4bpp variant. atlasStrideBytes is the number of bytes per row (= ceil(atlasW / 2)).
// Pixel packing matches the preprocessor: even-x in low nibble, odd-x in high nibble.
void NspireRenderer_drawSpritePart4(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY
);

// Solid-tint variants for fonts: any non-transparent source pixel writes `color`
// to the framebuffer, ignoring the palette. Used for engine drawColor tint on text.
void NspireRenderer_drawGlyphPart8Solid(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    uint16_t color,
    int32_t destX, int32_t destY
);

void NspireRenderer_drawGlyphPart4Solid(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    uint16_t color,
    int32_t destX, int32_t destY
);

// Half-size variant: 2x downsample. Each output pixel samples a 2x2 source block and
// emits the highest palette index found in that block (which preserves pixel art —
// any opaque pixel in the block keeps the output opaque). Used when an engine GUI
// is set up at twice the framebuffer's coord space (battle's 640-wide GUI on our 320 fb).
void NspireRenderer_drawGlyphPart8Half(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY
);
void NspireRenderer_drawGlyphPart8HalfSolid(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    uint16_t color,
    int32_t destX, int32_t destY
);
void NspireRenderer_drawGlyphPart4Half(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY
);
void NspireRenderer_drawGlyphPart4HalfSolid(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    uint16_t color,
    int32_t destX, int32_t destY
);

// Area-pooled downsampler. For each output pixel, find the source block that maps to
// it via floor(out * src / dest) bounds, then emit the max palette index in that block.
// Handles arbitrary shrink ratios (2:1, 3:1, 4:1, ...) and keeps pixel fonts crisp.
void NspireRenderer_drawGlyphPart8Shrunk(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH
);
void NspireRenderer_drawGlyphPart8ShrunkSolid(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    uint16_t color,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH
);
void NspireRenderer_drawGlyphPart4Shrunk(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH
);
void NspireRenderer_drawGlyphPart4ShrunkSolid(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    uint16_t color,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH
);

// Alpha-blended sprite blits (1:1). Each non-transparent source pixel is blended over
// the framebuffer using `alpha` (0..255). Used for fading sprites (intro images, etc.).
void NspireRenderer_drawSpritePart8Blend(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, uint8_t alpha
);
void NspireRenderer_drawSpritePart4Blend(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, uint8_t alpha
);

// Nearest-neighbor stretched blit for scaled sprites. destW/destH are the FINAL on-screen
// pixel dimensions (after scaling). Source rectangle is sampled with fixed-point inverse
// mapping. Palette index 0 = transparent. Axis-aligned only.
void NspireRenderer_drawSpritePart8Stretched(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH
);

void NspireRenderer_drawSpritePart4Stretched(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH
);

// Alpha-blended stretched variants (for fades that scale a small sprite fullscreen).
void NspireRenderer_drawSpritePart8StretchedBlend(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH, uint8_t alpha
);
void NspireRenderer_drawSpritePart4StretchedBlend(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH, uint8_t alpha
);

// General affine blit: source pixel (s,t) maps to framebuffer point
//   (ox + s*ux + t*vx, oy + s*uy + t*vy)
// Inverse-mapped per destination pixel (nearest sample), so it covers rotation,
// scale, flip and arbitrary parallelograms in one path. bpp is 4 or 8. Palette
// index 0 is transparent. alpha is 0..255 (255 = opaque copy). Scissor-clipped.
void NspireRenderer_drawSpriteAffine(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride, uint8_t bpp,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    float ox, float oy, float ux, float uy, float vx, float vy,
    uint8_t alpha
);

// Width-aware line (square pen). alpha is 0..255. Scissor-clipped. width < 1
// is treated as 1 so a "thin line" always renders.
void NspireRenderer_drawLine(
    NspireRenderer* r,
    int32_t x0, int32_t y0, int32_t x1, int32_t y1,
    int32_t width, uint16_t color, uint8_t alpha
);

// Filled triangle (integer edge-function rasterizer, incremental — no FPU use).
// alpha 0..255. Scissor-clipped. Used by draw_triangle (Jevil's carousel).
void NspireRenderer_fillTriangle(
    NspireRenderer* r,
    int32_t x0, int32_t y0, int32_t x1, int32_t y1, int32_t x2, int32_t y2,
    uint16_t color, uint8_t alpha
);

// RGB565 -> RGB565 scaled composite (software surfaces). A sub-rect
// (sx,sy,sw,sh) of a srcStride-wide RGB565 buffer is inverse-mapped (nearest,
// 16.16 fixed point, no FPU) into dest rect (dx,dy,dw,dh) of the bound target.
// alpha 0..255, tint is GM 0x00BBGGRR (0xFFFFFF = none). Scissor-clipped.
// Source is treated as fully opaque (no transparency key) for this pass.
void NspireRenderer_blitSurface(
    NspireRenderer* r,
    const uint16_t* src, int32_t srcStride,
    int32_t sx, int32_t sy, int32_t sw, int32_t sh,
    int32_t dx, int32_t dy, int32_t dw, int32_t dh,
    uint8_t alpha, uint32_t tint
);
