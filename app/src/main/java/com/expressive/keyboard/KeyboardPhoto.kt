package com.expressive.keyboard

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PhotoFit { COVER, FIT, STRETCH }

data class PhotoFrame(
    val mode: PhotoFit = PhotoFit.COVER,
    val zoom: Float = 1f,
    val width: Float = 1f,
    val height: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f
)

@Composable
internal fun KeyboardPhoto(image: ImageBitmap, settings: KeyboardSettings, modifier: Modifier, alpha: Float = 1f) {
    val blur = settings.bgBlurDp
    val frozen = settings.staticPhotoBlur && blur > 0f
    val frosted by produceState<ImageBitmap?>(null, image, blur, frozen) {
        value = if (frozen) withContext(Dispatchers.Default) { blurPhotoOnce(image, blur) } else null
    }
    Canvas(modifier.then(
        if (!frozen && blur > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(blur.dp) else Modifier
    )) {
        drawKeyboardPhoto(if (frozen) frosted ?: image else image, size, settings.photoFrame, alpha)
    }
}

private fun blurPhotoOnce(image: ImageBitmap, strength: Float): ImageBitmap {
    val bitmap = image.asAndroidBitmap()
    val scale = (144f / max(bitmap.width, bitmap.height)).coerceAtMost(1f)
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    val sampled = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val readable = if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
        bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return image
    } else bitmap
    android.graphics.Canvas(sampled).drawBitmap(readable, null, android.graphics.Rect(0, 0, width, height),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    val pixels = IntArray(width * height)
    val scratch = IntArray(pixels.size)
    sampled.getPixels(pixels, 0, width, 0, 0, width, height)
    val radius = (strength / 24f * 9f).roundToInt().coerceAtLeast(1)
    val count = 2 * radius + 1
    fun pass(source: IntArray, target: IntArray, horizontal: Boolean) {
        for (y in 0 until height) for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (offset in -radius..radius) {
                val sx = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
                val sy = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
                val color = source[sy * width + sx]
                a += color ushr 24
                r += (color ushr 16) and 255
                g += (color ushr 8) and 255
                b += color and 255
            }
            target[y * width + x] = (a / count shl 24) or (r / count shl 16) or (g / count shl 8) or (b / count)
        }
    }
    repeat(2) {
        pass(pixels, scratch, horizontal = true)
        pass(scratch, pixels, horizontal = false)
    }
    sampled.setPixels(pixels, 0, width, 0, 0, width, height)
    return sampled.asImageBitmap()
}

internal fun backgroundFrame(source: Size, area: Size, frame: PhotoFrame): Rect {
    val scaleX = area.width / source.width
    val scaleY = area.height / source.height
    val scale = when (frame.mode) {
        PhotoFit.COVER -> max(scaleX, scaleY)
        PhotoFit.FIT -> min(scaleX, scaleY)
        PhotoFit.STRETCH -> 1f
    }
    val width = (if (frame.mode == PhotoFit.STRETCH) area.width else source.width * scale) * frame.zoom * frame.width
    val height = (if (frame.mode == PhotoFit.STRETCH) area.height else source.height * scale) * frame.zoom * frame.height
    val left = (area.width - width + area.width * frame.x) / 2f
    val top = (area.height - height + area.height * frame.y) / 2f
    return Rect(left, top, left + width, top + height)
}

internal fun DrawScope.drawKeyboardPhoto(image: ImageBitmap, area: Size, frame: PhotoFrame, alpha: Float = 1f) {
    val bounds = backgroundFrame(Size(image.width.toFloat(), image.height.toFloat()), area, frame)
    clipRect(right = area.width, bottom = area.height) {
        drawImage(
            image,
            dstOffset = IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()),
            dstSize = IntSize(bounds.width.roundToInt().coerceAtLeast(1), bounds.height.roundToInt().coerceAtLeast(1)),
            alpha = alpha,
            filterQuality = FilterQuality.Medium
        )
    }
}
