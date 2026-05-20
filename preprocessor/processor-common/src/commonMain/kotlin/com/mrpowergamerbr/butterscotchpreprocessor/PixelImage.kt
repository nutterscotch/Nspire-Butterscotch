package com.mrpowergamerbr.butterscotchpreprocessor

class PixelImage(val width: Int, val height: Int, val pixels: IntArray) {
    fun getPixel(x: Int, y: Int): Int = pixels[y * width + x]

    fun setPixel(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }
}

data class CropResult(
    val image: PixelImage,
    val offsetX: Int,
    val offsetY: Int
)

fun cropTransparentBorders(img: PixelImage): CropResult {
    var minY = -1
    // Scan top to bottom for first non-transparent row
    for (y in 0 until img.height) {
        for (x in 0 until img.width) {
            if ((img.pixels[y * img.width + x] ushr 24) != 0) {
                minY = y
                break
            }
        }
        if (0 <= minY) break
    }

    // Entirely transparent
    if (0 > minY) {
        return CropResult(PixelImage(1, 1, IntArray(1)), 0, 0)
    }

    // Scan bottom to top
    var maxY = minY
    for (y in img.height - 1 downTo minY) {
        var found = false
        for (x in 0 until img.width) {
            if ((img.pixels[y * img.width + x] ushr 24) != 0) {
                maxY = y
                found = true
                break
            }
        }
        if (found) break
    }

    // Scan left to right
    var minX = img.width
    for (y in minY..maxY) {
        for (x in 0 until minX) {
            if ((img.pixels[y * img.width + x] ushr 24) != 0) {
                minX = x
                break
            }
        }
    }

    // Scan right to left
    var maxX = minX
    for (y in minY..maxY) {
        for (x in img.width - 1 downTo maxX + 1) {
            if ((img.pixels[y * img.width + x] ushr 24) != 0) {
                maxX = x
                break
            }
        }
    }

    val cropW = maxX - minX + 1
    val cropH = maxY - minY + 1
    val croppedPixels = IntArray(cropW * cropH)
    for (y in 0 until cropH) {
        for (x in 0 until cropW) {
            croppedPixels[y * cropW + x] = img.pixels[(minY + y) * img.width + (minX + x)]
        }
    }

    return CropResult(PixelImage(cropW, cropH, croppedPixels), minX, minY)
}
