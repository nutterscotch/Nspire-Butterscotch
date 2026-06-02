// Butterscotch Nspire runner — engine integration bootstrap.
// Boots the Butterscotch engine against Undertale's data.win + cooked sidecar
// atlases in /documents/bs/, then runs the frame loop blitting into a 320x240
// RGB565 framebuffer. Surfaces / audio / file I/O are no-ops; slow-path draws
// (rotation, scale, tint) are stubbed.

#include <libndls.h>
#include <nspireio/nspireio.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <stdarg.h>
#include <time.h>

#include "data_win.h"
#include "vm.h"
#include "rvalue.h"
#include "runner.h"
#include "json_reader.h"
#include "noop_file_system.h"
#include "noop_audio_system.h"
#include "audio_system.h"
#include "instance.h"
#include "stb_ds.h"

#include "nspire_renderer.h"
#include "nspire_asset.h"
#include "nspire_engine_renderer.h"
#include "nspire_overlay.h"
#include "nspire_file_system.h"

#define ASSET_DIR     "/documents/bs/"
#define DATAWIN_PATH  "/documents/bs/data.win"
#define DATAWIN_ALT   "/documents/bs/data.win.tns"

static uint16_t framebuffer[NSPIRE_FB_W * NSPIRE_FB_H];

// Overlay state shared with the parse progress callback. Initialized in main(),
// then the callback uses it to paint a PS2-style loading screen as chunks land.
static NspireRenderer gOverlayFb;
static NspireOverlay gOverlay;
static bool gOverlayReady = false;

// libsyscalls_nspireio auto-initializes an nio console at program start and routes _write
// (and therefore printf/fprintf) through it. log_flush is a thin wrapper kept for back-compat
// with existing call sites that match the old signature.
static void log_flush(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
}

// Reservation block: malloc'd at startup when the heap is fresh and unfragmented, freed
// right before CODE parses so newlib's allocator can satisfy the 5+ MB bytecodeBuffer alloc.
static void* codeHeapReservation = NULL;

// Reads the optional cooked config the web preprocessor emits (config.tns: a
// tiny JSON blob alongside the .tns assets) and returns the debugMode flag. This
// is the Nspire analog of the PS2 target's CONFIG.JSN "debugOverlayEnabled" key.
// When debugMode is true the stats overlay and the debug hotkeys (pause, room
// warp, global-reset, uncap, etc.) are active; when false both are suppressed and
// only game input reaches the engine. Default when the file is absent/unparseable
// is TRUE so hand-managed /documents/bs/ folders (no config) keep the dev workflow
// — preprocessor-generated bundles always ship an explicit config.tns.
static bool load_debug_mode(void) {
    FILE* f = fopen(ASSET_DIR "config.tns", "rb");
    if (!f) return true;
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (sz <= 0) { fclose(f); return true; }
    char* text = (char*) malloc((size_t) sz + 1);
    if (!text) { fclose(f); return true; }
    size_t rd = fread(text, 1, (size_t) sz, f);
    text[rd] = '\0';
    fclose(f);

    JsonValue* root = JsonReader_parse(text);
    free(text);
    if (!root) return true;

    bool debugMode = true;  // default when the key is missing
    JsonValue* dm = JsonReader_getObject(root, "debugMode");
    if (dm && JsonReader_isBool(dm)) debugMode = JsonReader_getBool(dm);
    JsonReader_free(root);
    return debugMode;
}

static void parse_progress_cb(const char* chunkName, int chunkIndex, int totalChunks, uint32_t chunkLength, DataWin* dataWin, void* userData) {
    (void) userData;
    log_flush("[%2d/%d] %.4s %lu B\n", chunkIndex + 1, totalChunks, chunkName, (unsigned long) chunkLength);
    // Release the heap reservation right before the first multi-megabyte chunk parse so
    // ROOM (4.5 MB) and CODE (5.25 MB) both have access to a freshly contiguous block.
    if (memcmp(chunkName, "ROOM", 4) == 0 && codeHeapReservation) {
        free(codeHeapReservation);
        codeHeapReservation = NULL;
        log_flush("       released big-chunk heap reservation\n");
    }
    // Paint a progress screen straight to the LCD so the user can see what's happening
    // while we keep dumping logs to the nio console behind the screen.
    if (gOverlayReady) {
        const char* gameName = (dataWin && dataWin->gen8.displayName) ? dataWin->gen8.displayName : "Loading...";
        NspireOverlay_drawLoadingScreen(&gOverlay, gameName, chunkName, chunkIndex, totalChunks, chunkLength, dataWin);
    }
}

static void chunk_loaded_cb(const char* chunkName, uint32_t chunkLength, void* userData) {
    (void) chunkLength; (void) userData;
    log_flush("       %.4s loaded, parsing...\n", chunkName);
}

static void parse_debug_log_cb(const char* msg, void* userData) {
    (void) userData;
    log_flush("       %s\n", msg);
}

int main(void) {
    // libsyscalls_nspireio already nio_init'd a console for us; printf/fprintf route through it.
    printf("Butterscotch nspire engine\n");
    printf("---------------------------\n");

    // Grab a contiguous block of memory before the parser allocates anything, so the heap is
    // freshly unfragmented. We free this right before CODE's bytecode buffer (~5 MB) is allocated.
    // Reserve a single block now (heap fresh, no fragmentation yet) and release it before
    // the first multi-megabyte chunk bulk-loads. Sized to cover ROOM's 4.5 MB chunk buffer.
    codeHeapReservation = malloc(5UL * 1024 * 1024);
    if (codeHeapReservation) log_flush("Reserved 5 MB for big-chunk parsing headroom\n");
    else log_flush("WARN: could not reserve 5 MB up front\n");

    // Stand the overlay up early so chunk-loading progress is on screen, not just in logs.
    // Heap is fresh (other than the 5 MB reservation), so the heap-size probe inside
    // NspireOverlay_init returns a sensible number too.
    NspireRenderer_init(&gOverlayFb, framebuffer, NSPIRE_FB_W, NSPIRE_FB_H);
    NspireOverlay_init(&gOverlay, &gOverlayFb, NULL);
    // Debug mode comes from the cooked config (config.tns). It gates the stats
    // overlay AND the debug hotkeys together. The loading screen below is drawn
    // unconditionally (NspireOverlay_drawLoadingScreen ignores the overlay state),
    // so a release/debug-off build still shows load progress — only the in-game
    // debug HUD and hotkeys are suppressed.
    bool debugMode = load_debug_mode();
    NspireOverlay_setState(&gOverlay, debugMode ? NSPIRE_OVERLAY_ENABLED : NSPIRE_OVERLAY_DISABLED);
    gOverlayReady = true;

    NspireAssets assetsStorage;
    NspireAssets* assets = NULL;

    // ===[ 1. data.win ]===
    // Parse the data.win FIRST while the heap is still mostly free — bulk-loading CODE (5+ MB)
    // and ROOM (4+ MB) needs contiguous space. Cooked assets get loaded afterwards.
    const char* dwPath = NULL;
    FILE* probe = fopen(DATAWIN_PATH, "rb");
    if (probe) { dwPath = DATAWIN_PATH; fclose(probe); }
    else {
        probe = fopen(DATAWIN_ALT, "rb");
        if (probe) { dwPath = DATAWIN_ALT; fclose(probe); }
    }
    if (!dwPath) {
        log_flush("FAIL: data.win not found at %s or %s\n", DATAWIN_PATH, DATAWIN_ALT);
        wait_key_pressed();

        lcd_init(SCR_TYPE_INVALID);
        return 2;
    }
    log_flush("data.win: %s\n", dwPath);

    DataWinParserOptions opts = (DataWinParserOptions) {
        .parseGen8 = true,
        .parseSprt = true, .parseBgnd = true,
        .parseFont = true, .parseObjt = true,
        .parseRoom = true, .parseTpag = true,
        .parseCode = true, .parseVari = true, .parseFunc = true, .parseStrg = true,
        .parseScpt = true,                            // CRITICAL: game scripts (scr_*). Without this every script_execute() fails.
        .parseGlob = true,                            // Global init code IDs — Runner_initFirstRoom dispatches these.
        .parsePath = true,                            // Paths used by some Undertale rooms.
        .skipLoadingPreciseMasksForNonPreciseSprites = true,  // safe: non-precise sprites use bbox anyway
        // Precise (sepMasks==1) masks MUST load: Deltarune builds room walls and
        // the battle box from one big precise collision sprite. Without its mask
        // the bbox fallback covers the whole room -> "always colliding" -> the
        // character turns but can't move. The old "too memory-tight" reasoning
        // was the 16 MB myth; real headroom is ~30 MB (Undertale ~25, DR ~13).
        .skipAllSpriteMasks = false,
        .lazyLoadRooms = true,                       // Stream per-room payload (backgrounds/objects/tiles/layers) from disk on transition.
        .streamChunkThresholdBytes = 0,              // 0 = always bulk-load. We rely on the up-front 6 MB reservation to keep contiguous heap available for CODE.
        .progressCallback = parse_progress_cb,
        .chunkLoadedCallback = chunk_loaded_cb,
        .parseDebugLog = parse_debug_log_cb,
    };
    DataWin* dataWin = DataWin_parse(dwPath, opts);
    if (!dataWin) {
        log_flush("FAIL: DataWin_parse returned NULL\n");
        wait_key_pressed();

        lcd_init(SCR_TYPE_INVALID);
        return 3;
    }

    // ===[ 2. Cooked sidecars (after parse so the parser had max contiguous heap) ]===
    log_flush("Loading cooked assets from %s ...\n", ASSET_DIR);
    if (NspireAssets_load(&assetsStorage, ASSET_DIR)) {
        assets = &assetsStorage;
        log_flush("  loaded\n");
        // Atlases are demand-loaded by the renderer (NspireAssets_ensureAtlas) and
        // evicted per frame (ns_endFrame). Preloading all 14 1024px atlases at once
        // (~9.5 MB in 0.5-1 MB contiguous chunks) on top of the 16 MB data.win
        // crash-restarts on the final load step. The font garble that motivated
        // preload-all was actually the 512-atlas oversize-resize bug, fixed in the
        // cook (--atlas-size 1024); streaming was never the cause.
    } else {
        log_flush("  WARN: cooked assets not found, sprites will not render\n");
    }
    // Hand the loaded assets + DataWin to the overlay so the HUD can show atlas residency stats
    // and render text in Undertale's real font.
    NspireOverlay_bindDataWin(&gOverlay, assets, dataWin, "fnt_small");

    // ===[ 3. Subsystems ]===
    // Real disk-backed saves under the asset dir (persist as sav_*.tns).
    FileSystem* fs = NspireFileSystem_create(ASSET_DIR);
    AudioSystem* audioSys = (AudioSystem*) NoopAudioSystem_create();
    VMContext* vm = VM_create(dataWin);
    Renderer* renderer = NspireEngineRenderer_create(assets, framebuffer, NSPIRE_FB_W, NSPIRE_FB_H);
    Runner* runner = Runner_create(dataWin, vm, renderer, fs, audioSys);
    if (runner) runner->debugMode = debugMode;
    if (!runner) {
        log_flush("FAIL: Runner_create\n");
        wait_key_pressed();

        lcd_init(SCR_TYPE_INVALID);
        return 4;
    }
    Runner_initFirstRoom(runner);

    // Try the natural intro flow with SCPT now loaded — global init + room creation events
    // can finally run scripts, which should let the intro auto-advance through to gameplay.
    // If room_introstory still crashes, swap this for a later force-jump.

    int32_t gameW = (int32_t) dataWin->gen8.defaultWindowWidth;
    int32_t gameH = (int32_t) dataWin->gen8.defaultWindowHeight;
    if (gameW <= 0) gameW = 640;
    if (gameH <= 0) gameH = 480;
    log_flush("game: %ldx%ld\n", (long) gameW, (long) gameH);

    // Step one frame so any first-frame VM logic runs, then dump the state we landed in.
    Runner_step(runner);
    {
            // Port rects must be scaled so the views fill the GEN8 application
            // surface (gameW x gameH). Hardcoding 1.0 only works when the port
            // extent already equals gameW (Undertale); Deltarune's doesn't, so
            // the window came out squished. Guard for pre-room boot frames.
            float dsx = 1.0f, dsy = 1.0f;
            if (runner->currentRoom)
                Runner_computeViewDisplayScale(runner, gameW, gameH, &dsx, &dsy);
            Runner_drawViews(runner, gameW, gameH, dsx, dsy, false);
        }
    if (runner->currentRoom) {
        log_flush("currentRoom: %s (%ux%u, bg=0x%lX)\n",
                  runner->currentRoom->name ? runner->currentRoom->name : "(null)",
                  (unsigned) runner->currentRoom->width,
                  (unsigned) runner->currentRoom->height,
                  (unsigned long) runner->currentRoom->backgroundColor);
    } else {
        log_flush("currentRoom: NULL\n");
    }
    log_flush("instances=%ld pendingRoom=%ld currRoomIdx=%ld\n",
              (long) arrlen(runner->instances), (long) runner->pendingRoom, (long) runner->currentRoomIndex);

    // Dump per-instance info.
    long n = (long) arrlen(runner->instances);
    for (long i = 0; i < n; i++) {
        Instance* inst = runner->instances[i];
        int32_t objIdx = inst->objectIndex;
        const char* objName = "?";
        if (objIdx >= 0 && (uint32_t) objIdx < dataWin->objt.count) {
            objName = dataWin->objt.objects[objIdx].name;
            if (!objName) objName = "(null)";
        }
        log_flush("  inst[%ld]: obj=%ld %s x=%d y=%d\n",
                  i, (long) objIdx, objName, (int) inst->x, (int) inst->y);
    }
    log_flush("room creationCodeId=%ld\n", (long) runner->currentRoom->creationCodeId);

    // Step a few more frames to see if anything changes.
    for (int extra = 0; extra < 5; extra++) {
        Runner_step(runner);
        {
            // Port rects must be scaled so the views fill the GEN8 application
            // surface (gameW x gameH). Hardcoding 1.0 only works when the port
            // extent already equals gameW (Undertale); Deltarune's doesn't, so
            // the window came out squished. Guard for pre-room boot frames.
            float dsx = 1.0f, dsy = 1.0f;
            if (runner->currentRoom)
                Runner_computeViewDisplayScale(runner, gameW, gameH, &dsx, &dsy);
            Runner_drawViews(runner, gameW, gameH, dsx, dsy, false);
        }
    }
    log_flush("after 6 frames: pendingRoom=%ld currRoomIdx=%ld inst=%ld\n",
              (long) runner->pendingRoom, (long) runner->currentRoomIndex,
              (long) arrlen(runner->instances));
    log_flush("\nPress any key to start render loop (ESC to quit during loop)\n");
    wait_key_pressed();

    // ===[ 4. Render loop ]===
    // Switch the LCD out of the OS console (nio text mode) into a 320x240
    // RGB565 framebuffer the panel will scan from. Without this, lcd_blit
    // writes go to a buffer the LCD isn't reading and the console stays
    // visible — same pattern as main.c / clock_test.c, which always worked.
    // Paired with the genzehn `--uses-lcd-blit true --240x320-support true`
    // flags in the Makefile, which tell the Ndless loader to set this up.
    if (!lcd_init(SCR_320x240_565)) {
        log_flush("FAIL: lcd_init(SCR_320x240_565)\n");
        wait_key_pressed();
        return 5;
    }

    int32_t frameCount = 0;
    int32_t lastRoomIndex = -1;

    // Map calc-side keys to GML virtual keys for game input + Butterscotch debug hotkeys.
    // Game keys: 8/4/5/6 = up/left/down/right, Enter / Z / X / C / Shift / Tab.
    // Debug keys (active when runner->debugMode): + = PageUp (next room), - = PageDown (prev),
    // P = Pause toggle, O = single-step while paused, T = toggle stats overlay.
    struct KeyMap { t_key nspire; int32_t gml; };
    static t_key keys[20];
    static const int32_t KEYMAP_GML[] = {
        VK_LEFT, VK_RIGHT, VK_UP, VK_DOWN,
        VK_ENTER, VK_ENTER, 'Z', 'X', 'C',
        VK_SHIFT, VK_TAB,
        VK_PAGEUP, VK_PAGEDOWN, 'P', 'O',
        VK_F10,
        'T',
        'A',
        'U',
        'N',
    };
    int keymapCount = (int) (sizeof(KEYMAP_GML) / sizeof(KEYMAP_GML[0]));
    // Directions on the number pad instead of the touchpad: 8=up 4=left 5=down 6=right.
    keys[0]  = KEY_NSPIRE_4;       // VK_LEFT
    keys[1]  = KEY_NSPIRE_6;       // VK_RIGHT
    keys[2]  = KEY_NSPIRE_8;       // VK_UP
    keys[3]  = KEY_NSPIRE_5;       // VK_DOWN
    keys[4]  = KEY_NSPIRE_ENTER;
    keys[5]  = KEY_NSPIRE_RET;
    keys[6]  = KEY_NSPIRE_Z;
    keys[7]  = KEY_NSPIRE_X;
    keys[8]  = KEY_NSPIRE_C;
    keys[9]  = KEY_NSPIRE_SHIFT;
    keys[10] = KEY_NSPIRE_TAB;
    keys[11] = KEY_NSPIRE_PLUS;    // PageUp: next room (debug)
    keys[12] = KEY_NSPIRE_MINUS;   // PageDown: prev room (debug)
    keys[13] = KEY_NSPIRE_P;
    keys[14] = KEY_NSPIRE_O;
    keys[15] = KEY_NSPIRE_0;       // F10: zero global.interact so the player can move after a force-jump
    keys[16] = KEY_NSPIRE_T;       // Toggle the debug stats overlay (PS2's F12 equivalent)
    keys[17] = KEY_NSPIRE_A;       // Warp to room 330 (room_asrielappears) for testing
    keys[18] = KEY_NSPIRE_U;       // Toggle uncap (no FPS cap when on)
    keys[19] = KEY_NSPIRE_N;       // Toggle: force text through the native 1:1 blit (diag)

    // Both gated by runner->debugMode (config.tns). Declared here, outside the
    // loop, because the frame-cap logic below reads `uncap` even though only the
    // debug-hotkey block (further down) ever flips these.
    bool debugPaused = false;
    bool uncap = false;

    // ---- Frame-cap timing setup ----
    // 0x900D0024 read as a 32768 Hz counter in bare clock_test, but its
    // behaviour under the full runtime is unverified (a wrong assumption here
    // froze the game). Validate it lives; if not, fall back to a CPU spin
    // calibrated against time() (the one primitive proven to advance). Both
    // paths are bounded and rely only on time() — neither can hang.
    volatile uint32_t* const hwTimer = (volatile uint32_t*) 0x900D0024u;
    bool hwTimerLive;
    {
        uint32_t a0 = *hwTimer;
        for (volatile int i = 0; i < 300000; i++) { }
        hwTimerLive = (*hwTimer != a0);
    }
    // CRITICAL: do NOT assume the tick rate. clock_test measured 32768 Hz in a
    // bare program, but under the engine runtime this register ticks at a
    // different rate (assuming 32768 capped Deltarune at ~15 fps). Measure the
    // real in-engine rate against time() (proven reliable) over one real second,
    // direction-agnostic. If it's unusable, drop to the spin fallback.
    uint32_t hwTicksPerSec = 0;
    if (hwTimerLive) {
        time_t t = time(NULL);
        unsigned long g = 0;
        while (time(NULL) == t && g < 2000000000UL) g++;        // align
        t = time(NULL);
        uint32_t s = *hwTimer, mx = 0;
        unsigned long guard = 0;
        while (time(NULL) == t && guard < 2000000000UL) {
            uint32_t cur = *hwTimer;
            uint32_t a = cur - s, b = s - cur;
            uint32_t e = a < b ? a : b;     // |cur-s|, monotone over 1 s
            if (e > mx) mx = e;
            guard++;
        }
        hwTicksPerSec = mx;
        if (hwTicksPerSec < 1000u) hwTimerLive = false;   // unusable -> spin
    }
    // Pure-increment iterations per real second (same loop body as the cap's
    // spin actuator so the unit matches). Only the fallback path needs it.
    // Guarded so a stuck time() can't hang startup.
    unsigned long spinsPerSec = 0;
    if (!hwTimerLive) {
        volatile unsigned long c = 0;
        time_t t = time(NULL);
        unsigned long g = 0;
        while (time(NULL) == t && g < 2000000000UL) g++;        // align
        t = time(NULL);
        unsigned long batches = 0;
        while (batches < 2000000UL) {
            for (int b = 0; b < 10000; b++) c++;
            batches++;
            if (time(NULL) != t) break;
        }
        spinsPerSec = batches * 10000UL;
    }

    while (!runner->shouldExit) {
        if (isKeyPressed(KEY_NSPIRE_ESC)) break;

        // Reference tick captured BEFORE this frame's work. The cap waits until
        // one frame period has elapsed from here, so the wait absorbs the work
        // (total = max(work, period)) instead of stacking a full period on top
        // of it (total = work + period), which was throttling 40fps down to 22.
        uint32_t frameStartTick = hwTimerLive ? *hwTimer : 0;

        // Per-frame keyboard reconcile: flip GML key state to match the calc keypad.
        RunnerKeyboard_beginFrame(runner->keyboard);
        for (int k = 0; k < keymapCount; k++) {
            bool nowDown = isKeyPressed(keys[k]);
            bool wasDown = runner->keyboard->keyDown[KEYMAP_GML[k] & 0xFF];
            if (nowDown && !wasDown) RunnerKeyboard_onKeyDown(runner->keyboard, KEYMAP_GML[k]);
            else if (!nowDown && wasDown) RunnerKeyboard_onKeyUp(runner->keyboard, KEYMAP_GML[k]);
        }

        // Butterscotch debug hotkeys — gated entirely on debug mode (config.tns).
        // When debug is off only game keys (handled in the reconcile loop above)
        // reach the engine; none of the pause/warp/reset/overlay-toggle actions fire.
        // PageUp/Down = +/- on the Nspire keypad.
        if (runner->debugMode) {
        if (RunnerKeyboard_checkPressed(runner->keyboard, 'P')) debugPaused = !debugPaused;
        if (RunnerKeyboard_checkPressed(runner->keyboard, 'T')) NspireOverlay_toggle(&gOverlay);
        // U toggles the frame-rate cap. When uncapped the game runs as fast as the
        // hardware can manage (handy for benchmarking; also useful in cutscenes).
        if (RunnerKeyboard_checkPressed(runner->keyboard, 'U')) uncap = !uncap;

        // 'A' warps to room 330 (room_asrielappears) for testing the final battle scene.
        if (RunnerKeyboard_checkPressed(runner->keyboard, 'A')) {
            DataWin* dw = runner->dataWin;
            if (dw->room.count > 330) {
                runner->pendingRoom = 330;
                log_flush("warp -> room 330 (%s)\n", dw->room.rooms[330].name ? dw->room.rooms[330].name : "?");
            }
        }

        // F10 (0 key): zero out every plausible movement-gate global. Force-jumping rooms leaves
        // these in cutscene-locked states; we shotgun-reset them so Frisk responds to input.
        if (RunnerKeyboard_checkPressed(runner->keyboard, VK_F10)) {
            static const char* const kRESET_GLOBALS[] = {
                "interact", "facing", "facechoice", "faceemotion",
                "dialoguer", "msc", "myinteract", "screenrumble",
                "fading", "fadebuffer", "dontmove", "lockmove",
                "battle", "fighting", "currentsong",
            };
            for (size_t k = 0; k < sizeof(kRESET_GLOBALS) / sizeof(kRESET_GLOBALS[0]); k++) {
                int32_t id = shget(runner->vmContext->globalVarNameMap, kRESET_GLOBALS[k]);
                if (id >= 0) {
                    runner->vmContext->globalVars[id] = RValue_makeInt32(0);
                }
            }
        }
        bool diagCheckPressedPU = RunnerKeyboard_checkPressed(runner->keyboard, VK_PAGEUP);
        bool diagSetPendingRoom = false;
        if (diagCheckPressedPU) {
            DataWin* dw = runner->dataWin;
            if ((int32_t) dw->gen8.roomOrderCount > runner->currentRoomOrderPosition + 1) {
                runner->pendingRoom = dw->gen8.roomOrder[runner->currentRoomOrderPosition + 1];
                diagSetPendingRoom = true;
            }
        }
        if (RunnerKeyboard_checkPressed(runner->keyboard, VK_PAGEDOWN)) {
            DataWin* dw = runner->dataWin;
            if (runner->currentRoomOrderPosition > 0) {
                runner->pendingRoom = dw->gen8.roomOrder[runner->currentRoomOrderPosition - 1];
            }
        }

        (void) diagSetPendingRoom; (void) diagCheckPressedPU;
        } // end debug-hotkey gate (runner->debugMode)

        bool shouldStep = !debugPaused || RunnerKeyboard_checkPressed(runner->keyboard, 'O');

        // clock() is DEAD on this newlib (never advances), so the old HUD
        // step/draw split was always ~0 and we were optimizing blind. Use the
        // same hardware counter the frame cap validated at startup
        // (hwTimer @ hwTicksPerSec) for a real profile. Direction-agnostic
        // deltas (the counter may run either way, like the cap logic).
        uint32_t tkStep0 = hwTimerLive ? *hwTimer : 0;
        if (shouldStep) Runner_step(runner);
        uint32_t tkStep1 = hwTimerLive ? *hwTimer : 0;

        // The engine does NOT call beginFrame/endFrame itself — the host must, like the
        // PS2 target. beginFrame clears the framebuffer; endFrame runs atlas LRU eviction
        // (without it, streamed atlases accumulate forever and we slowly OOM).
        renderer->vtable->beginFrame(renderer, gameW, gameH, NSPIRE_FB_W, NSPIRE_FB_H);
        {
            // Port rects must be scaled so the views fill the GEN8 application
            // surface (gameW x gameH). Hardcoding 1.0 only works when the port
            // extent already equals gameW (Undertale); Deltarune's doesn't, so
            // the window came out squished. Guard for pre-room boot frames.
            float dsx = 1.0f, dsy = 1.0f;
            if (runner->currentRoom)
                Runner_computeViewDisplayScale(runner, gameW, gameH, &dsx, &dsy);
            Runner_drawViews(runner, gameW, gameH, dsx, dsy, false);
        }
        renderer->vtable->endFrame(renderer);
        uint32_t tkDraw1 = hwTimerLive ? *hwTimer : 0;
        if (shouldStep) Runner_handlePendingRoomChange(runner);

        // Stats overlay (only when toggled on). stepMs = VM/logic, drawMs =
        // render; if stepMs >> drawMs the bottleneck is the bytecode engine
        // (not fixable from the renderer); if drawMs >> stepMs it's the blits.
        float tickMs = 0.0f, stepMs = 0.0f, drawMs = 0.0f;
        if (hwTimerLive && hwTicksPerSec > 0u) {
            uint32_t dS = tkStep1 - tkStep0, dSr = tkStep0 - tkStep1;
            uint32_t dD = tkDraw1 - tkStep1, dDr = tkStep1 - tkDraw1;
            uint32_t dF = tkDraw1 - tkStep0, dFr = tkStep0 - tkDraw1;
            float kms = 1000.0f / (float) hwTicksPerSec;
            stepMs = (float) (dS < dSr ? dS : dSr) * kms;
            drawMs = (float) (dD < dDr ? dD : dDr) * kms;
            tickMs = (float) (dF < dFr ? dF : dFr) * kms;
        }
        NspireOverlay_drawDebugHud(&gOverlay, runner, tickMs, stepMs, drawMs);

        (void) lastRoomIndex;

        lcd_blit(framebuffer, SCR_320x240_565);

        // Frame-rate cap. clock() is dead and msleep() hangs on this newlib.
        // If the 0x900D0024 hardware counter validated live at startup, busy-
        // wait the per-frame tick budget (precise, no settle, direction-
        // agnostic, time() watchdog so it can never freeze). Otherwise pace
        // with a CPU spin whose per-frame count is solved each RTC second
        // (deadbeat: converges in 1 s, bounded so a frame can't exceed ~1 s).
        uint32_t roomSpeed = runner->currentRoom ? runner->currentRoom->speed : 30;
        if (roomSpeed == 0) roomSpeed = 30;

        if (hwTimerLive) {
            if (!uncap && !debugPaused) {
                uint32_t budget = hwTicksPerSec / roomSpeed;  // ticks per frame
                time_t wstart = time(NULL);
                for (;;) {
                    uint32_t cur = *hwTimer;
                    uint32_t a = cur - frameStartTick, b = frameStartTick - cur;
                    uint32_t elapsed = a < b ? a : b;      // abs dist, any dir
                    if (elapsed >= budget) break;          // period filled
                    if (time(NULL) - wstart >= 2) break;   // watchdog
                }
            }
        } else if (spinsPerSec > 0) {
            static long spinFrame = -1;
            static time_t capSec = 0;
            static int framesSec = 0;
            if (spinFrame < 0) spinFrame = (long) (spinsPerSec / roomSpeed);
            framesSec++;
            time_t ns = time(NULL);
            if (capSec == 0) capSec = ns;
            if (ns != capSec) {
                if (!uncap && !debugPaused && framesSec > 0) {
                    // want framesSec == roomSpeed:
                    // spinFrame += spins/target - spins/actual
                    spinFrame += (long) (spinsPerSec / roomSpeed)
                               - (long) (spinsPerSec / (unsigned) framesSec);
                    if (spinFrame < 0) spinFrame = 0;
                    if (spinFrame > (long) spinsPerSec) spinFrame = (long) spinsPerSec;
                }
                framesSec = 0;
                capSec = ns;
            }
            if (!uncap && !debugPaused && spinFrame > 0) {
                volatile unsigned long c = 0;
                for (long s = 0; s < spinFrame; s++) c++;
            }
        }
        frameCount++;
    }

    // ===[ 5. Teardown ]===
    Runner_free(runner);
    NspireEngineRenderer_destroy(renderer);
    VM_free(vm);
    NspireFileSystem_destroy(fs);
    free(audioSys);
    DataWin_free(dataWin);
    if (assets) NspireAssets_free(assets);
    if (gOverlayReady) NspireOverlay_free(&gOverlay);
    gOverlayReady = false;

    lcd_init(SCR_TYPE_INVALID);
    return 0;
}
