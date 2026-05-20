package com.mrpowergamerbr.butterscotchpreprocessor

// A palette-indexed image for PS2 (PSMT4 = 4bpp/16 colors, PSMT8 = 8bpp/256 colors)
class ClutImage(
    val name: String,
    val width: Int,
    val height: Int,
    var bpp: Int,
    var palette: IntArray,  // ARGB colors, sorted by unsigned value, padded to 16 or 256
    var usedColors: Int,    // actual number of used palette entries
    var indices: ByteArray  // one byte per pixel (palette index)
)

// A shared CLUT group (multiple images can reference the same CLUT)
class ClutGroup(
    val id: Int,
    val bpp: Int,
    val colors: Set<Int>,
    val palette: IntArray,
    val imageNames: MutableList<String>
)

object ClutProcessor {
    // NeuQuant requires at least 3*503 = 1509 bytes
    private const val NEUQUANT_MINIMUM = 1509

    fun createClutImage(name: String, img: PixelImage, force4bpp: Boolean): ClutImage {
        val w = img.width
        val h = img.height
        val pixels = img.pixels.copyOf()

        // Normalize transparent pixels to 0x00000000
        for (i in pixels.indices) {
            if ((pixels[i] ushr 24) == 0) pixels[i] = 0
        }

        val uniqueColors = mutableSetOf<Int>()
        for (p in pixels) uniqueColors.add(p)

        if (force4bpp) {
            return if (16 >= uniqueColors.size) {
                buildDirectClut(name, w, h, pixels, uniqueColors, 4)
            } else {
                buildQuantizedClut(name, w, h, pixels, uniqueColors, 4)
            }
        }

        return when {
            16 >= uniqueColors.size -> buildDirectClut(name, w, h, pixels, uniqueColors, 4)
            256 >= uniqueColors.size -> buildDirectClut(name, w, h, pixels, uniqueColors, 8)
            else -> buildQuantizedClut(name, w, h, pixels, uniqueColors, 8)
        }
    }

    private fun buildDirectClut(
        name: String, w: Int, h: Int, pixels: IntArray,
        uniqueColors: Set<Int>, bpp: Int
    ): ClutImage {
        val maxSlots = if (bpp == 4) 16 else 256
        val sorted = uniqueColors.sortedBy { it.toUInt() }.toIntArray()
        val palette = IntArray(maxSlots)
        sorted.copyInto(palette)

        val colorToIndex = HashMap<Int, Int>(sorted.size * 2)
        for ((i, c) in sorted.withIndex()) colorToIndex[c] = i

        val indices = ByteArray(pixels.size) { colorToIndex[pixels[it]]!!.toByte() }
        return ClutImage(name, w, h, bpp, palette, sorted.size, indices)
    }

    private fun buildQuantizedClut(
        name: String,
        w: Int,
        h: Int,
        pixels: IntArray,
        uniqueColors: Set<Int>,
        targetBpp: Int
    ): ClutImage {
        val paletteSlots = if (targetBpp == 4) 16 else 256
        val hasTransparent = uniqueColors.contains(0)
        val opaquePixels = if (hasTransparent) {
            pixels.filter { (it ushr 24) != 0 }.toIntArray()
        } else {
            pixels.copyOf()
        }

        if (opaquePixels.isEmpty()) {
            val palette = IntArray(paletteSlots)
            return ClutImage(name, w, h, targetBpp, palette, 1, ByteArray(pixels.size))
        }

        val maxQuantColors = if (hasTransparent) paletteSlots - 1 else paletteSlots
        val quantPalette = MedianCut.quantize(opaquePixels, maxQuantColors)

        val unsortedPalette = mutableListOf<Int>()
        if (hasTransparent) unsortedPalette.add(0x00000000)
        for (c in quantPalette) unsortedPalette.add(c)

        // Sort canonically by unsigned ARGB to match the rest of the pipeline
        val sortedColors = unsortedPalette.sortedBy { it.toUInt() }.toIntArray()
        val palette = IntArray(paletteSlots)
        sortedColors.copyInto(palette)

        // Cache nearest-color lookups; sprites usually have heavy color repetition
        val cache = HashMap<Int, Int>()
        val indices = ByteArray(pixels.size) { idx ->
            val argb = pixels[idx]
            if ((argb ushr 24) == 0) {
                // sortedColors[0] is 0x00000000 when hasTransparent; otherwise this branch is unreachable
                0
            } else {
                val cached = cache[argb]
                if (cached != null) {
                    cached.toByte()
                } else {
                    val n = nearestOpaqueSortedIdx(argb, sortedColors, hasTransparent)
                    cache[argb] = n
                    n.toByte()
                }
            }
        }

        return ClutImage(name, w, h, targetBpp, palette, sortedColors.size, indices)
    }

    // Nearest-color search by squared RGB distance, skipping the transparent slot.
    fun nearestOpaqueSortedIdx(argb: Int, sortedColors: IntArray, hasTransparent: Boolean): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val start = if (hasTransparent) 1 else 0
        var bestIdx = start
        var bestDist = Int.MAX_VALUE
        for (i in start until sortedColors.size) {
            val c = sortedColors[i]
            val dr = ((c shr 16) and 0xFF) - r
            val dg = ((c shr 8) and 0xFF) - g
            val db = (c and 0xFF) - b
            val d = dr * dr + dg * dg + db * db
            if (bestDist > d) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }


    // Get the set of actually-used colors from a ClutImage's palette
    fun getUsedColorSet(img: ClutImage): Set<Int> {
        val colors = mutableSetOf<Int>()
        for (i in 0 until img.usedColors) {
            colors.add(img.palette[i])
        }
        return colors
    }

    // Structural key for palette dedup that avoids List<Int> boxing overhead on JS
    private class PaletteKey(val palette: IntArray, val usedColors: Int) {
        private val hash: Int = run {
            var h = usedColors
            for (i in 0 until usedColors) {
                h = h * 31 + palette[i]
            }
            h
        }

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (other !is PaletteKey) return false
            if (usedColors != other.usedColors) return false
            for (i in 0 until usedColors) {
                if (palette[i] != other.palette[i]) return false
            }
            return true
        }
    }

    // Deduplicate CLUTs: group images with identical palette color sets
    fun deduplicateCluts(images: List<ClutImage>): List<ClutGroup> {
        val groups = LinkedHashMap<PaletteKey, MutableList<ClutImage>>()

        for (img in images) {
            val key = PaletteKey(img.palette, img.usedColors)
            groups.getOrPut(key) { mutableListOf() }.add(img)
        }

        val clutGroups = mutableListOf<ClutGroup>()
        for ((_, groupImages) in groups) {
            val representative = groupImages[0]
            val group = ClutGroup(
                id = clutGroups.size,
                bpp = representative.bpp,
                colors = getUsedColorSet(representative),
                palette = representative.palette.copyOf(),
                imageNames = groupImages.map { it.name }.toMutableList()
            )
            for (img in groupImages) {
                img.palette = group.palette
            }
            clutGroups.add(group)
        }

        return clutGroups
    }

    // Sorted merge of two sorted IntArrays, returns a new sorted IntArray with unique values.
    // Both inputs must be sorted by unsigned value.
    private fun sortedUnion(a: IntArray, aLen: Int, b: IntArray, bLen: Int): IntArray {
        val result = IntArray(aLen + bLen)
        var ai = 0
        var bi = 0
        var ri = 0
        while (aLen > ai && bLen > bi) {
            val av = a[ai].toUInt()
            val bv = b[bi].toUInt()
            when {
                bv > av -> result[ri++] = a[ai++]
                av > bv -> result[ri++] = b[bi++]
                else -> { result[ri++] = a[ai++]; bi++ }
            }
        }
        while (aLen > ai) result[ri++] = a[ai++]
        while (bLen > bi) result[ri++] = b[bi++]
        return if (ri == result.size) result else result.copyOf(ri)
    }

    // Count the union size of two sorted IntArrays (by unsigned value) with early exit.
    // Returns Int.MAX_VALUE if union would exceed limit.
    private fun sortedUnionSize(a: IntArray, aLen: Int, b: IntArray, bLen: Int, limit: Int): Int {
        var ai = 0
        var bi = 0
        var count = 0
        while (aLen > ai && bLen > bi) {
            val av = a[ai].toUInt()
            val bv = b[bi].toUInt()
            count++
            if (count > limit) return Int.MAX_VALUE
            when {
                bv > av -> ai++
                av > bv -> bi++
                else -> { ai++; bi++ }
            }
        }
        count += (aLen - ai) + (bLen - bi)
        return if (count > limit) Int.MAX_VALUE else count
    }

    // Sort an IntArray by unsigned value in-place
    private fun sortUnsigned(arr: IntArray, len: Int) {
        // Simple insertion sort for small arrays (palettes are at most 256)
        for (i in 1 until len) {
            val key = arr[i]
            val ku = key.toUInt()
            var j = i - 1
            while (j >= 0 && arr[j].toUInt() > ku) {
                arr[j + 1] = arr[j]
                j--
            }
            arr[j + 1] = key
        }
    }

    // Merge CLUTs that have available slots with compatible CLUTs
    fun mergeCluts(images: List<ClutImage>, initialGroups: List<ClutGroup>): List<ClutGroup> {
        // MergeableClut uses a sorted IntArray instead of Set<Int> for fast primitive operations
        class MergeableClut(
            var id: Int,
            val bpp: Int,
            var colors: IntArray,     // sorted by unsigned value
            var colorCount: Int,      // number of used entries in colors
            var imageNames: MutableList<String>,
            var alive: Boolean = true
        )

        // Build name -> ClutImage lookup
        val imageByName = images.associateBy { it.name }

        fun toSortedColors(colors: Set<Int>): IntArray {
            val arr = colors.toIntArray()
            sortUnsigned(arr, arr.size)
            return arr
        }

        // Separate by bpp upfront so we never compare across bpp
        val groups4 = initialGroups.filter { it.bpp == 4 }.map {
            MergeableClut(it.id, it.bpp, toSortedColors(it.colors), it.colors.size, it.imageNames.toMutableList())
        }
        val groups8 = initialGroups.filter { it.bpp == 8 }.map {
            MergeableClut(it.id, it.bpp, toSortedColors(it.colors), it.colors.size, it.imageNames.toMutableList())
        }

        fun mergeByBpp(cluts: List<MergeableClut>, maxSlots: Int) {
            var changed = true
            while (changed) {
                changed = false
                val aliveCluts = cluts.filter { it.alive }.sortedBy { it.colorCount }

                for (i in aliveCluts.indices) {
                    val clutA = aliveCluts[i]
                    if (!clutA.alive) continue
                    if (clutA.colorCount >= maxSlots) continue

                    var bestPartner: MergeableClut? = null
                    var bestUnionSize = Int.MAX_VALUE

                    for (j in aliveCluts.indices) {
                        if (i == j) continue
                        val clutB = aliveCluts[j]
                        if (!clutB.alive) continue

                        val unionSize = sortedUnionSize(
                            clutA.colors, clutA.colorCount,
                            clutB.colors, clutB.colorCount,
                            minOf(maxSlots, bestUnionSize - 1)
                        )

                        if (maxSlots >= unionSize && bestUnionSize > unionSize) {
                            bestUnionSize = unionSize
                            bestPartner = clutB
                            // Perfect subset - can't do better
                            if (unionSize == maxOf(clutA.colorCount, clutB.colorCount)) break
                        }
                    }

                    if (bestPartner != null) {
                        val merged = sortedUnion(
                            bestPartner.colors, bestPartner.colorCount,
                            clutA.colors, clutA.colorCount
                        )
                        val newPalette = IntArray(maxSlots)
                        merged.copyInto(newPalette)

                        for (imgName in bestPartner.imageNames + clutA.imageNames) {
                            val img = imageByName[imgName] ?: continue
                            remapIndices(img, newPalette, merged.size)
                        }

                        bestPartner.colors = merged
                        bestPartner.colorCount = merged.size
                        bestPartner.imageNames.addAll(clutA.imageNames)
                        clutA.alive = false
                        changed = true
                    }
                }
            }
        }

        mergeByBpp(groups4, 16)
        mergeByBpp(groups8, 256)

        // Build final ClutGroups
        val finalGroups = mutableListOf<ClutGroup>()
        for (clut in groups4 + groups8) {
            if (!clut.alive) continue
            val palette = IntArray(if (clut.bpp == 4) 16 else 256)
            clut.colors.copyInto(palette, 0, 0, clut.colorCount)
            finalGroups.add(
                ClutGroup(
                    id = finalGroups.size,
                    bpp = clut.bpp,
                    colors = clut.colors.take(clut.colorCount).toSet(),
                    palette = palette,
                    imageNames = clut.imageNames
                )
            )
        }

        return finalGroups
    }

    private fun remapIndices(img: ClutImage, newPalette: IntArray, newUsedColors: Int) {
        val oldPalette = img.palette

        // Build old index -> new index lookup table (avoids HashMap boxing)
        val oldUsed = img.usedColors
        val remapTable = IntArray(oldUsed) { oldIdx ->
            val color = oldPalette[oldIdx]
            // Binary search in the new palette (sorted by unsigned value)
            var lo = 0
            var hi = newUsedColors - 1
            var found = oldIdx // fallback
            val uv = color.toUInt()
            while (hi >= lo) {
                val mid = (lo + hi) ushr 1
                val mv = newPalette[mid].toUInt()
                when {
                    uv > mv -> lo = mid + 1
                    mv > uv -> hi = mid - 1
                    else -> { found = mid; break }
                }
            }
            found
        }

        for (i in img.indices.indices) {
            val oldIdx = img.indices[i].toInt() and 0xFF
            if (oldUsed > oldIdx) {
                img.indices[i] = remapTable[oldIdx].toByte()
            }
        }

        img.palette = newPalette
        img.usedColors = newUsedColors
    }

    // Render a ClutImage back to PixelImage for verification
    fun renderClutImage(img: ClutImage): PixelImage {
        val pixels = IntArray(img.width * img.height)
        for (i in pixels.indices) {
            val idx = img.indices[i].toInt() and 0xFF
            pixels[i] = img.palette[idx]
        }
        return PixelImage(img.width, img.height, pixels)
    }
}
