package com.mrpowergamerbr.butterscotchpreprocessor

import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

// Converts texture atlas .BIN files back to .PNG for debugging
object BinToPngConverter {
    @JvmStatic
    fun main(args: Array<String>) {
        val inputDir = if (args.isNotEmpty()) File(args[0]) else {
            println("Usage: BinToPngConverter <path/to/bin/directory> [output/directory]")
            return
        }
        val outputDir = if (args.size > 1) File(args[1]) else File(inputDir, "bin2png_output")
        outputDir.mkdirs()

        // Load CLUTs
        val clut4Palettes = loadClutFile(File(inputDir, "CLUT4.BIN"), 16)
        val clut8Palettes = loadClutFile(File(inputDir, "CLUT8.BIN"), 256)
        println("Loaded ${clut4Palettes.size} 4bpp CLUTs, ${clut8Palettes.size} 8bpp CLUTs")

        // Load ATLAS.BIN
        val atlasFile = File(inputDir, "ATLAS.BIN")
        if (!atlasFile.exists()) {
            println("ATLAS.BIN not found, will render atlases without CLUT mapping")
        }
        val atlasEntries = if (atlasFile.exists()) loadAtlasMetadata(atlasFile) else null

        // Find and convert TEXn.BIN files
        val texFiles = inputDir.listFiles { f -> f.name.matches(Regex("TEX\\d+\\.BIN")) }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (texFiles.isEmpty()) {
            println("No TEXn.BIN files found in ${inputDir.path}")
            return
        }

        for (texFile in texFiles) {
            val atlasId = Regex("TEX(\\d+)\\.BIN").find(texFile.name)!!.groupValues[1].toInt()
            println("Converting ${texFile.name}...")

            val tex = loadTexture(texFile)

            // Collect all entries for this atlas from ATLAS.BIN
            val entries = atlasEntries?.filter { it.atlasId == atlasId } ?: emptyList()

            val img = renderTexture(tex, entries, clut4Palettes, clut8Palettes)
            val outFile = File(outputDir, "TEX${atlasId}.png")
            ImageIO.write(img, "PNG", outFile)
            println("  -> ${outFile.path} (${tex.width}x${tex.height}, ${tex.bpp}bpp)")
        }

        println("Done!")
    }

    private data class TexData(
        val width: Int,
        val height: Int,
        val bpp: Int,
        val pixelData: ByteArray
    )

    data class AtlasEntryInfo(
        val atlasId: Int,
        val atlasX: Int,
        val atlasY: Int,
        val width: Int,
        val height: Int,
        val cropX: Int,
        val cropY: Int,
        val cropW: Int,
        val cropH: Int,
        val clutIndex: Int,
        val bpp: Int
    )

    private fun loadTexture(file: File): TexData {
        val data = file.readBytes()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val version = buf.get().toInt() and 0xFF
        val width = buf.getShort().toInt() and 0xFFFF
        val height = buf.getShort().toInt() and 0xFFFF
        val bpp = buf.get().toInt() and 0xFF
        val pixelDataSize = buf.getInt()

        val headerSize = 128
        val pixelData = data.copyOfRange(headerSize, headerSize + pixelDataSize)
        return TexData(width, height, bpp, pixelData)
    }

    private fun loadClutFile(file: File, paletteSize: Int): List<IntArray> {
        if (!file.exists()) return emptyList()

        val data = file.readBytes()
        val entryBytes = paletteSize * 4
        val count = data.size / entryBytes
        val palettes = mutableListOf<IntArray>()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until count) {
            var palette = IntArray(paletteSize)
            for (j in 0 until paletteSize) {
                palette[j] = convertPS2RGBAtoARGB(buf.getInt())
            }
            // Unswizzle 8bpp palettes (reverse CSM1 swizzle)
            if (paletteSize == 256) {
                palette = unswizzlePalette8bpp(palette)
            }
            palettes.add(palette)
        }

        return palettes
    }

    // Reverse of convertARGBtoPS2RGBA: PS2 RGBA (alpha 0-128) back to standard ARGB (alpha 0-255)
    private fun convertPS2RGBAtoARGB(ps2Color: Int): Int {
        val ps2Alpha = (ps2Color ushr 24) and 0xFF
        val b = (ps2Color ushr 16) and 0xFF
        val g = (ps2Color ushr 8) and 0xFF
        val r = ps2Color and 0xFF
        // Reverse alpha remap: 0-128 -> 0-255 (clamped)
        val alpha = minOf(ps2Alpha shl 1, 255)
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    // Reverse PS2 CSM1 CLUT swizzle for 8bpp
    private fun unswizzlePalette8bpp(palette: IntArray): IntArray {
        val unswizzled = palette.copyOf()
        for (i in unswizzled.indices) {
            if ((i and 0x18) == 8) {
                val tmp = unswizzled[i]
                unswizzled[i] = unswizzled[i + 8]
                unswizzled[i + 8] = tmp
            }
        }
        return unswizzled
    }

    private fun loadAtlasMetadata(file: File): List<AtlasEntryInfo> {
        val data = file.readBytes()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val version = buf.get().toInt() and 0xFF
        val tpagCount = buf.getShort().toInt() and 0xFFFF
        val tileCount = buf.getShort().toInt() and 0xFFFF
        val atlasCount = buf.getShort().toInt() and 0xFFFF

        // Skip atlas offset table
        for (i in 0 until atlasCount) {
            buf.getInt()
        }

        val entries = mutableListOf<AtlasEntryInfo>()

        // Read TPAG entries (21 bytes each)
        for (i in 0 until tpagCount) {
            val atlasId = buf.getShort().toInt() and 0xFFFF
            val atlasX = buf.getShort().toInt() and 0xFFFF
            val atlasY = buf.getShort().toInt() and 0xFFFF
            val width = buf.getShort().toInt() and 0xFFFF
            val height = buf.getShort().toInt() and 0xFFFF
            val cropX = buf.getShort().toInt() and 0xFFFF
            val cropY = buf.getShort().toInt() and 0xFFFF
            val cropW = buf.getShort().toInt() and 0xFFFF
            val cropH = buf.getShort().toInt() and 0xFFFF
            val clutIndex = buf.getShort().toInt() and 0xFFFF
            val bpp = buf.get().toInt() and 0xFF

            if (atlasId != 0xFFFF) {
                entries.add(AtlasEntryInfo(atlasId, atlasX, atlasY, width, height, cropX, cropY, cropW, cropH, clutIndex, bpp))
            }
        }

        // Read tile entries (31 bytes each)
        for (i in 0 until tileCount) {
            buf.getShort() // bgDef - skip
            buf.getShort() // srcX - skip
            buf.getShort() // srcY - skip
            buf.getShort() // srcW - skip
            buf.getShort() // srcH - skip
            val atlasId = buf.getShort().toInt() and 0xFFFF
            val atlasX = buf.getShort().toInt() and 0xFFFF
            val atlasY = buf.getShort().toInt() and 0xFFFF
            val width = buf.getShort().toInt() and 0xFFFF
            val height = buf.getShort().toInt() and 0xFFFF
            val cropX = buf.getShort().toInt() and 0xFFFF
            val cropY = buf.getShort().toInt() and 0xFFFF
            val cropW = buf.getShort().toInt() and 0xFFFF
            val cropH = buf.getShort().toInt() and 0xFFFF
            val clutIndex = buf.getShort().toInt() and 0xFFFF
            val bpp = buf.get().toInt() and 0xFF

            if (atlasId != 0xFFFF) {
                entries.add(AtlasEntryInfo(atlasId, atlasX, atlasY, width, height, cropX, cropY, cropW, cropH, clutIndex, bpp))
            }
        }

        println("Loaded ATLAS.BIN: $tpagCount TPAG entries, $tileCount tile entries, $atlasCount atlases, ${entries.size} mapped")
        return entries
    }

    // Render an entire texture atlas to a BufferedImage using per-entry CLUTs
    private fun renderTexture(
        tex: TexData,
        entries: List<AtlasEntryInfo>,
        clut4Palettes: List<IntArray>,
        clut8Palettes: List<IntArray>
    ): BufferedImage {
        val img = BufferedImage(tex.width, tex.height, BufferedImage.TYPE_INT_ARGB)

        if (entries.isEmpty()) {
            // No atlas metadata - render with a grayscale palette as fallback
            val fallbackPalette = IntArray(256) { i ->
                (0xFF shl 24) or (i shl 16) or (i shl 8) or i
            }
            renderRegion(img, tex, 0, 0, tex.width, tex.height, fallbackPalette)
        } else {
            // Render each entry with its own CLUT
            for (entry in entries) {
                val palettes = if (entry.bpp == 4) clut4Palettes else clut8Palettes
                if (entry.clutIndex >= palettes.size) continue
                val palette = palettes[entry.clutIndex]
                renderRegion(img, tex, entry.atlasX, entry.atlasY, entry.width, entry.height, palette)
            }
        }

        return img
    }

    // Render a rectangular region of the texture using the given palette
    private fun renderRegion(
        img: BufferedImage,
        tex: TexData,
        x: Int, y: Int, w: Int, h: Int,
        palette: IntArray
    ) {
        for (py in 0 until h) {
            val imgY = y + py
            if (tex.height <= imgY) break
            for (px in 0 until w) {
                val imgX = x + px
                if (tex.width <= imgX) break

                val pixelIndex = imgY * tex.width + imgX
                val paletteIdx = getPixelIndex(tex, pixelIndex)
                if (palette.size > paletteIdx) {
                    img.setRGB(imgX, imgY, palette[paletteIdx])
                }
            }
        }
    }

    // Get the palette index for a pixel at the given linear position
    private fun getPixelIndex(tex: TexData, pixelIndex: Int): Int {
        return if (tex.bpp == 4) {
            val byteIdx = pixelIndex / 2
            if (byteIdx >= tex.pixelData.size) return 0
            val b = tex.pixelData[byteIdx].toInt() and 0xFF
            if (pixelIndex % 2 == 0) b and 0x0F else (b ushr 4) and 0x0F
        } else {
            if (pixelIndex >= tex.pixelData.size) return 0
            tex.pixelData[pixelIndex].toInt() and 0xFF
        }
    }

}
