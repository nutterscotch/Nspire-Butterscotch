package com.mrpowergamerbr.butterscotchpreprocessor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class ProcessingResult(
    val gameName: String,
    val clut4Bin: ByteArray,
    val clut8Bin: ByteArray,
    val texturesBin: ByteArray,
    val atlasBin: ByteArray,
    val soundBnkBin: ByteArray,
    val soundsBin: ByteArray,
    val atlases: List<TextureAtlas> = emptyList()
)

private const val AUDIO_FORMAT_PCM = 0
private const val AUDIO_FORMAT_ADPCM = 1

data class AudioData(
    val format: Int,         // AUDIO_FORMAT_PCM or AUDIO_FORMAT_ADPCM
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,  // Only meaningful for PCM (8 or 16)
    val data: ByteArray,     // Raw PCM, raw OGG, or ADPCM encoded data
    val sampleCount: Int     // Decoded sample count per channel; length in seconds = sampleCount / sampleRate
)

internal data class TileKey(val useSpriteDefinition: Boolean, val bgDef: Int, val srcX: Int, val srcY: Int, val w: Int, val h: Int)
internal data class CropInfo(val offsetX: Int, val offsetY: Int, val croppedWidth: Int, val croppedHeight: Int)

suspend fun processDataWin(
    dataWinBytes: ByteArray,
    externalAudioFiles: Map<String, ByteArray> = emptyMap(),
    audioGroupFiles: Map<Int, ByteArray> = emptyMap(),
    musFiles: Map<String, ByteArray> = emptyMap(),
    force4bppPatterns: List<String> = emptyList(),
    atlasSize: Int = TextureAtlasPacker.DEFAULT_ATLAS_SIZE,
    audioDecoder: suspend (ByteArray) -> (AudioData?),
    progressCallback: ((String) -> Unit)? = null
): ProcessingResult {
    val log = progressCallback ?: {}

    log("Parsing data.win...")
    val dw = DataWin.parse(dataWinBytes, DataWinParserOptions(
        parseGen8 = true,
        parseOptn = false,
        parseLang = false,
        parseExtn = false,
        parseSond = true,
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
        parseAudo = true,
        skipLoadingPreciseMasksForNonPreciseSprites = true
    ))

    log("Parsed: ${dw.sprt.sprites.size} sprites, ${dw.bgnd.backgrounds.size} backgrounds, ${dw.font.fonts.size} fonts, ${dw.txtr.textures.size} textures, ${dw.tpag.items.size} TPAG items")

    // Decode texture pages as PixelImages
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

    // Collect sprites
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

    // Collect backgrounds
    for ((bgIdx, bg) in dw.bgnd.backgrounds.withIndex()) {
        val name = bg.name ?: "bg_$bgIdx"
        val tpagIdx = dw.resolveTPAG(bg.textureOffset)
        if (0 > tpagIdx) continue
        val imgName = "bg/$name"
        allImages.add(imgName to extractFromTPAG(dw.tpag.items[tpagIdx], texturePages))
        atlasGroupKeys[imgName] = imgName
        tpagIndexMap[imgName] = tpagIdx
    }

    // Collect fonts
    for ((fontIdx, font) in dw.font.fonts.withIndex()) {
        val name = font.name ?: "font_$fontIdx"
        val tpagIdx = dw.resolveTPAG(font.textureOffset)
        if (0 > tpagIdx) continue
        val imgName = "font/$name"
        allImages.add(imgName to extractFromTPAG(dw.tpag.items[tpagIdx], texturePages))
        atlasGroupKeys[imgName] = imgName
        tpagIndexMap[imgName] = tpagIdx
    }

    // Collect unique tiles (from legacy tiles and GMS2 asset layers)
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
    // Extract source images for tiles (background or sprite depending on useSpriteDefinition)
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
        // Clamp tile rectangle to source bounds. Some rooms reference tiles whose requested rect extends past the source background
        // The runner just samples whatever exists.
        // Keep the original w/h in the imgName so the runtime  lookup key matches, the atlas entry stores only the in-bounds pixels.
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

    // Crop transparent borders before packing (sprites only)
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
            // No crop: store original dimensions for correct cropW/cropH in ATLAS.BIN
            cropInfoMap[name] = CropInfo(0, 0, img.width, img.height)
        }
    }
    if (croppedCount > 0) {
        log("Cropped transparent borders from $croppedCount sprite images")
    }


    var resizedCount = 0
    for (i in allImages.indices) {
        val (name, img) = allImages[i]

        // Resize any images exceeding the max atlas size
        // We'll also resize any "problematic" sprites
        val maxDim = if (name.startsWith("font/")) {
            atlasSize
        } else if (name.startsWith("spr/spr_sidestalk")) {
            16
        } else if (name.startsWith("spr/spr_mouthball_")) {
            32
        } else if (name.startsWith("spr/spr_fa_seq_b_") || name.startsWith("spr/spr_floweyarm")) {
            64
        } else if (name.startsWith("spr/spr_fa_stemunder_")) {
            64
        } else if (name.startsWith("spr/spr_floweynuke_explosion")) {
            16
        } else if (name.startsWith("spr/spr_floweyx_flame_move_")) {
            16
        } else if (name.startsWith("spr/spr_venus_placeholder")) {
            32
        } else if (name.startsWith("spr/spr_f_handgun_neo_")) {
            32
        } else if (name.startsWith("spr/spr_vines_flowey")) {
            32
        } else if (name.startsWith("spr/spr_floweyx_flamethrower")) {
            64
        } else if (name.startsWith("spr/spr_floweynuke")) {
            32
        } else if (name.startsWith("spr/spr_bgpipe")) {
            64
        } else if (name.startsWith("spr/spr_tvinside_old")) {
            64
        } else if (name.startsWith("spr/spr_floweyx_tv")) {
            128
        } else if (name.startsWith("spr/spr_floweyx_fleshmound")) {
            64
        } else if (name.startsWith("spr/spr_tv_floweyx_laugh")) {
            64
        } else if (name.startsWith("spr/spr_floweyx_mouthedge")) {
            64
        } else {
            atlasSize
        }

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
        log("Resized $resizedCount images to fit")
    }

    log("Total images to process: ${allImages.size}")

    // Step 1: Create CLUTs
    log("Creating CLUTs...")
    val force4bppMatchers = force4bppPatterns.map { Regex(it) }
    val clutImages = mutableListOf<ClutImage>()
    var forced4bppCount = 0
    for ((name, img) in allImages) {
        val force4bpp = force4bppMatchers.any { it.matches(name) }
        if (force4bpp) forced4bppCount++
        val clutImage = ClutProcessor.createClutImage(name, img, force4bpp)
        clutImages.add(clutImage)
    }
    if (force4bppMatchers.isNotEmpty()) {
        log("  Forced ${forced4bppCount} images to 4bpp via ${force4bppMatchers.size} pattern(s)")
    }

    val bpp4Count = clutImages.count { it.bpp == 4 }
    val bpp8Count = clutImages.count { it.bpp == 8 }
    log("  4bpp: $bpp4Count images, 8bpp: $bpp8Count images")

    // Step 2: Deduplicate CLUTs
    log("Deduplicating CLUTs...")
    val dedupGroups = ClutProcessor.deduplicateCluts(clutImages)
    log("  After dedup: ${dedupGroups.size} unique CLUTs (from ${clutImages.size} images)")

    // Step 3: Merge CLUTs
    log("Merging CLUTs...")
    val mergedGroups = ClutProcessor.mergeCluts(clutImages, dedupGroups)
    val merged4 = mergedGroups.count { it.bpp == 4 }
    val merged8 = mergedGroups.count { it.bpp == 8 }
    log("  After merge: $merged4 merged 4bpp CLUTs, $merged8 merged 8bpp CLUTs (${mergedGroups.size} total)")

    // Step 4: Pack into texture atlases
    log("Packing texture atlases...")
    val atlases = TextureAtlasPacker.packAtlases(clutImages, atlasGroupKeys, atlasSize)
    log("  ${atlases.count { it.bpp == 4 }} 4bpp atlases, ${atlases.count { it.bpp == 8 }} 8bpp atlases (${atlases.size} total)")

    // Step 5: Write CLUT binaries
    log("Writing CLUT binaries...")
    val clut4Bin = writeClutBinary(mergedGroups.filter { it.bpp == 4 }.sortedBy { it.id }, 16)
    val clut8Bin = writeClutBinary(mergedGroups.filter { it.bpp == 8 }.sortedBy { it.id }, 256)

    // Step 6: Write texture pages
    log("Writing texture pages...")
    val (texturesBin, atlasOffsets) = writeTexturePagesBytes(atlases, log)

    // Step 7: Build lookups and write ATLAS.BIN
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

    val atlasBin = writeAtlasMetadataBytes(dw, uniqueTiles, tpagIdxToImageName, atlasEntryMap, clutIndexMap, atlasOffsets, cropInfoMap)

    // Step 8: Process sounds
    log("Processing sounds...")

    // Parse audiogroup files (audiogroup%d.dat) for their AUDO chunks
    val audioGroupAudo = HashMap<Int, Audo>()
    for ((groupId, groupBytes) in audioGroupFiles) {
        log("Parsing audiogroup${groupId}.dat...")
        val agDw = DataWin.parse(groupBytes, DataWinParserOptions(
            parseGen8 = false, parseOptn = false, parseLang = false, parseExtn = false,
            parseSond = false, parseAgrp = false, parseSprt = false, parseBgnd = false,
            parsePath = false, parseScpt = false, parseGlob = false, parseShdr = false,
            parseFont = false, parseTmln = false, parseObjt = false, parseRoom = false,
            parseTpag = false, parseCode = false, parseVari = false, parseFunc = false,
            parseStrg = false, parseTxtr = false, parseAudo = true,
            skipLoadingPreciseMasksForNonPreciseSprites = true
        ))
        audioGroupAudo[groupId] = agDw.audo
        log("  audiogroup${groupId}.dat: ${agDw.audo.entries.size} audio entries")
    }

    log("Decoding embedded audio files...")

    // Decode embedded AUDO entries from the main data.win (audiogroup 0)
    // The async block per entry preserves index alignment with dw.audo.entries
    // (entries with null data resolve to null in-place, instead of being filtered out)
    val parsedAudio = coroutineScope {
        dw.audo.entries.mapIndexed { idx, entry ->
            async(Dispatchers.Default) {
                if (entry.data == null) return@async null
                val decoded = audioDecoder.invoke(entry.data)
                log("  Decoded embedded audio #$idx${if (decoded == null) " (FAILED)" else ""}")
                decoded
            }
        }.awaitAll()
    }.toMutableList()
    val embeddedCount = parsedAudio.count { it != null }

    // Decode AUDO entries from audiogroup files and map them
    // sondIndex -> new audoIndex in parsedAudio
    val externalAudoMap = HashMap<Int, Int>()
    var audioGroupCount = 0

    // Dispatch decodes in parallel, then assign indices in serial order so the
    // sondIdx -> audoIndex mapping is deterministic regardless of completion order
    val audioGroupDecodes = coroutineScope {
        dw.sond.sounds.withIndex().mapNotNull { (sondIdx, sound) ->
            if (sound.audioGroup == 0) return@mapNotNull null
            val groupAudo = audioGroupAudo[sound.audioGroup] ?: return@mapNotNull null
            if (0 > sound.audioFile || sound.audioFile >= groupAudo.entries.size) return@mapNotNull null
            val entry = groupAudo.entries[sound.audioFile]
            if (entry.data == null) return@mapNotNull null
            val label = sound.file ?: sound.name ?: "audiogroup${sound.audioGroup}/${sound.audioFile}"
            sondIdx to async(Dispatchers.Default) {
                val decoded = audioDecoder.invoke(entry.data)
                log("  Decoded $label${if (decoded == null) " (FAILED)" else ""}")
                decoded
            }
        }.map { (sondIdx, def) -> sondIdx to def.await() }
    }
    for ((sondIdx, decoded) in audioGroupDecodes) {
        if (decoded != null) {
            externalAudoMap[sondIdx] = parsedAudio.size
            parsedAudio.add(decoded)
            audioGroupCount++
        }
    }

    // Resolve non-embedded sounds from external audio files
    // These get appended as new AUDO entries at the end of the list
    var externalCount = 0
    log("Decoding non-embedded audio files...")
    val externalDecodes = coroutineScope {
        dw.sond.sounds.withIndex().mapNotNull { (sondIdx, sound) ->
            val isEmbedded = (sound.flags and 0x01) != 0
            if (isEmbedded) return@mapNotNull null
            if (externalAudoMap.containsKey(sondIdx)) return@mapNotNull null

            // Non-embedded audio files DO have an entry on the AUDO chunk, but we will ignore them because they are bogus entries

            val fileName = sound.file ?: return@mapNotNull null
            val fileData = externalAudioFiles[fileName]
                ?: externalAudioFiles["$fileName.ogg"]
                ?: externalAudioFiles["$fileName.wav"]
                ?: return@mapNotNull null
            sondIdx to async(Dispatchers.Default) {
                val decoded = audioDecoder.invoke(fileData)
                log("  Decoded ${sound.file}${if (decoded == null) " (FAILED)" else ""}")
                decoded
            }
        }.map { (sondIdx, def) -> sondIdx to def.await() }
    }
    for ((sondIdx, decoded) in externalDecodes) {
        if (decoded != null) {
            externalAudoMap[sondIdx] = parsedAudio.size
            parsedAudio.add(decoded)
            externalCount++
        }
    }

    val totalDecoded = parsedAudio.count { it != null }
    val failedCount = parsedAudio.size - totalDecoded
    log("  $embeddedCount embedded + $audioGroupCount from audiogroups + $externalCount external = $totalDecoded decoded sounds, $failedCount failed or empty")

    // Process streamed music files (mus/ directory)
    data class MusEntry(val path: String, val audio: AudioData)
    val musEntries = mutableListOf<MusEntry>()
    if (musFiles.isNotEmpty()) {
        log("Decoding ${musFiles.size} streamed music files...")
        val musDecodes = coroutineScope {
            musFiles.entries.sortedBy { it.key }.map { (path, fileData) ->
                path to async(Dispatchers.Default) {
                    val decoded = audioDecoder.invoke(fileData)
                    if (decoded != null) {
                        log("  $path: ${decoded.sampleRate}Hz ${decoded.channels}ch -> ADPCM (${decoded.data.size} bytes)")
                    } else {
                        log("  $path: FAILED to decode")
                    }
                    decoded
                }
            }.map { (path, def) -> path to def.await() }
        }
        for ((path, decoded) in musDecodes) {
            if (decoded != null) {
                musEntries.add(MusEntry(path, decoded))
            }
        }
        log("  ${musEntries.size}/${musFiles.size} music files decoded")
    }

    // Build SOUNDS.BIN (PCM/ADPCM data concatenated: SFX first, then music)
    val soundsWriter = ByteWriter()
    val audioOffsets = IntArray(parsedAudio.size)
    val audioSizes = IntArray(parsedAudio.size)
    for ((i, audio) in parsedAudio.withIndex()) {
        if (audio != null) {
            audioOffsets[i] = soundsWriter.size
            audioSizes[i] = audio.data.size
            soundsWriter.writeByteArray(audio.data)
        }
    }

    // Append music data to SOUNDS.BIN
    val musOffsets = IntArray(musEntries.size)
    val musSizes = IntArray(musEntries.size)
    for ((i, entry) in musEntries.withIndex()) {
        musOffsets[i] = soundsWriter.size
        musSizes[i] = entry.audio.data.size
        soundsWriter.writeByteArray(entry.audio.data)
    }

    val soundsBin = soundsWriter.getAsByteArray()
    val pcmCount = parsedAudio.count { it != null && it.format == AUDIO_FORMAT_PCM }
    val adpcmCount = parsedAudio.count { it != null && it.format == AUDIO_FORMAT_ADPCM }
    log("  SOUNDS.BIN: ${soundsBin.size} bytes ($pcmCount PCM, $adpcmCount ADPCM, ${musEntries.size} music tracks)")

    // Build SOUNDBNK.BIN
    val soundBnkWriter = ByteWriter()
    // Header (7 bytes)
    soundBnkWriter.writeByte(0)                                    // version
    soundBnkWriter.writeShortLE(dw.sond.sounds.size)               // sondEntryCount
    soundBnkWriter.writeShortLE(parsedAudio.size)                  // audoEntryCount
    soundBnkWriter.writeShortLE(musEntries.size)                   // musEntryCount

    // SOND entries (12 bytes each)
    for ((sondIdx, sound) in dw.sond.sounds.withIndex()) {
        // Use the external mapping if available, otherwise use the original audioFile index
        val audoIndex = externalAudoMap[sondIdx]
            ?: if (0 > sound.audioFile || sound.audioFile >= parsedAudio.size) 0xFFFF else sound.audioFile
        soundBnkWriter.writeShortLE(audoIndex)                     // audoIndex
        soundBnkWriter.writeIntLE(sound.flags)                     // flags
        soundBnkWriter.writeShortLE((sound.volume * 256).toInt())  // volume (fixed-point)
        soundBnkWriter.writeShortLE((sound.pitch * 256).toInt())   // pitch (fixed-point)
        soundBnkWriter.writeShortLE(0)                             // reserved
    }

    // AUDO entries (20 bytes each)
    for ((i, audio) in parsedAudio.withIndex()) {
        if (audio != null) {
            soundBnkWriter.writeIntLE(audioOffsets[i])             // dataOffset
            soundBnkWriter.writeIntLE(audioSizes[i])               // dataSize
            soundBnkWriter.writeShortLE(audio.sampleRate)          // sampleRate
            soundBnkWriter.writeByte(audio.channels)               // channels
            soundBnkWriter.writeByte(audio.bitsPerSample)          // bitsPerSample
            soundBnkWriter.writeByte(audio.format)                 // format (0=PCM, 1=ADPCM)
            soundBnkWriter.writeByte(0)                            // reserved
            soundBnkWriter.writeShortLE(0)                         // reserved
            soundBnkWriter.writeIntLE(audio.sampleCount)           // sampleCount (per channel)
        } else {
            // Unmapped entry
            soundBnkWriter.writeIntLE(0)                           // dataOffset
            soundBnkWriter.writeIntLE(0)                           // dataSize
            soundBnkWriter.writeShortLE(0)                         // sampleRate
            soundBnkWriter.writeByte(0)                            // channels
            soundBnkWriter.writeByte(0)                            // bitsPerSample
            soundBnkWriter.writeByte(0)                            // format
            soundBnkWriter.writeByte(0)                            // reserved
            soundBnkWriter.writeShortLE(0)                         // reserved
            soundBnkWriter.writeIntLE(0)                           // sampleCount
        }
    }

    // MUS string table (variable size: u8 nameLength + name bytes per entry)
    for (entry in musEntries) {
        val nameBytes = entry.path.encodeToByteArray()
        soundBnkWriter.writeByte(nameBytes.size)                   // nameLength
        soundBnkWriter.writeByteArray(nameBytes)                   // name (UTF-8, no null terminator)
    }

    // MUS entries (16 bytes each)
    for ((i, entry) in musEntries.withIndex()) {
        soundBnkWriter.writeIntLE(musOffsets[i])                   // dataOffset in SOUNDS.BIN
        soundBnkWriter.writeIntLE(musSizes[i])                     // dataSize
        soundBnkWriter.writeShortLE(entry.audio.sampleRate)        // sampleRate
        soundBnkWriter.writeByte(entry.audio.channels)             // channels
        soundBnkWriter.writeByte(entry.audio.format)               // format (0=PCM, 1=ADPCM)
        soundBnkWriter.writeIntLE(entry.audio.sampleCount)         // sampleCount (per channel)
    }

    val soundBnkBin = soundBnkWriter.getAsByteArray()

    log("Done!")
    return ProcessingResult(dw.gen8.displayName ?: dw.gen8.name ?: "GAME", clut4Bin, clut8Bin, texturesBin, atlasBin, soundBnkBin, soundsBin, atlases)
}

// Pure pixel copy from a TPAG item (replaces Graphics2D.drawImage)
internal fun extractFromTPAG(tpag: TexturePageItem, texturePages: List<PixelImage?>): PixelImage {
    val w = maxOf(tpag.boundingWidth, 1)
    val h = maxOf(tpag.boundingHeight, 1)
    val pixels = IntArray(w * h)

    if (tpag.texturePageId < 0) return PixelImage(w, h, pixels)
    val texPage = texturePages.getOrNull(tpag.texturePageId.toInt()) ?: return PixelImage(w, h, pixels)
    if (tpag.targetWidth == 0 || tpag.targetHeight == 0) return PixelImage(w, h, pixels)

    // Copy the rectangle from source texture page to destination
    for (dy in 0 until tpag.targetHeight) {
        val srcY = tpag.sourceY + dy
        val dstY = tpag.targetY + dy
        if (srcY >= texPage.height || dstY >= h) continue
        for (dx in 0 until tpag.targetWidth) {
            val srcX = tpag.sourceX + dx
            val dstX = tpag.targetX + dx
            if (srcX >= texPage.width || dstX >= w) continue
            pixels[dstY * w + dstX] = texPage.pixels[srcY * texPage.width + srcX]
        }
    }

    return PixelImage(w, h, pixels)
}

// Extract a sub-rectangle from an image (replaces BufferedImage.getSubimage + Graphics2D copy)
internal fun extractSubImage(src: PixelImage, srcX: Int, srcY: Int, w: Int, h: Int): PixelImage {
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            pixels[y * w + x] = src.pixels[(srcY + y) * src.width + (srcX + x)]
        }
    }
    return PixelImage(w, h, pixels)
}

// Converts ARGB palette to PS2 RGBA format with alpha remapped to 0-128 range
private fun convertARGBtoPS2RGBA(argb: Int): Int {
    val a = (argb ushr 24) and 0xFF
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    // PS2 alpha: 0-255 -> 0-128 (>> 1) + 1, fully opaque = 0x80
    val ps2Alpha = (a + 1) ushr 1

    return (ps2Alpha shl 24) or (b shl 16) or (g shl 8) or r
}

// PS2 CSM1 CLUT swizzle for 8bpp
private fun swizzlePalette8bpp(palette: IntArray): IntArray {
    val swizzled = palette.copyOf()
    for (i in swizzled.indices) {
        if ((i and 0x18) == 8) {
            val tmp = swizzled[i]
            swizzled[i] = swizzled[i + 8]
            swizzled[i + 8] = tmp
        }
    }
    return swizzled
}

internal fun writeClutBinary(groups: List<ClutGroup>, paletteSize: Int): ByteArray {
    if (groups.isEmpty()) return ByteArray(0)

    val writer = ByteWriter(groups.size * paletteSize * 4)
    for (group in groups) {
        val palette = IntArray(paletteSize)
        group.palette.copyInto(palette, 0, 0, minOf(group.palette.size, paletteSize))

        // Convert ARGB to PS2 RGBA with alpha remap
        for (i in palette.indices) {
            palette[i] = convertARGBtoPS2RGBA(palette[i])
        }

        // Swizzle 8bpp palettes for CSM1
        val finalPalette = if (paletteSize == 256) swizzlePalette8bpp(palette) else palette

        for (color in finalPalette) {
            writer.writeIntLE(color)
        }
    }
    return writer.getAsByteArray()
}

private fun rleCompress(data: ByteArray): ByteArray {
    if (data.isEmpty()) return ByteArray(0)

    val writer = ByteWriter(data.size)
    var i = 0
    while (data.size > i) {
        val current = data[i]
        var runLength = 1
        while (data.size > i + runLength && runLength < 255 && data[i + runLength] == current) {
            runLength++
        }
        writer.writeByte(runLength)
        writer.writeByte(current.toInt() and 0xFF)
        i += runLength
    }
    return writer.getAsByteArray()
}

internal fun writeTexturePagesBytes(atlases: List<TextureAtlas>, log: (String) -> Unit): Pair<ByteArray, Map<Int, Long>> {
    val headerSize = 128
    val atlasOffsets = HashMap<Int, Long>()
    var currentOffset = 0L

    val sortedAtlases = atlases.sortedBy { it.id }
    val writer = ByteWriter()

    for (atlas in sortedAtlases) {
        atlasOffsets[atlas.id] = currentOffset

        // Composite all entries into a single pixel index buffer
        val canvas = ByteArray(atlas.width * atlas.height)
        for (entry in atlas.entries) {
            val img = entry.image
            for (y in 0 until img.height) {
                for (x in 0 until img.width) {
                    canvas[(entry.y + y) * atlas.width + (entry.x + x)] =
                        img.indices[y * img.width + x]
                }
            }
        }

        // Pack pixel data according to bpp
        val uncompressedPixelData: ByteArray
        if (atlas.bpp == 4) {
            val packedSize = (atlas.width * atlas.height + 1) / 2
            uncompressedPixelData = ByteArray(packedSize)
            for (i in canvas.indices step 2) {
                val lo = canvas[i].toInt() and 0x0F
                val hi = if (i + 1 < canvas.size) (canvas[i + 1].toInt() and 0x0F) shl 4 else 0
                uncompressedPixelData[i / 2] = (lo or hi).toByte()
            }
        } else {
            uncompressedPixelData = canvas
        }

        // Try RLE compression
        val rleData = rleCompress(uncompressedPixelData)
        val useRle = uncompressedPixelData.size > rleData.size
        val compressionType: Int
        val pixelData: ByteArray

        if (useRle) {
            compressionType = 1
            pixelData = rleData
            log("  Atlas ${atlas.id} (${atlas.bpp}bpp): RLE compressed ${uncompressedPixelData.size} -> ${rleData.size} bytes (saved ${uncompressedPixelData.size - rleData.size} bytes, ${(100.0 * (uncompressedPixelData.size - rleData.size) / uncompressedPixelData.size).toInt()}%)")
        } else {
            compressionType = 0
            pixelData = uncompressedPixelData
            log("  Atlas ${atlas.id} (${atlas.bpp}bpp): RLE not beneficial (${uncompressedPixelData.size} -> ${rleData.size} bytes), keeping uncompressed")
        }

        // Header (128 bytes)
        writer.writeByte(0)                          // version
        writer.writeShortLE(atlas.width)             // width
        writer.writeShortLE(atlas.height)            // height
        writer.writeByte(atlas.bpp)                  // bpp
        writer.writeIntLE(pixelData.size)            // pixelDataSize
        writer.writeByte(compressionType)            // compressionType (0 = uncompressed, 1 = RLE)
        // Pad to 128 bytes (we wrote 1+2+2+1+4+1 = 11 bytes so far)
        writer.writeZeroPadding(headerSize - 11)

        // Pixel data
        writer.writeByteArray(pixelData)

        currentOffset += headerSize + pixelData.size
    }

    return writer.getAsByteArray() to atlasOffsets
}

internal fun writeAtlasMetadataBytes(
    dw: DataWin,
    uniqueTiles: LinkedHashMap<TileKey, RoomTile>,
    tpagIdxToImageName: Map<Int, String>,
    atlasEntryMap: Map<String, Pair<TextureAtlas, AtlasEntry>>,
    clutIndexMap: Map<String, Int>,
    atlasOffsets: Map<Int, Long>,
    cropInfoMap: Map<String, CropInfo>,
    tileCoordDivisor: Int = 1
): ByteArray {
    val tpagCount = dw.tpag.items.size
    val tileCount = uniqueTiles.size
    val atlasCount = atlasOffsets.size

    val writer = ByteWriter()

    // Header
    writer.writeByte(0)                            // version
    writer.writeShortLE(tpagCount)                 // tpagEntryCount
    writer.writeShortLE(tileCount)                 // tileEntryCount
    writer.writeShortLE(atlasCount)                // atlasCount

    // Atlas offset table
    for (id in atlasOffsets.keys.sorted()) {
        writer.writeIntLE(atlasOffsets[id]!!.toInt())
    }

    // TPAG entries
    for (tpagIdx in 0 until tpagCount) {
        val imgName = tpagIdxToImageName[tpagIdx]
        if (imgName != null) {
            val pair = atlasEntryMap[imgName]
            if (pair != null) {
                val (atlas, entry) = pair
                writer.writeShortLE(atlas.id)               // atlasId
                writer.writeShortLE(entry.x)                // atlasX
                writer.writeShortLE(entry.y)                // atlasY
                writer.writeShortLE(entry.image.width)      // width
                writer.writeShortLE(entry.image.height)     // height
                val crop = cropInfoMap[imgName]
                writer.writeShortLE(crop?.offsetX ?: 0)     // cropX
                writer.writeShortLE(crop?.offsetY ?: 0)     // cropY
                writer.writeShortLE(crop?.croppedWidth ?: entry.image.width)  // cropW
                writer.writeShortLE(crop?.croppedHeight ?: entry.image.height) // cropH
                writer.writeShortLE(clutIndexMap[imgName] ?: 0) // clutIndex
                continue
            }
        }
        // Unmapped TPAG entry
        writer.writeShortLE(0xFFFF)  // atlasId = unmapped
        writer.writeShortLE(0)       // atlasX
        writer.writeShortLE(0)       // atlasY
        writer.writeShortLE(0)       // width
        writer.writeShortLE(0)       // height
        writer.writeShortLE(0)       // cropX
        writer.writeShortLE(0)       // cropY
        writer.writeShortLE(0)       // cropW
        writer.writeShortLE(0)       // cropH
        writer.writeShortLE(0)       // clutIndex
    }

    // Tile entries
    for ((key, _) in uniqueTiles) {
        val defName = if (key.useSpriteDefinition) {
            dw.sprt.sprites[key.bgDef].name ?: "spr${key.bgDef}"
        } else {
            dw.bgnd.backgrounds[key.bgDef].name ?: "bg${key.bgDef}"
        }
        val imgName = "tile/${defName}_${key.srcX}_${key.srcY}_${key.w}x${key.h}"
        val pair = atlasEntryMap[imgName]
        val atlas = pair?.first
        val entry = pair?.second

        writer.writeShortLE(key.bgDef)                              // bgDef
        writer.writeShortLE(key.srcX / tileCoordDivisor)            // srcX
        writer.writeShortLE(key.srcY / tileCoordDivisor)            // srcY
        writer.writeShortLE(key.w / tileCoordDivisor)               // srcW
        writer.writeShortLE(key.h / tileCoordDivisor)               // srcH
        writer.writeShortLE(atlas?.id ?: 0xFFFF)                    // atlasId
        writer.writeShortLE(entry?.x ?: 0)                          // atlasX
        writer.writeShortLE(entry?.y ?: 0)                          // atlasY
        writer.writeShortLE(entry?.image?.width ?: 0)               // width
        writer.writeShortLE(entry?.image?.height ?: 0)              // height
        val crop = cropInfoMap[imgName]
        writer.writeShortLE(crop?.offsetX ?: 0)                    // cropX
        writer.writeShortLE(crop?.offsetY ?: 0)                    // cropY
        writer.writeShortLE(crop?.croppedWidth ?: (entry?.image?.width ?: 0))  // cropW
        writer.writeShortLE(crop?.croppedHeight ?: (entry?.image?.height ?: 0)) // cropH
        writer.writeShortLE(clutIndexMap[imgName] ?: 0)             // clutIndex
    }

    return writer.getAsByteArray()
}

// Parse a WAV file and extract raw PCM data. Returns null for non-WAV (e.g. OGG) or non-PCM formats.
fun parseWav(data: ByteArray): AudioData? {
    if (4 > data.size) return null

    // Check RIFF magic
    if (data[0] != 'R'.code.toByte() || data[1] != 'I'.code.toByte() ||
        data[2] != 'F'.code.toByte() || data[3] != 'F'.code.toByte()) return null

    // Check WAVE format at offset 8
    if (12 > data.size) return null
    if (data[8] != 'W'.code.toByte() || data[9] != 'A'.code.toByte() ||
        data[10] != 'V'.code.toByte() || data[11] != 'E'.code.toByte()) return null

    var pos = 12
    var sampleRate = 0
    var channels = 0
    var bitsPerSample = 0
    var pcmData: ByteArray? = null
    var foundFmt = false

    // Walk through chunks
    while (data.size >= pos + 8) {
        val chunkId = ByteArray(4) { data[pos + it] }.decodeToString()
        val chunkSize = readIntLE(data, pos + 4)
        pos += 8

        when (chunkId) {
            "fmt " -> {
                if (pos + 16 > data.size) return null
                val audioFormat = readShortLE(data, pos)
                // Only PCM (format 1) is supported
                if (audioFormat != 1) return null
                channels = readShortLE(data, pos + 2)
                sampleRate = readIntLE(data, pos + 4)
                bitsPerSample = readShortLE(data, pos + 14)
                foundFmt = true
            }
            "data" -> {
                val dataEnd = minOf(pos + chunkSize, data.size)
                pcmData = data.copyOfRange(pos, dataEnd)
            }
        }

        pos += chunkSize
        // Chunks are word-aligned
        if (pos % 2 != 0) pos++
    }

    if (!foundFmt || pcmData == null) return null

    // Convert PCM data to 16-bit samples, then encode as IMA ADPCM
    val pcmSamples: ShortArray = when (bitsPerSample) {
        16 -> {
            // Already 16-bit LE, just reinterpret
            ShortArray(pcmData.size / 2) { i ->
                ((pcmData[i * 2].toInt() and 0xFF) or ((pcmData[i * 2 + 1].toInt() and 0xFF) shl 8)).toShort()
            }
        }
        8 -> {
            // 8-bit unsigned PCM, convert to 16-bit signed
            ShortArray(pcmData.size) { i ->
                ((pcmData[i].toInt() and 0xFF) - 128).let { (it * 257).toShort() }
            }
        }
        else -> return null
    }

    // Downsample to 22050 Hz if the sample rate is higher
    val (downsampledSamples, finalRate) = downsampleTo22050(pcmSamples, sampleRate, channels)

    // Downmix to mono
    val finalSamples = downmixToMono(downsampledSamples, channels)

    val adpcmData = imaAdpcmEncode(finalSamples, 1)
    return AudioData(AUDIO_FORMAT_ADPCM, finalRate, 1, 4, adpcmData, finalSamples.size)
}

private fun readShortLE(data: ByteArray, offset: Int): Int {
    return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
}

private fun readIntLE(data: ByteArray, offset: Int): Int {
    return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}

// Decode an OGG Vorbis file and encode to IMA ADPCM.
// Returns null if the data is not a valid OGG Vorbis file.
fun parseOgg(data: ByteArray): AudioData? {
    val (vorbis, _) = StbVorbis.openMemory(data)
    if (vorbis == null) return null

    val info = vorbis.getInfo()
    val totalSamples = vorbis.streamLengthInSamples()
    if (totalSamples == 0) {
        vorbis.close()
        return null
    }

    // Decode all samples as interleaved floats
    val floatBuf = FloatArray(totalSamples * info.channels)
    val decoded = vorbis.getSamplesFloatInterleaved(info.channels, floatBuf, floatBuf.size)
    vorbis.close()
    if (decoded == 0) return null

    // Convert float samples to 16-bit signed PCM (interleaved)
    val totalFloats = decoded * info.channels
    val pcmSamples = ShortArray(totalFloats)
    for (i in 0 until totalFloats) {
        val clamped = floatBuf[i].coerceIn(-1.0f, 1.0f)
        pcmSamples[i] = (clamped * 32767.0f).toInt().toShort()
    }

    // Downsample to 22050 Hz if the sample rate is higher
    val (downsampledSamples, finalRate) = downsampleTo22050(pcmSamples, info.sampleRate, info.channels)

    // Downmix to mono
    val finalSamples = downmixToMono(downsampledSamples, info.channels)

    // Encode to IMA ADPCM
    val adpcmData = imaAdpcmEncode(finalSamples, 1)
    return AudioData(AUDIO_FORMAT_ADPCM, finalRate, 1, 4, adpcmData, finalSamples.size)
}

// Downsample interleaved PCM samples to 22050 Hz using linear interpolation.
// Returns the original samples unchanged if the sample rate is already 22050 Hz or below.
private fun downsampleTo22050(samples: ShortArray, sampleRate: Int, channels: Int): Pair<ShortArray, Int> {
    val targetRate = 22050
    if (targetRate >= sampleRate) return Pair(samples, sampleRate)

    val frameCount = samples.size / channels
    val ratio = sampleRate.toDouble() / targetRate
    val newFrameCount = (frameCount / ratio).toInt()
    val output = ShortArray(newFrameCount * channels)

    for (f in 0 until newFrameCount) {
        val srcPos = f * ratio
        val srcIdx = srcPos.toInt()
        val frac = (srcPos - srcIdx).toFloat()

        for (ch in 0 until channels) {
            val s0 = samples[srcIdx * channels + ch].toInt()
            val s1 = if (frameCount > srcIdx + 1) samples[(srcIdx + 1) * channels + ch].toInt() else s0
            output[f * channels + ch] = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    return Pair(output, targetRate)
}

// Downmix interleaved multi-channel PCM samples to mono by averaging all channels.
// Returns the original samples unchanged if already mono.
private fun downmixToMono(samples: ShortArray, channels: Int): ShortArray {
    if (1 >= channels) return samples

    val frameCount = samples.size / channels
    val output = ShortArray(frameCount)
    for (f in 0 until frameCount) {
        var sum = 0
        for (ch in 0 until channels) {
            sum += samples[f * channels + ch].toInt()
        }
        output[f] = (sum / channels).coerceIn(-32768, 32767).toShort()
    }
    return output
}

// ===[ IMA ADPCM Encoder ]===

private val IMA_STEP_TABLE = intArrayOf(
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
    34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
    157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
    724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
    3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
    15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
)

private val IMA_INDEX_TABLE = intArrayOf(
    -1, -1, -1, -1, 2, 4, 6, 8,
    -1, -1, -1, -1, 2, 4, 6, 8
)

private fun imaAdpcmEncode(pcmSamples: ShortArray, channels: Int): ByteArray {
    val totalSamples = pcmSamples.size
    // Each sample becomes one 4-bit nibble, packed two per byte
    val adpcmSize = (totalSamples + 1) / 2
    val output = ByteArray(adpcmSize)

    // Per-channel encoder state
    val predictor = IntArray(channels)
    val stepIndex = IntArray(channels)

    var outPos = 0
    var nibbleHigh = false
    for (i in 0 until totalSamples) {
        val ch = i % channels
        val sample = pcmSamples[i].toInt()
        val step = IMA_STEP_TABLE[stepIndex[ch]]

        var diff = sample - predictor[ch]
        var nibble = 0
        if (0 > diff) {
            nibble = 8
            diff = -diff
        }
        if (diff >= step) { nibble = nibble or 4; diff -= step }
        if (diff >= step / 2) { nibble = nibble or 2; diff -= step / 2 }
        if (diff >= step / 4) { nibble = nibble or 1 }

        // Decode the nibble to update predictor (same as decoder would)
        var decodedDiff = step shr 3
        if (nibble and 4 != 0) decodedDiff += step
        if (nibble and 2 != 0) decodedDiff += step shr 1
        if (nibble and 1 != 0) decodedDiff += step shr 2
        if (nibble and 8 != 0) decodedDiff = -decodedDiff

        predictor[ch] = (predictor[ch] + decodedDiff).coerceIn(-32768, 32767)
        stepIndex[ch] = (stepIndex[ch] + IMA_INDEX_TABLE[nibble]).coerceIn(0, 88)

        // Pack nibbles: low nibble first, then high nibble
        if (!nibbleHigh) {
            output[outPos] = (nibble and 0x0F).toByte()
            nibbleHigh = true
        } else {
            output[outPos] = (output[outPos].toInt() or ((nibble and 0x0F) shl 4)).toByte()
            outPos++
            nibbleHigh = false
        }
    }

    return output
}

// Omega Flowey sprite prefixes that should all be packed into the same atlas to reduce VRAM evictions during the fight
private val OMEGA_FLOWEY_PREFIXES = listOf(
    "spr_floweyx_tv",
    "spr_tvinside",
    "spr_lefteye_",
    "spr_flipeye_",
    "spr_floweyx_mouth",
    "spr_floweyx_dimple",
    "spr_mouthball",
    "spr_mouthbeam",
    "spr_mouthflash",
    "spr_floweyx_fleshmound",
    "spr_fleshmound",
    "spr_nostrils",
    "spr_dentata_",
    "spr_halfdentata_",
    "spr_floweyx_flame",
    "spr_floweynuke",
    "spr_venus_placeholder",
    "spr_bgpipe",
    "spr_pipepart",
    "spr_sidestalk",
    "spr_vines_flowey",
    "spr_floweyarm",
    "spr_tv_warning",
    "spr_tv_exface",
    "spr_tv_floweyface",
    "spr_tv_floweyx_",
    "spr_noise",
)

private const val OMEGA_FLOWEY_GROUP = "spr/_omega_flowey"

internal fun getAtlasGroupKey(spriteName: String): String {
    for (prefix in OMEGA_FLOWEY_PREFIXES) {
        if (spriteName.startsWith(prefix)) return OMEGA_FLOWEY_GROUP
    }
    return "spr/$spriteName"
}