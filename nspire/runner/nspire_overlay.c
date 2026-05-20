#include "nspire_overlay.h"
#include "nspire_font5x7.h"

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>

#include "data_win.h"
#include "runner.h"
#include "instance.h"
#include "stb_ds.h"

#include <libndls.h>
#include <os.h>

// Plot a single pixel with bounds + scissor check.
static inline void put_px(NspireRenderer* fb, int32_t x, int32_t y, uint16_t c) {
    if ((uint32_t) x >= (uint32_t) fb->fbW) return;
    if ((uint32_t) y >= (uint32_t) fb->fbH) return;
    fb->fb[y * fb->fbW + x] = c;
}

// Render one glyph (top-left at (x, y)) using `scale` integer upscaling.
// Background is transparent; only foreground pixels are written.
static void draw_glyph(NspireRenderer* fb, int32_t x, int32_t y, int32_t scale, uint16_t color, char ch) {
    uint8_t c = (uint8_t) ch;
    if (c < NSPIRE_FONT_FIRST || c > NSPIRE_FONT_LAST) c = '?';
    const uint8_t* g = &nspire_font5x7[(c - NSPIRE_FONT_FIRST) * NSPIRE_FONT_W];
    if (scale <= 1) {
        for (int col = 0; col < NSPIRE_FONT_W; col++) {
            uint8_t bits = g[col];
            for (int row = 0; row < NSPIRE_FONT_H; row++) {
                if (bits & (1u << row)) put_px(fb, x + col, y + row, color);
            }
        }
    } else {
        for (int col = 0; col < NSPIRE_FONT_W; col++) {
            uint8_t bits = g[col];
            for (int row = 0; row < NSPIRE_FONT_H; row++) {
                if (!(bits & (1u << row))) continue;
                int32_t px = x + col * scale;
                int32_t py = y + row * scale;
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        put_px(fb, px + dx, py + dy, color);
                    }
                }
            }
        }
    }
}

void NspireOverlay_drawText(NspireRenderer* fb, int32_t x, int32_t y, uint16_t color, const char* text) {
    NspireOverlay_drawTextScaled(fb, x, y, color, 1, text);
}

// Render a single Undertale-font glyph. atlas pixels are already halved by the preprocessor,
// so we halve the glyph's engine-space source coords too. `color` is currently ignored — the
// glyph paints with its baked palette colors. Returns the advance (in framebuffer pixels).
static int32_t draw_glyph_game(NspireOverlay* o, int32_t x, int32_t y, char ch, uint16_t fallbackColor) {
    if (!o->hudFont || !o->assets || !o->dataWin) return 0;
    const Font* font = (const Font*) o->hudFont;
    uint8_t uch = (uint8_t) ch;
    FontGlyph* g = uch < 128 ? font->glyphLUT[uch] : NULL;
    if (!g && ch == ' ') {
        // Many fonts skip the space glyph; fake the advance.
        int32_t adv = (int32_t) (font->emSize / 4);
        if (adv < 3) adv = 3;
        return adv;
    }
    if (!g) return draw_glyph_game(o, x, y, '?', fallbackColor);

    // The font's tpagIndex points at the cooked TPAG table that nspire_asset built.
    int32_t tpagIdx = font->tpagIndex;
    if (tpagIdx < 0 || tpagIdx >= o->assets->tpagCount) {
        draw_glyph(o->fb, x, y, 1, fallbackColor, ch);
        return NSPIRE_FONT_W + 1;
    }
    NspireTpagEntry* t = &o->assets->tpags[tpagIdx];
    if (t->atlasId == 0xFFFF || (int32_t) t->atlasId >= o->assets->atlasCount) {
        draw_glyph(o->fb, x, y, 1, fallbackColor, ch);
        return NSPIRE_FONT_W + 1;
    }
    if (!NspireAssets_ensureAtlas(o->assets, (int32_t) t->atlasId)) {
        draw_glyph(o->fb, x, y, 1, fallbackColor, ch);
        return NSPIRE_FONT_W + 1;
    }
    NspireAtlas* atlas = &o->assets->atlases[t->atlasId];

    // Halve the glyph's engine source rect to land on the cooked (halved) atlas.
    int32_t srcX = t->atlasX + (g->sourceX / 2);
    int32_t srcY = t->atlasY + (g->sourceY / 2);
    int32_t srcW = (g->sourceWidth + 1) / 2;
    int32_t srcH = (g->sourceHeight + 1) / 2;
    if (srcW <= 0 || srcH <= 0) {
        int32_t adv = g->shift / 2;
        return adv > 0 ? adv : 1;
    }

    // Pick the right palette. Font glyphs use the TPAG's CLUT entry.
    const uint16_t* palette = NULL;
    if (atlas->bpp == 8) {
        if (t->clutIndex < (uint16_t) o->assets->clut8Count) palette = o->assets->clut8[t->clutIndex].entries;
    } else {
        if (t->clutIndex < (uint16_t) o->assets->clut4Count) palette = o->assets->clut4[t->clutIndex].entries;
    }
    if (!palette) {
        draw_glyph(o->fb, x, y, 1, fallbackColor, ch);
        return NSPIRE_FONT_W + 1;
    }

    int32_t destX = x;
    int32_t destY = y;
    if (atlas->bpp == 8) {
        NspireRenderer_drawSpritePart8(o->fb, atlas->pixels, atlas->stride,
            srcX, srcY, srcW, srcH, palette, destX, destY);
    } else {
        NspireRenderer_drawSpritePart4(o->fb, atlas->pixels, atlas->stride,
            srcX, srcY, srcW, srcH, palette, destX, destY);
    }

    int32_t adv = g->shift / 2;
    return adv > 0 ? adv : srcW + 1;
}

// Draw a string using Undertale's font if available, otherwise the 5x7 fallback.
static void draw_text_hud(NspireOverlay* o, int32_t x, int32_t y, uint16_t fallbackColor, const char* text) {
    if (!o->hudFont || !o->assets || !o->dataWin) {
        NspireOverlay_drawTextScaled(o->fb, x, y, fallbackColor, 1, text);
        return;
    }
    int32_t cx = x;
    int32_t cy = y;
    int32_t lineH = (((const Font*) o->hudFont)->maxGlyphHeight + 1) / 2 + 1;
    if (lineH < 6) lineH = 6;
    for (const char* p = text; *p; p++) {
        char ch = *p;
        if (ch == '\n') { cx = x; cy += lineH; continue; }
        cx += draw_glyph_game(o, cx, cy, ch, fallbackColor);
    }
}

void NspireOverlay_drawTextScaled(NspireRenderer* fb, int32_t x, int32_t y, uint16_t color, int32_t scale, const char* text) {
    if (!text) return;
    if (scale < 1) scale = 1;
    int32_t cx = x;
    int32_t cy = y;
    int32_t advance = (NSPIRE_FONT_W + 1) * scale;
    int32_t lineH = (NSPIRE_FONT_H + 1) * scale;
    for (const char* p = text; *p; p++) {
        char ch = *p;
        if (ch == '\n') { cx = x; cy += lineH; continue; }
        draw_glyph(fb, cx, cy, scale, color, ch);
        cx += advance;
    }
}

// Width of a string in pixels at scale `scale` (terminates at first '\n').
static int32_t text_width(int32_t scale, const char* s) {
    int32_t advance = (NSPIRE_FONT_W + 1) * scale;
    int32_t n = 0;
    while (*s && *s != '\n') { n++; s++; }
    return n > 0 ? n * advance - scale : 0;
}

static void draw_text_centered(NspireRenderer* fb, int32_t cx, int32_t y, int32_t scale, uint16_t color, const char* text) {
    int32_t w = text_width(scale, text);
    NspireOverlay_drawTextScaled(fb, cx - w / 2, y, color, scale, text);
}

void NspireOverlay_init(NspireOverlay* o, NspireRenderer* fb, NspireAssets* assets) {
    memset(o, 0, sizeof(*o));
    o->fb = fb;
    o->assets = assets;
    o->state = NSPIRE_OVERLAY_DISABLED;
    // Best-effort heap-size estimate: walk down from 24 MB and grab the largest block we can.
    o->heapTotalBytes = 0;
    for (int32_t mb = 24; mb >= 1; mb--) {
        size_t bytes = (size_t) mb * 1024 * 1024;
        void* p = malloc(bytes);
        if (p) { o->heapTotalBytes = (uint32_t) bytes; free(p); break; }
    }
}

void NspireOverlay_free(NspireOverlay* o) {
    memset(o, 0, sizeof(*o));
}

void NspireOverlay_bindDataWin(NspireOverlay* o, NspireAssets* assets, const struct DataWin* dataWin, const char* preferredFontName) {
    o->assets = assets;
    o->dataWin = dataWin;
    o->hudFont = NULL;
    if (!dataWin || dataWin->font.count == 0) return;
    // Try the requested name first.
    if (preferredFontName) {
        for (uint32_t i = 0; i < dataWin->font.count; i++) {
            const Font* f = &dataWin->font.fonts[i];
            if (f->name && strcmp(f->name, preferredFontName) == 0) {
                o->hudFont = f;
                return;
            }
        }
    }
    // Common Undertale font names, in order of preference.
    static const char* const kFallbacks[] = { "fnt_small", "fnt_curs", "fnt_main", "fnt_maintext" };
    for (size_t k = 0; k < sizeof(kFallbacks) / sizeof(kFallbacks[0]); k++) {
        for (uint32_t i = 0; i < dataWin->font.count; i++) {
            const Font* f = &dataWin->font.fonts[i];
            if (f->name && strcmp(f->name, kFallbacks[k]) == 0) {
                o->hudFont = f;
                return;
            }
        }
    }
    // Last resort: first font.
    o->hudFont = &dataWin->font.fonts[0];
}

NspireOverlayState NspireOverlay_getState(const NspireOverlay* o) {
    return o->state;
}

void NspireOverlay_setState(NspireOverlay* o, NspireOverlayState s) {
    o->state = s;
}

void NspireOverlay_toggle(NspireOverlay* o) {
    o->state = (NspireOverlayState) ((o->state + 1) % NSPIRE_OVERLAY_MAX);
}

// Approximate the largest contiguous block we can still malloc, in bytes.
// Cheap-by-design: probe MB-granularity from 24 down to 1, then 256 KB units
// to refine. At most ~28 malloc/free calls — fine for once-per-frame.
static uint32_t probe_free_bytes(void) {
    int32_t mb = 0;
    for (mb = 24; mb >= 1; mb--) {
        void* p = malloc((size_t) mb * 1024 * 1024);
        if (p) { free(p); break; }
    }
    if (mb < 1) {
        // Less than 1 MB free. Refine in 64 KB steps.
        for (int kb = 960; kb >= 64; kb -= 64) {
            void* p = malloc((size_t) kb * 1024);
            if (p) { free(p); return (uint32_t) kb * 1024; }
        }
        return 0;
    }
    // Refine the upper end in 256 KB units above `mb`.
    uint32_t base = (uint32_t) mb * 1024 * 1024;
    for (int extra = 768; extra >= 256; extra -= 256) {
        void* p = malloc(base + (size_t) extra * 1024);
        if (p) { free(p); return base + (uint32_t) extra * 1024; }
    }
    return base;
}

void NspireOverlay_drawLoadingScreen(NspireOverlay* o,
                                     const char* gameName,
                                     const char* chunkName,
                                     int chunkIndex,
                                     int totalChunks,
                                     uint32_t chunkLength,
                                     const struct DataWin* dataWin) {
    NspireRenderer* fb = o->fb;
    NspireRenderer_clear(fb, 0);

    uint16_t titleC = nspire_rgb565(0xE8, 0xA5, 0x52);
    uint16_t white  = nspire_rgb565(0xFF, 0xFF, 0xFF);
    uint16_t gray   = nspire_rgb565(0xAA, 0xAA, 0xAA);
    uint16_t darkGy = nspire_rgb565(0x60, 0x60, 0x60);
    uint16_t barBg  = nspire_rgb565(0x40, 0x40, 0x40);
    uint16_t barFg  = nspire_rgb565(0xFF, 0xCC, 0x00);

    int32_t cx = fb->fbW / 2;

    // Title + game name.
    draw_text_centered(fb, cx, 18, 2, titleC, "Butterscotch");
    if (gameName) {
        draw_text_centered(fb, cx, 40, 1, gray, gameName);
    }

    // Progress bar.
    int32_t barX = 30;
    int32_t barY = 130;
    int32_t barW = fb->fbW - 2 * barX;
    int32_t barH = 10;
    NspireRenderer_fillRect(fb, barX, barY, barW, barH, barBg);
    if (totalChunks > 0) {
        int32_t fillW = (int32_t) (((int64_t) barW * (chunkIndex + 1)) / totalChunks);
        if (fillW > 0) NspireRenderer_fillRect(fb, barX, barY, fillW, barH, barFg);

        // Percent text centered on the bar.
        char pct[12];
        int p = (int) (((int64_t) (chunkIndex + 1) * 100) / totalChunks);
        snprintf(pct, sizeof(pct), "%d%%", p);
        draw_text_centered(fb, cx, barY + (barH - NSPIRE_FONT_H) / 2, 1, white, pct);
    }

    // Status line below the bar.
    char buf[64];
    snprintf(buf, sizeof(buf), "Loading %.4s... (%d/%d)", chunkName, chunkIndex + 1, totalChunks);
    draw_text_centered(fb, cx, barY + barH + 6, 1, white, buf);

    snprintf(buf, sizeof(buf), "%lu bytes", (unsigned long) chunkLength);
    draw_text_centered(fb, cx, barY + barH + 16, 1, gray, buf);

    // Chunk-item stat sidebar in the top-left, accumulating as parser learns counts.
    if (dataWin) {
        struct { const uint32_t* p; const char* label; } src[] = {
            { &dataWin->sond.count, "sounds"  },
            { &dataWin->sprt.count, "sprites" },
            { &dataWin->bgnd.count, "bgnds"   },
            { &dataWin->font.count, "fonts"   },
            { &dataWin->objt.count, "objs"    },
            { &dataWin->room.count, "rooms"   },
            { &dataWin->code.count, "codes"   },
            { &dataWin->scpt.count, "scripts" },
            { &dataWin->txtr.count, "tex"     },
        };
        int N = (int) (sizeof(src) / sizeof(src[0]));
        for (int i = 0; i < N; i++) {
            if (*src[i].p == 0) continue;
            bool found = false;
            for (int j = 0; j < o->statCount; j++) {
                if (strcmp(o->stats[j].label, src[i].label) == 0) { found = true; break; }
            }
            if (!found && o->statCount < NSPIRE_OVERLAY_MAX_STATS) {
                NspireChunkStat* s = &o->stats[o->statCount++];
                snprintf(s->label, sizeof(s->label), "%s", src[i].label);
                s->count = *src[i].p;
            }
        }
    }
    int32_t sy = 6;
    for (int i = 0; i < o->statCount; i++) {
        char ln[24];
        snprintf(ln, sizeof(ln), "%lu %s", (unsigned long) o->stats[i].count, o->stats[i].label);
        NspireOverlay_drawTextScaled(fb, 4, sy, gray, 1, ln);
        sy += NSPIRE_FONT_H + 1;
    }

    // Footer.
    NspireOverlay_drawTextScaled(fb, 4, fb->fbH - NSPIRE_FONT_H - 2, darkGy, 1, "Butterscotch nspire");

    // Blit immediately so progress is visible even if the next chunk takes seconds.
    lcd_blit(fb->fb, SCR_320x240_565);
}

void NspireOverlay_drawDebugHud(NspireOverlay* o, const struct Runner* runner, float tickMs, float stepMs, float drawMs) {
    if (o->state == NSPIRE_OVERLAY_DISABLED) return;
    NspireRenderer* fb = o->fb;

    o->lastTickMs = tickMs;
    o->lastStepMs = stepMs;
    o->lastDrawMs = drawMs;

    uint16_t white = nspire_rgb565(0xFF, 0xFF, 0xFF);
    uint16_t shade = nspire_rgb565(0x00, 0x00, 0x00);

    const char* roomName = "?";
    if (runner && runner->currentRoom && runner->currentRoom->name) roomName = runner->currentRoom->name;

    // Heap estimate — cached for 30 frames (~1s @ 30 FPS). The malloc/free walk is
    // expensive enough on newlib that running it every frame eats a chunk of our budget.
    static uint32_t cachedFreeBytes = 0;
    static int probeCountdown = 0;
    if (probeCountdown <= 0) {
        cachedFreeBytes = probe_free_bytes();
        probeCountdown = 30;
    } else {
        probeCountdown--;
    }
    uint32_t freeBytes = cachedFreeBytes;

    // Atlas residency (count atlases whose pixels are loaded).
    int residentAtlases = 0;
    int totalAtlases = 0;
    uint32_t residentKB = 0;
    if (o->assets) {
        totalAtlases = o->assets->atlasCount;
        for (int i = 0; i < o->assets->atlasCount; i++) {
            if (o->assets->atlases[i].pixels) {
                residentAtlases++;
            }
        }
        residentKB = (uint32_t) (o->assets->residentBytes / 1024);
    }

    int instances = runner ? (int) arrlen(runner->instances) : 0;

    char line[80];
    int32_t lineH = NSPIRE_FONT_H + 1;
    int32_t x = 2;
    int32_t y = 2;

    // Darken a strip behind the text (50% alpha) so it stays legible while the
    // game behind it is still visible.
    int32_t bgH = lineH * 9 + 2;
    NspireRenderer_blendRect(fb, 0, 0, fb->fbW, bgH, shade, 128);

    snprintf(line, sizeof(line), "Room: %s", roomName);
    NspireOverlay_drawTextScaled(fb, x, y, white, 1, line); y += lineH;

    // FPS = frames in the last whole second. clock() is unreliable on this newlib,
    // so we use time() which gives 1-second resolution — perfect for an FPS readout.
    static time_t lastSec = 0;
    static int framesThisSec = 0;
    static int displayedFps = 0;
    framesThisSec++;
    time_t now = time(NULL);
    if (now != lastSec) {
        displayedFps = framesThisSec;
        framesThisSec = 0;
        lastSec = now;
    }
    snprintf(line, sizeof(line), "FPS: %d", displayedFps);
    NspireOverlay_drawTextScaled(fb, x, y, white, 1, line); y += lineH;

    // Real per-frame split (hardware timer; clock() is dead on this newlib).
    // step = VM/logic, draw = render. Tells us which side is the bottleneck.
    snprintf(line, sizeof(line), "ms step:%d draw:%d tick:%d",
             (int) (stepMs + 0.5f), (int) (drawMs + 0.5f), (int) (tickMs + 0.5f));
    NspireOverlay_drawTextScaled(fb, x, y, white, 1, line); y += lineH;

    // Which draw path owns the frame: tri = draw_triangle (carousel), spr =
    // sprite blits (background/entities). c = calls, k = coarse Kpx proxy.
    snprintf(line, sizeof(line), "tri %uc %uk  spr %uc %uk",
             (unsigned) gNspireDrawStats.triCalls, (unsigned) (gNspireDrawStats.triPx / 1000u),
             (unsigned) gNspireDrawStats.sprCalls, (unsigned) (gNspireDrawStats.sprPx / 1000u));
    NspireOverlay_drawTextScaled(fb, x, y, white, 1, line); y += lineH;

    snprintf(line, sizeof(line), "Heap free: %lu KB", (unsigned long) (freeBytes / 1024));
    NspireOverlay_drawTextScaled(fb, x, y, white, 1, line); y += lineH;

    snprintf(line, sizeof(line), "Atlas resident: %d/%d  %lu KB",
             residentAtlases, totalAtlases, (unsigned long) residentKB);
    NspireOverlay_drawTextScaled(fb, x, y, white, 1, line); y += lineH;

    snprintf(line, sizeof(line), "Instances: %d", instances);
    NspireOverlay_drawTextScaled(fb, x, y, white, 1, line); y += lineH;

    int32_t roomSpeed = 0;
    if (runner && runner->currentRoom) roomSpeed = (int32_t) runner->currentRoom->speed;
    int32_t roomW = 0, roomH = 0;
    if (runner && runner->currentRoom) { roomW = runner->currentRoom->width; roomH = runner->currentRoom->height; }
    snprintf(line, sizeof(line), "Room speed: %ld  size: %ldx%ld", (long) roomSpeed, (long) roomW, (long) roomH);
    NspireOverlay_drawTextScaled(fb, x, y, white, 1, line); y += lineH;

    snprintf(line, sizeof(line), "+/- next/prev room  P pause  O step  0 reset");
    NspireOverlay_drawTextScaled(fb, x, y, nspire_rgb565(0xCC, 0xCC, 0xCC), 1, line); y += lineH;

}
