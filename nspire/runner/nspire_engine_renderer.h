#pragma once

#include "renderer.h"
#include "nspire_renderer.h"
#include "nspire_asset.h"

// Engine-facing renderer: embeds the engine's `Renderer` struct as its first
// field so calls to vtable functions resolve correctly. Wraps our framebuffer
// fast path and the cooked-asset tables.

typedef struct {
    Renderer base;          // MUST be first; engine sees a Renderer*
    NspireRenderer fb;      // our framebuffer + scissor state
    NspireAssets* assets;   // borrowed pointer to loaded sidecars

    // Game logical dimensions from beginFrame.
    int32_t gameW;
    int32_t gameH;

    // Per-view port rect (set in beginView/beginGUI). Used as scissor.
    int32_t portX, portY, portW, portH;
    // Diagnostic copy of the view/gui rect passed to beginView/beginGUI.
    int32_t lastViewW, lastViewH;
    int32_t lastPortX, lastPortY;

    // View-to-framebuffer mapping. Engine room coord X is translated to
    // framebuffer X via: fbX = (engineX - viewX) * viewToFbScaleX + portFbX.
    // viewToFbScaleX = (portW / viewW) * HR_SCALE_ATLAS — the factor that
    // turns engine-space coords into halved-framebuffer space.
    float viewToFbScaleX, viewToFbScaleY;
    float viewOriginX, viewOriginY;   // engine room coord at the top-left of the port
    int32_t portFbX, portFbY;          // port top-left in framebuffer pixels

    // Tinted-palette cache. image_blend on a tiled background re-tints the same
    // (palette,colour) hundreds of times/frame; rebuild once, reuse the rest.
    // Reset each frame so an evicted/reloaded atlas can't alias a stale entry.
    const uint16_t* tintSrc;   // cached source palette pointer (NULL = empty)
    uint32_t        tintColor; // cached GM colour
    uint8_t         tintBpp;   // cached bpp
    uint16_t        tintPal[256];

    // Software surfaces (RGB565, opaque — benchmark pass). Slot index = GM
    // surface id. setRenderTarget retargets `fb` in place; the screen target
    // is saved so APPLICATION_SURFACE_ID can restore it.
    struct { uint16_t* px; int32_t w, h; } surfaces[16];
    uint16_t* screenPx;
    int32_t   screenW, screenH;
    int32_t   activeSurface;   // -1 = screen (APPLICATION_SURFACE_ID)

    // Screen coordinate-transform + scissor stashed while a surface is the
    // target. Rendering INTO a bound surface must use surface-local 1:1 coords,
    // not the screen's view transform — otherwise draws map off the surface
    // buffer and get scissor-clipped (the "Muffet surface is black" bug).
    float   savedViewOriginX, savedViewOriginY;
    float   savedViewToFbScaleX, savedViewToFbScaleY;
    int32_t savedPortFbX, savedPortFbY;
    int32_t savedScissorX, savedScissorY, savedScissorW, savedScissorH;
    bool    transformSaved;
} NspireEngineRenderer;

// Allocates a renderer and installs the vtable. The caller owns `assets`.
Renderer* NspireEngineRenderer_create(NspireAssets* assets, uint16_t* framebuffer, int32_t fbW, int32_t fbH);

void NspireEngineRenderer_destroy(Renderer* r);
