package com.mrpowergamerbr.butterscotchpreprocessor

class ProcessingResultPS3(
    val gameName: String,
    val texturesBin: ByteArray
)

suspend fun processDataWinPS3(
    dataWinBytes: ByteArray,
    progressCallback: ((String) -> Unit)? = null
): ProcessingResultPS3 {
    val log = progressCallback ?: {}

    log("Parsing data.win...")
    val dw = DataWin.parse(dataWinBytes, DataWinParserOptions(
        parseGen8 = true,
        parseOptn = false,
        parseLang = false,
        parseExtn = false,
        parseSond = false,
        parseAgrp = false,
        parseSprt = false,
        parseBgnd = false,
        parsePath = false,
        parseScpt = false,
        parseGlob = false,
        parseShdr = false,
        parseFont = false,
        parseTmln = false,
        parseObjt = false,
        parseRoom = false,
        parseTpag = true,
        parseCode = false,
        parseVari = false,
        parseFunc = false,
        parseStrg = true,
        parseTxtr = true,
        parseAudo = false,
        skipLoadingPreciseMasksForNonPreciseSprites = true
    ))

    log("Parsed: ${dw.txtr.textures.size} texture pages, ${dw.tpag.items.size} TPAG items")

    log("Loading texture pages...")
    val gm2022_5 = dw.isVersionAtLeast(2022, 5, 0, 0)
    val texturePages = mutableListOf<PixelImage?>()
    for (tex in dw.txtr.textures) {
        if (tex.blobData != null) {
            texturePages.add(decodeImageBytes(tex.blobData, gm2022_5))
        } else {
            texturePages.add(null)
        }
    }
    log("Loaded ${texturePages.count { it != null }} texture pages")

    // One ClutImage per tpag (sub-rect). Index aligned with dw.tpag.items.
    log("Building per-tpag CLUT images...")
    val tpagClutImages = arrayOfNulls<ClutImage>(dw.tpag.items.size)
    var emptyCount = 0
    for ((idx, tpag) in dw.tpag.items.withIndex()) {
        val srcImg = if (tpag.texturePageId >= 0) texturePages.getOrNull(tpag.texturePageId.toInt()) else null
        if (srcImg == null || tpag.targetWidth == 0 || tpag.targetHeight == 0) {
            emptyCount++
            continue
        }
        val sub = extractFromTPAG(tpag, texturePages)
        // force4bpp=false: createClutImage will pick 4 or 8 based on color count.
        // We then promote any 4bpp to 8bpp so dedup/merge stays in the 8bpp lane.
        val clut = ClutProcessor.createClutImage("tpag_$idx", sub, force4bpp = false)
        tpagClutImages[idx] = if (clut.bpp == 8) clut else promoteTo8bpp(clut)
    }
    if (emptyCount > 0) log("  $emptyCount tpag entries had no source page or zero size (will map to CLUT 0xFFFF)")

    val clutImages = tpagClutImages.filterNotNull()

    // Dedupe + merge CLUTs across tpags so identical/compatible palettes share a row.
    log("Deduplicating CLUTs...")
    val dedupGroups = ClutProcessor.deduplicateCluts(clutImages)
    log("  After dedup: ${dedupGroups.size} unique CLUTs (from ${clutImages.size} tpag images)")

    log("Merging CLUTs...")
    val mergedGroups = ClutProcessor.mergeCluts(clutImages, dedupGroups)
    log("  After merge: ${mergedGroups.size} CLUTs")

    // image name -> CLUT row index (final index in the written table)
    val sortedGroups = mergedGroups.sortedBy { it.id }
    val clutIndexByName = HashMap<String, Int>()
    for ((rowIdx, group) in sortedGroups.withIndex()) {
        for (name in group.imageNames) clutIndexByName[name] = rowIdx
    }

    // Re-blit each tpag's 8bpp indices back into the texture page canvas.
    // The canvas keeps the original page dimensions; pixels not covered by any tpag stay 0.
    log("Building 8bpp texture pages...")
    val pageCanvases = arrayOfNulls<ByteArray>(dw.txtr.textures.size)
    val pageWidths = IntArray(dw.txtr.textures.size)
    val pageHeights = IntArray(dw.txtr.textures.size)
    for ((i, page) in texturePages.withIndex()) {
        if (page == null) continue
        pageCanvases[i] = ByteArray(page.width * page.height)
        pageWidths[i] = page.width
        pageHeights[i] = page.height
    }
    for ((idx, tpag) in dw.tpag.items.withIndex()) {
        val clutImg = tpagClutImages[idx] ?: continue
        val pageIdx = tpag.texturePageId.toInt()
        val canvas = pageCanvases.getOrNull(pageIdx) ?: continue
        val pageW = pageWidths[pageIdx]
        val pageH = pageHeights[pageIdx]
        // Layout of clutImg matches extractFromTPAG: targetX/Y is where the real pixels live within
        // a boundingWidth x boundingHeight image. Walk only the targetWidth x targetHeight valid region
        // and write it back at sourceX/Y in the original page.
        for (dy in 0 until tpag.targetHeight) {
            val srcY = tpag.targetY + dy
            val pageY = tpag.sourceY + dy
            if (srcY >= clutImg.height || pageY >= pageH) continue
            for (dx in 0 until tpag.targetWidth) {
                val srcX = tpag.targetX + dx
                val pageX = tpag.sourceX + dx
                if (srcX >= clutImg.width || pageX >= pageW) continue
                canvas[pageY * pageW + pageX] = clutImg.indices[srcY * clutImg.width + srcX]
            }
        }
    }

    log("Writing textures.bin...")
    val texturesBin = writeTexturesBinPS3(
        sortedGroups,
        dw.tpag.items.size,
        tpagClutImages,
        clutIndexByName,
        pageCanvases,
        pageWidths,
        pageHeights,
        log
    )

    log("Done!")
    return ProcessingResultPS3(dw.gen8.displayName ?: dw.gen8.name ?: "GAME", texturesBin)
}

private fun promoteTo8bpp(img: ClutImage): ClutImage {
    val newPalette = IntArray(256)
    img.palette.copyInto(newPalette, 0, 0, minOf(img.palette.size, 256))
    img.bpp = 8
    img.palette = newPalette
    return img
}

private fun writeTexturesBinPS3(
    cluts: List<ClutGroup>,
    tpagCount: Int,
    tpagClutImages: Array<ClutImage?>,
    clutIndexByName: Map<String, Int>,
    pageCanvases: Array<ByteArray?>,
    pageWidths: IntArray,
    pageHeights: IntArray,
    log: (String) -> Unit
): ByteArray {
    val pageCount = pageCanvases.size
    val w = ByteWriter()

    // Header
    w.writeByte(0)                  // version
    w.writeShortBE(cluts.size)      // clutCount
    w.writeShortBE(pageCount)       // texturePageCount
    w.writeShortBE(tpagCount)       // tpagCount

    // CLUT table: clutCount rows of 256 RGBA8 colors (no swizzle, no PS2 alpha remap)
    for (group in cluts) {
        for (i in 0 until 256) {
            val argb = if (i < group.palette.size) group.palette[i] else 0
            val a = (argb ushr 24) and 0xFF
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            w.writeByte(r); w.writeByte(g); w.writeByte(b); w.writeByte(a)
        }
    }

    // tpag -> CLUT index (0xFFFF = no CLUT for this tpag)
    var unmappedTpags = 0
    for (i in 0 until tpagCount) {
        val img = tpagClutImages[i]
        val rowIdx = if (img != null) clutIndexByName[img.name] ?: -1 else -1
        if (rowIdx < 0) {
            w.writeShortBE(0xFFFF)
            unmappedTpags++
        } else {
            w.writeShortBE(rowIdx)
        }
    }
    if (unmappedTpags > 0) log("  $unmappedTpags tpag entries unmapped (no source pixels)")

    // Texture page table: width, height, pixelDataSize for each page (header row before pixel data)
    val pageOffsets = IntArray(pageCount)
    var cursor = 0
    for (i in 0 until pageCount) {
        pageOffsets[i] = cursor
        val size = (pageCanvases[i]?.size ?: 0)
        cursor += size
    }
    for (i in 0 until pageCount) {
        w.writeShortBE(pageWidths[i])
        w.writeShortBE(pageHeights[i])
        w.writeIntBE(pageOffsets[i])
        w.writeIntBE(pageCanvases[i]?.size ?: 0)
    }

    // Pixel data, concatenated in page index order
    for (i in 0 until pageCount) {
        val canvas = pageCanvases[i] ?: continue
        w.writeByteArray(canvas)
    }

    return w.getAsByteArray()
}
