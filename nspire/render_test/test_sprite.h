#pragma once

#include <stdint.h>
#include "nspire_renderer.h"

// 16x16 8bpp test sprite. Concentric colored rings with transparent rounded corners
// and transparent rings between bands, so alpha-test is visible by eye.
//
//   palette index legend
//     0 = transparent  3 = green   6 = magenta
//     1 = white        4 = blue    7 = cyan
//     2 = red          5 = yellow

#define TEST_SPRITE_W 16
#define TEST_SPRITE_H 16

static const uint8_t test_sprite_data[TEST_SPRITE_W * TEST_SPRITE_H] = {
    0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0,
    1,2,2,3,3,4,4,5,5,6,6,7,7,2,2,1,
    1,2,0,0,0,0,0,0,0,0,0,0,0,0,2,1,
    1,3,0,5,5,5,5,5,5,5,5,5,5,0,3,1,
    1,3,0,5,7,7,7,7,7,7,7,7,5,0,3,1,
    1,4,0,5,7,2,2,2,2,2,2,7,5,0,4,1,
    1,4,0,5,7,2,3,3,3,3,2,7,5,0,4,1,
    1,5,0,5,7,2,3,4,4,3,2,7,5,0,5,1,
    1,5,0,5,7,2,3,4,4,3,2,7,5,0,5,1,
    1,6,0,5,7,2,3,3,3,3,2,7,5,0,6,1,
    1,6,0,5,7,2,2,2,2,2,2,7,5,0,6,1,
    1,7,0,5,7,7,7,7,7,7,7,7,5,0,7,1,
    1,7,0,5,5,5,5,5,5,5,5,5,5,0,7,1,
    1,2,0,0,0,0,0,0,0,0,0,0,0,0,2,1,
    1,2,2,3,3,4,4,5,5,6,6,7,7,2,2,1,
    0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,0,
};

// 8-entry palette, expanded to 256 with zeros so the renderer can use the same
// fast path it'll use against the real preprocessor output later.
static const uint16_t test_sprite_palette[256] = {
    /* 0 transparent */ 0x0000,
    /* 1 white       */ 0xFFFF,
    /* 2 red         */ 0xF800,
    /* 3 green       */ 0x07E0,
    /* 4 blue        */ 0x001F,
    /* 5 yellow      */ 0xFFE0,
    /* 6 magenta     */ 0xF81F,
    /* 7 cyan        */ 0x07FF,
};
