#include "nspire_asset.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// Read whole file into a malloc'd buffer. Tries `path` first, then `path.tns`
// to handle TI's transfer behavior (it appends .tns to non-.tns filenames).
// Returns NULL and sets *outSize to 0 on failure.
static uint8_t* read_whole_file(const char* path, size_t* outSize) {
    *outSize = 0;
    FILE* f = fopen(path, "rb");
    if (!f) {
        char alt[300];
        snprintf(alt, sizeof(alt), "%s.tns", path);
        f = fopen(alt, "rb");
        if (!f) return NULL;
    }
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return NULL; }
    long sz = ftell(f);
    if (sz < 0) { fclose(f); return NULL; }
    rewind(f);
    uint8_t* buf = (uint8_t*) malloc((size_t) sz);
    if (!buf) { fclose(f); return NULL; }
    if (fread(buf, 1, (size_t) sz, f) != (size_t) sz) { free(buf); fclose(f); return NULL; }
    fclose(f);
    *outSize = (size_t) sz;
    return buf;
}

static inline uint16_t read_u16le(const uint8_t* p) {
    return (uint16_t) (p[0] | (p[1] << 8));
}
static inline int16_t read_i16le(const uint8_t* p) {
    return (int16_t) read_u16le(p);
}
static inline uint32_t read_u32le(const uint8_t* p) {
    return (uint32_t) p[0] | ((uint32_t) p[1] << 8) | ((uint32_t) p[2] << 16) | ((uint32_t) p[3] << 24);
}

// Convert one PS2 CLUT entry to RGB565. File stores bytes [R, G, B, ps2Alpha-0..128].
// ps2Alpha == 0 means transparent; we return 0x0000 (would not be written anyway
// since the alpha-test guards on palette index 0, but keep it consistent).
static uint16_t ps2_argb_to_rgb565(const uint8_t* p4) {
    uint8_t r = p4[0];
    uint8_t g = p4[1];
    uint8_t b = p4[2];
    uint8_t ps2A = p4[3];
    if (ps2A == 0) return 0;
    return (uint16_t) (((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
}

// Undo the preprocessor's 8bpp CSM1 swizzle: for indices where (i & 0x18) == 8,
// swap palette[i] with palette[i+8]. The swap is its own inverse.
static void unswizzle_clut8(uint16_t* p256) {
    for (int i = 0; i < 256; i++) {
        if ((i & 0x18) == 8) {
            uint16_t tmp = p256[i];
            p256[i] = p256[i + 8];
            p256[i + 8] = tmp;
        }
    }
}

// Parse CLUT*.BIN into an array of NspireClut. Each CLUT entry is 4 bytes;
// each CLUT row has `slots` entries. `unswizzle` is true only for 8bpp (CSM1).
static bool load_clut_file(const char* path, int slots, bool unswizzle,
                           NspireClut** outArr, int32_t* outCount) {
    *outArr = NULL;
    *outCount = 0;
    size_t sz;
    uint8_t* buf = read_whole_file(path, &sz);
    if (!buf) return false;
    size_t rowSize = (size_t) slots * 4;
    if (sz == 0) { free(buf); return true; }   // no CLUTs is valid
    if (sz % rowSize != 0) { free(buf); return false; }
    int32_t count = (int32_t) (sz / rowSize);
    NspireClut* arr = (NspireClut*) calloc((size_t) count, sizeof(NspireClut));
    if (!arr) { free(buf); return false; }
    for (int32_t c = 0; c < count; c++) {
        uint16_t* dst = arr[c].entries;
        const uint8_t* src = buf + (size_t) c * rowSize;
        for (int i = 0; i < slots; i++) {
            dst[i] = ps2_argb_to_rgb565(src + i * 4);
        }
        // Clear unused tail for 4bpp so out-of-range indices don't read garbage.
        for (int i = slots; i < NSPIRE_PALETTE_SIZE_8; i++) dst[i] = 0;
        if (unswizzle) unswizzle_clut8(dst);
    }
    free(buf);
    *outArr = arr;
    *outCount = count;
    return true;
}

// Decompress an RLE stream (alternating run-length + value bytes) into outSize bytes.
// Returns false on under/over-run.
static bool rle_decompress(const uint8_t* src, size_t srcSize,
                           uint8_t* dst, size_t dstSize) {
    size_t s = 0, d = 0;
    while (s + 1 < srcSize && d < dstSize) {
        uint8_t run = src[s++];
        uint8_t val = src[s++];
        if (d + run > dstSize) return false;
        memset(dst + d, val, run);
        d += run;
    }
    return d == dstSize && s == srcSize;
}

// Parse TEXTURES.BIN: a stream of (128-byte header, pixel data) atlas records.
// Streams the file atlas-by-atlas so we only ever hold one compressed atlas
// payload in memory at a time, in addition to the decompressed canvases we keep.
//
// Header layout (from writeTexturePagesBytes):
//   u8 version (=0)
//   u16 width
//   u16 height
//   u8 bpp (4 or 8)
//   u32 pixelDataSize
//   u8 compressionType (0=uncompressed, 1=RLE)
//   117 bytes zero padding
// Reads each atlas's 128-byte header to capture dimensions/bpp/compression/payload offset,
// but does NOT decompress pixel data. Pixel buffers are allocated on demand by
// NspireAssets_ensureAtlas and freed when stale via NspireAssets_releaseUntouchedAtlases.
static bool load_textures_file(const char* path,
                               const uint32_t* atlasOffsets, int32_t atlasCount,
                               NspireAtlas** outArr, FILE** outFile) {
    *outArr = NULL;
    *outFile = NULL;
    FILE* f = fopen(path, "rb");
    if (!f) {
        char alt[300]; snprintf(alt, sizeof(alt), "%s.tns", path);
        f = fopen(alt, "rb");
        if (!f) return false;
    }

    NspireAtlas* arr = (NspireAtlas*) calloc((size_t) atlasCount, sizeof(NspireAtlas));
    if (!arr) { fclose(f); return false; }

    uint8_t header[128];
    for (int32_t i = 0; i < atlasCount; i++) {
        uint32_t off = atlasOffsets[i];
        if (fseek(f, (long) off, SEEK_SET) != 0) goto fail;
        if (fread(header, 1, 128, f) != 128)     goto fail;

        uint16_t w = read_u16le(header + 1);
        uint16_t hgt = read_u16le(header + 3);
        uint8_t bpp = header[5];
        uint32_t pixelDataSize = read_u32le(header + 6);
        uint8_t compression = header[10];

        int32_t stride;
        if (bpp == 8) stride = (int32_t) w;
        else if (bpp == 4) stride = (int32_t) ((w + 1) / 2);
        else goto fail;

        arr[i].pixels = NULL;
        arr[i].width = w;
        arr[i].height = hgt;
        arr[i].stride = stride;
        arr[i].bpp = bpp;
        arr[i].fileOffsetHeader = off;
        arr[i].pixelDataSize = pixelDataSize;
        arr[i].compression = compression;
        arr[i].touchedThisFrame = 0;
    }

    *outArr = arr;
    *outFile = f;  // ownership transferred — caller must fclose() later
    return true;

fail:
    free(arr);
    fclose(f);
    return false;
}

int32_t NspireAssets_preloadAllAtlases(NspireAssets* a, uint32_t* outResidentBytes) {
    int32_t loaded = 0;
    for (int32_t i = 0; i < a->atlasCount; i++) {
        if (NspireAssets_ensureAtlas(a, i)) {
            // Pin it: clear the touch flag so a stray releaseUntouchedAtlases pass
            // (if one ever runs) doesn't immediately evict the pre-loaded set.
            a->atlases[i].touchedThisFrame = 1;
            loaded++;
        }
    }
    if (outResidentBytes) *outResidentBytes = (uint32_t) a->residentBytes;
    return loaded;
}

bool NspireAssets_ensureAtlas(NspireAssets* a, int32_t atlasIndex) {
    if (atlasIndex < 0 || atlasIndex >= a->atlasCount) return false;
    NspireAtlas* atlas = &a->atlases[atlasIndex];
    atlas->touchedThisFrame = 1;
    if (atlas->pixels) return true;
    if (!a->texturesFile) return false;

    size_t uncompressedSize = (size_t) atlas->stride * (size_t) atlas->height;
    uint8_t* pixels = (uint8_t*) malloc(uncompressedSize);
    if (!pixels) return false;

    // Skip the 128-byte header and read the compressed payload.
    if (fseek(a->texturesFile, (long) atlas->fileOffsetHeader + 128, SEEK_SET) != 0) { free(pixels); return false; }

    if (atlas->compression == 0) {
        if (atlas->pixelDataSize != uncompressedSize) { free(pixels); return false; }
        if (fread(pixels, 1, uncompressedSize, a->texturesFile) != uncompressedSize) { free(pixels); return false; }
    } else if (atlas->compression == 1) {
        // RLE: read compressed bytes into a temp buffer then decode.
        uint8_t* compressed = (uint8_t*) malloc(atlas->pixelDataSize);
        if (!compressed) { free(pixels); return false; }
        if (fread(compressed, 1, atlas->pixelDataSize, a->texturesFile) != atlas->pixelDataSize) { free(compressed); free(pixels); return false; }
        bool ok = rle_decompress(compressed, atlas->pixelDataSize, pixels, uncompressedSize);
        free(compressed);
        if (!ok) { free(pixels); return false; }
    } else {
        free(pixels);
        return false;
    }

    atlas->pixels = pixels;
    a->residentBytes += (int32_t) uncompressedSize;
    return true;
}

void NspireAssets_releaseUntouchedAtlases(NspireAssets* a) {
    for (int32_t i = 0; i < a->atlasCount; i++) {
        NspireAtlas* atlas = &a->atlases[i];
        if (!atlas->pixels) continue;
        if (atlas->touchedThisFrame) {
            atlas->touchedThisFrame = 0;
            continue;
        }
        size_t bytes = (size_t) atlas->stride * (size_t) atlas->height;
        free(atlas->pixels);
        atlas->pixels = NULL;
        a->residentBytes -= (int32_t) bytes;
    }
}

// Parse ATLAS.BIN: header + atlas offset table + per-TPAG entries (+ per-tile entries, ignored here).
// Header (from writeAtlasMetadataBytes):
//   u8 version
//   u16 tpagEntryCount
//   u16 tileEntryCount
//   u16 atlasCount
//   u32 * atlasCount: byte offset of each atlas record in TEXTURES.BIN
// Then tpagEntryCount * 20-byte TPAG entries, then tileEntryCount * 30-byte tile entries.
static bool load_atlas_file(const char* path,
                            uint32_t** outAtlasOffsets, int32_t* outAtlasCount,
                            NspireTpagEntry** outTpags, int32_t* outTpagCount) {
    *outAtlasOffsets = NULL;
    *outAtlasCount = 0;
    *outTpags = NULL;
    *outTpagCount = 0;

    size_t sz;
    uint8_t* buf = read_whole_file(path, &sz);
    if (!buf) return false;
    if (sz < 7) { free(buf); return false; }

    // h[0]=version, h[1..2]=tpagCount, h[3..4]=tileCount, h[5..6]=atlasCount
    uint16_t tpagCount = read_u16le(buf + 1);
    uint16_t tileCount = read_u16le(buf + 3);
    uint16_t atlasCount = read_u16le(buf + 5);
    (void) tileCount;

    size_t pos = 7;
    if (pos + (size_t) atlasCount * 4 > sz) { free(buf); return false; }

    uint32_t* offsets = (uint32_t*) malloc((size_t) atlasCount * sizeof(uint32_t));
    if (!offsets) { free(buf); return false; }
    for (int i = 0; i < atlasCount; i++) {
        offsets[i] = read_u32le(buf + pos);
        pos += 4;
    }

    if (pos + (size_t) tpagCount * 20 > sz) { free(offsets); free(buf); return false; }
    NspireTpagEntry* tpags = (NspireTpagEntry*) calloc((size_t) tpagCount, sizeof(NspireTpagEntry));
    if (!tpags) { free(offsets); free(buf); return false; }

    for (int i = 0; i < tpagCount; i++) {
        const uint8_t* e = buf + pos;
        tpags[i].atlasId   = read_u16le(e + 0);
        tpags[i].atlasX    = read_i16le(e + 2);
        tpags[i].atlasY    = read_i16le(e + 4);
        tpags[i].width     = read_u16le(e + 6);
        tpags[i].height    = read_u16le(e + 8);
        tpags[i].cropX     = read_i16le(e + 10);
        tpags[i].cropY     = read_i16le(e + 12);
        tpags[i].cropW     = read_u16le(e + 14);
        tpags[i].cropH     = read_u16le(e + 16);
        tpags[i].clutIndex = read_u16le(e + 18);
        pos += 20;
    }

    free(buf);
    *outAtlasOffsets = offsets;
    *outAtlasCount = atlasCount;
    *outTpags = tpags;
    *outTpagCount = tpagCount;
    return true;
}

static void join_path(char* dst, size_t cap, const char* dir, const char* name) {
    size_t dlen = strlen(dir);
    bool needSlash = dlen > 0 && dir[dlen - 1] != '/';
    snprintf(dst, cap, "%s%s%s", dir, needSlash ? "/" : "", name);
}

bool NspireAssets_load(NspireAssets* a, const char* dir) {
    memset(a, 0, sizeof(*a));
    char path[256];
    uint32_t* atlasOffsets = NULL;
    int32_t atlasCount = 0;

    join_path(path, sizeof(path), dir, "ATLAS.BIN");
    if (!load_atlas_file(path, &atlasOffsets, &atlasCount, &a->tpags, &a->tpagCount)) {
        goto fail;
    }

    join_path(path, sizeof(path), dir, "TEXTURES.BIN");
    if (!load_textures_file(path, atlasOffsets, atlasCount, &a->atlases, &a->texturesFile)) {
        goto fail;
    }
    a->atlasCount = atlasCount;
    free(atlasOffsets);
    atlasOffsets = NULL;

    join_path(path, sizeof(path), dir, "CLUT4.BIN");
    if (!load_clut_file(path, NSPIRE_PALETTE_SIZE_4, false, &a->clut4, &a->clut4Count)) {
        goto fail;
    }

    join_path(path, sizeof(path), dir, "CLUT8.BIN");
    if (!load_clut_file(path, NSPIRE_PALETTE_SIZE_8, true, &a->clut8, &a->clut8Count)) {
        goto fail;
    }

    return true;

fail:
    free(atlasOffsets);
    NspireAssets_free(a);
    return false;
}

void NspireAssets_free(NspireAssets* a) {
    free(a->clut4);
    free(a->clut8);
    if (a->atlases) {
        for (int32_t i = 0; i < a->atlasCount; i++) free(a->atlases[i].pixels);
        free(a->atlases);
    }
    free(a->tpags);
    if (a->texturesFile) fclose(a->texturesFile);
    memset(a, 0, sizeof(*a));
}
