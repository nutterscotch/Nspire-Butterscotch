package com.mrpowergamerbr.butterscotchpreprocessor

private const val QOI_HEADER_SIZE = 12

// Sign-extend the low "bits" bits of "value" to 8-bit two's-complement.
private fun signExtend(value: Int, bits: Int): Int {
    val mask = 1 shl (bits - 1)
    return ((value xor mask) - mask) and 0xFF
}

// Decodes GameMaker's custom QOI format ("fioq" magic). Ported from UndertaleModTool's QoiConverter.
fun decodeQoi(data: ByteArray): PixelImage {
    require(data.size >= QOI_HEADER_SIZE) { "QOI: blob too small" }
    require(data[0] == 'f'.code.toByte() && data[1] == 'i'.code.toByte() && data[2] == 'o'.code.toByte() && data[3] == 'q'.code.toByte()) {
        "QOI: bad magic"
    }

    val width = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
    val height = (data[6].toInt() and 0xFF) or ((data[7].toInt() and 0xFF) shl 8)
    val length =
        (data[8].toInt() and 0xFF) or
        ((data[9].toInt() and 0xFF) shl 8) or
        ((data[10].toInt() and 0xFF) shl 16) or
        ((data[11].toInt() and 0xFF) shl 24)

    require(width > 0 && height > 0) { "QOI: bad dimensions" }
    require(QOI_HEADER_SIZE + length <= data.size) { "QOI: truncated payload" }

    val pixelDataSize = length
    val pixelDataBase = QOI_HEADER_SIZE
    val pixelCount = width * height
    val pixels = IntArray(pixelCount)

    val index = IntArray(64)

    var pos = 0
    var run = 0
    var r = 0
    var g = 0
    var b = 0
    var a = 255

    var rawPos = 0
    while (pixelCount > rawPos) {
        if (run > 0) {
            run--
        } else if (pixelDataSize > pos) {
            val b1 = data[pixelDataBase + pos].toInt() and 0xFF
            pos++

            when {
                (b1 and 0xC0) == 0x00 -> {
                    // QOI_INDEX
                    val argb = index[b1 and 0x3F]
                    a = (argb ushr 24) and 0xFF
                    r = (argb ushr 16) and 0xFF
                    g = (argb ushr 8) and 0xFF
                    b = argb and 0xFF
                }
                (b1 and 0xE0) == 0x40 -> {
                    // QOI_RUN_8
                    run = b1 and 0x1F
                }
                (b1 and 0xE0) == 0x60 -> {
                    // QOI_RUN_16
                    require(pixelDataSize > pos) { "QOI: truncated RUN_16" }
                    val b2 = data[pixelDataBase + pos].toInt() and 0xFF
                    pos++
                    run = (((b1 and 0x1F) shl 8) or b2) + 32
                }
                (b1 and 0xC0) == 0x80 -> {
                    // QOI_DIFF_8 (2-2-2 signed deltas on r,g,b)
                    r = (r + signExtend((b1 ushr 4) and 3, 2)) and 0xFF
                    g = (g + signExtend((b1 ushr 2) and 3, 2)) and 0xFF
                    b = (b + signExtend(b1 and 3, 2)) and 0xFF
                }
                (b1 and 0xE0) == 0xC0 -> {
                    // QOI_DIFF_16 (5-4-4 signed deltas)
                    require(pixelDataSize > pos) { "QOI: truncated DIFF_16" }
                    val b2 = data[pixelDataBase + pos].toInt() and 0xFF
                    pos++
                    val merged = (b1 shl 8) or b2
                    r = (r + signExtend((merged ushr 8) and 0x1F, 5)) and 0xFF
                    g = (g + signExtend((merged ushr 4) and 0x0F, 4)) and 0xFF
                    b = (b + signExtend(merged and 0x0F, 4)) and 0xFF
                }
                (b1 and 0xF0) == 0xE0 -> {
                    // QOI_DIFF_24 (5-5-5-5 signed deltas)
                    require(pixelDataSize > pos + 1) { "QOI: truncated DIFF_24" }
                    val b2 = data[pixelDataBase + pos].toInt() and 0xFF
                    val b3 = data[pixelDataBase + pos + 1].toInt() and 0xFF
                    pos += 2
                    val merged = (b1 shl 16) or (b2 shl 8) or b3
                    r = (r + signExtend((merged ushr 15) and 0x1F, 5)) and 0xFF
                    g = (g + signExtend((merged ushr 10) and 0x1F, 5)) and 0xFF
                    b = (b + signExtend((merged ushr 5) and 0x1F, 5)) and 0xFF
                    a = (a + signExtend(merged and 0x1F, 5)) and 0xFF
                }
                (b1 and 0xF0) == 0xF0 -> {
                    // QOI_COLOR (per-channel raw bytes, only those flagged)
                    if ((b1 and 8) != 0) { require(pixelDataSize > pos); r = data[pixelDataBase + pos].toInt() and 0xFF; pos++ }
                    if ((b1 and 4) != 0) { require(pixelDataSize > pos); g = data[pixelDataBase + pos].toInt() and 0xFF; pos++ }
                    if ((b1 and 2) != 0) { require(pixelDataSize > pos); b = data[pixelDataBase + pos].toInt() and 0xFF; pos++ }
                    if ((b1 and 1) != 0) { require(pixelDataSize > pos); a = data[pixelDataBase + pos].toInt() and 0xFF; pos++ }
                }
            }

            val indexPos = (r xor g xor b xor a) and 63
            index[indexPos] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        pixels[rawPos] = (a shl 24) or (r shl 16) or (g shl 8) or b
        rawPos++
    }

    return PixelImage(width, height, pixels)
}
