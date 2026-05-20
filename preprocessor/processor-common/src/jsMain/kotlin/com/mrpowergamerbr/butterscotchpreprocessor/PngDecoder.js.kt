package com.mrpowergamerbr.butterscotchpreprocessor

import kotlinx.coroutines.await
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import web.canvas.OffscreenCanvas

actual suspend fun decodePngBytes(bytes: ByteArray): PixelImage {
    val blob = Blob(arrayOf(bytes), BlobPropertyBag(type = "image/png"))

    // createImageBitmap + OffscreenCanvas work in both main thread and web workers
    val bitmap: dynamic = (js("createImageBitmap(blob)") as kotlin.js.Promise<*>).await()

    val w: Int = bitmap.width
    val h: Int = bitmap.height

    val canvas = OffscreenCanvas(w.toDouble(), h.toDouble())
    val ctx: dynamic = canvas.asDynamic().getContext("2d")
    ctx.drawImage(bitmap, 0, 0)
    bitmap.close()

    val imageData = ctx.getImageData(0, 0, w, h)
    val rgbaData: dynamic = imageData.data

    val pixelCount = w * h
    val pixels = IntArray(pixelCount)

    // Uint32Array view over the ImageData buffer for fast bulk read
    val uint32View = js("new Uint32Array(rgbaData.buffer, rgbaData.byteOffset, rgbaData.length / 4)")
    for (i in 0 until pixelCount) {
        // Canvas RGBA in little-endian native byte order = ABGR as uint32
        val abgr = uint32View[i] as Int
        val r = abgr and 0xFF
        val g = (abgr ushr 8) and 0xFF
        val b = (abgr ushr 16) and 0xFF
        val a = (abgr ushr 24) and 0xFF
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return PixelImage(w, h, pixels)
}
