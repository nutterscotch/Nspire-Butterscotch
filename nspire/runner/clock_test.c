// Standalone timing diagnostic for this Nspire newlib.
//
// Findings so far: clock() never advances; msleep() hangs. Both are unusable,
// which means the frame cap (whose only actuator is msleep) does nothing -> the
// game runs uncapped. This build answers the questions needed to rebuild the
// cap WITHOUT clock()/msleep:
//   [A] reconfirm clock() is dead (cheap, no hang risk)
//   [B] is time() alive, and how many busy-spin iterations fit in 1 real
//       second (the calibration a busy-wait frame limiter would use)?
//   [C] msleep probe LAST and capped, so if it hangs the data above is saved
//
// Renders via the engine's proven path (lcd_init + framebuffer + 5x7 font).

#include <libndls.h>
#include "nspire_font5x7.h"
#include <stdio.h>
#include <string.h>
#include <time.h>

#define FB_W 320
#define FB_H 240
static uint16_t fb[FB_W * FB_H];

#define MAXLINES 40
static char lines[MAXLINES][64];
static int nlines = 0;

static void putGlyph(int x, int y, char ch, uint16_t color) {
    if (ch < NSPIRE_FONT_FIRST || ch > NSPIRE_FONT_LAST) ch = '?';
    const uint8_t* g = &nspire_font5x7[(ch - NSPIRE_FONT_FIRST) * NSPIRE_FONT_W];
    for (int col = 0; col < NSPIRE_FONT_W; col++) {
        uint8_t bits = g[col];
        for (int row = 0; row < NSPIRE_FONT_H; row++)
            if (bits & (1u << row)) {
                int px = x + col, py = y + row;
                if (px >= 0 && px < FB_W && py >= 0 && py < FB_H)
                    fb[py * FB_W + px] = color;
            }
    }
}
static void render(void) {
    for (int i = 0; i < FB_W * FB_H; i++) fb[i] = 0x0000;
    for (int i = 0; i < nlines; i++) {
        int x = 4, y = 2 + i * 9;
        if (y + NSPIRE_FONT_H >= FB_H) break;
        for (const char* p = lines[i]; *p; p++) { putGlyph(x, y, *p, 0xFFFF); x += 6; }
    }
    lcd_blit(fb, SCR_320x240_565);
}
static void addline(const char* s) {
    if (nlines < MAXLINES) { strncpy(lines[nlines], s, 63); lines[nlines][63] = 0; nlines++; }
    render();
}

int main(void) {
    if (!lcd_init(SCR_320x240_565)) return 1;
    char buf[64];

    addline("=== timing diagnostic ===");

    // [A] clock(): reconfirm it never advances (bounded, cannot hang).
    {
        clock_t c0 = clock();
        long changes = 0;
        for (long i = 0; i < 300000; i++) if (clock() != c0) changes++;
        snprintf(buf, sizeof(buf), "[A] clock() changes=%ld CPS=%ld",
                 changes, (long) CLOCKS_PER_SEC);
        addline(buf);
    }

    // [B] time() liveness + busy-spin iterations per real second.
    // Spin in fixed batches; bail if a huge cap is hit (time() also dead).
    {
        addline("[B] probing time() (<=~6s)...");
        volatile unsigned long spin = 0;
        const unsigned long CAP = 2000000000UL;
        const int BATCH = 2000;

        time_t t = time(NULL);
        while (time(NULL) == t && spin < CAP) for (int b = 0; b < BATCH; b++) spin++;
        if (spin >= CAP && time(NULL) == t) {
            addline("    time() DEAD too (no clock at all)");
        } else {
            addline("    time() is ALIVE");
            for (int s = 0; s < 4; s++) {
                t = time(NULL);
                unsigned long s0 = spin;
                while (time(NULL) == t && spin < CAP)
                    for (int b = 0; b < BATCH; b++) spin++;
                snprintf(buf, sizeof(buf), "    sec %d: spin/sec=%lu",
                         s, spin - s0);
                addline(buf);
            }
        }
    }

    // [D] SP804 hardware timers (read-only; the page says Ndless' msleep uses
    // these). Measure decrements over one real second -> tick frequency. The
    // ~32768 one is what we'd pace frames with. Done BEFORE the msleep probe
    // because that one hangs and would hide this.
    {
        addline("[D] SP804 timers (d/s ~= Hz):");
        static const unsigned long ADDR[] = {
            0x900C0004UL, 0x900D0004UL, 0x90010004UL, 0x900D0024UL
        };
        for (int k = 0; k < 4; k++) {
            volatile unsigned long* p = (volatile unsigned long*) ADDR[k];
            unsigned long v0 = *p;
            for (volatile int s = 0; s < 5000; s++) { }
            unsigned long v1 = *p;
            if (v0 == v1) {
                snprintf(buf, sizeof(buf), "  %08lX static (%lu)", ADDR[k], v0);
                addline(buf);
                continue;
            }
            time_t t = time(NULL);
            while (time(NULL) == t) { }      // align to a boundary
            t = time(NULL);
            unsigned long prev = *p, sumdec = 0;
            long chg = 0;
            while (time(NULL) == t) {
                unsigned long c = *p;
                if (c != prev) { sumdec += (prev - c); chg++; prev = c; }
            }
            snprintf(buf, sizeof(buf), "  %08lX d/s=%lu chg=%ld",
                     ADDR[k], sumdec, chg);
            addline(buf);
        }
    }

    // [C] msleep probe LAST (it hung before). Capped: count how many
    // msleep(50) calls complete inside one real second. If this hangs,
    // everything above is already on screen.
    {
        addline("[C] msleep(50) probe (may hang)...");
        time_t t = time(NULL);
        while (time(NULL) == t) { }     // align
        t = time(NULL);
        long calls = 0;
        while (time(NULL) == t && calls < 100000) { msleep(50); calls++; }
        snprintf(buf, sizeof(buf), "    msleep(50)/sec = %ld", calls);
        addline(buf);
        addline("    (~20=real sleep, huge=no-op, hang=broken)");
    }

    addline("=== done - press a key ===");
    wait_key_pressed();
    lcd_init(SCR_TYPE_INVALID);
    return 0;
}
