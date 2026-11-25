package com.expressive.keyboard

import android.content.Context
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal data class ModScreen(
    val owner: String, val display: String, val keys: Boolean, val panel: ScriptPanel?,
    val width: Float, val height: Float, val exit: String
) {
    val floating get() = display == "floating"
    val rotation get() = when (display) {
        "landscape" -> 90f
        "landscape-right" -> -90f
        else -> 0f
    }
}

internal object ModScreenGrant {
    private fun prefs(context: Context) = context.getSharedPreferences("plugin_screen_access", Context.MODE_PRIVATE)
    fun has(context: Context, id: String) = prefs(context).getBoolean(id, false)
    fun set(context: Context, id: String, enabled: Boolean) {
        prefs(context).edit().apply { if (enabled) putBoolean(id, true) else remove(id) }.apply()
        if (!enabled) android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (ScriptHost.modScreen?.owner == id && !has(context, id)) ScriptHost.closeModScreen()
        }
    }
}

@Composable
internal fun ModScreenFrame(
    screen: ModScreen?,
    palette: KeyPalette,
    font: FontFamily,
    onBounds: (Rect?, Rect?) -> Unit,
    normal: @Composable () -> Unit,
    fullscreen: @Composable (ModScreen, Boolean) -> Unit
) {
    LaunchedEffect(screen) { if (screen?.floating != true) onBounds(null, null) }
    if (screen == null) {
        normal()
        return
    }
    if (screen.floating && screen.panel != null) {
        val bounds = remember(screen) { arrayOfNulls<Rect>(2) }
        fun update(index: Int, rect: androidx.compose.ui.geometry.Rect) {
            bounds[index] = Rect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
            onBounds(bounds[0], bounds[1])
        }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val cardWidth = screen.width.dp.coerceAtMost(maxWidth)
            val cardHeight = screen.height.dp.coerceAtMost(maxHeight * 0.65f)
            val density = LocalDensity.current
            val right = with(density) { (maxWidth - cardWidth).roundToPx().coerceAtLeast(0) }
            val bottom = with(density) { (maxHeight - cardHeight).roundToPx().coerceAtLeast(0) }
            var position by remember(screen, right, bottom) { mutableStateOf(Offset(right / 2f, bottom / 6f)) }
            Box(Modifier.align(Alignment.BottomCenter).onGloballyPositioned { update(0, it.boundsInWindow()) }) { normal() }
            Column(Modifier.offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                .size(cardWidth, cardHeight).onGloballyPositioned { update(1, it.boundsInWindow()) }
                .clip(RoundedCornerShape(20.dp)).background(palette.background.copy(alpha = 1f))) {
                Row(Modifier.fillMaxWidth().background(palette.func), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(screen.panel.title), Modifier.weight(1f).pointerInput(right, bottom) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            position = Offset((position.x + drag.x).coerceIn(0f, right.toFloat()),
                                (position.y + drag.y).coerceIn(0f, bottom.toFloat()))
                        }
                    }.padding(12.dp), color = palette.onFunc, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ModScreenClose(palette)
                }
                ScriptPanelView(screen.panel, palette, font, Modifier.fillMaxWidth().weight(1f))
            }
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.background.copy(alpha = 1f))
        .windowInsetsPadding(WindowInsets.safeDrawing)) {
        val rotated = screen.rotation != 0f && maxHeight > maxWidth
        Layout(content = {
            Box(Modifier.fillMaxSize()) {
                fullscreen(screen, rotated)
                val alignment = when (screen.exit) {
                    "topStart" -> Alignment.TopStart
                    "bottomStart" -> Alignment.BottomStart
                    "bottomEnd" -> Alignment.BottomEnd
                    else -> Alignment.TopEnd
                }
                ModScreenClose(palette, Modifier.align(alignment).padding(4.dp))
            }
        }) { measurables, parent ->
            val w = parent.maxWidth
            val h = parent.maxHeight
            val child = measurables.single().measure(if (rotated) Constraints.fixed(h, w) else Constraints.fixed(w, h))
            layout(w, h) {
                child.placeWithLayer((w - child.width) / 2, (h - child.height) / 2) {
                    rotationZ = if (rotated) screen.rotation else 0f
                }
            }
        }
    }
}

@Composable
private fun ModScreenClose(palette: KeyPalette, modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clip(CircleShape).background(palette.func)
        .clickable { ScriptHost.closeModScreen() }, contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Close, tr("Закрыть экран мода"), tint = palette.onFunc, modifier = Modifier.size(24.dp))
    }
}
