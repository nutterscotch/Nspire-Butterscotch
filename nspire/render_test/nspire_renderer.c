#include "nspire_renderer.h"

void NspireRenderer_init(NspireRenderer* r, uint16_t* fb, int32_t w, int32_t h) {
    r->fb = fb;
    r->fbW = w;
    r->fbH = h;
    r->scissorX = 0;
    r->scissorY = 0;
    r->scissorW = w;
    r->scissorH = h;
}

void NspireRenderer_clear(NspireRenderer* r, uint16_t color) {
    int32_t n = r->fbW * r->fbH;
    uint16_t* p = r->fb;
    for (int32_t i = 0; i < n; i++) p[i] = color;
}

void NspireRenderer_drawSpriteAxisAligned8(
    NspireRenderer* r,
    const uint8_t* indices, int32_t spriteW, int32_t spriteH,
    const uint16_t* palette,
    int32_t destX, int32_t destY
) {
    int32_t scX0 = r->scissorX;
    int32_t scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW;
    int32_t scY1 = scY0 + r->scissorH;

    int32_t startX = destX < scX0 ? scX0 : destX;
    int32_t startY = destY < scY0 ? scY0 : destY;
    int32_t spriteRight = destX + spriteW;
    int32_t spriteBottom = destY + spriteH;
    int32_t endX = spriteRight < scX1 ? spriteRight : scX1;
    int32_t endY = spriteBottom < scY1 ? spriteBottom : scY1;
    if (startX >= endX || startY >= endY) return;

    int32_t spanW = endX - startX;
    int32_t srcOffX = startX - destX;

    for (int32_t y = startY; y < endY; y++) {
        const uint8_t* srcRow = indices + (y - destY) * spriteW + srcOffX;
        uint16_t* dstRow = r->fb + y * r->fbW + startX;
        for (int32_t x = 0; x < spanW; x++) {
            uint8_t idx = srcRow[x];
            if (idx == 0) continue;
            dstRow[x] = palette[idx];
        }
    }
}
