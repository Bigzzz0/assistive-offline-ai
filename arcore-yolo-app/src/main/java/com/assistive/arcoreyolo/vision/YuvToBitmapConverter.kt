package com.assistive.arcoreyolo.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

/**
 * Utility object to convert YUV_420_888 android.media.Image buffers to standard Bitmaps.
 * Handles row and pixel strides dynamically.
 */
object YuvToBitmapConverter {

    fun convertYuv420888ToBitmap(image: Image): Bitmap {
        val nv21 = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 85, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun yuv420888ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(width * height * 3 / 2)

        // Y-plane is copied directly
        yBuffer.get(nv21, 0, ySize)

        // UV planes are interleaved as V U V U...
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        var pos = ySize
        val uvWidth = width / 2
        val uvHeight = height / 2

        val vRow = ByteArray(vRowStride)
        val uRow = ByteArray(uRowStride)

        for (row in 0 until uvHeight) {
            vBuffer.position(row * vRowStride)
            vBuffer.get(vRow, 0, Math.min(vRowStride, vBuffer.remaining()))
            uBuffer.position(row * uRowStride)
            uBuffer.get(uRow, 0, Math.min(uRowStride, uBuffer.remaining()))

            for (col in 0 until uvWidth) {
                nv21[pos++] = vRow[col * vPixelStride]
                nv21[pos++] = uRow[col * uPixelStride]
            }
        }
        return nv21
    }
}

/**
 * Extension function to easily convert native android.media.Image to Bitmap.
 */
fun Image.toBitmap(): Bitmap {
    return YuvToBitmapConverter.convertYuv420888ToBitmap(this)
}
