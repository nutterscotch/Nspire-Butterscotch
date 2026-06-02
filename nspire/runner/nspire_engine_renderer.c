#include "nspire_engine_renderer.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "data_win.h"
#include "nspire_overlay.h"
#include "renderer.h"

NspireDrawStats gNspireDrawStats = {0};

// The cooked atlas stores each sprite at 1/2 the engine's native pixel size —
// that constant is the preprocessor's bargain. The view→fb scale combines this
// atlas halving with the engine's port/view ratio so a 320-wide Undertale room
// and a 640-wide window-coord battle GUI both end up filling our 320-px
// framebuffer.
#define HR_SCALE_ATLAS 0.5f
#define HR_SCALE HR_SCALE_ATLAS // legacy alias used by a couple of places below

static inline int32_t halve_floor(int32_t v) { return v / 2; }
static inline int32_t halve_ceil(int32_t v) { return (v + 1) / 2; }

// Convert an engine x/y to framebuffer pixel coords using the active view
// mapping.
static inline int32_t engine_to_fb_x(const NspireEngineRenderer *ner, float x) {
  return (int32_t)((x - ner->viewOriginX) * ner->viewToFbScaleX) + ner->portFbX;
}
static inline int32_t engine_to_fb_y(const NspireEngineRenderer *ner, float y) {
  return (int32_t)((y - ner->viewOriginY) * ner->viewToFbScaleY) + ner->portFbY;
}

static inline NspireEngineRenderer *as_ner(Renderer *r) {
  return (NspireEngineRenderer *)r;
}
static void ns_bindTarget(NspireEngineRenderer *ner, int32_t id);

// Pick the right CLUT for an atlas (4bpp vs 8bpp lookup tables).
static const uint16_t *clut_for(NspireEngineRenderer *ner, NspireAtlas *atlas,
                                uint16_t clutIndex) {
  if (atlas->bpp == 8) {
    if (clutIndex >= (uint16_t)ner->assets->clut8Count)
      return NULL;
    return ner->assets->clut8[clutIndex].entries;
  } else {
    if (clutIndex >= (uint16_t)ner->assets->clut4Count)
      return NULL;
    return ner->assets->clut4[clutIndex].entries;
  }
}

// ===[ Lifecycle ]===

static void ns_init(Renderer *r, DataWin *dataWin) {
  r->dataWin = dataWin;
#if NS_ENABLE_SURFACE_SNAPSHOT
  NspireEngineRenderer *ner = as_ner(r);
  ner->originalSpriteCount = dataWin->sprt.count;
  ner->originalTPagCount = dataWin->tpag.count;
  for (int32_t i = 0; i < NS_RUNTIME_SPRITE_MAX; i++) {
    ner->runtimeSprites[i].pixels = NULL;
    ner->runtimeSprites[i].spriteIndex = -1;
  }

  // Reserve NS_RUNTIME_SPRITE_MAX TPAG slots at the tail of dw->tpag.items so
  // engine code that reads tpag fields (vm_builtins.c:6323 reads boundingHeight)
  // sees a valid entry for runtime-sprite tpagIndices. Marked free
  // (texturePageId = -1, upstream convention) until createSpriteFromSurface
  // claims one.
  uint32_t newCount = ner->originalTPagCount + NS_RUNTIME_SPRITE_MAX;
  dataWin->tpag.items = (TexturePageItem *)realloc(
      dataWin->tpag.items, newCount * sizeof(TexturePageItem));
  for (uint32_t i = ner->originalTPagCount; i < newCount; i++) {
    memset(&dataWin->tpag.items[i], 0, sizeof(TexturePageItem));
    dataWin->tpag.items[i].texturePageId = -1;
  }
  dataWin->tpag.count = newCount;
#else
  // Snapshot off: leave dw->tpag untouched. Set originalTPagCount to
  // UINT32_MAX so the runtime-sprite dispatch in ns_drawSprite/Part never
  // fires (a valid tpagIndex can't reach that value).
  as_ner(r)->originalTPagCount = (uint32_t)-1;
#endif
}

static void ns_destroy(Renderer *r) {
  (void)r; // assets owned by the caller
}

static void ns_beginFrame(Renderer *r, int32_t gameW, int32_t gameH,
                          int32_t windowW, int32_t windowH) {
  NspireEngineRenderer *ner = as_ner(r);
  ner->gameW = gameW;
  ner->gameH = gameH;
  (void)windowW;
  (void)windowH;
  ns_bindTarget(ner, APPLICATION_SURFACE_ID); // a dangling surface target
                                              // must not eat the frame clear
  NspireRenderer_clear(&ner->fb, 0);
  ner->tintSrc = NULL; // invalidate the tinted-palette cache each frame
  gNspireDrawStats.triCalls = gNspireDrawStats.sprCalls = 0;
  gNspireDrawStats.triPx = gNspireDrawStats.sprPx = 0;
}

static void ns_endFrame(Renderer *r) {
  NspireEngineRenderer *ner = as_ner(r);
  // Free any atlas not touched this frame. Bounds resident RAM to the current
  // scene's working set — preloading/keeping all 14 1024px atlases is ~9.5 MB
  // and crash-restarts alongside the 16 MB data.win. Eviction was wrongly
  // suspected of the battle-font garble; the real cause was the 512-atlas
  // resize bug (now fixed in the cook), so per-frame eviction is safe again.
  if (ner->assets)
    NspireAssets_releaseUntouchedAtlases(ner->assets);
}

static void ns_beginView(Renderer *r, int32_t viewX, int32_t viewY,
                         int32_t viewW, int32_t viewH, int32_t portX,
                         int32_t portY, int32_t portW, int32_t portH,
                         float viewAngle) {
  NspireEngineRenderer *ner = as_ner(r);
  (void)viewAngle;

  // The view describes "what to look at" in room coords; the port describes
  // "where to paint that on the platform's output buffer." Since our
  // framebuffer is HR_SCALE_ATLAS of the engine's nominal port pixels, the
  // engine-to-fb scale folds both factors.
  float scaleX = (viewW > 0) ? ((float)portW / (float)viewW) * HR_SCALE_ATLAS
                             : HR_SCALE_ATLAS;
  float scaleY = (viewH > 0) ? ((float)portH / (float)viewH) * HR_SCALE_ATLAS
                             : HR_SCALE_ATLAS;
  ner->viewToFbScaleX = scaleX;
  ner->viewToFbScaleY = scaleY;
  ner->viewOriginX = (float)viewX;
  ner->viewOriginY = (float)viewY;
  ner->portFbX = (int32_t)((float)portX * HR_SCALE_ATLAS);
  ner->portFbY = (int32_t)((float)portY * HR_SCALE_ATLAS);

  // Scissor = port rect mapped to framebuffer space.
  int32_t sx = ner->portFbX;
  int32_t sy = ner->portFbY;
  int32_t sw = (int32_t)((float)portW * HR_SCALE_ATLAS + 0.5f);
  int32_t sh = (int32_t)((float)portH * HR_SCALE_ATLAS + 0.5f);
  if (sx < 0)
    sx = 0;
  if (sy < 0)
    sy = 0;
  if (sx + sw > NSPIRE_FB_W)
    sw = NSPIRE_FB_W - sx;
  if (sy + sh > NSPIRE_FB_H)
    sh = NSPIRE_FB_H - sy;
  if (sw < 0)
    sw = 0;
  if (sh < 0)
    sh = 0;
  ner->fb.scissorX = sx;
  ner->fb.scissorY = sy;
  ner->fb.scissorW = sw;
  ner->fb.scissorH = sh;
  ner->portX = portX;
  ner->portY = portY;
  ner->portW = portW;
  ner->portH = portH;
  ner->lastViewW = viewW;
  ner->lastViewH = viewH;
  ner->lastPortX = portX;
  ner->lastPortY = portY;
}

static void ns_endView(Renderer *r) { (void)r; }

static void ns_beginGUI(Renderer *r, int32_t guiW, int32_t guiH, int32_t portX,
                        int32_t portY, int32_t portW, int32_t portH) {
  // GUI coords run 0..guiW in engine-space, mapped to the same port rect.
  ns_beginView(r, 0, 0, guiW, guiH, portX, portY, portW, portH, 0.0f);
}

static void ns_endGUI(Renderer *r) { (void)r; }

static void ns_clearScreen(Renderer *r, uint32_t color, float alpha) {
  NspireEngineRenderer *ner = as_ner(r);
  (void)alpha;
  // Engine BGR -> 8bit channels -> RGB565.
  uint8_t b = (uint8_t)((color >> 16) & 0xFF);
  uint8_t g = (uint8_t)((color >> 8) & 0xFF);
  uint8_t rr = (uint8_t)(color & 0xFF);
  NspireRenderer_clear(&ner->fb, nspire_rgb565(rr, g, b));
}

// ===[ Sprite drawing ]===

// Returns the TPAG entry for `tpagIndex`, or NULL if unmapped / out of range.
// `assets` may be NULL during boot tests with the cooked sidecars disabled — in
// that case every TPAG lookup returns NULL and sprite draws are silent no-ops.
//
// As a side effect, marks the underlying atlas as touched-this-frame and
// demand-loads its pixel buffer if not currently resident. Returns NULL if the
// atlas can't be loaded (out-of-memory, file error) so the caller skips the
// draw silently.
static NspireTpagEntry *lookup_tpag(NspireEngineRenderer *ner,
                                    int32_t tpagIndex) {
  if (!ner->assets)
    return NULL;
  if (tpagIndex < 0 || tpagIndex >= ner->assets->tpagCount)
    return NULL;
  NspireTpagEntry *t = &ner->assets->tpags[tpagIndex];
  if (t->atlasId == 0xFFFF)
    return NULL;
  if (t->cropW == 0 || t->cropH == 0)
    return NULL;
  if ((int32_t)t->atlasId >= ner->assets->atlasCount)
    return NULL;
  if (!NspireAssets_ensureAtlas(ner->assets, (int32_t)t->atlasId))
    return NULL;
  return t;
}

// GameMaker image_blend / draw_sprite_ext colour multiplies the texel per
// channel. Rather than add tinted variants of every blitter, build a tinted
// copy of the (<=256 entry) palette once per draw and feed it to the existing
// fast paths. `color` is GM 0x00BBGGRR; 0xFFFFFF (c_white) = no tint. Uses the
// divide-free (v*(t+1))>>8 modulate (exact identity at t=255; ARM926 has no
// FPU). Cached on (src,colour,bpp) in `ner` and reset each frame: a tiled
// tinted background hits this hundreds of times/frame with identical args, so
// the O(256) rebuild collapses to one and the rest are pointer-equal cache
// hits.
static const uint16_t *tint_palette(NspireEngineRenderer *ner,
                                    const uint16_t *src, uint8_t bpp,
                                    uint32_t color) {
  if ((color & 0x00FFFFFFu) == 0x00FFFFFFu)
    return src;
  if (ner->tintSrc == src && ner->tintColor == color && ner->tintBpp == bpp)
    return ner->tintPal;
  uint32_t tr = (color) & 0xFFu;
  uint32_t tg = (color >> 8) & 0xFFu;
  uint32_t tb = (color >> 16) & 0xFFu;
  int32_t n = (bpp == 8) ? 256 : 16;
  for (int32_t i = 0; i < n; i++) {
    uint32_t p = src[i];
    uint32_t r5 = (((p >> 11) & 0x1Fu) * (tr + 1u)) >> 8;
    uint32_t g6 = (((p >> 5) & 0x3Fu) * (tg + 1u)) >> 8;
    uint32_t b5 = ((p & 0x1Fu) * (tb + 1u)) >> 8;
    ner->tintPal[i] = (uint16_t)((r5 << 11) | (g6 << 5) | b5);
  }
  ner->tintSrc = src;
  ner->tintColor = color;
  ner->tintBpp = bpp;
  return ner->tintPal;
}

// Draw an RGB565 runtime-sprite slot (created via createSpriteFromSurface).
// Handles the common axis-aligned positive-scale case via the dedicated
// raw-565 blit; falls through to no-op for rotated/negative/blended draws
// (Muffet doesn't need those paths — extend if other content turns out to).
static void ns_drawRuntimeSprite(NspireEngineRenderer *ner,
                                 const NspireRuntimeSprite *rs, float x,
                                 float y, float originX, float originY,
                                 float xscale, float yscale, float angleDeg,
                                 float alpha) {
  if (alpha <= 0.0f)
    return;
  // Rotation / negative scale not supported for runtime sprites yet — silently
  // skip rather than mis-render. Trip if a real game needs them.
  if (angleDeg != 0.0f && angleDeg != 360.0f)
    return;
  if (xscale < 0.0f || yscale < 0.0f)
    return;

  // Atlas-to-fb stretch math matches the cooked-sprite path: atlas px
  // (= our rs->pixels) is engine px * HR_SCALE_ATLAS by construction (we
  // snapshotted from the fb, which is at that scale).
  float stretchX = xscale * ner->viewToFbScaleX / HR_SCALE_ATLAS;
  float stretchY = yscale * ner->viewToFbScaleY / HR_SCALE_ATLAS;

  int32_t destX = engine_to_fb_x(ner, x - originX * xscale);
  int32_t destY = engine_to_fb_y(ner, y - originY * yscale);
  int32_t srcStridePx = rs->stride / 2;

  // Identity fast path (atlas pixel = framebuffer pixel).
  if (stretchX > 0.999f && stretchX < 1.001f && stretchY > 0.999f &&
      stretchY < 1.001f) {
    NspireRenderer_drawSpritePart16(&ner->fb, rs->pixels, srcStridePx, 0, 0,
                                    rs->w, rs->h, destX, destY);
    return;
  }
  int32_t destW = (int32_t)((float)rs->w * stretchX);
  int32_t destH = (int32_t)((float)rs->h * stretchY);
  if (destW < 1 || destH < 1)
    return;
  NspireRenderer_drawSpritePart16Stretched(&ner->fb, rs->pixels, srcStridePx,
                                           0, 0, rs->w, rs->h, destX, destY,
                                           destW, destH);
}

static void ns_drawSprite(Renderer *r, int32_t tpagIndex, float x, float y,
                          float originX, float originY, float xscale,
                          float yscale, float angleDeg, uint32_t color,
                          float alpha) {
  NspireEngineRenderer *ner = as_ner(r);
  // Runtime-sprite dispatch: tpagIndex was assigned by createSpriteFromSurface
  // into the reserved tail of dw->tpag. Route to the dedicated RGB565 path.
  if ((uint32_t)tpagIndex >= ner->originalTPagCount) {
    int32_t slot = tpagIndex - (int32_t)ner->originalTPagCount;
    if (slot < 0 || slot >= NS_RUNTIME_SPRITE_MAX)
      return;
    NspireRuntimeSprite *rs = &ner->runtimeSprites[slot];
    if (!rs->pixels)
      return;
    gNspireDrawStats.sprCalls++;
    gNspireDrawStats.sprPx += (uint32_t)rs->w * (uint32_t)rs->h;
    gNspireDrawStats.runSprDraws++;
    gNspireDrawStats.runLastDrawX = (int32_t)x;
    gNspireDrawStats.runLastDrawY = (int32_t)y;
    (void)color; // tint not supported for runtime RGB565 sprites
    ns_drawRuntimeSprite(ner, rs, x, y, originX, originY, xscale, yscale,
                         angleDeg, alpha);
    return;
  }
  NspireTpagEntry *t = lookup_tpag(ner, tpagIndex);
  if (!t)
    return;
  gNspireDrawStats.sprCalls++;
  gNspireDrawStats.sprPx += (uint32_t)t->cropW * (uint32_t)t->cropH;

  NspireAtlas *atlas = &ner->assets->atlases[t->atlasId];
  const uint16_t *palette = clut_for(ner, atlas, t->clutIndex);
  if (!palette)
    return;
  palette = tint_palette(ner, palette, atlas->bpp, color);

  bool doBlend = (alpha < 0.996f);
  if (alpha <= 0.0f)
    return;
  uint8_t alpha255 = (uint8_t)(alpha * 255.0f);

  float ax = xscale < 0 ? -xscale : xscale;
  float ay = yscale < 0 ? -yscale : yscale;

  // Atlas-to-fb stretch: cropW/cropH are atlas pixels (= engine pixels *
  // HR_SCALE_ATLAS). Final fb size = engineW * xscale * viewToFb. Atlas size on
  // disk = engineW * HR_SCALE_ATLAS. Therefore stretchAtlas = xscale * viewToFb
  // / HR_SCALE_ATLAS.
  float stretchX = ax * ner->viewToFbScaleX / HR_SCALE_ATLAS;
  float stretchY = ay * ner->viewToFbScaleY / HR_SCALE_ATLAS;

  // ok so like apparently like u have to handle neg acale so draw it usingthis
  // path if its negative
  bool isneg = (xscale < 0.0f) || (yscale < 0.0f);
  if ((angleDeg != 0.0f && angleDeg != 360.0f) || isneg) {
    float rad = -angleDeg * 3.14159265358979f / 180.0f;
    float c = cosf(rad), sn = sinf(rad);
    float sgnX = xscale < 0 ? -1.0f : 1.0f;
    float sgnY = yscale < 0 ? -1.0f : 1.0f;
    float sxv = stretchX * sgnX, syv = stretchY * sgnY;
    float Ux = sxv * c, Uy = sxv * sn;  // d(fb)/d(srcX)
    float Vx = -syv * sn, Vy = syv * c; // d(fb)/d(srcY)
    // halveImage clamps the minimum atlas dim to 1, so a 1-engine-px source
    // (1x1 / 1xN line sprites used for box borders) stays 1 atlas-px in that
    // dim — atlas_px = engine_px (unhalved), not engine_px*0.5. The stretch
    // formula assumes the contract holds, so it draws 2x too wide/tall in the
    // affected dim. Halve the per-source-pixel fb extent when we detect the
    // clamp. Nspire-only (3DS port doesn't pre-halve).
    if (t->cropW == 1) { Ux *= 0.5f; Uy *= 0.5f; }
    if (t->cropH == 1) { Vx *= 0.5f; Vy *= 0.5f; }
    // Crop-local position of the sprite origin (atlas px = engine px *
    // HR_SCALE).
    float i0 = originX * HR_SCALE_ATLAS - (float)t->cropX;
    float j0 = originY * HR_SCALE_ATLAS - (float)t->cropY;
    float pivotX = (float)engine_to_fb_x(ner, x);
    float pivotY = (float)engine_to_fb_y(ner, y);
    float ox = pivotX - i0 * Ux - j0 * Vx;
    float oy = pivotY - i0 * Uy - j0 * Vy;
    NspireRenderer_drawSpriteAffine(&ner->fb, atlas->pixels, atlas->stride,
                                    atlas->bpp, t->atlasX, t->atlasY, t->cropW,
                                    t->cropH, palette, ox, oy, Ux, Uy, Vx, Vy,
                                    alpha255);
    return;
  }

  int32_t destX = engine_to_fb_x(ner, x - originX * ax) +
                  (int32_t)((float)t->cropX * stretchX);
  int32_t destY = engine_to_fb_y(ner, y - originY * ay) +
                  (int32_t)((float)t->cropY * stretchY);

  // Identity fast path (atlas pixel = framebuffer pixel).
  if (stretchX > 0.999f && stretchX < 1.001f && stretchY > 0.999f &&
      stretchY < 1.001f) {
    if (doBlend) {
      if (atlas->bpp == 8) {
        NspireRenderer_drawSpritePart8Blend(
            &ner->fb, atlas->pixels, atlas->stride, t->atlasX, t->atlasY,
            t->cropW, t->cropH, palette, destX, destY, alpha255);
      } else {
        NspireRenderer_drawSpritePart4Blend(
            &ner->fb, atlas->pixels, atlas->stride, t->atlasX, t->atlasY,
            t->cropW, t->cropH, palette, destX, destY, alpha255);
      }
    } else if (atlas->bpp == 8) {
      NspireRenderer_drawSpritePart8(&ner->fb, atlas->pixels, atlas->stride,
                                     t->atlasX, t->atlasY, t->cropW, t->cropH,
                                     palette, destX, destY);
    } else {
      NspireRenderer_drawSpritePart4(&ner->fb, atlas->pixels, atlas->stride,
                                     t->atlasX, t->atlasY, t->cropW, t->cropH,
                                     palette, destX, destY);
    }
    return;
  }

  // Same clamp correction as the affine path above: halveImage's 1-px floor
  // breaks the atlas_px = engine_px * 0.5 contract for 1-engine-px sprites,
  // so the stretched destW/destH come out 2x too large for those.
  float effStretchX = (t->cropW == 1) ? stretchX * 0.5f : stretchX;
  float effStretchY = (t->cropH == 1) ? stretchY * 0.5f : stretchY;
  int32_t destW = (int32_t)((float)t->cropW * effStretchX);
  int32_t destH = (int32_t)((float)t->cropH * effStretchY);
  if (destW < 1 || destH < 1)
    return;

  if (doBlend) {
    if (atlas->bpp == 8) {
      NspireRenderer_drawSpritePart8StretchedBlend(
          &ner->fb, atlas->pixels, atlas->stride, t->atlasX, t->atlasY,
          t->cropW, t->cropH, palette, destX, destY, destW, destH, alpha255);
    } else {
      NspireRenderer_drawSpritePart4StretchedBlend(
          &ner->fb, atlas->pixels, atlas->stride, t->atlasX, t->atlasY,
          t->cropW, t->cropH, palette, destX, destY, destW, destH, alpha255);
    }
  } else if (atlas->bpp == 8) {
    NspireRenderer_drawSpritePart8Stretched(
        &ner->fb, atlas->pixels, atlas->stride, t->atlasX, t->atlasY, t->cropW,
        t->cropH, palette, destX, destY, destW, destH);
  } else {
    NspireRenderer_drawSpritePart4Stretched(
        &ner->fb, atlas->pixels, atlas->stride, t->atlasX, t->atlasY, t->cropW,
        t->cropH, palette, destX, destY, destW, destH);
  }
}

static void ns_drawSpritePart(Renderer *r, int32_t tpagIndex, int32_t srcOffX,
                              int32_t srcOffY, int32_t srcW, int32_t srcH,
                              float x, float y, float xscale, float yscale,
                              float angleDeg, float pivotX, float pivotY,
                              uint32_t color, float alpha) {
  NspireEngineRenderer *ner = as_ner(r);
  // Runtime-sprite dispatch (same as ns_drawSprite). draw_sprite_part on a
  // runtime sprite is rare but the engine could in principle hit it; route
  // through the whole-sprite path treating srcOffX/Y/W/H as offsets into the
  // snapshot. For Muffet (the only known caller) this isn't exercised; kept
  // safe to call regardless.
  if ((uint32_t)tpagIndex >= ner->originalTPagCount) {
    int32_t slot = tpagIndex - (int32_t)ner->originalTPagCount;
    if (slot < 0 || slot >= NS_RUNTIME_SPRITE_MAX)
      return;
    NspireRuntimeSprite *rs = &ner->runtimeSprites[slot];
    if (!rs->pixels)
      return;
    gNspireDrawStats.sprCalls++;
    gNspireDrawStats.sprPx += (uint32_t)rs->w * (uint32_t)rs->h;
    gNspireDrawStats.runSprDraws++;
    gNspireDrawStats.runLastDrawX = (int32_t)x;
    gNspireDrawStats.runLastDrawY = (int32_t)y;
    (void)srcOffX;
    (void)srcOffY;
    (void)srcW;
    (void)srcH;
    (void)pivotX;
    (void)pivotY;
    (void)color;
    ns_drawRuntimeSprite(ner, rs, x, y, 0, 0, xscale, yscale, angleDeg, alpha);
    return;
  }
  NspireTpagEntry *t = lookup_tpag(ner, tpagIndex);
  if (!t)
    return;
  (void)pivotX;
  (void)pivotY;
  (void)alpha;
  gNspireDrawStats.sprCalls++;
  gNspireDrawStats.sprPx += (uint32_t)t->cropW * (uint32_t)t->cropH;

  // Halve the engine's source-rect to match cooked (halved) crop coords.
  int32_t hSrcX = halve_floor(srcOffX);
  int32_t hSrcY = halve_floor(srcOffY);
  int32_t hSrcW = halve_ceil(srcW);
  int32_t hSrcH = halve_ceil(srcH);

  int32_t iX = hSrcX > t->cropX ? hSrcX : t->cropX;
  int32_t iY = hSrcY > t->cropY ? hSrcY : t->cropY;
  int32_t srcEndX = hSrcX + hSrcW;
  int32_t srcEndY = hSrcY + hSrcH;
  int32_t cropEndX = t->cropX + t->cropW;
  int32_t cropEndY = t->cropY + t->cropH;
  int32_t iEndX = srcEndX < cropEndX ? srcEndX : cropEndX;
  int32_t iEndY = srcEndY < cropEndY ? srcEndY : cropEndY;
  if (iX >= iEndX || iY >= iEndY)
    return;
  int32_t iW = iEndX - iX;
  int32_t iH = iEndY - iY;

  NspireAtlas *atlas = &ner->assets->atlases[t->atlasId];
  const uint16_t *palette = clut_for(ner, atlas, t->clutIndex);
  if (!palette)
    return;
  palette = tint_palette(ner, palette, atlas->bpp, color);

  int32_t sampleX = t->atlasX + (iX - t->cropX);
  int32_t sampleY = t->atlasY + (iY - t->cropY);

  float ax = xscale < 0 ? -xscale : xscale;
  float ay = yscale < 0 ? -yscale : yscale;
  float stretchX = ax * ner->viewToFbScaleX / HR_SCALE_ATLAS;
  float stretchY = ay * ner->viewToFbScaleY / HR_SCALE_ATLAS;

  // same stuff here
  bool hasNegScale = (xscale < 0.0f) || (yscale < 0.0f);
  if ((angleDeg != 0.0f && angleDeg != 360.0f) || hasNegScale) {
    float rad = -angleDeg * 3.14159265358979f / 180.0f;
    float c = cosf(rad), sn = sinf(rad);
    float sgnX = xscale < 0 ? -1.0f : 1.0f;
    float sgnY = yscale < 0 ? -1.0f : 1.0f;
    float sxv = stretchX * sgnX, syv = stretchY * sgnY;
    float Ux = sxv * c, Uy = sxv * sn;
    float Vx = -syv * sn, Vy = syv * c;
    // Same halve-on-clamp correction as ns_drawSprite — see comment there.
    // For sprite_part the visible-source extent uses iW/iH (intersection of
    // engine srcW with the atlas crop); 1-engine-px sources still surface as
    // iW == 1 / iH == 1 here.
    if (iW == 1) { Ux *= 0.5f; Uy *= 0.5f; }
    if (iH == 1) { Vx *= 0.5f; Vy *= 0.5f; }
    float pivotX = (float)engine_to_fb_x(ner, x);
    float pivotY = (float)engine_to_fb_y(ner, y);
    float offS = (float)(iX - hSrcX);
    float offT = (float)(iY - hSrcY);
    float ox = pivotX + offS * Ux + offT * Vx;
    float oy = pivotY + offS * Uy + offT * Vy;
    NspireRenderer_drawSpriteAffine(&ner->fb, atlas->pixels, atlas->stride,
                                    atlas->bpp, sampleX, sampleY, iW, iH,
                                    palette, ox, oy, Ux, Uy, Vx, Vy, 255);
    return;
  }

  int32_t destX =
      engine_to_fb_x(ner, x) + (int32_t)((float)(iX - hSrcX) * stretchX);
  int32_t destY =
      engine_to_fb_y(ner, y) + (int32_t)((float)(iY - hSrcY) * stretchY);

  if (stretchX > 0.999f && stretchX < 1.001f && stretchY > 0.999f &&
      stretchY < 1.001f) {
    if (atlas->bpp == 8) {
      NspireRenderer_drawSpritePart8(&ner->fb, atlas->pixels, atlas->stride,
                                     sampleX, sampleY, iW, iH, palette, destX,
                                     destY);
    } else {
      NspireRenderer_drawSpritePart4(&ner->fb, atlas->pixels, atlas->stride,
                                     sampleX, sampleY, iW, iH, palette, destX,
                                     destY);
    }
    return;
  }

  // Halve-on-clamp correction (see ns_drawSprite for the rationale).
  float effStretchX = (iW == 1) ? stretchX * 0.5f : stretchX;
  float effStretchY = (iH == 1) ? stretchY * 0.5f : stretchY;
  int32_t destW = (int32_t)((float)iW * effStretchX);
  int32_t destH = (int32_t)((float)iH * effStretchY);
  if (destW < 1 || destH < 1)
    return;

  if (atlas->bpp == 8) {
    NspireRenderer_drawSpritePart8Stretched(
        &ner->fb, atlas->pixels, atlas->stride, sampleX, sampleY, iW, iH,
        palette, destX, destY, destW, destH);
  } else {
    NspireRenderer_drawSpritePart4Stretched(
        &ner->fb, atlas->pixels, atlas->stride, sampleX, sampleY, iW, iH,
        palette, destX, destY, destW, destH);
  }
}

static void ns_drawSpritePos(Renderer *r, int32_t tpagIndex, float x1, float y1,
                             float x2, float y2, float x3, float y3, float x4,
                             float y4, float alpha) {
  NspireEngineRenderer *ner = as_ner(r);
  (void)x3;
  (void)y3; // BR derived from the parallelogram (no perspective)
  NspireTpagEntry *t = lookup_tpag(ner, tpagIndex);
  if (!t)
    return;
  gNspireDrawStats.sprCalls++;
  gNspireDrawStats.sprPx += (uint32_t)t->cropW * (uint32_t)t->cropH;
  NspireAtlas *atlas = &ner->assets->atlases[t->atlasId];
  const uint16_t *palette = clut_for(ner, atlas, t->clutIndex);
  if (!palette || alpha <= 0.0f)
    return;

  // Corners: 1=TL 2=TR 4=BL. Map to fb, build the affine that sends the crop
  // region's (0,0)->TL, (cropW,0)->TR, (0,cropH)->BL.
  float tlx = (float)engine_to_fb_x(ner, x1),
        tly = (float)engine_to_fb_y(ner, y1);
  float trx = (float)engine_to_fb_x(ner, x2),
        try_ = (float)engine_to_fb_y(ner, y2);
  float blx = (float)engine_to_fb_x(ner, x4),
        bly = (float)engine_to_fb_y(ner, y4);
  float cw = t->cropW > 0 ? (float)t->cropW : 1.0f;
  float ch = t->cropH > 0 ? (float)t->cropH : 1.0f;
  float Ux = (trx - tlx) / cw, Uy = (try_ - tly) / cw;
  float Vx = (blx - tlx) / ch, Vy = (bly - tly) / ch;
  uint8_t a255 = alpha >= 0.996f ? 255 : (uint8_t)(alpha * 255.0f);
  NspireRenderer_drawSpriteAffine(
      &ner->fb, atlas->pixels, atlas->stride, atlas->bpp, t->atlasX, t->atlasY,
      t->cropW, t->cropH, palette, tlx, tly, Ux, Uy, Vx, Vy, a255);
}

// ===[ Primitives ]===

// One fb-space rectangle span, opaque (fillRect) or alpha-blended (blendRect).
static inline void ns_rectSpan(NspireEngineRenderer *ner, int32_t x, int32_t y,
                               int32_t w, int32_t h, uint16_t c565,
                               float alpha) {
  if (w <= 0 || h <= 0 || alpha <= 0.0f)
    return;
  if (alpha >= 0.996f) {
    NspireRenderer_fillRect(&ner->fb, x, y, w, h, c565);
  } else {
    NspireRenderer_blendRect(&ner->fb, x, y, w, h, c565,
                             (uint8_t)(alpha * 255.0f));
  }
}

// Shared by draw_rectangle / draw_rectangle_color. GameMaker's `outline` flag
// draws only the 1px border — Undertale's attack telegraphs (Sans bone
// warnings, Asriel's box/star attacks) rely on this to mark where a hit will
// land WITHOUT occluding the play area. Filling it instead (the old `(void)
// outline`) turned every telegraph into a solid box. Border thickness tracks
// the view->fb scale so it stays visible at any scale (like ns_drawLine's pen
// width).
static void ns_fillOrOutlineRect(NspireEngineRenderer *ner, float x1, float y1,
                                 float x2, float y2, uint32_t color,
                                 float alpha, bool outline) {
  uint8_t b = (uint8_t)((color >> 16) & 0xFF);
  uint8_t g = (uint8_t)((color >> 8) & 0xFF);
  uint8_t rr = (uint8_t)(color & 0xFF);
  int32_t hx1 = engine_to_fb_x(ner, x1), hy1 = engine_to_fb_y(ner, y1);
  int32_t hx2 = engine_to_fb_x(ner, x2), hy2 = engine_to_fb_y(ner, y2);
  if (hx2 < hx1) {
    int32_t t = hx1;
    hx1 = hx2;
    hx2 = t;
  }
  if (hy2 < hy1) {
    int32_t t = hy1;
    hy1 = hy2;
    hy2 = t;
  }
  uint16_t c565 = nspire_rgb565(rr, g, b);
  int32_t w = hx2 - hx1, h = hy2 - hy1;
  if (w <= 0 || h <= 0)
    return;

  if (!outline) {
    ns_rectSpan(ner, hx1, hy1, w, h, c565, alpha);
    return;
  }
  int32_t bt = (int32_t)(ner->viewToFbScaleX + 0.5f);
  if (bt < 1)
    bt = 1;
  if (w <= 2 * bt || h <= 2 * bt) { // too thin to be hollow
    ns_rectSpan(ner, hx1, hy1, w, h, c565, alpha);
    return;
  }
  ns_rectSpan(ner, hx1, hy1, w, bt, c565, alpha);                    // top
  ns_rectSpan(ner, hx1, hy2 - bt, w, bt, c565, alpha);               // bottom
  ns_rectSpan(ner, hx1, hy1 + bt, bt, h - 2 * bt, c565, alpha);      // left
  ns_rectSpan(ner, hx2 - bt, hy1 + bt, bt, h - 2 * bt, c565, alpha); // right
}

static void ns_drawRectangle(Renderer *r, float x1, float y1, float x2,
                             float y2, uint32_t color, float alpha,
                             bool outline) {
  ns_fillOrOutlineRect(as_ner(r), x1, y1, x2, y2, color, alpha, outline);
}
static void ns_drawRectangleColor(Renderer *r, float x1, float y1, float x2,
                                  float y2, uint32_t c1, uint32_t c2,
                                  uint32_t c3, uint32_t c4, float alpha,
                                  bool outline) {
  // No per-vertex gradient in the software path — use the top-left color flat.
  (void)c2;
  (void)c3;
  (void)c4;
  ns_fillOrOutlineRect(as_ner(r), x1, y1, x2, y2, c1, alpha, outline);
}
static void ns_drawLine(Renderer *r, float x1, float y1, float x2, float y2,
                        float width, uint32_t color, float alpha) {
  NspireEngineRenderer *ner = as_ner(r);
  if (alpha <= 0.0f)
    return;
  uint8_t b = (uint8_t)((color >> 16) & 0xFF);
  uint8_t g = (uint8_t)((color >> 8) & 0xFF);
  uint8_t rr = (uint8_t)(color & 0xFF);
  int32_t fw = (int32_t)(width * ner->viewToFbScaleX + 0.5f);
  if (fw < 1)
    fw = 1;
  NspireRenderer_drawLine(&ner->fb, engine_to_fb_x(ner, x1),
                          engine_to_fb_y(ner, y1), engine_to_fb_x(ner, x2),
                          engine_to_fb_y(ner, y2), fw, nspire_rgb565(rr, g, b),
                          alpha >= 0.996f ? 255 : (uint8_t)(alpha * 255.0f));
}
static void ns_drawLineColor(Renderer *r, float x1, float y1, float x2,
                             float y2, float width, uint32_t c1, uint32_t c2,
                             float alpha) {
  (void)c2; // no gradient: use the start color
  ns_drawLine(r, x1, y1, x2, y2, width, c1, alpha);
}
static void ns_drawTriangle(Renderer *r, float x1, float y1, float x2, float y2,
                            float x3, float y3, bool outline) {
  NspireEngineRenderer *ner = as_ner(r);
  if (r->drawAlpha <= 0.0f)
    return;
  uint8_t cb = (uint8_t)((r->drawColor >> 16) & 0xFF);
  uint8_t cg = (uint8_t)((r->drawColor >> 8) & 0xFF);
  uint8_t cr = (uint8_t)(r->drawColor & 0xFF);
  uint16_t c = nspire_rgb565(cr, cg, cb);
  uint8_t a = r->drawAlpha >= 0.996f ? 255 : (uint8_t)(r->drawAlpha * 255.0f);
  int32_t ax = engine_to_fb_x(ner, x1), ay = engine_to_fb_y(ner, y1);
  int32_t bx = engine_to_fb_x(ner, x2), by = engine_to_fb_y(ner, y2);
  int32_t cx = engine_to_fb_x(ner, x3), cy = engine_to_fb_y(ner, y3);
  {
    int32_t mnx = ax < bx ? (ax < cx ? ax : cx) : (bx < cx ? bx : cx);
    int32_t mxx = ax > bx ? (ax > cx ? ax : cx) : (bx > cx ? bx : cx);
    int32_t mny = ay < by ? (ay < cy ? ay : cy) : (by < cy ? by : cy);
    int32_t mxy = ay > by ? (ay > cy ? ay : cy) : (by > cy ? by : cy);
    gNspireDrawStats.triCalls++;
    if (mxx > mnx && mxy > mny)
      gNspireDrawStats.triPx += (uint32_t)(mxx - mnx) * (uint32_t)(mxy - mny);
  }
  if (outline) {
    NspireRenderer_drawLine(&ner->fb, ax, ay, bx, by, 1, c, a);
    NspireRenderer_drawLine(&ner->fb, bx, by, cx, cy, 1, c, a);
    NspireRenderer_drawLine(&ner->fb, cx, cy, ax, ay, 1, c, a);
  } else {
    NspireRenderer_fillTriangle(&ner->fb, ax, ay, bx, by, cx, cy, c, a);
  }
}

// ===[ Text — stubs ]===

// Pick a draw color from the engine's drawColor (BGR) so engine-side font tints
// come through.
static inline uint16_t engine_color_to_565(uint32_t bgr) {
  uint8_t b = (uint8_t)((bgr >> 16) & 0xFF);
  uint8_t g = (uint8_t)((bgr >> 8) & 0xFF);
  uint8_t r = (uint8_t)(bgr & 0xFF);
  return nspire_rgb565(r, g, b);
}

// Render text using the GameMaker font selected by r->drawFont. Each glyph is a
// sub-rect of the font's TPAG on a cooked atlas; we halve the engine-space
// source coords (preprocessor halved the atlas pixels), then blit at the
// engine-space dest scaled to halved framebuffer space. Falls back to the 5x7
// overlay font if the font is missing/invalid.
static void render_game_text(NspireEngineRenderer *ner, const char *text,
                             float x, float y, float xs, float ys,
                             uint16_t fallbackColor) {
  if (!text || !ner->base.dataWin) {
    NspireOverlay_drawTextScaled(&ner->fb, engine_to_fb_x(ner, x),
                                 engine_to_fb_y(ner, y), fallbackColor, 1,
                                 text);
    return;
  }
  DataWin *dw = ner->base.dataWin;
  int32_t fontIdx = ner->base.drawFont;
  if (fontIdx < 0 || (uint32_t)fontIdx >= dw->font.count) {
    NspireOverlay_drawTextScaled(&ner->fb, engine_to_fb_x(ner, x),
                                 engine_to_fb_y(ner, y), fallbackColor, 1,
                                 text);
    return;
  }
  const Font *font = &dw->font.fonts[fontIdx];

  if (font->tpagIndex < 0 || font->tpagIndex >= ner->assets->tpagCount) {
    NspireOverlay_drawTextScaled(&ner->fb, engine_to_fb_x(ner, x),
                                 engine_to_fb_y(ner, y), fallbackColor, 1,
                                 text);
    return;
  }
  NspireTpagEntry *t = &ner->assets->tpags[font->tpagIndex];
  // The preprocessor's extractFromTPAG repacks the font glyph sheet into a
  // boundingWidth x boundingHeight canvas, writing the real pixels at offset
  // (tpag.targetX, tpag.targetY). Fonts are NOT cropped afterward (only spr/
  // images are), so that padding survives into the cooked atlas: a glyph's
  // pixels live at atlasX + targetX + glyph.sourceX. The cooked NspireTpagEntry
  // doesn't carry targetX/Y, but the original TexturePageItem does and shares
  // the same index space (font->tpagIndex), so read it straight from data.win.
  int32_t glyphOriginX = 0, glyphOriginY = 0;
  if ((uint32_t)font->tpagIndex < dw->tpag.count) {
    TexturePageItem *gmTpag = &dw->tpag.items[font->tpagIndex];
    glyphOriginX = (int32_t)gmTpag->targetX;
    glyphOriginY = (int32_t)gmTpag->targetY;
  }
  if (t->atlasId == 0xFFFF || (int32_t)t->atlasId >= ner->assets->atlasCount) {
    NspireOverlay_drawTextScaled(&ner->fb, engine_to_fb_x(ner, x),
                                 engine_to_fb_y(ner, y), fallbackColor, 1,
                                 text);
    return;
  }
  if (!NspireAssets_ensureAtlas(ner->assets, (int32_t)t->atlasId)) {
    NspireOverlay_drawTextScaled(&ner->fb, engine_to_fb_x(ner, x),
                                 engine_to_fb_y(ner, y), fallbackColor, 1,
                                 text);
    return;
  }
  NspireAtlas *atlas = &ner->assets->atlases[t->atlasId];
  const uint16_t *palette = NULL;
  if (atlas->bpp == 8) {
    if (t->clutIndex < (uint16_t)ner->assets->clut8Count)
      palette = ner->assets->clut8[t->clutIndex].entries;
  } else {
    if (t->clutIndex < (uint16_t)ner->assets->clut4Count)
      palette = ner->assets->clut4[t->clutIndex].entries;
  }
  if (!palette) {
    NspireOverlay_drawTextScaled(&ner->fb, engine_to_fb_x(ner, x),
                                 engine_to_fb_y(ner, y), fallbackColor, 1,
                                 text);
    return;
  }

  // Fonts skip the preprocessor's halving pass — atlas is at full engine
  // resolution. Engine's full text-scale formula (from gs_renderer.c
  // gsResolveFontState):
  //   screenScale = xscale * font->scaleX * gs->scaleX
  // For us, viewToFb already folds in the gs->scaleX equivalent (it's the
  // engine-to-framebuffer ratio). So effective glyph-to-fb scale = xs *
  // fontScale * viewToFb.
  float fontScaleX = font->scaleX > 0 ? font->scaleX : 1.0f;
  float fontScaleY = font->scaleY > 0 ? font->scaleY : 1.0f;
  float effScaleX = xs * fontScaleX * ner->viewToFbScaleX;
  float effScaleY = ys * fontScaleY * ner->viewToFbScaleY;

  // No floor clamp on effScale. The earlier clamp-to-1.0 was a workaround for
  // what looked like "downsampling destroys pixel fonts" — but that garble was
  // actually the 512-atlas oversize-resize corrupting fnt_main (now fixed in
  // the cook). With correct full-res glyph data, the area-pool Shrunk path
  // renders text at the engine's intended size (battle's 640-wide GUI →
  // effScale~0.5 → proper half-size text that fits its box).

  int32_t lineStartFbX = engine_to_fb_x(ner, x);
  int32_t penFbX = lineStartFbX;
  int32_t penFbY = engine_to_fb_y(ner, y);
  bool useTint = (fallbackColor != 0xFFFF);

  for (const char *p = text; *p; p++) {
    uint8_t ch = (uint8_t)*p;
    if (ch == '\n') {
      penFbX = lineStartFbX;
      penFbY += (int32_t)((float)font->maxGlyphHeight * effScaleY);
      continue;
    }
    FontGlyph *g = ch < 128 ? font->glyphLUT[ch] : NULL;
    if (!g) {
      penFbX += (int32_t)((float)(font->emSize / 4) * effScaleX);
      continue;
    }
    int32_t srcX = t->atlasX + glyphOriginX + (int32_t)g->sourceX;
    int32_t srcY = t->atlasY + glyphOriginY + (int32_t)g->sourceY;
    int32_t srcW = (int32_t)g->sourceWidth;
    int32_t srcH = (int32_t)g->sourceHeight;
    if (srcW > 0 && srcH > 0) {
      // Per-glyph X offset (engine: cursorX + glyph->offset). Many fonts use
      // this to position narrow chars (e.g. 'i', 'l') correctly within their
      // cell.
      int32_t destX = penFbX + (int32_t)((float)g->offset * effScaleX);
      int32_t destY = penFbY;
      if (effScaleX > 0.999f && effScaleX < 1.001f && effScaleY > 0.999f &&
          effScaleY < 1.001f) {
        // Native size — no rescale (effScale ~= 1).
        if (useTint) {
          if (atlas->bpp == 8) {
            NspireRenderer_drawGlyphPart8Solid(
                &ner->fb, atlas->pixels, atlas->stride, srcX, srcY, srcW, srcH,
                fallbackColor, destX, destY);
          } else {
            NspireRenderer_drawGlyphPart4Solid(
                &ner->fb, atlas->pixels, atlas->stride, srcX, srcY, srcW, srcH,
                fallbackColor, destX, destY);
          }
        } else if (atlas->bpp == 8) {
          NspireRenderer_drawSpritePart8(&ner->fb, atlas->pixels, atlas->stride,
                                         srcX, srcY, srcW, srcH, palette, destX,
                                         destY);
        } else {
          NspireRenderer_drawSpritePart4(&ner->fb, atlas->pixels, atlas->stride,
                                         srcX, srcY, srcW, srcH, palette, destX,
                                         destY);
        }
      } else if (effScaleX < 0.999f || effScaleY < 0.999f) {
        // Sub-1 scale: area-pool downsample (handles 2:1, 3:1, 4:1, anything).
        int32_t destW = (int32_t)((float)srcW * effScaleX);
        int32_t destH = (int32_t)((float)srcH * effScaleY);
        if (destW < 1)
          destW = 1;
        if (destH < 1)
          destH = 1;
        if (useTint) {
          if (atlas->bpp == 8) {
            NspireRenderer_drawGlyphPart8ShrunkSolid(
                &ner->fb, atlas->pixels, atlas->stride, srcX, srcY, srcW, srcH,
                fallbackColor, destX, destY, destW, destH);
          } else {
            NspireRenderer_drawGlyphPart4ShrunkSolid(
                &ner->fb, atlas->pixels, atlas->stride, srcX, srcY, srcW, srcH,
                fallbackColor, destX, destY, destW, destH);
          }
        } else if (atlas->bpp == 8) {
          NspireRenderer_drawGlyphPart8Shrunk(
              &ner->fb, atlas->pixels, atlas->stride, srcX, srcY, srcW, srcH,
              palette, destX, destY, destW, destH);
        } else {
          NspireRenderer_drawGlyphPart4Shrunk(
              &ner->fb, atlas->pixels, atlas->stride, srcX, srcY, srcW, srcH,
              palette, destX, destY, destW, destH);
        }
      } else {
        // Upscale path (e.g. engine asks for big "GAME OVER" text).
        int32_t destW = (int32_t)((float)srcW * effScaleX);
        int32_t destH = (int32_t)((float)srcH * effScaleY);
        if (destW < 1)
          destW = 1;
        if (destH < 1)
          destH = 1;
        if (atlas->bpp == 8) {
          NspireRenderer_drawSpritePart8Stretched(
              &ner->fb, atlas->pixels, atlas->stride, srcX, srcY, srcW, srcH,
              palette, destX, destY, destW, destH);
        } else {
          NspireRenderer_drawSpritePart4Stretched(
              &ner->fb, atlas->pixels, atlas->stride, srcX, srcY, srcW, srcH,
              palette, destX, destY, destW, destH);
        }
      }
    }
    penFbX += (int32_t)((float)g->shift * effScaleX);
  }
}

static void ns_drawText(Renderer *r, const char *text, float x, float y,
                        float xs, float ys, float angleDeg) {
  NspireEngineRenderer *ner = as_ner(r);
  (void)angleDeg;
  if (!text)
    return;
  render_game_text(ner, text, x, y, xs, ys, engine_color_to_565(r->drawColor));
}

static void ns_drawTextColor(Renderer *r, const char *text, float x, float y,
                             float xs, float ys, float angleDeg, int32_t c1,
                             int32_t c2, int32_t c3, int32_t c4, float alpha) {
  (void)c2;
  (void)c3;
  (void)c4;
  (void)alpha;
  (void)angleDeg;
  NspireEngineRenderer *ner = as_ner(r);
  if (!text)
    return;
  render_game_text(ner, text, x, y, xs, ys, engine_color_to_565((uint32_t)c1));
}

// ===[ Misc ]===

static void ns_flush(Renderer *r) { (void)r; }

// ===[ Surfaces & GPU state — all stubs; surface non-goal per the brief ]===

// `sprite_create_from_surface(application_surface, x, y, w, h, …)` — snapshot
// the current fb at the requested rect into a new sprite slot, then return
// the sprite index so the game can later draw it via the normal sprite path.
// Architecturally this is iProgramMC's approach (sw_renderer.c:1642): we
// don't implement render-target surfaces at all, just the readback. Enough
// to make Undertale's Muffet bullet-box work (the only Undertale surface
// user per the creator), at zero cost to any other content.
static int32_t ns_createSpriteFromSurface(Renderer *r, int32_t surfaceID,
                                          int32_t x, int32_t y, int32_t w,
                                          int32_t h, bool removeBack,
                                          bool smooth, int32_t xorig,
                                          int32_t yorig) {
#if !NS_ENABLE_SURFACE_SNAPSHOT
  (void)r; (void)surfaceID; (void)x; (void)y; (void)w; (void)h;
  (void)removeBack; (void)smooth; (void)xorig; (void)yorig;
  return -1;
#else
  (void)removeBack;
  (void)smooth;
  NspireEngineRenderer *ner = as_ner(r);
  DataWin *dw = r->dataWin;

  // Only the application_surface (id = -1) is supported — matches iProgramMC.
  // For any other surface ID we'd need a real render-target backend, which is
  // out of scope here.
  if (surfaceID != -1) {
    fprintf(stderr,
            "ns_createSpriteFromSurface: only application_surface (-1) "
            "supported, got %d\n",
            (int)surfaceID);
    return -1;
  }
  if (w <= 0 || h <= 0)
    return -1;

  // Find a free runtime slot.
  int32_t slot = -1;
  for (int32_t i = 0; i < NS_RUNTIME_SPRITE_MAX; i++) {
    if (!ner->runtimeSprites[i].pixels) {
      slot = i;
      break;
    }
  }
  if (slot < 0) {
    fprintf(stderr, "ns_createSpriteFromSurface: runtime slot overflow\n");
    return -1;
  }

  // Engine coords are in game-logical (room) space. The fb is at HR_SCALE_ATLAS
  // of that, so multiply through to get fb pixel coords + dims.
  int32_t fbX = (int32_t)((float)x * HR_SCALE_ATLAS);
  int32_t fbY = (int32_t)((float)y * HR_SCALE_ATLAS);
  int32_t fbW = (int32_t)((float)w * HR_SCALE_ATLAS);
  int32_t fbH = (int32_t)((float)h * HR_SCALE_ATLAS);
  if (fbW < 1) fbW = 1;
  if (fbH < 1) fbH = 1;
  // Stamp source rect so the HUD can show what we captured — confirms whether
  // we grabbed Muffet's box region or empty fb.
  gNspireDrawStats.snapLastSrcX = fbX;
  gNspireDrawStats.snapLastSrcY = fbY;
  gNspireDrawStats.snapLastSrcW = fbW;
  gNspireDrawStats.snapLastSrcH = fbH;

  uint16_t *buf = (uint16_t *)malloc((size_t)fbW * (size_t)fbH * sizeof(uint16_t));
  if (!buf) {
    fprintf(stderr, "ns_createSpriteFromSurface: malloc failed (%dx%d)\n", (int)fbW, (int)fbH);
    return -1;
  }

  // Read pixels out of the current fb at (fbX, fbY) with edge clamping
  // (transparent black for out-of-bounds, matching iProgramMC's behaviour).
  for (int32_t iy = 0; iy < fbH; iy++) {
    uint16_t *dstRow = &buf[iy * fbW];
    int32_t sy = fbY + iy;
    if (sy < 0 || sy >= ner->fb.fbH) {
      memset(dstRow, 0, (size_t)fbW * sizeof(uint16_t));
      continue;
    }
    const uint16_t *srcRow = ner->fb.fb + sy * ner->fb.fbW;
    for (int32_t ix = 0; ix < fbW; ix++) {
      int32_t sx = fbX + ix;
      dstRow[ix] = (sx < 0 || sx >= ner->fb.fbW) ? 0 : srcRow[sx];
    }
  }

  // Populate our renderer-side runtime sprite slot.
  ner->runtimeSprites[slot].pixels = buf;
  ner->runtimeSprites[slot].w = fbW;
  ner->runtimeSprites[slot].h = fbH;
  ner->runtimeSprites[slot].stride = fbW * 2;

  // Reuse-or-claim a reserved dw->tpag slot (one per runtime sprite, at the
  // tail we extended in ns_init). The slot index in dw->tpag is the same as
  // our runtime slot — the dispatch in ns_drawSprite/Part keys off this.
  uint32_t tpagIndex = ner->originalTPagCount + (uint32_t)slot;
  TexturePageItem *tpag = &dw->tpag.items[tpagIndex];
  tpag->sourceX = 0;
  tpag->sourceY = 0;
  tpag->sourceWidth = (uint16_t)w;
  tpag->sourceHeight = (uint16_t)h;
  tpag->targetX = 0;
  tpag->targetY = 0;
  tpag->targetWidth = (uint16_t)w;
  tpag->targetHeight = (uint16_t)h;
  tpag->boundingWidth = (uint16_t)w;
  tpag->boundingHeight = (uint16_t)h;
  // Mark as "claimed but synthetic"; engine doesn't read this field for
  // anything safety-relevant, our dispatch path bypasses tpag entirely.
  tpag->texturePageId = 0;

  // Allocate a sprite slot via the upstream helper (extends dw->sprt if no
  // free slot exists, sets a "__newsprite<N>" name).
  uint32_t spriteIndex =
      DataWin_allocSpriteSlot(dw, ner->originalSpriteCount);
  Sprite *sprite = &dw->sprt.sprites[spriteIndex];
  sprite->width = (uint32_t)w;
  sprite->height = (uint32_t)h;
  sprite->originX = xorig;
  sprite->originY = yorig;
  sprite->textureCount = 1;
  // Upstream resolves the unresolved textureOffsets at parse time and only
  // stores the resolved tpagIndices here — no textureOffsets field exists.
  sprite->tpagIndices = (int32_t *)malloc(sizeof(int32_t));
  if (sprite->tpagIndices)
    sprite->tpagIndices[0] = (int32_t)tpagIndex;
  sprite->maskCount = 0;
  sprite->masks = NULL;

  ner->runtimeSprites[slot].spriteIndex = (int32_t)spriteIndex;
  gNspireDrawStats.snapCreates++;
  gNspireDrawStats.snapLive++;
  return (int32_t)spriteIndex;
#endif
}

static void ns_deleteSprite(Renderer *r, int32_t spriteIndex) {
#if !NS_ENABLE_SURFACE_SNAPSHOT
  (void)r;
  (void)spriteIndex;
#else
  NspireEngineRenderer *ner = as_ner(r);
  DataWin *dw = r->dataWin;
  if (spriteIndex < 0)
    return;
  // Refuse to delete a cooked sprite (those don't have runtime backing).
  if ((uint32_t)spriteIndex < ner->originalSpriteCount)
    return;

  // Find the runtime slot that owns this spriteIndex.
  int32_t slot = -1;
  for (int32_t i = 0; i < NS_RUNTIME_SPRITE_MAX; i++) {
    if (ner->runtimeSprites[i].spriteIndex == spriteIndex) {
      slot = i;
      break;
    }
  }
  if (slot < 0)
    return;

  free(ner->runtimeSprites[slot].pixels);
  ner->runtimeSprites[slot].pixels = NULL;
  ner->runtimeSprites[slot].w = 0;
  ner->runtimeSprites[slot].h = 0;
  ner->runtimeSprites[slot].stride = 0;
  ner->runtimeSprites[slot].spriteIndex = -1;
  if (gNspireDrawStats.snapLive > 0) gNspireDrawStats.snapLive--;

  // Mark the tpag slot free (upstream convention: texturePageId = -1).
  uint32_t tpagIndex = ner->originalTPagCount + (uint32_t)slot;
  if (tpagIndex < dw->tpag.count) {
    memset(&dw->tpag.items[tpagIndex], 0, sizeof(TexturePageItem));
    dw->tpag.items[tpagIndex].texturePageId = -1;
  }

  // Free per-sprite owned arrays, then memset the slot so the textureCount=0
  // signal lets DataWin_allocSpriteSlot reuse it. Name is preserved (the
  // upstream allocator strdups it once and reuses across slot recycling).
  if ((uint32_t)spriteIndex < dw->sprt.count) {
    Sprite *sprite = &dw->sprt.sprites[spriteIndex];
    free(sprite->tpagIndices);
    free(sprite->masks);
    const char *keepName = sprite->name;
    memset(sprite, 0, sizeof(Sprite));
    sprite->name = (char *)keepName;
  }
#endif
}

static void ns_gpuSetBlendMode(Renderer *r, int32_t mode) {
  (void)r;
  (void)mode;
}
static void ns_gpuSetBlendModeExt(Renderer *r, int32_t s, int32_t d) {
  (void)r;
  (void)s;
  (void)d;
}
static void ns_gpuSetBlendEnable(Renderer *r, bool e) {
  (void)r;
  (void)e;
}
static void ns_gpuSetAlphaTestEnable(Renderer *r, bool e) {
  (void)r;
  (void)e;
}
static void ns_gpuSetAlphaTestRef(Renderer *r, uint8_t v) {
  (void)r;
  (void)v;
}
static void ns_gpuSetColorWriteEnable(Renderer *r, bool a, bool b, bool c,
                                      bool d) {
  (void)r;
  (void)a;
  (void)b;
  (void)c;
  (void)d;
}
// Software renderer has no colour-write mask (always writes all channels;
// RGB565 has no alpha plane), so report all-enabled — matches the no-op setter.
static void ns_gpuGetColorWriteEnable(Renderer *r, bool *red, bool *green,
                                      bool *blue, bool *alpha) {
  (void)r;
  if (red)
    *red = true;
  if (green)
    *green = true;
  if (blue)
    *blue = true;
  if (alpha)
    *alpha = true;
}
static bool ns_gpuGetBlendEnable(Renderer *r) {
  (void)r;
  return true;
}
static void ns_gpuSetFog(Renderer *r, bool e, uint32_t c) {
  (void)r;
  (void)e;
  (void)c;
}

#define NS_MAX_SURFACES 16

// Bisect switch. 0 = restore the old no-op stub behavior (createSurface -1,
// surfaceExists false, setRenderTarget false) so Ch3 runs the proven
// pre-surface path; 1 = real software surface backend.
#ifndef NS_ENABLE_SURFACES
#define NS_ENABLE_SURFACES 0
#endif

// Retarget the renderer's framebuffer in place. id<0 / invalid -> screen
// (APPLICATION_SURFACE_ID). All ns_draw* paths use ner->fb, so swapping its
// pointer+dims redirects every subsequent draw with no other changes.
static void ns_bindTarget(NspireEngineRenderer *ner, int32_t id) {
  bool toSurface =
      (id >= 0 && id < NS_MAX_SURFACES && ner->surfaces[id].px != NULL);
  bool wasSurface = (ner->activeSurface >= 0);

  if (toSurface && !wasSurface) {
    // screen -> surface: stash the screen view transform + scissor so it
    // can be restored exactly when the game ends the surface target.
    ner->savedViewOriginX = ner->viewOriginX;
    ner->savedViewOriginY = ner->viewOriginY;
    ner->savedViewToFbScaleX = ner->viewToFbScaleX;
    ner->savedViewToFbScaleY = ner->viewToFbScaleY;
    ner->savedPortFbX = ner->portFbX;
    ner->savedPortFbY = ner->portFbY;
    ner->savedScissorX = ner->fb.scissorX;
    ner->savedScissorY = ner->fb.scissorY;
    ner->savedScissorW = ner->fb.scissorW;
    ner->savedScissorH = ner->fb.scissorH;
    ner->transformSaved = true;
  } else if (!toSurface && wasSurface && ner->transformSaved) {
    // surface -> screen: restore exactly what beginView/beginGUI had set.
    ner->viewOriginX = ner->savedViewOriginX;
    ner->viewOriginY = ner->savedViewOriginY;
    ner->viewToFbScaleX = ner->savedViewToFbScaleX;
    ner->viewToFbScaleY = ner->savedViewToFbScaleY;
    ner->portFbX = ner->savedPortFbX;
    ner->portFbY = ner->savedPortFbY;
    ner->fb.scissorX = ner->savedScissorX;
    ner->fb.scissorY = ner->savedScissorY;
    ner->fb.scissorW = ner->savedScissorW;
    ner->fb.scissorH = ner->savedScissorH;
    ner->transformSaved = false;
  }

  if (toSurface) {
    ner->fb.fb = ner->surfaces[id].px;
    ner->fb.fbW = ner->surfaces[id].w;
    ner->fb.fbH = ner->surfaces[id].h;
    ner->activeSurface = id;
    // Identity transform: surface coord == surface pixel (1:1). viewToFb=1
    // makes the halved-atlas sprite-stretch formula (viewToFb/HR_SCALE)
    // resolve to engine-size content inside the surface.
    ner->viewOriginX = 0.0f;
    ner->viewOriginY = 0.0f;
    ner->viewToFbScaleX = 1.0f;
    ner->viewToFbScaleY = 1.0f;
    ner->portFbX = 0;
    ner->portFbY = 0;
    ner->fb.scissorX = 0;
    ner->fb.scissorY = 0;
    ner->fb.scissorW = ner->fb.fbW;
    ner->fb.scissorH = ner->fb.fbH;
  } else {
    ner->fb.fb = ner->screenPx;
    ner->fb.fbW = ner->screenW;
    ner->fb.fbH = ner->screenH;
    ner->activeSurface = -1;
    // Screen transform/scissor were restored above if we came from a
    // surface; otherwise leave them exactly as beginView set them — do
    // NOT clobber the scissor here (zeroing it would blank the screen).
  }
}

static int32_t ns_createSurface(Renderer *r, int32_t w, int32_t h) {
#if !NS_ENABLE_SURFACES
  (void)r;
  (void)w;
  (void)h;
  return -1;
#else
  NspireEngineRenderer *ner = as_ner(r);
  if (w <= 0 || h <= 0)
    return -1;
  for (int32_t i = 0; i < NS_MAX_SURFACES; i++) {
    if (!ner->surfaces[i].px) {
      uint16_t *p =
          (uint16_t *)malloc((size_t)w * (size_t)h * sizeof(uint16_t));
      if (!p)
        return -1; // OOM -> engine degrades (surface_exists false)
      memset(p, 0, (size_t)w * (size_t)h * sizeof(uint16_t));
      ner->surfaces[i].px = p;
      ner->surfaces[i].w = w;
      ner->surfaces[i].h = h;
      return i;
    }
  }
  return -1;
#endif
}
static bool ns_surfaceExists(Renderer *r, int32_t s) {
#if !NS_ENABLE_SURFACES
  (void)r;
  (void)s;
  return false;
#else
  NspireEngineRenderer *ner = as_ner(r);
  if (s == APPLICATION_SURFACE_ID)
    return true;
  return s >= 0 && s < NS_MAX_SURFACES && ner->surfaces[s].px != NULL;
#endif
}
static bool ns_setRenderTarget(Renderer *r, int32_t s) {
#if !NS_ENABLE_SURFACES
  (void)r;
  (void)s;
  return false;
#else
  ns_bindTarget(as_ner(r), s);
  return true;
#endif
}
static float ns_getSurfaceWidth(Renderer *r, int32_t s) {
  NspireEngineRenderer *ner = as_ner(r);
  if (s == APPLICATION_SURFACE_ID)
    return (float)ner->screenW;
  if (s >= 0 && s < NS_MAX_SURFACES && ner->surfaces[s].px)
    return (float)ner->surfaces[s].w;
  return 0.0f;
}
static float ns_getSurfaceHeight(Renderer *r, int32_t s) {
  NspireEngineRenderer *ner = as_ner(r);
  if (s == APPLICATION_SURFACE_ID)
    return (float)ner->screenH;
  if (s >= 0 && s < NS_MAX_SURFACES && ner->surfaces[s].px)
    return (float)ner->surfaces[s].h;
  return 0.0f;
}
// Surfaces are not atlas-halved, so view->fb factor is viewToFb / HR_SCALE.
static inline float ns_v2f_x(NspireEngineRenderer *ner) {
  return ner->viewToFbScaleX / HR_SCALE_ATLAS;
}
static inline float ns_v2f_y(NspireEngineRenderer *ner) {
  return ner->viewToFbScaleY / HR_SCALE_ATLAS;
}

// Upstream consolidated draw_surface / _part / _stretched into one vtable entry
// that always carries a source sub-rect. rotation (angleDeg) falls back to
// axis-aligned this pass.
static void ns_drawSurface(Renderer *r, int32_t s, int32_t srcLeft,
                           int32_t srcTop, int32_t srcW, int32_t srcH, float x,
                           float y, float xs, float ys, float ang, uint32_t c,
                           float a) {
  NspireEngineRenderer *ner = as_ner(r);
  (void)ang;
  if (s < 0 || s >= NS_MAX_SURFACES || !ner->surfaces[s].px || a <= 0.0f)
    return;
  int32_t SW = ner->surfaces[s].w, SH = ner->surfaces[s].h;
  // Clamp the engine-supplied source sub-rect to the surface bounds.
  if (srcLeft < 0)
    srcLeft = 0;
  if (srcTop < 0)
    srcTop = 0;
  if (srcW <= 0 || srcW > SW - srcLeft)
    srcW = SW - srcLeft;
  if (srcH <= 0 || srcH > SH - srcTop)
    srcH = SH - srcTop;
  if (srcW <= 0 || srcH <= 0)
    return;
  float ax = xs < 0 ? -xs : xs, ay = ys < 0 ? -ys : ys;
  int32_t dw = (int32_t)((float)srcW * ax * ns_v2f_x(ner) + 0.5f);
  int32_t dh = (int32_t)((float)srcH * ay * ns_v2f_y(ner) + 0.5f);
  if (dw <= 0 || dh <= 0)
    return;
  uint8_t a8 = a >= 0.996f ? 255 : (uint8_t)(a * 255.0f);
  NspireRenderer_blitSurface(&ner->fb, ner->surfaces[s].px, SW, srcLeft, srcTop,
                             srcW, srcH, engine_to_fb_x(ner, x),
                             engine_to_fb_y(ner, y), dw, dh, a8, c);
}
static void ns_surfaceResize(Renderer *r, int32_t s, int32_t w, int32_t h) {
  NspireEngineRenderer *ner = as_ner(r);
  if (s < 0 || s >= NS_MAX_SURFACES || !ner->surfaces[s].px || w <= 0 || h <= 0)
    return;
  uint16_t *p = (uint16_t *)malloc((size_t)w * (size_t)h * sizeof(uint16_t));
  if (!p)
    return;
  memset(p, 0, (size_t)w * (size_t)h * sizeof(uint16_t));
  free(ner->surfaces[s].px);
  ner->surfaces[s].px = p;
  ner->surfaces[s].w = w;
  ner->surfaces[s].h = h;
  if (ner->activeSurface == s)
    ns_bindTarget(ner, s); // refresh bound dims
}
static void ns_surfaceFree(Renderer *r, int32_t s) {
  NspireEngineRenderer *ner = as_ner(r);
  if (s < 0 || s >= NS_MAX_SURFACES || !ner->surfaces[s].px)
    return;
  free(ner->surfaces[s].px);
  ner->surfaces[s].px = NULL;
  ner->surfaces[s].w = ner->surfaces[s].h = 0;
  if (ner->activeSurface == s)
    ns_bindTarget(ner, APPLICATION_SURFACE_ID);
}
static void ns_surfaceCopy(Renderer *r, int32_t d, int32_t dx, int32_t dy,
                           int32_t s, int32_t sx, int32_t sy, int32_t sw,
                           int32_t sh, bool p) {
  NspireEngineRenderer *ner = as_ner(r);
  (void)p;
  if (d < 0 || d >= NS_MAX_SURFACES || !ner->surfaces[d].px)
    return;
  if (s < 0 || s >= NS_MAX_SURFACES || !ner->surfaces[s].px)
    return;
  int32_t SW = ner->surfaces[s].w, SH = ner->surfaces[s].h;
  if (sx < 0)
    sx = 0;
  if (sy < 0)
    sy = 0;
  if (sw <= 0 || sw > SW - sx)
    sw = SW - sx;
  if (sh <= 0 || sh > SH - sy)
    sh = SH - sy;
  if (sw <= 0 || sh <= 0)
    return;
  NspireRenderer tmp;
  NspireRenderer_init(&tmp, ner->surfaces[d].px, ner->surfaces[d].w,
                      ner->surfaces[d].h);
  NspireRenderer_blitSurface(&tmp, ner->surfaces[s].px, SW, sx, sy, sw, sh, dx,
                             dy, sw, sh, 255, 0xFFFFFFu);
}
static bool ns_surfaceGetPixels(Renderer *r, int32_t s, uint8_t *out) {
  NspireEngineRenderer *ner = as_ner(r);
  if (!out || s < 0 || s >= NS_MAX_SURFACES || !ner->surfaces[s].px)
    return false;
  int32_t n = ner->surfaces[s].w * ner->surfaces[s].h;
  const uint16_t *px = ner->surfaces[s].px;
  for (int32_t i = 0; i < n; i++) {
    uint16_t v = px[i];
    uint32_t r5 = (v >> 11) & 0x1Fu, g6 = (v >> 5) & 0x3Fu, b5 = v & 0x1Fu;
    out[i * 4 + 0] = (uint8_t)((r5 << 3) | (r5 >> 2));
    out[i * 4 + 1] = (uint8_t)((g6 << 2) | (g6 >> 4));
    out[i * 4 + 2] = (uint8_t)((b5 << 3) | (b5 >> 2));
    out[i * 4 + 3] = 0xFF;
  }
  return true;
}

// ===[ Vtable ]===

static RendererVtable nsVtable = {
    .init = ns_init,
    .destroy = ns_destroy,
    .beginFrame = ns_beginFrame,
    .endFrame = ns_endFrame,
    .beginView = ns_beginView,
    .endView = ns_endView,
    .beginGUI = ns_beginGUI,
    .endGUI = ns_endGUI,
    .drawSprite = ns_drawSprite,
    .drawSpritePart = ns_drawSpritePart,
    .drawSpritePos = ns_drawSpritePos,
    .drawRectangle = ns_drawRectangle,
    .drawRectangleColor = ns_drawRectangleColor,
    .drawLine = ns_drawLine,
    .drawTriangle = ns_drawTriangle,
    .drawLineColor = ns_drawLineColor,
    .drawText = ns_drawText,
    .drawTextColor = ns_drawTextColor,
    .flush = ns_flush,
    .clearScreen = ns_clearScreen,
    .createSpriteFromSurface = ns_createSpriteFromSurface,
    .deleteSprite = ns_deleteSprite,
    .gpuSetBlendMode = ns_gpuSetBlendMode,
    .gpuSetBlendModeExt = ns_gpuSetBlendModeExt,
    .gpuSetBlendEnable = ns_gpuSetBlendEnable,
    .gpuSetAlphaTestEnable = ns_gpuSetAlphaTestEnable,
    .gpuSetAlphaTestRef = ns_gpuSetAlphaTestRef,
    .gpuSetColorWriteEnable = ns_gpuSetColorWriteEnable,
    .gpuGetColorWriteEnable = ns_gpuGetColorWriteEnable,
    .gpuGetBlendEnable = ns_gpuGetBlendEnable,
    .gpuSetFog = ns_gpuSetFog,
    .drawTile = NULL,  // fall back to drawSpritePart
    .drawTiled = NULL, // fall back to per-tile drawSprite loop
    .createSurface = ns_createSurface,
    .surfaceExists = ns_surfaceExists,
    .setRenderTarget = ns_setRenderTarget,
    .getSurfaceWidth = ns_getSurfaceWidth,
    .getSurfaceHeight = ns_getSurfaceHeight,
    .drawSurface = ns_drawSurface,
    .surfaceResize = ns_surfaceResize,
    .surfaceFree = ns_surfaceFree,
    .surfaceCopy = ns_surfaceCopy,
    .surfaceGetPixels = ns_surfaceGetPixels,
    .drawTiledPart = NULL,
};

// ===[ Public API ]===

Renderer *NspireEngineRenderer_create(NspireAssets *assets,
                                      uint16_t *framebuffer, int32_t fbW,
                                      int32_t fbH) {
  NspireEngineRenderer *ner =
      (NspireEngineRenderer *)calloc(1, sizeof(NspireEngineRenderer));
  if (!ner)
    return NULL;
  ner->base.vtable = &nsVtable;
  ner->base.drawColor = 0xFFFFFF;
  ner->base.drawAlpha = 1.0f;
  ner->base.drawFont = -1;
  ner->base.drawHalign = 0;
  ner->base.drawValign = 0;
  ner->base.circlePrecision = 24;
  ner->assets = assets;
  // Sensible defaults so any draw call before the first beginView still maps
  // coords.
  ner->viewToFbScaleX = HR_SCALE_ATLAS;
  ner->viewToFbScaleY = HR_SCALE_ATLAS;
  ner->viewOriginX = 0.0f;
  ner->viewOriginY = 0.0f;
  ner->portFbX = 0;
  ner->portFbY = 0;
  NspireRenderer_init(&ner->fb, framebuffer, fbW, fbH);
  ner->screenPx = framebuffer;
  ner->screenW = fbW;
  ner->screenH = fbH;
  ner->activeSurface = -1; // surfaces[] zeroed by calloc
  return (Renderer *)ner;
}

void NspireEngineRenderer_destroy(Renderer *r) {
  NspireEngineRenderer *ner = as_ner(r);
  for (int32_t i = 0; i < NS_MAX_SURFACES; i++) {
    if (ner->surfaces[i].px)
      free(ner->surfaces[i].px);
  }
  free(r);
}
