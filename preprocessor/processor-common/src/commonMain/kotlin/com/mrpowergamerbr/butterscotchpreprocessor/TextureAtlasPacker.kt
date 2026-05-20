package com.mrpowergamerbr.butterscotchpreprocessor

data class AtlasEntry(
    val image: ClutImage,
    val x: Int,
    val y: Int
)

class TextureAtlas(
    val id: Int,
    val bpp: Int,
    val width: Int,
    val height: Int,
    val entries: MutableList<AtlasEntry> = mutableListOf()
)

object TextureAtlasPacker {
    const val DEFAULT_ATLAS_SIZE = 512

    // Pack ClutImages into texture atlases, grouped by groupKey and separated by bpp.
    fun packAtlases(images: List<ClutImage>, groupKeys: Map<String, String>, atlasSize: Int): List<TextureAtlas> {
        // Separate by bpp
        val by4bpp = images.filter { it.bpp == 4 }
        val by8bpp = images.filter { it.bpp == 8 }

        println("  packAtlases: ${by4bpp.size} 4bpp images, ${by8bpp.size} 8bpp images")

        val atlases = mutableListOf<TextureAtlas>()
        val packers = mutableListOf<MaxRectsPacker>()
        println("  Packing 4bpp...")
        packByBpp(by4bpp, 4, groupKeys, atlases, packers, atlasSize)
        println("  Packing 8bpp...")
        packByBpp(by8bpp, 8, groupKeys, atlases, packers, atlasSize)
        println("  Packing done.")
        return atlases
    }

    private fun packByBpp(
        images: List<ClutImage>,
        bpp: Int,
        groupKeys: Map<String, String>,
        atlases: MutableList<TextureAtlas>,
        packers: MutableList<MaxRectsPacker>,
        atlasSize: Int
    ) {
        if (images.isEmpty()) return

        // Group images by their group key
        val groups = LinkedHashMap<String, MutableList<ClutImage>>()
        for (img in images) {
            val key = groupKeys[img.name] ?: img.name
            groups.getOrPut(key) { mutableListOf() }.add(img)
        }

        // Sort groups by total area descending (pack large groups first for better utilization)
        val sortedGroups = groups.entries.sortedByDescending { entry ->
            entry.value.sumOf { it.width * it.height }
        }

        for ((groupIdx, entry) in sortedGroups.withIndex()) {
            val (_, groupImages) = entry
            if (groupIdx % 100 == 0) {
                println("    Group $groupIdx/${sortedGroups.size} (${groupImages.size} images, ${atlases.size} atlases so far)")
            }

            // Sort images within group by height descending for better packing
            val sorted = groupImages.sortedByDescending { maxOf(it.width, it.height) }

            // Try to fit the entire group into one existing atlas (same bpp)
            var packed = false
            for (i in atlases.indices) {
                val atlas = atlases[i]
                if (atlas.bpp != bpp) continue
                val packer = packers[i]

                // Speculatively try on a clone of the packer
                val clonedPacker = packer.clone()
                val placements = mutableListOf<Pair<ClutImage, Pair<Int, Int>>>()
                var allFit = true
                for (img in sorted) {
                    val pos = clonedPacker.insert(img.width, img.height)
                    if (pos == null) {
                        allFit = false
                        break
                    }
                    placements.add(img to pos)
                }

                if (allFit) {
                    // Commit: replace the packer with the clone and add entries
                    packers[i] = clonedPacker
                    for ((img, pos) in placements) {
                        atlas.entries.add(AtlasEntry(img, pos.first, pos.second))
                    }
                    packed = true
                    break
                }
            }

            if (!packed) {
                // Create new atlas(es) for this group
                val remaining = sorted.toMutableList()
                while (remaining.isNotEmpty()) {
                    val atlas = TextureAtlas(atlases.size, bpp, atlasSize, atlasSize)
                    val packer = MaxRectsPacker(atlasSize, atlasSize)

                    val iterator = remaining.iterator()
                    while (iterator.hasNext()) {
                        val img = iterator.next()
                        val pos = packer.insert(img.width, img.height)
                        if (pos != null) {
                            atlas.entries.add(AtlasEntry(img, pos.first, pos.second))
                            iterator.remove()
                        }
                    }

                    if (atlas.entries.isEmpty()) {
                        break
                    }
                    atlases.add(atlas)
                    packers.add(packer)
                }
            }
        }
    }
}

// MaxRects bin packing algorithm (Best Short Side Fit heuristic)
class MaxRectsPacker(private val binWidth: Int, private val binHeight: Int) {
    private val freeRects = mutableListOf(Rect(0, 0, binWidth, binHeight))

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    // Private constructor for cloning
    private constructor(binWidth: Int, binHeight: Int, freeRects: List<Rect>) : this(binWidth, binHeight) {
        this.freeRects.clear()
        this.freeRects.addAll(freeRects)
    }

    fun clone(): MaxRectsPacker = MaxRectsPacker(binWidth, binHeight, freeRects)

    fun insert(w: Int, h: Int): Pair<Int, Int>? {
        var bestX = -1
        var bestY = -1
        var bestShortSide = Int.MAX_VALUE
        var bestLongSide = Int.MAX_VALUE

        for (rect in freeRects) {
            if (rect.width >= w && rect.height >= h) {
                val leftoverHoriz = rect.width - w
                val leftoverVert = rect.height - h
                val shortSide = minOf(leftoverHoriz, leftoverVert)
                val longSide = maxOf(leftoverHoriz, leftoverVert)
                if (shortSide < bestShortSide || (shortSide == bestShortSide && bestLongSide > longSide)) {
                    bestX = rect.x
                    bestY = rect.y
                    bestShortSide = shortSide
                    bestLongSide = longSide
                }
            }
        }

        if (bestX == -1) return null

        val placed = Rect(bestX, bestY, w, h)
        splitFreeRects(placed)
        pruneFreeRects()
        return bestX to bestY
    }

    private fun splitFreeRects(used: Rect) {
        val newRects = mutableListOf<Rect>()
        val iterator = freeRects.iterator()
        while (iterator.hasNext()) {
            val free = iterator.next()
            if (!overlaps(free, used)) continue
            iterator.remove()

            // Left strip
            if (free.x < used.x) {
                newRects.add(Rect(free.x, free.y, used.x - free.x, free.height))
            }
            // Right strip
            if (free.x + free.width > used.x + used.width) {
                newRects.add(Rect(used.x + used.width, free.y, free.x + free.width - used.x - used.width, free.height))
            }
            // Top strip
            if (free.y < used.y) {
                newRects.add(Rect(free.x, free.y, free.width, used.y - free.y))
            }
            // Bottom strip
            if (free.y + free.height > used.y + used.height) {
                newRects.add(Rect(free.x, used.y + used.height, free.width, free.y + free.height - used.y - used.height))
            }
        }
        freeRects.addAll(newRects)
    }

    private fun overlaps(a: Rect, b: Rect): Boolean {
        return a.x < b.x + b.width && a.x + a.width > b.x &&
                a.y < b.y + b.height && a.y + a.height > b.y
    }

    private fun pruneFreeRects() {
        // Remove any free rect that is fully contained within another
        val size = freeRects.size
        var writeIdx = 0
        outer@ for (i in 0 until size) {
            val rectI = freeRects[i]
            for (j in 0 until size) {
                if (i != j && contains(freeRects[j], rectI)) {
                    continue@outer
                }
            }
            freeRects[writeIdx++] = rectI
        }
        // Trim the list in place
        while (freeRects.size > writeIdx) {
            freeRects.removeAt(freeRects.lastIndex)
        }
    }

    private fun contains(outer: Rect, inner: Rect): Boolean {
        return outer.x <= inner.x && outer.y <= inner.y &&
                outer.x + outer.width >= inner.x + inner.width &&
                outer.y + outer.height >= inner.y + inner.height
    }
}
