package vibe.tube.ui.components

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

internal object ShortsAmbient : Transformation() {
    override val cacheKey = "shorts-ambient-box-v2"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = 64
        val height = (input.height.toFloat() / input.width * width).toInt().coerceIn(16, 128)
        val cropWidth = if (input.width.toFloat() / input.height > 1f) (input.height * 9f / 16f).toInt().coerceAtLeast(1) else input.width
        val source = if (cropWidth < input.width) Bitmap.createBitmap(input, (input.width - cropWidth) / 2, 0, cropWidth, input.height) else input
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = IntArray(pixels.size)
        val radius = 6
        repeat(2) {
            for (y in 0 until height) for (x in 0 until width) {
                var red = 0; var green = 0; var blue = 0; var count = 0
                for (dx in -radius..radius) {
                    val color = pixels[y * width + (x + dx).coerceIn(0, width - 1)]
                    red += color shr 16 and 255; green += color shr 8 and 255; blue += color and 255; count++
                }
                output[y * width + x] = (255 shl 24) or ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
            }
            for (y in 0 until height) for (x in 0 until width) {
                var red = 0; var green = 0; var blue = 0; var count = 0
                for (dy in -radius..radius) {
                    val color = output[(y + dy).coerceIn(0, height - 1) * width + x]
                    red += color shr 16 and 255; green += color shr 8 and 255; blue += color and 255; count++
                }
                pixels[y * width + x] = (255 shl 24) or ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
