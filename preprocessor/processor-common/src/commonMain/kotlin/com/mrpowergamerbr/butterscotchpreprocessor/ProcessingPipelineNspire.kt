package com.mrpowergamerbr.butterscotchpreprocessor

// Nspire (TI-Nspire CX II CAS) target.
// Mirrors the PS2 asset pipeline but skips all audio stages: SOND/AUDO chunks are not
// parsed, audiogroup/external/mus files are not loaded, and SOUNDBNK.BIN/SOUNDS.BIN
// are not emitted. Audio is stubbed at runtime on the Nspire (no usable hardware).
//
// Current scope (scaffold): emits CLUT4.BIN/CLUT8.BIN/TEXTURES.BIN/ATLAS.BIN in the
// same on-disk format as the PS2 target. Half-resolution sprite preprocessing, the
// RGB565 CLUT format, and the SPRMETA sidecar are planned follow-ups.

class ProcessingResultNspire(
    val gameName: String,
    val clut4Bin: ByteArray,
    val clut8Bin: ByteArray,
    val texturesBin: ByteArray,
    val atlasBin: ByteArray,
    val slimDataWin: ByteArray,
    val atlases: List<TextureAtlas> = emptyList()
)

// FORM-level chunks the Nspire runner does NOT need.
//
// CRITICAL: Only chunks that come AFTER all chunks containing absolute-file-offset
// pointer tables can be dropped. SPRT/BGND/FONT/TPAG/etc. store absolute offsets
// into the original file in their pointer tables, so removing any earlier chunk
// shifts those pointed-to records and corrupts the file. TXTR and AUDO are the
// last two chunks in a typical data.win, so dropping them is safe.
//
// Do NOT add SOND/AGRP/OPTN/LANG/EXTN here — they live BEFORE SPRT and dropping
// them silently breaks every sprite/background/font/tpag lookup at runtime.
private val NSPIRE_DROP_CHUNKS = setOf("TXTR", "AUDO")

private fun stripDataWinForNspire(dataWinBytes: ByteArray, log: (String) -> Unit): ByteArray {
    // FORM header: "FORM" + u32 LE body size, then a sequence of (name[4] + u32 LE size + payload).
    if (dataWinBytes.size < 8) return dataWinBytes
    if (!(dataWinBytes[0] == 'F'.code.toByte() && dataWinBytes[1] == 'O'.code.toByte()
       && dataWinBytes[2] == 'R'.code.toByte() && dataWinBytes[3] == 'M'.code.toByte())) {
        log("WARN: data.win does not start with FORM; skipping strip")
        return dataWinBytes
    }
    val formBodySize = readUIntLE(dataWinBytes, 4).toLong()
    val end = (8L + formBodySize).coerceAtMost(dataWinBytes.size.toLong()).toInt()

    val keptChunks = mutableListOf<Pair<String, IntRange>>()   // chunk name + range in input (inclusive header)
    var pos = 8
    var droppedBytes = 0L
    while (pos + 8 <= end) {
        val name = ByteArray(4) { dataWinBytes[pos + it] }.decodeToString()
        val size = readUIntLE(dataWinBytes, pos + 4).toLong()
        val chunkTotal = 8 + size.toInt()
        val chunkEnd = pos + chunkTotal
        if (chunkEnd > end) {
            log("WARN: truncated chunk $name; aborting strip")
            return dataWinBytes
        }
        if (name in NSPIRE_DROP_CHUNKS) {
            droppedBytes += chunkTotal
        } else {
            keptChunks.add(name to (pos until chunkEnd))
        }
        pos = chunkEnd
    }

    var keptBodySize = 0L
    for ((_, range) in keptChunks) keptBodySize += range.last - range.first + 1
    val outSize = 8L + keptBodySize
    val out = ByteArray(outSize.toInt())
    out[0] = 'F'.code.toByte(); out[1] = 'O'.code.toByte(); out[2] = 'R'.code.toByte(); out[3] = 'M'.code.toByte()
    writeUIntLE(out, 4, keptBodySize.toInt())
    var cursor = 8
    for ((_, range) in keptChunks) {
        val len = range.last - range.first + 1
        dataWinBytes.copyInto(out, cursor, range.first, range.last + 1)
        cursor += len
    }

    val droppedNames = NSPIRE_DROP_CHUNKS.joinToString()
    log("Slim data.win: ${dataWinBytes.size} -> ${out.size} bytes (dropped $droppedBytes bytes of $droppedNames)")
    return out
}

private fun readUIntLE(b: ByteArray, off: Int): UInt =
    ((b[off].toInt() and 0xFF)
            or ((b[off + 1].toInt() and 0xFF) shl 8)
            or ((b[off + 2].toInt() and 0xFF) shl 16)
            or ((b[off + 3].toInt() and 0xFF) shl 24)).toUInt()

private fun writeUIntLE(b: ByteArray, off: Int, v: Int) {
    b[off]     = (v and 0xFF).toByte()
    b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    b[off + 2] = ((v ushr 16) and 0xFF).toByte()
    b[off + 3] = ((v ushr 24) and 0xFF).toByte()
}

// Half-res pass: images whose name matches any of these prefixes are NOT scaled down.
// Fonts must stay at native resolution to remain readable on the 320x240 LCD.
private val HALF_RES_EXCLUDED_PREFIXES = listOf("font/")

private fun isExcludedFromHalfRes(name: String): Boolean =
    HALF_RES_EXCLUDED_PREFIXES.any { name.startsWith(it) }

// 2x2 box-average downscale. Nearest-neighbour decimation (take every other
// pixel) is what made half-res sprites look aliased/"crap" — this averages each
// 2x2 block instead. Alpha-WEIGHTED so fully/partly transparent pixels don't
// bleed dark colour into the edge (GameMaker sprites have hard alpha cutouts;
// a naive RGB average would halo them). Output dimensions are unchanged, so the
// HR_SCALE_ATLAS=0.5 atlas contract and the fast 1:1 blit path are untouched —
// this is a pure cook-time quality win with zero runtime/RAM cost.
private fun halveImage(img: PixelImage): PixelImage {
    val newW = maxOf((img.width + 1) / 2, 1)
    val newH = maxOf((img.height + 1) / 2, 1)
    if (newW == img.width && newH == img.height) return img
    val src = img.pixels
    val sw = img.width
    val sh = img.height
    val out = IntArray(newW * newH)
    for (y in 0 until newH) {
        val y0 = y * 2
        val y1 = (y0 + 1).coerceAtMost(sh - 1)
        for (x in 0 until newW) {
            val x0 = x * 2
            val x1 = (x0 + 1).coerceAtMost(sw - 1)
            val p00 = src[y0 * sw + x0]
            val p01 = src[y0 * sw + x1]
            val p10 = src[y1 * sw + x0]
            val p11 = src[y1 * sw + x1]
            val a00 = p00 ushr 24; val a01 = p01 ushr 24
            val a10 = p10 ushr 24; val a11 = p11 ushr 24
            val aSum = a00 + a01 + a10 + a11
            val aOut = (aSum + 2) / 4
            val rgb = if (aSum == 0) 0 else {
                val r = ((p00 ushr 16 and 0xFF) * a00 + (p01 ushr 16 and 0xFF) * a01 +
                         (p10 ushr 16 and 0xFF) * a10 + (p11 ushr 16 and 0xFF) * a11 + aSum / 2) / aSum
                val g = ((p00 ushr 8 and 0xFF) * a00 + (p01 ushr 8 and 0xFF) * a01 +
                         (p10 ushr 8 and 0xFF) * a10 + (p11 ushr 8 and 0xFF) * a11 + aSum / 2) / aSum
                val b = ((p00 and 0xFF) * a00 + (p01 and 0xFF) * a01 +
                         (p10 and 0xFF) * a10 + (p11 and 0xFF) * a11 + aSum / 2) / aSum
                (r shl 16) or (g shl 8) or b
            }
            out[y * newW + x] = (aOut shl 24) or rgb
        }
    }
    return PixelImage(newW, newH, out)
}

private fun halveCropInfo(c: CropInfo): CropInfo =
    CropInfo(c.offsetX / 2, c.offsetY / 2, maxOf((c.croppedWidth + 1) / 2, 0), maxOf((c.croppedHeight + 1) / 2, 0))

suspend fun processDataWinNspire(
    dataWinBytes: ByteArray,
    force4bppPatterns: List<String> = emptyList(),
    atlasSize: Int = TextureAtlasPacker.DEFAULT_ATLAS_SIZE,
    halfRes: Boolean = true,
    progressCallback: ((String) -> Unit)? = null
): ProcessingResultNspire {
    val log = progressCallback ?: {}

    log("Parsing data.win (nspire target, audio skipped)...")
    val dw = DataWin.parse(dataWinBytes, DataWinParserOptions(
        parseGen8 = true,
        parseOptn = false,
        parseLang = false,
        parseExtn = false,
        parseSond = false,
        parseAgrp = false,
        parseSprt = true,
        parseBgnd = true,
        parsePath = false,
        parseScpt = false,
        parseGlob = false,
        parseShdr = false,
        parseFont = true,
        parseTmln = false,
        parseObjt = false,
        parseRoom = true,
        parseTpag = true,
        parseCode = false,
        parseVari = false,
        parseFunc = false,
        parseStrg = true,
        parseTxtr = true,
        parseAudo = false,
        skipLoadingPreciseMasksForNonPreciseSprites = true
    ))

    log("Parsed: ${dw.sprt.sprites.size} sprites, ${dw.bgnd.backgrounds.size} backgrounds, ${dw.font.fonts.size} fonts, ${dw.txtr.textures.size} textures, ${dw.tpag.items.size} TPAG items")

    log("Loading texture pages...")
    val texturePages = mutableListOf<PixelImage?>()
    val gm2022_5 = dw.isVersionAtLeast(2022, 5, 0, 0)
    for (tex in dw.txtr.textures) {
        if (tex.blobData != null) {
            texturePages.add(decodeImageBytes(tex.blobData, gm2022_5))
        } else {
            texturePages.add(null)
        }
    }
    log("Loaded ${texturePages.count { it != null }} texture pages")

    val allImages = mutableListOf<Pair<String, PixelImage>>()
    val atlasGroupKeys = HashMap<String, String>()
    val tpagIndexMap = HashMap<String, Int>()

    for ((sprIdx, sprite) in dw.sprt.sprites.withIndex()) {
        val name = sprite.name ?: "sprite_$sprIdx"
        val groupKey = getAtlasGroupKey(name)
        for ((frameIdx, texOffset) in sprite.textureOffsets.withIndex()) {
            val tpagIdx = dw.resolveTPAG(texOffset)
            if (0 > tpagIdx) continue
            val img = extractFromTPAG(dw.tpag.items[tpagIdx], texturePages)
            val imgName = if (sprite.textureOffsets.size > 1) "spr/${name}_$frameIdx" else "spr/$name"
            allImages.add(imgName to img)
            atlasGroupKeys[imgName] = groupKey
            tpagIndexMap[imgName] = tpagIdx
        }
    }

    for ((bgIdx, bg) in dw.bgnd.backgrounds.withIndex()) {
        val name = bg.name ?: "bg_$bgIdx"
        val tpagIdx = dw.resolveTPAG(bg.textureOffset)
        if (0 > tpagIdx) continue
        val imgName = "bg/$name"
        allImages.add(imgName to extractFromTPAG(dw.tpag.items[tpagIdx], texturePages))
        atlasGroupKeys[imgName] = imgName
        tpagIndexMap[imgName] = tpagIdx
    }

    for ((fontIdx, font) in dw.font.fonts.withIndex()) {
        val name = font.name ?: "font_$fontIdx"
        val tpagIdx = dw.resolveTPAG(font.textureOffset)
        if (0 > tpagIdx) continue
        val imgName = "font/$name"
        allImages.add(imgName to extractFromTPAG(dw.tpag.items[tpagIdx], texturePages))
        atlasGroupKeys[imgName] = imgName
        tpagIndexMap[imgName] = tpagIdx
    }

    val uniqueTiles = LinkedHashMap<TileKey, RoomTile>()
    fun collectTile(tile: RoomTile) {
        val defCount = if (tile.useSpriteDefinition) dw.sprt.sprites.size else dw.bgnd.backgrounds.size
        if (0 > tile.backgroundDefinition || tile.backgroundDefinition >= defCount) return
        val key = TileKey(tile.useSpriteDefinition, tile.backgroundDefinition, tile.sourceX, tile.sourceY, tile.width, tile.height)
        if (key !in uniqueTiles) {
            uniqueTiles[key] = tile
        }
    }
    for (room in dw.room.rooms) {
        for (tile in room.tiles) collectTile(tile)
        for (layer in room.layers) {
            val assets = layer.assetsData ?: continue
            for (tile in assets.legacyTiles) collectTile(tile)
        }
    }
    data class TileSourceKey(val useSpriteDefinition: Boolean, val defIndex: Int)
    val tileSourceImages = HashMap<TileSourceKey, PixelImage>()
    for ((key, _) in uniqueTiles) {
        val srcKey = TileSourceKey(key.useSpriteDefinition, key.bgDef)
        if (tileSourceImages.containsKey(srcKey)) continue
        val tpagIdx = if (key.useSpriteDefinition) {
            val sprite = dw.sprt.sprites[key.bgDef]
            if (sprite.textureOffsets.isEmpty()) continue
            dw.resolveTPAG(sprite.textureOffsets[0])
        } else {
            val bg = dw.bgnd.backgrounds[key.bgDef]
            dw.resolveTPAG(bg.textureOffset)
        }
        if (0 > tpagIdx) continue
        tileSourceImages[srcKey] = extractFromTPAG(dw.tpag.items[tpagIdx], texturePages)
    }
    var clampedTileCount = 0
    for ((key, _) in uniqueTiles) {
        val srcKey = TileSourceKey(key.useSpriteDefinition, key.bgDef)
        val srcImg = tileSourceImages[srcKey] ?: continue
        if (0 > key.srcX || 0 > key.srcY) continue
        if (key.srcX >= srcImg.width || key.srcY >= srcImg.height) continue
        if (key.w == 0 || key.h == 0) continue
        val effW = minOf(key.w, srcImg.width - key.srcX)
        val effH = minOf(key.h, srcImg.height - key.srcY)
        if (effW != key.w || effH != key.h) clampedTileCount++
        val tileImg = extractSubImage(srcImg, key.srcX, key.srcY, effW, effH)
        val defName = if (key.useSpriteDefinition) {
            dw.sprt.sprites[key.bgDef].name ?: "spr${key.bgDef}"
        } else {
            dw.bgnd.backgrounds[key.bgDef].name ?: "bg${key.bgDef}"
        }
        val imgName = "tile/${defName}_${key.srcX}_${key.srcY}_${key.w}x${key.h}"
        allImages.add(imgName to tileImg)
        atlasGroupKeys[imgName] = "tile/$defName"
    }
    if (clampedTileCount > 0) {
        log("Clamped $clampedTileCount tiles whose source rect exceeded the background")
    }

    val cropInfoMap = HashMap<String, CropInfo>()
    var croppedCount = 0
    for (i in allImages.indices) {
        val (name, img) = allImages[i]
        if (name.startsWith("spr/")) {
            val crop = cropTransparentBorders(img)
            cropInfoMap[name] = CropInfo(crop.offsetX, crop.offsetY, crop.image.width, crop.image.height)
            if (crop.image.width != img.width || crop.image.height != img.height) {
                croppedCount++
            }
            allImages[i] = name to crop.image
        } else {
            cropInfoMap[name] = CropInfo(0, 0, img.width, img.height)
        }
    }
    if (croppedCount > 0) {
        log("Cropped transparent borders from $croppedCount sprite images")
    }

    if (halfRes) {
        var halvedCount = 0
        for (i in allImages.indices) {
            val (name, img) = allImages[i]
            if (isExcludedFromHalfRes(name)) continue
            val halved = halveImage(img)
            allImages[i] = name to halved
            cropInfoMap[name]?.let { cropInfoMap[name] = halveCropInfo(it) }
            halvedCount++
        }
        log("Half-res: scaled $halvedCount images to 50% (fonts excluded)")
    }

    var resizedCount = 0
    for (i in allImages.indices) {
        val (name, img) = allImages[i]
        val maxDim = atlasSize
        if (maxDim >= img.width && maxDim >= img.height) continue

        val scale = minOf(maxDim.toDouble() / img.width, maxDim.toDouble() / img.height)
        val newW = maxOf((img.width * scale).toInt(), 1)
        val newH = maxOf((img.height * scale).toInt(), 1)
        val resizedPixels = IntArray(newW * newH)
        for (y in 0 until newH) {
            val srcY = (y * img.height) / newH
            for (x in 0 until newW) {
                val srcX = (x * img.width) / newW
                resizedPixels[y * newW + x] = img.pixels[srcY * img.width + srcX]
            }
        }
        allImages[i] = name to PixelImage(newW, newH, resizedPixels)
        resizedCount++
    }
    if (resizedCount > 0) {
        log("Resized $resizedCount images to fit atlas")
    }

    log("Total images to process: ${allImages.size}")

    log("Creating CLUTs...")
    val force4bppMatchers = force4bppPatterns.map { Regex(it) }
    val clutImages = mutableListOf<ClutImage>()
    var forced4bppCount = 0
    for ((name, img) in allImages) {
        val force4bpp = force4bppMatchers.any { it.matches(name) }
        if (force4bpp) forced4bppCount++
        clutImages.add(ClutProcessor.createClutImage(name, img, force4bpp))
    }
    if (force4bppMatchers.isNotEmpty()) {
        log("  Forced ${forced4bppCount} images to 4bpp via ${force4bppMatchers.size} pattern(s)")
    }
    val bpp4Count = clutImages.count { it.bpp == 4 }
    val bpp8Count = clutImages.count { it.bpp == 8 }
    log("  4bpp: $bpp4Count images, 8bpp: $bpp8Count images")

    log("Deduplicating CLUTs...")
    val dedupGroups = ClutProcessor.deduplicateCluts(clutImages)
    log("  After dedup: ${dedupGroups.size} unique CLUTs (from ${clutImages.size} images)")

    log("Merging CLUTs...")
    val mergedGroups = ClutProcessor.mergeCluts(clutImages, dedupGroups)
    val merged4 = mergedGroups.count { it.bpp == 4 }
    val merged8 = mergedGroups.count { it.bpp == 8 }
    log("  After merge: $merged4 merged 4bpp CLUTs, $merged8 merged 8bpp CLUTs (${mergedGroups.size} total)")

    log("Packing texture atlases...")
    val atlases = TextureAtlasPacker.packAtlases(clutImages, atlasGroupKeys, atlasSize)
    log("  ${atlases.count { it.bpp == 4 }} 4bpp atlases, ${atlases.count { it.bpp == 8 }} 8bpp atlases (${atlases.size} total)")

    log("Writing CLUT binaries...")
    val clut4Bin = writeClutBinary(mergedGroups.filter { it.bpp == 4 }.sortedBy { it.id }, 16)
    val clut8Bin = writeClutBinary(mergedGroups.filter { it.bpp == 8 }.sortedBy { it.id }, 256)

    log("Writing texture pages...")
    val (texturesBin, atlasOffsets) = writeTexturePagesBytes(atlases, log)

    log("Writing ATLAS.BIN...")
    val clutIndexMap = HashMap<String, Int>()
    var clut4Idx = 0
    var clut8Idx = 0
    for (group in mergedGroups.sortedBy { it.id }) {
        val idx = if (group.bpp == 4) clut4Idx++ else clut8Idx++
        for (name in group.imageNames) {
            clutIndexMap[name] = idx
        }
    }

    val atlasEntryMap = HashMap<String, Pair<TextureAtlas, AtlasEntry>>()
    for (atlas in atlases) {
        for (entry in atlas.entries) {
            atlasEntryMap[entry.image.name] = atlas to entry
        }
    }

    val tpagIdxToImageName = HashMap<Int, String>()
    for ((imgName, tpagIdx) in tpagIndexMap) {
        tpagIdxToImageName[tpagIdx] = imgName
    }

    val tileCoordDivisor = if (halfRes) 2 else 1
    val atlasBin = writeAtlasMetadataBytes(dw, uniqueTiles, tpagIdxToImageName, atlasEntryMap, clutIndexMap, atlasOffsets, cropInfoMap, tileCoordDivisor)

    log("Stripping data.win chunks for Nspire...")
    val slimDataWin = stripDataWinForNspire(dataWinBytes, log)

    log("Done!")
    return ProcessingResultNspire(
        dw.gen8.displayName ?: dw.gen8.name ?: "GAME",
        clut4Bin, clut8Bin, texturesBin, atlasBin, slimDataWin, atlases
    )
}
