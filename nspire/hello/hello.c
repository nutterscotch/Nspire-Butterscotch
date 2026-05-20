// Butterscotch Nspire port — Hello World framebuffer test.
// Verifies the toolchain + libndls + LCD path: fills a 320x240 RGB565 buffer
// with a gradient, blits it, waits for a key, restores the OS LCD mode, exits.

#include <libndls.h>
#include <stdint.h>

#define FB_W 320
#define FB_H 240

static uint16_t framebuffer[FB_W * FB_H];

static inline uint16_t rgb565(uint8_t r, uint8_t g, uint8_t b) {
    return (uint16_t) (((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
}

int main(void) {
    if (!lcd_init(SCR_320x240_565)) {
        return 1;
    }

    for (int y = 0; y < FB_H; y++) {
        for (int x = 0; x < FB_W; x++) {
            uint8_t r = (uint8_t) ((x * 255) / (FB_W - 1));
            uint8_t g = (uint8_t) ((y * 255) / (FB_H - 1));
            uint8_t b = (uint8_t) (255 - r);
            framebuffer[y * FB_W + x] = rgb565(r, g, b);
        }
    }

    lcd_blit(framebuffer, SCR_320x240_565);
    wait_key_pressed();
    lcd_init(SCR_TYPE_INVALID);
    return 0;
}
