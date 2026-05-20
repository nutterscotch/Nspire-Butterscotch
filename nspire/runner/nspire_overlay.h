#pragma once

#include <stdint.h>
#include <stdbool.h>

#include "nspire_renderer.h"
#include "nspire_asset.h"

// PS2-style debug + loading overlay for the Nspire build. Borrows the look and
// feel of src/ps2/ps2_overlay.* but renders into our 320x240 RGB565 framebuffer
// using the bundled 5x7 bitmap font.

typedef enum {
    NSPIRE_OVERLAY_DISABLED = 0,
    NSPIRE_OVERLAY_ENABLED  = 1,
    NSPIRE_OVERLAY_MAX
} NspireOverlayState;

#define NSPIRE_OVERLAY_MAX_STATS 12
typedef struct {
    char label[12];
    uint32_t count;
} NspireChunkStat;

struct DataWin;

typedef struct {
    NspireOverlayState state;
    NspireRenderer* fb;
    NspireAssets* assets;       // borrowed; may be NULL
    const struct DataWin* dataWin; // borrowed; may be NULL during early boot
    const void* hudFont;        // const Font* — Undertale's HUD font; NULL falls back to 5x7
    uint32_t heapTotalBytes;    // approximate heap budget (best-effort)
    // Loading screen accumulators.
    NspireChunkStat stats[NSPIRE_OVERLAY_MAX_STATS];
    int statCount;
    // Per-frame counters fed in from the engine loop.
    float lastTickMs;
    float lastStepMs;
    float lastDrawMs;
} NspireOverlay;

void NspireOverlay_init(NspireOverlay* o, NspireRenderer* fb, NspireAssets* assets);
void NspireOverlay_free(NspireOverlay* o);

// Hand DataWin + an asset bundle to the overlay so the HUD can use Undertale's real
// font for text. Pick a font by name; if it's missing we fall back to 5x7.
void NspireOverlay_bindDataWin(NspireOverlay* o, NspireAssets* assets, const struct DataWin* dataWin, const char* preferredFontName);

NspireOverlayState NspireOverlay_getState(const NspireOverlay* o);
void NspireOverlay_setState(NspireOverlay* o, NspireOverlayState state);
void NspireOverlay_toggle(NspireOverlay* o);

// Direct text rendering helpers (used by both overlay views and ns_drawText).
void NspireOverlay_drawText(NspireRenderer* fb, int32_t x, int32_t y, uint16_t color, const char* text);
void NspireOverlay_drawTextScaled(NspireRenderer* fb, int32_t x, int32_t y, uint16_t color, int32_t scale, const char* text);

// Loading screen — call from a DataWin parser progress callback. Paints title,
// progress bar, chunk-name, memory usage, and a sidebar of cumulative chunk
// stats, then blits the framebuffer to the LCD.
struct DataWin;
void NspireOverlay_drawLoadingScreen(NspireOverlay* o,
                                     const char* gameName,
                                     const char* chunkName,
                                     int chunkIndex,
                                     int totalChunks,
                                     uint32_t chunkLength,
                                     const struct DataWin* dataWin);

// Run-time HUD: drawn on top of the engine output once per frame when state ==
// NSPIRE_OVERLAY_ENABLED. Pass the per-frame timings (milliseconds).
struct Runner;
void NspireOverlay_drawDebugHud(NspireOverlay* o,
                                const struct Runner* runner,
                                float tickMs, float stepMs, float drawMs);
