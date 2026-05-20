package com.mrpowergamerbr.butterscotchpreprocessor

private const val COMPRESSED_QOI_HEADER_SIZE_OLD = 8
private const val COMPRESSED_QOI_HEADER_SIZE_NEW = 12

// Detects the image format by header and dispatches to the right decoder.
// Supports GameMaker custom QOI ("fioq"), BZip2-compressed QOI ("2zoq") and PNG.
// `gm2022_5` selects the BZ2-QOI header variant: GMS 2022.5+ adds a 4-byte uncompressed-length field.
suspend fun decodeImageBytes(blob: ByteArray, gm2022_5: Boolean): PixelImage {
    require(blob.size >= 4) { "ImageDecoder: blob too small" }

    // GameMaker custom QOI ("fioq")
    if (blob[0] == 'f'.code.toByte() && blob[1] == 'i'.code.toByte() && blob[2] == 'o'.code.toByte() && blob[3] == 'q'.code.toByte()) {
        return decodeQoi(blob)
    }

    // BZip2 + GameMaker custom QOI ("2zoq")
    if (blob[0] == '2'.code.toByte() && blob[1] == 'z'.code.toByte() && blob[2] == 'o'.code.toByte() && blob[3] == 'q'.code.toByte()) {
        return decodeBz2Qoi(blob, gm2022_5)
    }

    // PNG (or anything the platform decoder accepts).
    return decodePngBytes(blob)
}

// BZip2-compressed QOI blob layout:
//   bytes 0..3 = "2zoq" magic
//   bytes 4..5 = width  (LE uint16)
//   bytes 6..7 = height (LE uint16)
//   bytes 8..11 = uncompressed BZ2 length (LE uint32) -- ONLY when gm2022_5 is true
//   bytes 8.. (or 12.. if gm2022_5) = raw BZip2 stream that decompresses into a full "fioq" QOI file.
private fun decodeBz2Qoi(blob: ByteArray, gm2022_5: Boolean): PixelImage {
    val headerSize = if (gm2022_5) COMPRESSED_QOI_HEADER_SIZE_NEW else COMPRESSED_QOI_HEADER_SIZE_OLD
    require(blob.size >= headerSize) { "BZ2-QOI: blob too small" }

    val width = (blob[4].toInt() and 0xFF) or ((blob[5].toInt() and 0xFF) shl 8)
    val height = (blob[6].toInt() and 0xFF) or ((blob[7].toInt() and 0xFF) shl 8)
    require(width > 0 && height > 0) { "BZ2-QOI: bad dimensions" }

    val bz2Stream = blob.copyOfRange(headerSize, blob.size)
    val uncompressed = bzip2Decompress(bz2Stream)
    return decodeQoi(uncompressed)
}
