// Standalone test of the Nspire renderer fast path.
// Clears the framebuffer to dark navy, then draws the 16x16 test sprite at four
// positions: fully visible top-left, centered, partially off the right/bottom
// edge, and partially off the left edge with a negative destX. The goal is to
// visually confirm:
//   - palette indexing produces the expected colors
//   - alpha-test on index 0 lets background show through
//   - scissor/clip works at all four edges including negative dest coords

#include <libndls.h>
#include <stdint.h>

#include "nspire_renderer.h"
#include "test_sprite.h"

static uint16_t framebuffer[NSPIRE_FB_W * NSPIRE_FB_H];

int main(void) {
    if (!lcd_init(SCR_320x240_565)) return 1;

    NspireRenderer r;
    NspireRenderer_init(&r, framebuffer, NSPIRE_FB_W, NSPIRE_FB_H);

    // Dark navy background so transparent sprite pixels are obvious.
    uint16_t bg = nspire_rgb565(8, 8, 48);
    NspireRenderer_clear(&r, bg);

    // Top-left, fully visible.
    NspireRenderer_drawSpriteAxisAligned8(&r, test_sprite_data, TEST_SPRITE_W, TEST_SPRITE_H, test_sprite_palette, 8, 8);

    // Centered.
    NspireRenderer_drawSpriteAxisAligned8(&r, test_sprite_data, TEST_SPRITE_W, TEST_SPRITE_H, test_sprite_palette, 160 - 8, 120 - 8);

    // Clipped off the right/bottom edge.
    NspireRenderer_drawSpriteAxisAligned8(&r, test_sprite_data, TEST_SPRITE_W, TEST_SPRITE_H, test_sprite_palette, 310, 230);

    // Clipped off the left edge with a negative destX.
    NspireRenderer_drawSpriteAxisAligned8(&r, test_sprite_data, TEST_SPRITE_W, TEST_SPRITE_H, test_sprite_palette, -6, 100);

    lcd_blit(framebuffer, SCR_320x240_565);
    wait_key_pressed();
    lcd_init(SCR_TYPE_INVALID);
    return 0;
}
