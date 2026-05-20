package com.mrpowergamerbr.butterscotchpreprocessor

fun bzip2Decompress(input: ByteArray): ByteArray {
    val br = BitReader(input)
    if (br.readBits(8) != 'B'.code) throw IllegalStateException("Not a bzip2 stream: missing 'B'")
    if (br.readBits(8) != 'Z'.code) throw IllegalStateException("Not a bzip2 stream: missing 'Z'")
    if (br.readBits(8) != 'h'.code) throw IllegalStateException("Not a bzip2 stream: missing 'h'")
    val level = br.readBits(8) - '0'.code
    if (level !in 1..9) throw IllegalStateException("Invalid bzip2 block size level: $level")
    val blockSize = 100000 * level

    var out = ByteArray(maxOf(64, input.size * 4))
    var outLen = 0

    while (true) {
        val magicHi = br.readBits(24)
        val magicLo = br.readBits(24)
        val magic = (magicHi.toLong() shl 24) or (magicLo.toLong() and 0xFFFFFF)
        if (magic == 0x177245385090L) {
            br.readBits(32) // stream CRC, skip
            // there could be another stream concatenated, but we stop here
            break
        }
        if (magic != 0x314159265359L) throw IllegalStateException("Invalid block magic: ${magic.toString(16)}")

        br.readBits(32) // block CRC, skip
        val randomised = br.readBits(1)
        val origPtr = br.readBits(24)

        // Symbol map
        val mapL1 = br.readBits(16)
        val seqToUnseq = IntArray(256)
        var numInUse = 0
        for (i in 0 until 16) {
            if ((mapL1 and (1 shl (15 - i))) != 0) {
                val sub = br.readBits(16)
                for (j in 0 until 16) {
                    if ((sub and (1 shl (15 - j))) != 0) {
                        seqToUnseq[numInUse++] = i * 16 + j
                    }
                }
            }
        }
        if (numInUse == 0) throw IllegalStateException("numInUse == 0")
        val alphaSize = numInUse + 2

        val numTrees = br.readBits(3)
        if (numTrees !in 2..6) throw IllegalStateException("Invalid numTrees: $numTrees")
        val numSelectors = br.readBits(15)
        if (numSelectors < 1) throw IllegalStateException("Invalid numSelectors: $numSelectors")

        val selectorsMtf = IntArray(numSelectors)
        for (i in 0 until numSelectors) {
            var j = 0
            while (br.readBits(1) == 1) {
                j++
                if (j >= numTrees) throw IllegalStateException("Selector MTF index out of range")
            }
            selectorsMtf[i] = j
        }

        // Inverse MTF the selectors
        val pos = IntArray(numTrees) { it }
        val selectors = IntArray(numSelectors)
        for (i in 0 until numSelectors) {
            var v = selectorsMtf[i]
            val tmp = pos[v]
            while (v > 0) {
                pos[v] = pos[v - 1]
                v--
            }
            pos[0] = tmp
            selectors[i] = tmp
        }

        // Read code lengths for each tree
        val codeLens = Array(numTrees) { IntArray(alphaSize) }
        for (t in 0 until numTrees) {
            var curr = br.readBits(5)
            for (s in 0 until alphaSize) {
                while (true) {
                    if (curr !in 1..20) throw IllegalStateException("Invalid code length: $curr")
                    if (br.readBits(1) == 0) break
                    if (br.readBits(1) == 1) curr-- else curr++
                }
                codeLens[t][s] = curr
            }
        }

        // Build canonical Huffman decoders
        val limits = Array(numTrees) { IntArray(21) }
        val bases = Array(numTrees) { IntArray(21) }
        val perms = Array(numTrees) { IntArray(alphaSize) }
        val minLens = IntArray(numTrees)
        for (t in 0 until numTrees) {
            val lens = codeLens[t]
            var minLen = 32
            var maxLen = 0
            for (l in lens) {
                if (maxLen > l) {} else maxLen = l
                if (l > minLen) {} else minLen = l
            }
            minLens[t] = minLen
            // perm sorted by (length, original index)
            val perm = perms[t]
            var pp = 0
            for (l in minLen..maxLen) {
                for (s in 0 until alphaSize) {
                    if (lens[s] == l) perm[pp++] = s
                }
            }
            // count of codes per length
            val length = IntArray(maxLen + 2)
            for (s in 0 until alphaSize) length[lens[s]]++
            // base/limit canonical construction
            val base = bases[t]
            val limit = limits[t]
            var code = 0
            var idx = 0
            for (l in minLen..maxLen) {
                val n = length[l]
                limit[l] = code + n - 1
                base[l] = code - idx
                idx += n
                code = (code + n) shl 1
            }
        }

        // Decode MTF/RLE2 stream
        val mtf = IntArray(numInUse) { it }
        val bwtInput = ByteArray(blockSize)
        var bwtLen = 0
        val EOB = numInUse + 1

        var groupPos = 0
        var groupNo = -1
        var currTree = 0
        var runAccum = 0
        var runShift = 0

        while (true) {
            if (groupPos == 0) {
                groupNo++
                if (groupNo >= numSelectors) throw IllegalStateException("Ran out of selectors")
                currTree = selectors[groupNo]
                groupPos = 50
            }
            groupPos--

            val limit = limits[currTree]
            val base = bases[currTree]
            val perm = perms[currTree]
            var len = minLens[currTree]
            var code = br.readBits(len)
            while (code > limit[len]) {
                len++
                if (len >= limit.size) throw IllegalStateException("Huffman decode overflow")
                code = (code shl 1) or br.readBits(1)
            }
            val sym = perm[code - base[len]]

            if (sym == EOB) break

            if (sym == 0 || sym == 1) {
                runAccum += (sym + 1) shl runShift
                runShift++
            } else {
                // Flush any pending run of MTF[0]
                if (runShift > 0) {
                    val b = (seqToUnseq[mtf[0]] and 0xFF).toByte()
                    if (bwtLen + runAccum > blockSize) throw IllegalStateException("BWT block overflow")
                    repeat(runAccum) { bwtInput[bwtLen++] = b }
                    runAccum = 0
                    runShift = 0
                }
                val mtfIdx = sym - 1
                if (mtfIdx >= numInUse) throw IllegalStateException("MTF index out of range")
                val v = mtf[mtfIdx]
                var k = mtfIdx
                while (k > 0) {
                    mtf[k] = mtf[k - 1]
                    k--
                }
                mtf[0] = v
                if (bwtLen >= blockSize) throw IllegalStateException("BWT block overflow")
                bwtInput[bwtLen++] = (seqToUnseq[v] and 0xFF).toByte()
            }
        }
        // Flush trailing run
        if (runShift > 0) {
            val b = (seqToUnseq[mtf[0]] and 0xFF).toByte()
            if (bwtLen + runAccum > blockSize) throw IllegalStateException("BWT block overflow")
            repeat(runAccum) { bwtInput[bwtLen++] = b }
        }

        if (origPtr >= bwtLen) throw IllegalStateException("origPtr out of range: $origPtr >= $bwtLen")

        // Randomisation pass: flip low bit of selected bytes per BZ2_rNums schedule
        if (randomised != 0) {
            var rNToGo = 0
            var rTPos = 0
            for (i in 0 until bwtLen) {
                if (rNToGo == 1) bwtInput[i] = (bwtInput[i].toInt() xor 1).toByte()
                rNToGo--
                if (rNToGo == 0) {
                    rNToGo = BZ2_R_NUMS[rTPos]
                    rTPos++
                    if (rTPos == 512) rTPos = 0
                }
            }
        }

        // Inverse BWT
        val cftab = IntArray(257)
        for (i in 0 until bwtLen) cftab[(bwtInput[i].toInt() and 0xFF) + 1]++
        for (i in 1..256) cftab[i] += cftab[i - 1]
        val tt = IntArray(bwtLen)
        for (i in 0 until bwtLen) {
            val c = bwtInput[i].toInt() and 0xFF
            tt[cftab[c]] = i
            cftab[c]++
        }

        // Inverse RLE-1 while iterating BWT output
        var idx = origPtr
        var remaining = bwtLen
        var lastByte = -1
        var runCount = 0
        while (remaining > 0) {
            idx = tt[idx]
            val b = bwtInput[idx].toInt() and 0xFF
            remaining--

            if (runCount == 4) {
                // b is the count of additional copies (0..251)
                if (b > 251) throw IllegalStateException("RLE-1 count too big: $b")
                if (outLen + b > out.size) out = out.copyOf(maxOf(out.size * 2, outLen + b + 16))
                val lb = lastByte.toByte()
                repeat(b) { out[outLen++] = lb }
                runCount = 0
                lastByte = -1
                continue
            }

            if (outLen >= out.size) out = out.copyOf(out.size * 2)
            out[outLen++] = b.toByte()

            if (b == lastByte) {
                runCount++
            } else {
                runCount = 1
                lastByte = b
            }
        }
    }

    return if (outLen == out.size) out else out.copyOf(outLen)
}

private class BitReader(private val data: ByteArray) {
    private var pos = 0
    private var buf = 0
    private var bitsLeft = 0

    fun readBits(n: Int): Int {
        if (n == 0) return 0
        if (n > 24) {
            val hi = readBits(n - 16)
            val lo = readBits(16)
            return (hi shl 16) or lo
        }
        while (bitsLeft < n) {
            if (pos >= data.size) throw IllegalStateException("Unexpected end of bzip2 stream")
            buf = (buf shl 8) or (data[pos].toInt() and 0xFF)
            pos++
            bitsLeft += 8
        }
        val result = (buf ushr (bitsLeft - n)) and ((1 shl n) - 1)
        bitsLeft -= n
        // Keep buf bounded to avoid sign issues across many reads
        buf = buf and ((1 shl bitsLeft) - 1).let { if (bitsLeft >= 31) -1 else it }
        return result
    }
}

private val BZ2_R_NUMS = intArrayOf(
    619, 720, 127, 481, 931, 816, 813, 233, 566, 247,
    985, 724, 205, 454, 863, 491, 741, 242, 949, 214,
    733, 859, 335, 708, 621, 574,  73, 654, 730, 472,
    419, 436, 278, 496, 867, 210, 399, 680, 480,  51,
    878, 465, 811, 169, 869, 675, 611, 697, 867, 561,
    862, 687, 507, 283, 482, 129, 807, 591, 733, 623,
    150, 238,  59, 379, 684, 877, 625, 169, 643, 105,
    170, 607, 520, 932, 727, 476, 693, 425, 174, 647,
     73, 122, 335, 530, 442, 853, 695, 249, 445, 515,
    909, 545, 703, 919, 874, 474, 882, 500, 594, 612,
    641, 801, 220, 162, 819, 984, 589, 513, 495, 799,
    161, 604, 958, 533, 221, 400, 386, 867, 600, 782,
    382, 596, 414, 171, 516, 375, 682, 485, 911, 276,
     98, 553, 163, 354, 666, 933, 424, 341, 533, 870,
    227, 730, 475, 186, 263, 647, 537, 686, 600, 224,
    469,  68, 770, 919, 190, 373, 294, 822, 808, 206,
    184, 943, 795, 384, 383, 461, 404, 758, 839, 887,
    715,  67, 618, 276, 204, 918, 873, 777, 604, 560,
    951, 160, 578, 722,  79, 804,  96, 409, 713, 940,
    652, 934, 970, 447, 318, 353, 859, 672, 112, 785,
    645, 863, 803, 350, 139,  93, 354,  99, 820, 908,
    609, 772, 154, 274, 580, 184,  79, 626, 630, 742,
    653, 282, 762, 623, 680,  81, 927, 626, 789, 125,
    411, 521, 938, 300, 821,  78, 343, 175, 128, 250,
    170, 774, 972, 275, 999, 639, 495,  78, 352, 126,
    857, 956, 358, 619, 580, 124, 737, 594, 701, 612,
    669, 112, 134, 694, 363, 992, 809, 743, 168, 974,
    944, 375, 748,  52, 600, 747, 642, 182, 862,  81,
    344, 805, 988, 739, 511, 655, 814, 334, 249, 515,
    897, 955, 664, 981, 649, 113, 974, 459, 893, 228,
    433, 837, 553, 268, 926, 240, 102, 654, 459,  51,
    686, 754, 806, 760, 493, 403, 415, 394, 687, 700,
    946, 670, 656, 610, 738, 392, 760, 799, 887, 653,
    978, 321, 576, 617, 626, 502, 894, 679, 243, 440,
    680, 879, 194, 572, 640, 724, 926,  56, 204, 700,
    707, 151, 457, 449, 797, 195, 791, 558, 945, 679,
    297,  59,  87, 824, 713, 663, 412, 693, 342, 606,
    134, 108, 571, 364, 631, 212, 174, 643, 304, 329,
    343,  97, 430, 751, 497, 314, 983, 374, 822, 928,
    140, 206,  73, 263, 980, 736, 876, 478, 430, 305,
    170, 514, 364, 692, 829,  82, 855, 953, 676, 246,
    369, 970, 294, 750, 807, 827, 150, 790, 288, 923,
    804, 378, 215, 828, 592, 281, 565, 555, 710,  82,
    896, 831, 547, 261, 524, 462, 293, 465, 502,  56,
    661, 821, 976, 991, 658, 869, 905, 758, 745, 193,
    768, 550, 608, 933, 378, 286, 215, 979, 792, 961,
     61, 688, 793, 644, 986, 403, 106, 366, 905, 644,
    372, 567, 466, 434, 645, 210, 389, 550, 919, 135,
    780, 773, 635, 389, 707, 100, 626, 958, 165, 504,
    920, 176, 193, 713, 857, 265, 203,  50, 668, 108,
    645, 990, 626, 197, 510, 357, 358, 850, 858, 364,
    936, 638
)
