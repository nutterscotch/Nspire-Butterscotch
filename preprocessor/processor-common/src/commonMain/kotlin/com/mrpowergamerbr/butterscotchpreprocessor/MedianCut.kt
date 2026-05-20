package com.mrpowergamerbr.butterscotchpreprocessor

object MedianCut {
    // Median-cut color quantization. Returns up to maxColors ARGB entries (alpha = 0xFF).
    fun quantize(pixels: IntArray, maxColors: Int): IntArray {
        if (pixels.isEmpty() || maxColors <= 0) return IntArray(0)

        val initial = Box(pixels.copyOf())
        val boxes = mutableListOf(initial)

        while (maxColors > boxes.size) {
            // Select the box that contributes the most quantization error: pixelCount * longestRange.
            // Pure range-based selection over-splits dominant colors; pixelCount weights toward
            // boxes that lots of output pixels will draw from.
            var target: Box? = null
            var bestScore = 0L
            for (b in boxes) {
                if (2 > b.pixels.size || b.longestRange == 0) continue
                val score = b.pixels.size.toLong() * b.longestRange
                if (score > bestScore) {
                    bestScore = score
                    target = b
                }
            }
            if (target == null) break
            val split = target.split() ?: run {
                // Mark this box as unsplittable so we stop trying it
                target.unsplittable = true
                continue
            }
            boxes.remove(target)
            boxes.add(split.first)
            boxes.add(split.second)
        }

        return IntArray(boxes.size) { boxes[it].average() }
    }

    private class Box(val pixels: IntArray) {
        var rMin = 255; var rMax = 0
        var gMin = 255; var gMax = 0
        var bMin = 255; var bMax = 0
        var unsplittable = false

        init {
            for (p in pixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (rMin > r) rMin = r
                if (r > rMax) rMax = r
                if (gMin > g) gMin = g
                if (g > gMax) gMax = g
                if (bMin > b) bMin = b
                if (b > bMax) bMax = b
            }
        }

        val rRange get() = rMax - rMin
        val gRange get() = gMax - gMin
        val bRange get() = bMax - bMin
        val longestRange get() = if (unsplittable) 0 else maxOf(rRange, gRange, bRange)

        fun average(): Int {
            var rSum = 0L
            var gSum = 0L
            var bSum = 0L
            for (p in pixels) {
                rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF
                bSum += p and 0xFF
            }
            val n = pixels.size
            val r = (rSum / n).toInt()
            val g = (gSum / n).toInt()
            val b = (bSum / n).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        // Split at a real value boundary on the widest channel. Returns null when every pixel in
        // the box has the same value on that channel (no real split exists).
        fun split(): Pair<Box, Box>? {
            val keyOf: (Int) -> Int = when {
                rRange >= gRange && rRange >= bRange -> { p -> (p shr 16) and 0xFF }
                gRange >= bRange -> { p -> (p shr 8) and 0xFF }
                else -> { p -> p and 0xFF }
            }
            val sorted = pixels.toTypedArray().also { it.sortBy(keyOf) }.toIntArray()

            val targetIdx = sorted.size / 2
            val targetKey = keyOf(sorted[targetIdx])

            // Walk outward from targetIdx until the key changes, so the split lands between
            // distinct color values rather than slicing a run of identical pixels in half.
            var fwd = targetIdx
            while (sorted.size > fwd && keyOf(sorted[fwd]) == targetKey) fwd++
            var bwd = targetIdx
            while (bwd > 0 && keyOf(sorted[bwd - 1]) == targetKey) bwd--

            val splitIdx = when {
                bwd > 0 && sorted.size > fwd -> if (targetIdx - bwd <= fwd - targetIdx) bwd else fwd
                bwd > 0 -> bwd
                sorted.size > fwd -> fwd
                else -> return null
            }

            return Box(sorted.copyOfRange(0, splitIdx)) to Box(sorted.copyOfRange(splitIdx, sorted.size))
        }
    }
}