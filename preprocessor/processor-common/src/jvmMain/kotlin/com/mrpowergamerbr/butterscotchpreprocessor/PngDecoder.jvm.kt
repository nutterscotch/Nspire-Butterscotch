package com.mrpowergamerbr.butterscotchpreprocessor

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

actual suspend fun decodePngBytes(bytes: ByteArray): PixelImage {
    val img = ImageIO.read(ByteArrayInputStream(bytes))
    val w = img.width
    val h = img.height
    val argb = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = argb.createGraphics()
    g.drawImage(img, 0, 0, null)
    g.dispose()
    val pixels = IntArray(w * h)
    argb.getRGB(0, 0, w, h, pixels, 0, w)
    return PixelImage(w, h, pixels)
}
