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

void NspireRenderer_fillRect(NspireRenderer* r, int32_t x, int32_t y, int32_t w, int32_t h, uint16_t color) {
    if (w <= 0 || h <= 0) return;
    int32_t x0 = x < 0 ? 0 : x;
    int32_t y0 = y < 0 ? 0 : y;
    int32_t x1 = x + w; if (x1 > r->fbW) x1 = r->fbW;
    int32_t y1 = y + h; if (y1 > r->fbH) y1 = r->fbH;
    for (int32_t yy = y0; yy < y1; yy++) {
        uint16_t* row = r->fb + yy * r->fbW;
        for (int32_t xx = x0; xx < x1; xx++) row[xx] = color;
    }
}

void NspireRenderer_blendRect(NspireRenderer* r, int32_t x, int32_t y, int32_t w, int32_t h,
                              uint16_t color, uint8_t alpha) {
    if (w <= 0 || h <= 0) return;
    int32_t x0 = x < 0 ? 0 : x;
    int32_t y0 = y < 0 ? 0 : y;
    int32_t x1 = x + w; if (x1 > r->fbW) x1 = r->fbW;
    int32_t y1 = y + h; if (y1 > r->fbH) y1 = r->fbH;
    if (x0 >= x1 || y0 >= y1) return;
    uint32_t a = (uint32_t) alpha * 32u / 255u;  // 0..32
    if (a == 0) return;
    if (a >= 32) {
        for (int32_t yy = y0; yy < y1; yy++) {
            uint16_t* row = r->fb + yy * r->fbW;
            for (int32_t xx = x0; xx < x1; xx++) row[xx] = color;
        }
        return;
    }
    for (int32_t yy = y0; yy < y1; yy++) {
        uint16_t* row = r->fb + yy * r->fbW;
        for (int32_t xx = x0; xx < x1; xx++) {
            row[xx] = nspire_blend565(color, row[xx], a);
        }
    }
}

void NspireRenderer_drawSpritePart8(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY
) {
    int32_t scX0 = r->scissorX;
    int32_t scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW;
    int32_t scY1 = scY0 + r->scissorH;

    int32_t startX = destX < scX0 ? scX0 : destX;
    int32_t startY = destY < scY0 ? scY0 : destY;
    int32_t spriteRight = destX + srcW;
    int32_t spriteBottom = destY + srcH;
    int32_t endX = spriteRight < scX1 ? spriteRight : scX1;
    int32_t endY = spriteBottom < scY1 ? spriteBottom : scY1;
    if (startX >= endX || startY >= endY) return;

    int32_t spanW = endX - startX;
    int32_t srcOffX = startX - destX;

    for (int32_t y = startY; y < endY; y++) {
        const uint8_t* srcRow = atlas + (srcY + (y - destY)) * atlasStride + srcX + srcOffX;
        uint16_t* dstRow = r->fb + y * r->fbW + startX;
        for (int32_t x = 0; x < spanW; x++) {
            uint8_t idx = srcRow[x];
            if (idx == 0) continue;
            dstRow[x] = palette[idx];
        }
    }
}

void NspireRenderer_drawSpritePart4(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY
) {
    int32_t scX0 = r->scissorX;
    int32_t scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW;
    int32_t scY1 = scY0 + r->scissorH;

    int32_t startX = destX < scX0 ? scX0 : destX;
    int32_t startY = destY < scY0 ? scY0 : destY;
    int32_t spriteRight = destX + srcW;
    int32_t spriteBottom = destY + srcH;
    int32_t endX = spriteRight < scX1 ? spriteRight : scX1;
    int32_t endY = spriteBottom < scY1 ? spriteBottom : scY1;
    if (startX >= endX || startY >= endY) return;

    int32_t spanW = endX - startX;
    int32_t srcOffX = startX - destX;

    for (int32_t y = startY; y < endY; y++) {
        const uint8_t* srcRow = atlas + (srcY + (y - destY)) * atlasStrideBytes;
        int32_t atlasXBase = srcX + srcOffX;
        uint16_t* dstRow = r->fb + y * r->fbW + startX;
        for (int32_t x = 0; x < spanW; x++) {
            int32_t ax = atlasXBase + x;
            uint8_t byte = srcRow[ax >> 1];
            uint8_t idx = (ax & 1) ? (byte >> 4) : (byte & 0x0F);
            if (idx == 0) continue;
            dstRow[x] = palette[idx];
        }
    }
}

void NspireRenderer_drawGlyphPart8Solid(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    uint16_t color,
    int32_t destX, int32_t destY
) {
    int32_t scX0 = r->scissorX;
    int32_t scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW;
    int32_t scY1 = scY0 + r->scissorH;

    int32_t startX = destX < scX0 ? scX0 : destX;
    int32_t startY = destY < scY0 ? scY0 : destY;
    int32_t spriteRight = destX + srcW;
    int32_t spriteBottom = destY + srcH;
    int32_t endX = spriteRight < scX1 ? spriteRight : scX1;
    int32_t endY = spriteBottom < scY1 ? spriteBottom : scY1;
    if (startX >= endX || startY >= endY) return;

    int32_t spanW = endX - startX;
    int32_t srcOffX = startX - destX;

    for (int32_t y = startY; y < endY; y++) {
        const uint8_t* srcRow = atlas + (srcY + (y - destY)) * atlasStride + srcX + srcOffX;
        uint16_t* dstRow = r->fb + y * r->fbW + startX;
        for (int32_t x = 0; x < spanW; x++) {
            if (srcRow[x] == 0) continue;
            dstRow[x] = color;
        }
    }
}

void NspireRenderer_drawGlyphPart4Solid(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    uint16_t color,
    int32_t destX, int32_t destY
) {
    int32_t scX0 = r->scissorX;
    int32_t scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW;
    int32_t scY1 = scY0 + r->scissorH;

    int32_t startX = destX < scX0 ? scX0 : destX;
    int32_t startY = destY < scY0 ? scY0 : destY;
    int32_t spriteRight = destX + srcW;
    int32_t spriteBottom = destY + srcH;
    int32_t endX = spriteRight < scX1 ? spriteRight : scX1;
    int32_t endY = spriteBottom < scY1 ? spriteBottom : scY1;
    if (startX >= endX || startY >= endY) return;

    int32_t spanW = endX - startX;
    int32_t srcOffX = startX - destX;

    for (int32_t y = startY; y < endY; y++) {
        const uint8_t* srcRow = atlas + (srcY + (y - destY)) * atlasStrideBytes;
        int32_t atlasXBase = srcX + srcOffX;
        uint16_t* dstRow = r->fb + y * r->fbW + startX;
        for (int32_t x = 0; x < spanW; x++) {
            int32_t ax = atlasXBase + x;
            uint8_t byte = srcRow[ax >> 1];
            uint8_t idx = (ax & 1) ? (byte >> 4) : (byte & 0x0F);
            if (idx == 0) continue;
            dstRow[x] = color;
        }
    }
}

// 2x downsample 8bpp glyph. Iterates over the FULL output rectangle in glyph-local
// coords (outX, outY), then converts to (destX+outX, destY+outY) for the framebuffer,
// gating with bounds checks. Simpler and less error-prone than scissor-aware loops.
void NspireRenderer_drawGlyphPart8Half(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY
) {
    int32_t outW = srcW / 2;
    int32_t outH = srcH / 2;
    if (outW <= 0 || outH <= 0) return;
    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;

    for (int32_t outY = 0; outY < outH; outY++) {
        int32_t fbY = destY + outY;
        if (fbY < scY0 || fbY >= scY1) continue;
        int32_t sY = srcY + outY * 2;
        const uint8_t* row0 = atlas + sY * atlasStride;
        const uint8_t* row1 = atlas + (sY + 1) * atlasStride;
        for (int32_t outX = 0; outX < outW; outX++) {
            int32_t fbX = destX + outX;
            if (fbX < scX0 || fbX >= scX1) continue;
            int32_t sX = srcX + outX * 2;
            uint8_t a = row0[sX], b = row0[sX + 1], c = row1[sX], d = row1[sX + 1];
            uint8_t m = a > b ? a : b;
            uint8_t n = c > d ? c : d;
            uint8_t idx = m > n ? m : n;
            if (idx == 0) continue;
            r->fb[fbY * r->fbW + fbX] = palette[idx];
        }
    }
}

void NspireRenderer_drawGlyphPart8HalfSolid(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    uint16_t color,
    int32_t destX, int32_t destY
) {
    int32_t outW = srcW / 2;
    int32_t outH = srcH / 2;
    if (outW <= 0 || outH <= 0) return;
    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;

    for (int32_t outY = 0; outY < outH; outY++) {
        int32_t fbY = destY + outY;
        if (fbY < scY0 || fbY >= scY1) continue;
        int32_t sY = srcY + outY * 2;
        const uint8_t* row0 = atlas + sY * atlasStride;
        const uint8_t* row1 = atlas + (sY + 1) * atlasStride;
        for (int32_t outX = 0; outX < outW; outX++) {
            int32_t fbX = destX + outX;
            if (fbX < scX0 || fbX >= scX1) continue;
            int32_t sX = srcX + outX * 2;
            if ((row0[sX] | row0[sX + 1] | row1[sX] | row1[sX + 1]) == 0) continue;
            r->fb[fbY * r->fbW + fbX] = color;
        }
    }
}

static inline uint8_t pick4_4bpp(const uint8_t* row, int32_t ax) {
    uint8_t b = row[ax >> 1];
    return (ax & 1) ? (b >> 4) : (b & 0x0F);
}

void NspireRenderer_drawGlyphPart4Half(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY
) {
    int32_t outW = srcW / 2;
    int32_t outH = srcH / 2;
    if (outW <= 0 || outH <= 0) return;
    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;

    for (int32_t outY = 0; outY < outH; outY++) {
        int32_t fbY = destY + outY;
        if (fbY < scY0 || fbY >= scY1) continue;
        int32_t sY = srcY + outY * 2;
        const uint8_t* row0 = atlas + sY * atlasStrideBytes;
        const uint8_t* row1 = atlas + (sY + 1) * atlasStrideBytes;
        for (int32_t outX = 0; outX < outW; outX++) {
            int32_t fbX = destX + outX;
            if (fbX < scX0 || fbX >= scX1) continue;
            int32_t sx = srcX + outX * 2;
            uint8_t a = pick4_4bpp(row0, sx);
            uint8_t b = pick4_4bpp(row0, sx + 1);
            uint8_t c = pick4_4bpp(row1, sx);
            uint8_t d = pick4_4bpp(row1, sx + 1);
            uint8_t m = a > b ? a : b;
            uint8_t n = c > d ? c : d;
            uint8_t idx = m > n ? m : n;
            if (idx == 0) continue;
            r->fb[fbY * r->fbW + fbX] = palette[idx];
        }
    }
}

void NspireRenderer_drawGlyphPart4HalfSolid(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    uint16_t color,
    int32_t destX, int32_t destY
) {
    int32_t outW = srcW / 2;
    int32_t outH = srcH / 2;
    if (outW <= 0 || outH <= 0) return;
    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;

    for (int32_t outY = 0; outY < outH; outY++) {
        int32_t fbY = destY + outY;
        if (fbY < scY0 || fbY >= scY1) continue;
        int32_t sY = srcY + outY * 2;
        const uint8_t* row0 = atlas + sY * atlasStrideBytes;
        const uint8_t* row1 = atlas + (sY + 1) * atlasStrideBytes;
        for (int32_t outX = 0; outX < outW; outX++) {
            int32_t fbX = destX + outX;
            if (fbX < scX0 || fbX >= scX1) continue;
            int32_t sx = srcX + outX * 2;
            uint8_t a = pick4_4bpp(row0, sx);
            uint8_t b = pick4_4bpp(row0, sx + 1);
            uint8_t c = pick4_4bpp(row1, sx);
            uint8_t d = pick4_4bpp(row1, sx + 1);
            if ((a | b | c | d) == 0) continue;
            r->fb[fbY * r->fbW + fbX] = color;
        }
    }
}

void NspireRenderer_drawSpritePart8Blend(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, uint8_t alpha
) {
    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;
    int32_t startX = destX < scX0 ? scX0 : destX;
    int32_t startY = destY < scY0 ? scY0 : destY;
    int32_t endX = (destX + srcW) < scX1 ? (destX + srcW) : scX1;
    int32_t endY = (destY + srcH) < scY1 ? (destY + srcH) : scY1;
    if (startX >= endX || startY >= endY) return;
    uint32_t a = (uint32_t) alpha * 32u / 255u;
    if (a == 0) return;
    int32_t srcOffX = startX - destX;
    for (int32_t y = startY; y < endY; y++) {
        const uint8_t* srcRow = atlas + (srcY + (y - destY)) * atlasStride + srcX + srcOffX;
        uint16_t* dstRow = r->fb + y * r->fbW + startX;
        int32_t span = endX - startX;
        for (int32_t x = 0; x < span; x++) {
            uint8_t idx = srcRow[x];
            if (idx == 0) continue;
            dstRow[x] = nspire_blend565(palette[idx], dstRow[x], a);
        }
    }
}

void NspireRenderer_drawSpritePart4Blend(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, uint8_t alpha
) {
    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;
    int32_t startX = destX < scX0 ? scX0 : destX;
    int32_t startY = destY < scY0 ? scY0 : destY;
    int32_t endX = (destX + srcW) < scX1 ? (destX + srcW) : scX1;
    int32_t endY = (destY + srcH) < scY1 ? (destY + srcH) : scY1;
    if (startX >= endX || startY >= endY) return;
    uint32_t a = (uint32_t) alpha * 32u / 255u;
    if (a == 0) return;
    int32_t srcOffX = startX - destX;
    for (int32_t y = startY; y < endY; y++) {
        const uint8_t* srcRow = atlas + (srcY + (y - destY)) * atlasStrideBytes;
        int32_t atlasXBase = srcX + srcOffX;
        uint16_t* dstRow = r->fb + y * r->fbW + startX;
        int32_t span = endX - startX;
        for (int32_t x = 0; x < span; x++) {
            int32_t ax = atlasXBase + x;
            uint8_t byte = srcRow[ax >> 1];
            uint8_t idx = (ax & 1) ? (byte >> 4) : (byte & 0x0F);
            if (idx == 0) continue;
            dstRow[x] = nspire_blend565(palette[idx], dstRow[x], a);
        }
    }
}

// Generic area-pooled downsampler — max palette index in the source block per output pixel.
void NspireRenderer_drawGlyphPart8Shrunk(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH
) {
    if (destW <= 0 || destH <= 0 || srcW <= 0 || srcH <= 0) return;
    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;
    for (int32_t outY = 0; outY < destH; outY++) {
        int32_t fbY = destY + outY;
        if (fbY < scY0 || fbY >= scY1) continue;
        int32_t sY0 = srcY + (int32_t) (((int64_t) outY * srcH) / destH);
        int32_t sY1 = srcY + (int32_t) (((int64_t) (outY + 1) * srcH) / destH);
        if (sY1 <= sY0) sY1 = sY0 + 1;
        for (int32_t outX = 0; outX < destW; outX++) {
            int32_t fbX = destX + outX;
            if (fbX < scX0 || fbX >= scX1) continue;
            int32_t sX0 = srcX + (int32_t) (((int64_t) outX * srcW) / destW);
            int32_t sX1 = srcX + (int32_t) (((int64_t) (outX + 1) * srcW) / destW);
            if (sX1 <= sX0) sX1 = sX0 + 1;
            uint8_t maxIdx = 0;
            for (int32_t sy = sY0; sy < sY1; sy++) {
                const uint8_t* row = atlas + sy * atlasStride;
                for (int32_t sx = sX0; sx < sX1; sx++) {
                    uint8_t v = row[sx];
                    if (v > maxIdx) maxIdx = v;
                }
            }
            if (maxIdx == 0) continue;
            r->fb[fbY * r->fbW + fbX] = palette[maxIdx];
        }
    }
}

void NspireRenderer_drawGlyphPart8ShrunkSolid(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    uint16_t color,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH
) {
    if (destW <= 0 || destH <= 0 || srcW <= 0 || srcH <= 0) return;
    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;
    for (int32_t outY = 0; outY < destH; outY++) {
        int32_t fbY = destY + outY;
        if (fbY < scY0 || fbY >= scY1) continue;
        int32_t sY0 = srcY + (int32_t) (((int64_t) outY * srcH) / destH);
        int32_t sY1 = srcY + (int32_t) (((int64_t) (outY + 1) * srcH) / destH);
        if (sY1 <= sY0) sY1 = sY0 + 1;
        for (int32_t outX = 0; outX < destW; outX++) {
            int32_t fbX = destX + outX;
            if (fbX < scX0 || fbX >= scX1) continue;
            int32_t sX0 = srcX + (int32_t) (((int64_t) outX * srcW) / destW);
            int32_t sX1 = srcX + (int32_t) (((int64_t) (outX + 1) * srcW) / destW);
            if (sX1 <= sX0) sX1 = sX0 + 1;
            bool any = false;
            for (int32_t sy = sY0; !any && sy < sY1; sy++) {
                const uint8_t* row = atlas + sy * atlasStride;
                for (int32_t sx = sX0; sx < sX1; sx++) {
                    if (row[sx] != 0) { any = true; break; }
                }
            }
            if (!any) continue;
            r->fb[fbY * r->fbW + fbX] = color;
        }
    }
}

void NspireRenderer_drawGlyphPart4Shrunk(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH
) {
    if (destW <= 0 || destH <= 0 || srcW <= 0 || srcH <= 0) return;
    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;
    for (int32_t outY = 0; outY < destH; outY++) {
        int32_t fbY = destY + outY;
        if (fbY < scY0 || fbY >= scY1) continue;
        int32_t sY0 = srcY + (int32_t) (((int64_t) outY * srcH) / destH);
        int32_t sY1 = srcY + (int32_t) (((int64_t) (outY + 1) * srcH) / destH);
        if (sY1 <= sY0) sY1 = sY0 + 1;
        for (int32_t outX = 0; outX < destW; outX++) {
            int32_t fbX = destX + outX;
            if (fbX < scX0 || fbX >= scX1) continue;
            int32_t sX0 = srcX + (int32_t) (((int64_t) outX * srcW) / destW);
            int32_t sX1 = srcX + (int32_t) (((int64_t) (outX + 1) * srcW) / destW);
            if (sX1 <= sX0) sX1 = sX0 + 1;
            uint8_t maxIdx = 0;
            for (int32_t sy = sY0; sy < sY1; sy++) {
                const uint8_t* row = atlas + sy * atlasStrideBytes;
                for (int32_t sx = sX0; sx < sX1; sx++) {
                    uint8_t v = pick4_4bpp(row, sx);
                    if (v > maxIdx) maxIdx = v;
                }
            }
            if (maxIdx == 0) continue;
            r->fb[fbY * r->fbW + fbX] = palette[maxIdx];
        }
    }
}

void NspireRenderer_drawGlyphPart4ShrunkSolid(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    uint16_t color,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH
) {
    if (destW <= 0 || destH <= 0 || srcW <= 0 || srcH <= 0) return;
    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;
    for (int32_t outY = 0; outY < destH; outY++) {
        int32_t fbY = destY + outY;
        if (fbY < scY0 || fbY >= scY1) continue;
        int32_t sY0 = srcY + (int32_t) (((int64_t) outY * srcH) / destH);
        int32_t sY1 = srcY + (int32_t) (((int64_t) (outY + 1) * srcH) / destH);
        if (sY1 <= sY0) sY1 = sY0 + 1;
        for (int32_t outX = 0; outX < destW; outX++) {
            int32_t fbX = destX + outX;
            if (fbX < scX0 || fbX >= scX1) continue;
            int32_t sX0 = srcX + (int32_t) (((int64_t) outX * srcW) / destW);
            int32_t sX1 = srcX + (int32_t) (((int64_t) (outX + 1) * srcW) / destW);
            if (sX1 <= sX0) sX1 = sX0 + 1;
            bool any = false;
            for (int32_t sy = sY0; !any && sy < sY1; sy++) {
                const uint8_t* row = atlas + sy * atlasStrideBytes;
                for (int32_t sx = sX0; sx < sX1; sx++) {
                    if (pick4_4bpp(row, sx) != 0) { any = true; break; }
                }
            }
            if (!any) continue;
            r->fb[fbY * r->fbW + fbX] = color;
        }
    }
}

// Common scaled blit setup: compute clipped destination range + 16.16 fixed-point step
// per output pixel, then call back per row.
void NspireRenderer_drawSpritePart8Stretched(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH
) {
    if (destW <= 0 || destH <= 0 || srcW <= 0 || srcH <= 0) return;
    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;

    int32_t startX = destX < scX0 ? scX0 : destX;
    int32_t startY = destY < scY0 ? scY0 : destY;
    int32_t endX = (destX + destW) < scX1 ? (destX + destW) : scX1;
    int32_t endY = (destY + destH) < scY1 ? (destY + destH) : scY1;
    if (startX >= endX || startY >= endY) return;

    int32_t stepX = (int32_t) (((int64_t) srcW << 16) / destW);
    int32_t stepY = (int32_t) (((int64_t) srcH << 16) / destH);
    int32_t startU = stepX * (startX - destX);
    int32_t startV = stepY * (startY - destY);

    int32_t v = startV;
    for (int32_t y = startY; y < endY; y++, v += stepY) {
        int32_t sy = srcY + (v >> 16);
        const uint8_t* srcRow = atlas + sy * atlasStride + srcX;
        uint16_t* dstRow = r->fb + y * r->fbW;
        int32_t u = startU;
        for (int32_t x = startX; x < endX; x++, u += stepX) {
            uint8_t idx = srcRow[u >> 16];
            if (idx == 0) continue;
            dstRow[x] = palette[idx];
        }
    }
}

void NspireRenderer_drawSpritePart4Stretched(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH
) {
    if (destW <= 0 || destH <= 0 || srcW <= 0 || srcH <= 0) return;
    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;

    int32_t startX = destX < scX0 ? scX0 : destX;
    int32_t startY = destY < scY0 ? scY0 : destY;
    int32_t endX = (destX + destW) < scX1 ? (destX + destW) : scX1;
    int32_t endY = (destY + destH) < scY1 ? (destY + destH) : scY1;
    if (startX >= endX || startY >= endY) return;

    int32_t stepX = (int32_t) (((int64_t) srcW << 16) / destW);
    int32_t stepY = (int32_t) (((int64_t) srcH << 16) / destH);
    int32_t startU = stepX * (startX - destX);
    int32_t startV = stepY * (startY - destY);

    int32_t v = startV;
    for (int32_t y = startY; y < endY; y++, v += stepY) {
        int32_t sy = srcY + (v >> 16);
        const uint8_t* srcRow = atlas + sy * atlasStrideBytes;
        uint16_t* dstRow = r->fb + y * r->fbW;
        int32_t u = startU;
        for (int32_t x = startX; x < endX; x++, u += stepX) {
            int32_t ax = srcX + (u >> 16);
            uint8_t byte = srcRow[ax >> 1];
            uint8_t idx = (ax & 1) ? (byte >> 4) : (byte & 0x0F);
            if (idx == 0) continue;
            dstRow[x] = palette[idx];
        }
    }
}

void NspireRenderer_drawSpritePart8StretchedBlend(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH, uint8_t alpha
) {
    if (destW <= 0 || destH <= 0 || srcW <= 0 || srcH <= 0) return;
    uint32_t a = (uint32_t) alpha * 32u / 255u;
    if (a == 0) return;
    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;
    int32_t startX = destX < scX0 ? scX0 : destX;
    int32_t startY = destY < scY0 ? scY0 : destY;
    int32_t endX = (destX + destW) < scX1 ? (destX + destW) : scX1;
    int32_t endY = (destY + destH) < scY1 ? (destY + destH) : scY1;
    if (startX >= endX || startY >= endY) return;
    int32_t stepX = (int32_t) (((int64_t) srcW << 16) / destW);
    int32_t stepY = (int32_t) (((int64_t) srcH << 16) / destH);
    int32_t startU = stepX * (startX - destX);
    int32_t startV = stepY * (startY - destY);
    int32_t v = startV;
    for (int32_t y = startY; y < endY; y++, v += stepY) {
        int32_t sy = srcY + (v >> 16);
        const uint8_t* srcRow = atlas + sy * atlasStride + srcX;
        uint16_t* dstRow = r->fb + y * r->fbW;
        int32_t u = startU;
        for (int32_t x = startX; x < endX; x++, u += stepX) {
            uint8_t idx = srcRow[u >> 16];
            if (idx == 0) continue;
            dstRow[x] = nspire_blend565(palette[idx], dstRow[x], a);
        }
    }
}

void NspireRenderer_drawSpritePart4StretchedBlend(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStrideBytes,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    int32_t destX, int32_t destY, int32_t destW, int32_t destH, uint8_t alpha
) {
    if (destW <= 0 || destH <= 0 || srcW <= 0 || srcH <= 0) return;
    uint32_t a = (uint32_t) alpha * 32u / 255u;
    if (a == 0) return;
    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;
    int32_t startX = destX < scX0 ? scX0 : destX;
    int32_t startY = destY < scY0 ? scY0 : destY;
    int32_t endX = (destX + destW) < scX1 ? (destX + destW) : scX1;
    int32_t endY = (destY + destH) < scY1 ? (destY + destH) : scY1;
    if (startX >= endX || startY >= endY) return;
    int32_t stepX = (int32_t) (((int64_t) srcW << 16) / destW);
    int32_t stepY = (int32_t) (((int64_t) srcH << 16) / destH);
    int32_t startU = stepX * (startX - destX);
    int32_t startV = stepY * (startY - destY);
    int32_t v = startV;
    for (int32_t y = startY; y < endY; y++, v += stepY) {
        int32_t sy = srcY + (v >> 16);
        const uint8_t* srcRow = atlas + sy * atlasStrideBytes;
        uint16_t* dstRow = r->fb + y * r->fbW;
        int32_t u = startU;
        for (int32_t x = startX; x < endX; x++, u += stepX) {
            int32_t ax = srcX + (u >> 16);
            uint8_t byte = srcRow[ax >> 1];
            uint8_t idx = (ax & 1) ? (byte >> 4) : (byte & 0x0F);
            if (idx == 0) continue;
            dstRow[x] = nspire_blend565(palette[idx], dstRow[x], a);
        }
    }
}

// ===[ General affine blit + line ]===

static inline int32_t ns_ifloor(float v) {
    int32_t i = (int32_t) v;
    return ((float) i > v) ? i - 1 : i;
}
static inline int32_t ns_iceil(float v) {
    int32_t i = (int32_t) v;
    return ((float) i < v) ? i + 1 : i;
}

// Per-row x-interval (continuous, in pixel space) where A*(x+0.5)+B stays in
// [0, lim). Returns false if the row never satisfies it. ±BIG = unconstrained.
#define NS_AFFINE_BIG 1.0e9f
static inline bool ns_axisSpan(float A, float B, float lim, float* lo, float* hi) {
    if (A > -1e-9f && A < 1e-9f) {
        if (B >= 0.0f && B < lim) { *lo = -NS_AFFINE_BIG; *hi = NS_AFFINE_BIG; return true; }
        return false;
    }
    float xa = (0.0f - B) / A - 0.5f;
    float xb = (lim   - B) / A - 0.5f;
    if (xa <= xb) { *lo = xa; *hi = xb; } else { *lo = xb; *hi = xa; }
    return true;
}

void NspireRenderer_drawSpriteAffine(
    NspireRenderer* r,
    const uint8_t* atlas, int32_t atlasStride, uint8_t bpp,
    int32_t srcX, int32_t srcY, int32_t srcW, int32_t srcH,
    const uint16_t* palette,
    float ox, float oy, float ux, float uy, float vx, float vy,
    uint8_t alpha
) {
    if (srcW <= 0 || srcH <= 0) return;
    float det = ux * vy - uy * vx;
    float adet = det < 0 ? -det : det;
    if (adet < 1e-6f) return;
    float inv = 1.0f / det;

    // Destination bounding box from the four mapped corners.
    float fsw = (float) srcW, fsh = (float) srcH;
    float cx[4] = { ox, ox + fsw * ux, ox + fsh * vx, ox + fsw * ux + fsh * vx };
    float cy[4] = { oy, oy + fsw * uy, oy + fsh * vy, oy + fsw * uy + fsh * vy };
    float minXf = cx[0], maxXf = cx[0], minYf = cy[0], maxYf = cy[0];
    for (int i = 1; i < 4; i++) {
        if (cx[i] < minXf) minXf = cx[i]; if (cx[i] > maxXf) maxXf = cx[i];
        if (cy[i] < minYf) minYf = cy[i]; if (cy[i] > maxYf) maxYf = cy[i];
    }

    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;
    if (scX0 < 0) scX0 = 0;
    if (scY0 < 0) scY0 = 0;
    if (scX1 > r->fbW) scX1 = r->fbW;
    if (scY1 > r->fbH) scY1 = r->fbH;

    (void) minXf; (void) maxXf; // x bounds come from the per-row span, not the AABB
    int32_t y0 = ns_ifloor(minYf);     if (y0 < scY0) y0 = scY0;
    int32_t y1 = ns_ifloor(maxYf) + 1; if (y1 > scY1) y1 = scY1;
    if (scX0 >= scX1 || y0 >= y1) return;

    uint32_t a = (uint32_t) alpha * 32u / 255u; // 0..32
    if (a == 0) return;
    bool opaque = (a >= 32u);

    // Per-pixel-x source increments are constant for the whole sprite. 16.16
    // fixed point so the inner loop has zero float ops (ARM926 has no FPU —
    // per-pixel software float was the bottleneck).
    float As = vy * inv;    // ds/dx
    float At = -uy * inv;   // dt/dx
    int32_t dsdx = (int32_t) (As * 65536.0f);
    int32_t dtdx = (int32_t) (At * 65536.0f);
    float fSrcW = (float) srcW, fSrcH = (float) srcH;

    for (int32_t py = y0; py < y1; py++) {
        float dyo = (float) py + 0.5f - oy;
        // s(x) = As*(x+0.5) + Bs ; t(x) = At*(x+0.5) + Bt
        float Bs = inv * (-ox * vy - dyo * vx);
        float Bt = inv * (ux * dyo + uy * ox);

        // Intersect the s∈[0,srcW) and t∈[0,srcH) x-intervals with the scissor.
        float sLo, sHi, tLo, tHi;
        if (!ns_axisSpan(As, Bs, fSrcW, &sLo, &sHi)) continue;
        if (!ns_axisSpan(At, Bt, fSrcH, &tLo, &tHi)) continue;
        float lo = sLo > tLo ? sLo : tLo;
        float hi = sHi < tHi ? sHi : tHi;
        int32_t startX = ns_iceil(lo);   if (startX < scX0) startX = scX0;
        int32_t endX   = ns_ifloor(hi) + 1; if (endX > scX1) endX = scX1;
        if (startX >= endX) continue;

        // Seed fixed-point (s,t) at the first pixel — one float eval per row.
        int32_t sFx = (int32_t) ((As * ((float) startX + 0.5f) + Bs) * 65536.0f);
        int32_t tFx = (int32_t) ((At * ((float) startX + 0.5f) + Bt) * 65536.0f);

        uint16_t* dstRow = r->fb + py * r->fbW;
        for (int32_t px = startX; px < endX; px++) {
            uint32_t s = (uint32_t) (sFx >> 16);
            uint32_t t = (uint32_t) (tFx >> 16);
            sFx += dsdx; tFx += dtdx;
            // Unsigned compare folds the >=0 and <srcW/H checks into one each;
            // catches the ±1px rounding the float span can leave at the edges.
            if (s >= (uint32_t) srcW || t >= (uint32_t) srcH) continue;
            const uint8_t* srcRow = atlas + (srcY + (int32_t) t) * atlasStride;
            uint8_t idx;
            if (bpp == 8) {
                idx = srcRow[srcX + (int32_t) s];
            } else {
                int32_t ax = srcX + (int32_t) s;
                uint8_t byte = srcRow[ax >> 1];
                idx = (ax & 1) ? (byte >> 4) : (byte & 0x0F);
            }
            if (idx == 0) continue;
            uint16_t c = palette[idx];
            dstRow[px] = opaque ? c : nspire_blend565(c, dstRow[px], a);
        }
    }
}

void NspireRenderer_drawLine(
    NspireRenderer* r,
    int32_t x0, int32_t y0, int32_t x1, int32_t y1,
    int32_t width, uint16_t color, uint8_t alpha
) {
    if (width < 1) width = 1;
    uint32_t a = (uint32_t) alpha * 32u / 255u;
    if (a == 0) return;
    bool opaque = (a >= 32u);

    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;
    if (scX0 < 0) scX0 = 0;
    if (scY0 < 0) scY0 = 0;
    if (scX1 > r->fbW) scX1 = r->fbW;
    if (scY1 > r->fbH) scY1 = r->fbH;
    if (scX0 >= scX1 || scY0 >= scY1) return;

    int32_t half = width / 2;

    // Axis-aligned fast path: box borders, dialogue frames, HUD rules — the
    // overwhelmingly common case. GameMaker line thickness is PERPENDICULAR to
    // the line with butt caps: endpoints are exact; width never extends ALONG
    // the line's axis. The DDA pen below centred a width*width square at every
    // step, so it spilled `half` past each endpoint — boxes drew their top/
    // bottom edges a few px too long past the corners (also visible in DELTARUNE
    // Ch1 dialogue frames). Rasterise these as one exact span: correct + crisp.
    if (y0 == y1) {                                   // horizontal
        int32_t xlo = x0 < x1 ? x0 : x1;
        int32_t xhi = x0 < x1 ? x1 : x0;
        int32_t ylo = y0 - half, yhi = ylo + width;
        if (xlo < scX0) xlo = scX0;
        if (xhi > scX1 - 1) xhi = scX1 - 1;
        if (ylo < scY0) ylo = scY0;
        if (yhi > scY1) yhi = scY1;
        for (int32_t yy = ylo; yy < yhi; yy++) {
            uint16_t* row = r->fb + yy * r->fbW;
            for (int32_t xx = xlo; xx <= xhi; xx++)
                row[xx] = opaque ? color : nspire_blend565(color, row[xx], a);
        }
        return;
    }
    if (x0 == x1) {                                   // vertical
        int32_t ylo = y0 < y1 ? y0 : y1;
        int32_t yhi = y0 < y1 ? y1 : y0;
        int32_t xlo = x0 - half, xhi = xlo + width;
        if (ylo < scY0) ylo = scY0;
        if (yhi > scY1 - 1) yhi = scY1 - 1;
        if (xlo < scX0) xlo = scX0;
        if (xhi > scX1) xhi = scX1;
        for (int32_t yy = ylo; yy <= yhi; yy++) {
            uint16_t* row = r->fb + yy * r->fbW;
            for (int32_t xx = xlo; xx < xhi; xx++)
                row[xx] = opaque ? color : nspire_blend565(color, row[xx], a);
        }
        return;
    }

    // Diagonal: keep the DDA square-pen. Rare in these games (carousels use
    // fillTriangle, not lines); its minor axial spill is not the reported bug,
    // and leaving it avoids regressing the uncommon diagonal-line path.
    int32_t dx = x1 - x0, dy = y1 - y0;
    int32_t adx = dx < 0 ? -dx : dx;
    int32_t ady = dy < 0 ? -dy : dy;
    int32_t steps = adx > ady ? adx : ady;

    float fx = (float) x0, fy = (float) y0;
    float ix = steps ? (float) dx / (float) steps : 0.0f;
    float iy = steps ? (float) dy / (float) steps : 0.0f;

    for (int32_t i = 0; i <= steps; i++) {
        int32_t cx = (int32_t) (fx + 0.5f);
        int32_t cy = (int32_t) (fy + 0.5f);
        int32_t bx0 = cx - half, bx1 = bx0 + width;
        int32_t by0 = cy - half, by1 = by0 + width;
        if (bx0 < scX0) bx0 = scX0;
        if (by0 < scY0) by0 = scY0;
        if (bx1 > scX1) bx1 = scX1;
        if (by1 > scY1) by1 = scY1;
        for (int32_t yy = by0; yy < by1; yy++) {
            uint16_t* row = r->fb + yy * r->fbW;
            for (int32_t xx = bx0; xx < bx1; xx++) {
                row[xx] = opaque ? color : nspire_blend565(color, row[xx], a);
            }
        }
        fx += ix; fy += iy;
    }
}

void NspireRenderer_fillTriangle(
    NspireRenderer* r,
    int32_t x0, int32_t y0, int32_t x1, int32_t y1, int32_t x2, int32_t y2,
    uint16_t color, uint8_t alpha
) {
    uint32_t aw = (uint32_t) alpha * 32u / 255u;
    if (aw == 0) return;
    bool opaque = (aw >= 32u);

    // Twice signed area; degenerate triangles contribute nothing.
    int32_t area = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
    if (area == 0) return;

    // Bounding box clamped to scissor ∩ framebuffer.
    int32_t minX = x0 < x1 ? (x0 < x2 ? x0 : x2) : (x1 < x2 ? x1 : x2);
    int32_t maxX = x0 > x1 ? (x0 > x2 ? x0 : x2) : (x1 > x2 ? x1 : x2);
    int32_t minY = y0 < y1 ? (y0 < y2 ? y0 : y2) : (y1 < y2 ? y1 : y2);
    int32_t maxY = y0 > y1 ? (y0 > y2 ? y0 : y2) : (y1 > y2 ? y1 : y2);
    int32_t scX0 = r->scissorX, scY0 = r->scissorY;
    int32_t scX1 = scX0 + r->scissorW, scY1 = scY0 + r->scissorH;
    if (scX0 < 0) scX0 = 0;
    if (scY0 < 0) scY0 = 0;
    if (scX1 > r->fbW) scX1 = r->fbW;
    if (scY1 > r->fbH) scY1 = r->fbH;
    if (minX < scX0) minX = scX0;
    if (minY < scY0) minY = scY0;
    if (maxX > scX1 - 1) maxX = scX1 - 1;
    if (maxY > scY1 - 1) maxY = scY1 - 1;
    if (minX > maxX || minY > maxY) return;

    // Edge functions wK(px,py) = (bx-ax)*(py-ay) - (by-ay)*(px-ax).
    // ∂/∂px = -(by-ay) = sxK, ∂/∂py = (bx-ax) = syK. Each wK is linear in px,
    // so per scanline the inside region is one contiguous span — solve for its
    // [lo,hi] from the three half-plane constraints instead of testing every
    // bbox pixel (no per-pixel multiplies, no float; ARM926 has no FPU).
    int32_t sx0 = -(y2 - y1), sy0 = (x2 - x1);   // w0: edge (x1,y1)->(x2,y2)
    int32_t sx1 = -(y0 - y2), sy1 = (x0 - x2);   // w1: edge (x2,y2)->(x0,y0)
    int32_t sx2 = -(y1 - y0), sy2 = (x1 - x0);   // w2: edge (x0,y0)->(x1,y1)
    int32_t w0r = (x2 - x1) * (minY - y1) - (y2 - y1) * (minX - x1);
    int32_t w1r = (x0 - x2) * (minY - y2) - (y0 - y2) * (minX - x2);
    int32_t w2r = (x1 - x0) * (minY - y0) - (y1 - y0) * (minX - x0);

    // Normalize so "inside" is fK >= 0 for all edges regardless of winding.
    int32_t s = (area > 0) ? 1 : -1;
    int32_t dx0 = s * sx0, dx1 = s * sx1, dx2 = s * sx2;
    int32_t spanW = maxX - minX;
    uint32_t c2 = (uint32_t) color | ((uint32_t) color << 16);

    // ARM926 has no hardware divide, so the per-edge column boundary
    // B = -f/dx (linear in the row) is tracked incrementally in 16.16 fixed
    // point: two 64-bit divides per non-vertical edge at setup, then one add
    // per scanline (vs a software divide per scanline before).
    int64_t b0 = 0, b1 = 0, b2 = 0;     // current boundary, 16.16
    int64_t db0 = 0, db1 = 0, db2 = 0;  // per-row delta
    int32_t f0_0 = s * w0r, f1_0 = s * w1r, f2_0 = s * w2r;
    if (dx0) { b0 = ((int64_t) (-f0_0) << 16) / dx0; db0 = ((int64_t) (-(s * sy0)) << 16) / dx0; }
    if (dx1) { b1 = ((int64_t) (-f1_0) << 16) / dx1; db1 = ((int64_t) (-(s * sy1)) << 16) / dx1; }
    if (dx2) { b2 = ((int64_t) (-f2_0) << 16) / dx2; db2 = ((int64_t) (-(s * sy2)) << 16) / dx2; }

    for (int32_t py = minY; py <= maxY; py++) {
        int32_t lo = 0, hi = spanW;
        bool empty = false;

        // edge k: inside <=> f = fRow + dx*t >= 0, t (= px-minX) in [0,spanW].
        // dx==0 -> f constant across the row (in/out wholesale);
        // dx>0  -> t >= ceil(B);  dx<0 -> t <= floor(B);  B tracked in b*.
        #define NS_TRI_EDGE(DX, WR, B) do {                                   \
            if ((DX) == 0) { if ((s) * (WR) < 0) empty = true; }              \
            else {                                                            \
                int64_t _fl = (B) >> 16;                                      \
                if ((DX) > 0) {                                               \
                    int64_t _c = _fl + (((uint64_t) (B) & 0xFFFFu) ? 1 : 0);  \
                    if (_c > spanW) _c = spanW + 1; else if (_c < 0) _c = 0;  \
                    if ((int32_t) _c > lo) lo = (int32_t) _c;                 \
                } else {                                                      \
                    if (_fl < 0) _fl = -1; else if (_fl > spanW) _fl = spanW; \
                    if ((int32_t) _fl < hi) hi = (int32_t) _fl;               \
                }                                                             \
            }                                                                 \
        } while (0)
        NS_TRI_EDGE(dx0, w0r, b0);
        if (!empty) NS_TRI_EDGE(dx1, w1r, b1);
        if (!empty) NS_TRI_EDGE(dx2, w2r, b2);
        #undef NS_TRI_EDGE

        if (!empty && lo <= hi) {
            uint16_t* row = r->fb + py * r->fbW;
            int32_t px = minX + lo;
            int32_t pxEnd = minX + hi;          // inclusive
            if (opaque) {
                if (px & 1) { row[px] = color; px++; }
                // 32-bit paired stores only when truly word-aligned (ARM926
                // faults on unaligned word access); scalar tail/fallback.
                if (px <= pxEnd && (((uintptr_t) (row + px)) & 3u) == 0u) {
                    uint32_t* rp = (uint32_t*) (row + px);
                    int32_t pairs = (pxEnd - px + 1) >> 1;
                    for (int32_t i = 0; i < pairs; i++) rp[i] = c2;
                    px += pairs << 1;
                }
                for (; px <= pxEnd; px++) row[px] = color;
            } else {
                for (; px <= pxEnd; px++)
                    row[px] = nspire_blend565(color, row[px], aw);
            }
        }
        w0r += sy0; w1r += sy1; w2r += sy2;
        b0 += db0; b1 += db1; b2 += db2;
    }
}

void NspireRenderer_blitSurface(
    NspireRenderer* r,
    const uint16_t* src, int32_t srcStride,
    int32_t sx, int32_t sy, int32_t sw, int32_t sh,
    int32_t dx, int32_t dy, int32_t dw, int32_t dh,
    uint8_t alpha, uint32_t tint
) {
    if (!src || dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0) return;
    uint32_t aw = (uint32_t) alpha * 32u / 255u;
    if (aw == 0) return;
    bool opaque = (aw >= 32u);
    bool doTint = ((tint & 0x00FFFFFFu) != 0x00FFFFFFu);
    uint32_t tr = 0, tg = 0, tb = 0;
    if (doTint) { tr = tint & 0xFFu; tg = (tint >> 8) & 0xFFu; tb = (tint >> 16) & 0xFFu; }

    // Dest clipped to scissor ∩ framebuffer.
    int32_t cx0 = r->scissorX, cy0 = r->scissorY;
    int32_t cx1 = cx0 + r->scissorW, cy1 = cy0 + r->scissorH;
    if (cx0 < 0) cx0 = 0;
    if (cy0 < 0) cy0 = 0;
    if (cx1 > r->fbW) cx1 = r->fbW;
    if (cy1 > r->fbH) cy1 = r->fbH;
    int32_t x0 = dx < cx0 ? cx0 : dx;
    int32_t y0 = dy < cy0 ? cy0 : dy;
    int32_t x1 = dx + dw < cx1 ? dx + dw : cx1;
    int32_t y1 = dy + dh < cy1 ? dy + dh : cy1;
    if (x0 >= x1 || y0 >= y1) return;

    // 16.16 source coords; inverse-mapped per dest pixel (nearest).
    int32_t stepX = (int32_t) (((int64_t) sw << 16) / dw);
    int32_t stepY = (int32_t) (((int64_t) sh << 16) / dh);
    int32_t su0 = (int32_t) ((((int64_t) (x0 - dx)) * sw << 16) / dw);
    int32_t sv  = (int32_t) ((((int64_t) (y0 - dy)) * sh << 16) / dh);

    for (int32_t py = y0; py < y1; py++, sv += stepY) {
        int32_t srow = sv >> 16;
        if (srow < 0) srow = 0; else if (srow >= sh) srow = sh - 1;
        const uint16_t* sp = src + (int64_t) (sy + srow) * srcStride + sx;
        uint16_t* dp = r->fb + (int64_t) py * r->fbW;
        int32_t su = su0;
        for (int32_t px = x0; px < x1; px++, su += stepX) {
            int32_t scol = su >> 16;
            if (scol < 0) scol = 0; else if (scol >= sw) scol = sw - 1;
            uint16_t s = sp[scol];
            if (doTint) {
                uint32_t r5 = (((s >> 11) & 0x1Fu) * (tr + 1u)) >> 8;
                uint32_t g6 = (((s >> 5)  & 0x3Fu) * (tg + 1u)) >> 8;
                uint32_t b5 = (( s        & 0x1Fu) * (tb + 1u)) >> 8;
                s = (uint16_t) ((r5 << 11) | (g6 << 5) | b5);
            }
            dp[px] = opaque ? s : nspire_blend565(s, dp[px], aw);
        }
    }
}
