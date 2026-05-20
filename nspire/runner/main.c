// Butterscotch Nspire runner — end-to-end smoke test.
// Loads the four cooked sidecar files from /documents/bs/ and draws the first
// N valid TPAG entries in a grid. Proves the preprocessor->runtime contract:
// CLUT lookup, atlas RLE decompression, PS2 swizzle undo, and the renderer
// fast path are all wired correctly.

#include <libndls.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>

#include "nspire_renderer.h"
#include "nspire_asset.h"

#define ASSET_DIR "/documents/bs/"

static uint16_t framebuffer[NSPIRE_FB_W * NSPIRE_FB_H];

static void draw_error_screen(NspireRenderer* r, uint16_t color) {
    NspireRenderer_clear(r, color);
}

int main(void) {
    if (!lcd_init(SCR_320x240_565)) return 1;

    NspireRenderer r;
    NspireRenderer_init(&r, framebuffer, NSPIRE_FB_W, NSPIRE_FB_H);

    NspireAssets assets;
    if (!NspireAssets_load(&assets, ASSET_DIR)) {
        draw_error_screen(&r, nspire_rgb565(180, 0, 0));
        lcd_blit(framebuffer, SCR_320x240_565);
        wait_key_pressed();
        lcd_init(SCR_TYPE_INVALID);
        return 1;
    }

    // Dark navy so transparent regions are obvious.
    NspireRenderer_clear(&r, nspire_rgb565(8, 8, 48));

    // Grid-layout the first ~24 valid TPAGs. Skips unmapped (0xFFFF) entries
    // and zero-size sprites. Wraps the cursor when a row fills.
    int32_t cursorX = 4;
    int32_t cursorY = 4;
    int32_t rowMaxH = 0;
    int32_t placed = 0;

    for (int32_t i = 0; i < assets.tpagCount && placed < 24; i++) {
        NspireTpagEntry* t = &assets.tpags[i];
        if (t->atlasId == 0xFFFF) continue;
        if (t->cropW == 0 || t->cropH == 0) continue;
        if (t->atlasId >= assets.atlasCount) continue;

        NspireAtlas* atlas = &assets.atlases[t->atlasId];
        const uint16_t* palette;
        if (atlas->bpp == 8) {
            if (t->clutIndex >= assets.clut8Count) continue;
            palette = assets.clut8[t->clutIndex].entries;
        } else if (atlas->bpp == 4) {
            if (t->clutIndex >= assets.clut4Count) continue;
            palette = assets.clut4[t->clutIndex].entries;
        } else {
            continue;
        }

        // Wrap to next row if this sprite doesn't fit in the current row.
        if (cursorX + t->cropW > NSPIRE_FB_W - 4) {
            cursorX = 4;
            cursorY += rowMaxH + 4;
            rowMaxH = 0;
        }
        if (cursorY + t->cropH > NSPIRE_FB_H - 4) break;

        if (atlas->bpp == 8) {
            NspireRenderer_drawSpritePart8(&r, atlas->pixels, atlas->stride,
                t->atlasX, t->atlasY, t->cropW, t->cropH,
                palette, cursorX, cursorY);
        } else {
            NspireRenderer_drawSpritePart4(&r, atlas->pixels, atlas->stride,
                t->atlasX, t->atlasY, t->cropW, t->cropH,
                palette, cursorX, cursorY);
        }

        cursorX += t->cropW + 4;
        if (t->cropH > rowMaxH) rowMaxH = t->cropH;
        placed++;
    }

    lcd_blit(framebuffer, SCR_320x240_565);
    wait_key_pressed();

    NspireAssets_free(&assets);
    lcd_init(SCR_TYPE_INVALID);
    return 0;
}
