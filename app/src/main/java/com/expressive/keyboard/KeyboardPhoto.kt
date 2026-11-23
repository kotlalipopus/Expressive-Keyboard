package com.expressive.keyboard

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    Canvas(modifier.then(
        if (blur > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(blur.dp) else Modifier
    )) {
        drawKeyboardPhoto(image, size, settings.photoFrame, alpha)
    }
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
