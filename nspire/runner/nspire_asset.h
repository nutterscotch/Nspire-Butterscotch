#pragma once

#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>

// Cooked-asset loader for the Butterscotch Nspire preprocessor output.
// Consumes CLUT4.BIN / CLUT8.BIN / TEXTURES.BIN / ATLAS.BIN and turns them into
// CPU-side structures the renderer can sample directly. Palettes are converted
// to RGB565 (16 bit) at load time; atlas pixel data is RLE-decompressed; the
// PS2 CSM1 swizzle on 8bpp CLUTs is undone.

#define NSPIRE_PALETTE_SIZE_4 16
#define NSPIRE_PALETTE_SIZE_8 256

typedef struct {
    uint16_t entries[NSPIRE_PALETTE_SIZE_8];  // also used for 4bpp (only first 16 valid)
} NspireClut;

typedef struct {
    uint8_t* pixels;    // NULL when not resident. Owned. 8bpp: stride*height; 4bpp: stride*height.
    int32_t width;
    int32_t height;
    int32_t stride;     // bytes per row of pixel data
    uint8_t bpp;        // 4 or 8

    // Streaming metadata (filled at load time; used to lazily decompress on demand).
    uint32_t fileOffsetHeader;   // absolute byte offset of this atlas's 128-byte header in TEXTURES.BIN
    uint32_t pixelDataSize;      // compressed payload byte count
    uint8_t  compression;        // 0 = uncompressed, 1 = RLE
    uint8_t  touchedThisFrame;   // set by renderer on every draw; cleared at endFrame
} NspireAtlas;

typedef struct {
    uint16_t atlasId;       // 0xFFFF = unmapped
    int16_t atlasX;
    int16_t atlasY;
    uint16_t width;
    uint16_t height;
    int16_t cropX;
    int16_t cropY;
    uint16_t cropW;
    uint16_t cropH;
    uint16_t clutIndex;
} NspireTpagEntry;

typedef struct {
    NspireClut* clut4;
    int32_t clut4Count;
    NspireClut* clut8;
    int32_t clut8Count;

    NspireAtlas* atlases;
    int32_t atlasCount;

    NspireTpagEntry* tpags;
    int32_t tpagCount;

    FILE* texturesFile;     // kept open for the session so atlases can stream on demand
    int32_t residentBytes;  // tracks total bytes currently held in atlas pixel buffers
} NspireAssets;

// Loads all four cooked files from `dir`. Returns true on success, false on any
// I/O or format error. On failure, partial state is freed and `*a` is zeroed.
bool NspireAssets_load(NspireAssets* a, const char* dir);

void NspireAssets_free(NspireAssets* a);

// Eagerly decompress every atlas into RAM up front. Best-effort: atlases that fail to
// allocate are left NULL for on-demand streaming to pick up later. Returns the number of
// atlases successfully made resident; writes total resident bytes to *outResidentBytes.
// With ~30 MB free on the Nspire the whole (halved) atlas set fits, so this removes the
// streaming/eviction churn that was corrupting fonts in atlas-heavy scenes (battles).
int32_t NspireAssets_preloadAllAtlases(NspireAssets* a, uint32_t* outResidentBytes);

// Demand-loads an atlas's pixel buffer if not already resident. Returns true if the
// atlas pixels are usable after the call. Idempotent.
bool NspireAssets_ensureAtlas(NspireAssets* a, int32_t atlasIndex);

// Frees the pixel buffer of any atlas whose touchedThisFrame flag is clear, then clears
// the flag on the others. Call once per frame after all draw calls have completed so that
// atlases unused this frame don't pile up in RAM across frames.
void NspireAssets_releaseUntouchedAtlases(NspireAssets* a);
