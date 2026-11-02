package com.expressive.keyboard

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.luminance
import kotlin.math.max

// ============================================================================
// СЛИЯНИЕ СТЕКЛЯННЫХ КЛАВИШ
// Нажатая клавиша вытягивает к соседним стеклянные перемычки, как сливающиеся капли. Сами клавиши рисуются
// отдельно и лежат выше, поэтому этот слой под ними рисует только то, что между ними. Считается шейдером
// и только пока клавиша нажата или возвращается: в покое слоя нет вообще.
// ============================================================================

internal object GlassMerge {
    const val MAX_NEIGHBORS = 8
    private const val MAX_ACTIVE = 4

    /** Нажатые клавиши и сила нажатия 0..1. */
    val active = mutableStateMapOf<CoordsHolder, Float>()
    private val cached = HashMap<CoordsHolder, Pair<Rect, List<Rect>>>()

    fun update(holder: CoordsHolder, press: Float) {
        val p = press.coerceIn(0f, 1f)
        if (p > 0.02f) {
            if (holder in active || active.size < MAX_ACTIVE) active[holder] = p
        } else remove(holder)
    }

    fun remove(holder: CoordsHolder) {
        active.remove(holder)
        cached.remove(holder)
    }

    /** Прямоугольник клавиши и её соседи, в dp от угла клавиатуры. Запоминается на время нажатия. */
    fun geometry(holder: CoordsHolder, reach: Float): Pair<Rect, List<Rect>>? {
        cached[holder]?.let { return it }
        val keys = KeyboardGeometry.visible().filter { it.first.key.type != KeyType.SPACER }
        val self = keys.firstOrNull { it.first.holder === holder }?.second ?: return null
        val near = keys.asSequence().map { it.second }.filter { it != self && gapBetween(self, it) <= reach }
            .sortedBy { gapBetween(self, it) }.take(MAX_NEIGHBORS).toList()
        return (self to near).also { cached[holder] = it }
    }

    internal fun gapBetween(a: Rect, b: Rect): Float {
        val dx = max(0f, max(b.left - a.right, a.left - b.right))
        val dy = max(0f, max(b.top - a.bottom, a.top - b.bottom))
        return max(dx, dy)
    }
}

private const val MERGE_SRC = """
uniform float4 key;
uniform float4 near[8];
uniform float count;
uniform float radius;
uniform float k;
uniform float band;
uniform float night;

float box(float2 p, float4 r, float rad) {
    float2 c = (r.xy + r.zw) * 0.5;
    float2 b = (r.zw - r.xy) * 0.5;
    float2 q = abs(p - c) - b + rad;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;
}

float smin(float a, float b, float s) {
    float h = max(s - abs(a - b), 0.0) / s;
    return min(a, b) - h * h * s * 0.25;
}

float field(float2 p) {
    float own = box(p, key, radius);
    float d = own;
    for (int i = 0; i < 8; i++) {
        if (float(i) < count) d = min(d, smin(own, box(p, near[i], radius), k));
    }
    return d;
}

float solid(float2 p) {
    float d = box(p, key, radius);
    for (int i = 0; i < 8; i++) {
        if (float(i) < count) d = min(d, box(p, near[i], radius));
    }
    return d;
}

half4 main(float2 p) {
    float d = field(p);
    if (d > 1.0) return half4(0.0);
    // только перемычки: сами клавиши лежат выше и рисуются своим стеклом
    float mask = (1.0 - smoothstep(-0.8, 0.8, d)) * smoothstep(-0.8, 0.8, solid(p));
    if (mask <= 0.0) return half4(0.0);
    float2 n = float2(field(p + float2(1.0, 0.0)) - d, field(p + float2(0.0, 1.0)) - d);
    n = n / max(length(n), 0.0001);
    float edge = smoothstep(-band, 0.0, d);
    float2 light = normalize(float2(-0.55, -0.83));
    float spec = pow(clamp(dot(n, light), 0.0, 1.0), 2.0) * edge;
    float a = mix(0.42, 0.20, night) + edge * 0.28 + spec * 0.35;
    a = clamp(a, 0.0, 1.0) * mask;
    return half4(a, a, a, a);
}
"""

/** Перемычки под клавишами. enabled — тема со стеклом и включённая настройка. */
@Composable
internal fun BoxScope.GlassMergeLayer(enabled: Boolean, background: androidx.compose.ui.graphics.Color) {
    if (!enabled || Build.VERSION.SDK_INT < 33 || GlassMerge.active.isEmpty()) return
    val shader = remember { runCatching { RuntimeShader(MERGE_SRC) }.getOrNull() } ?: return
    val night = if (background.luminance() < 0.5f) 1f else 0f
    val nearValues = remember { FloatArray(GlassMerge.MAX_NEIGHBORS * 4) }
    Canvas(Modifier.matchParentSize()) {
        val d = density
        for ((holder, press) in GlassMerge.active) {
            val (self, near) = GlassMerge.geometry(holder, 14f) ?: continue
            val radius = (minOf(self.width, self.height) * 0.3f) * d
            nearValues.fill(0f)
            near.forEachIndexed { i, r ->
                nearValues[i * 4] = r.left * d
                nearValues[i * 4 + 1] = r.top * d
                nearValues[i * 4 + 2] = r.right * d
                nearValues[i * 4 + 3] = r.bottom * d
            }
            shader.setFloatUniform("key", self.left * d, self.top * d, self.right * d, self.bottom * d)
            shader.setFloatUniform("near", nearValues)
            shader.setFloatUniform("count", near.size.toFloat())
            shader.setFloatUniform("radius", radius)
            // перемычка появляется, когда k больше двойного промежутка между клавишами
            shader.setFloatUniform("k", (28f * press).coerceAtLeast(0.5f) * d)
            shader.setFloatUniform("band", 3f * d)
            shader.setFloatUniform("night", night)
            val pad = 16f * d
            drawRect(
                ShaderBrush(shader),
                Offset(self.left * d - pad, self.top * d - pad),
                Size(self.width * d + pad * 2, self.height * d + pad * 2)
            )
        }
    }
}
