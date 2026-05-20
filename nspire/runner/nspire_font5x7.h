#pragma once

#include <stdint.h>

// Tiny 5x7 ASCII font for the Nspire overlay. Glyphs are stored as 5 bytes (one
// per column, left-to-right). Each byte holds 7 bits of pixel data, bit 0 = top
// row, bit 6 = bottom row. Bit 7 is unused.
//
// Coverage: printable ASCII 0x20..0x7E. Out-of-range codepoints fall back to
// the '?' glyph.

#define NSPIRE_FONT_W 5
#define NSPIRE_FONT_H 7
#define NSPIRE_FONT_FIRST 0x20
#define NSPIRE_FONT_LAST  0x7E

extern const uint8_t nspire_font5x7[(NSPIRE_FONT_LAST - NSPIRE_FONT_FIRST + 1) * NSPIRE_FONT_W];
