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
import kotlin.math.min

// ============================================================================
// СЛИЯНИЕ СТЕКЛЯННЫХ КЛАВИШ
// Нажатая клавиша вытягивает к соседним стеклянные перемычки, как сливающиеся капли. Сами клавиши рисуются
// отдельно и лежат выше, поэтому этот слой под ними рисует только то, что между ними: заливку с мягким
// градиентом, яркую кромку и блик. Считается шейдером и только пока клавиша нажата или возвращается:
// в покое слоя нет вообще.
// ============================================================================

internal object GlassMerge {
    const val MAX_NEIGHBORS = 8
    private const val MAX_ACTIVE = 4

    /** Клавиша для шейдера: прямоугольник в dp и тип — от типа зависит скругление. */
    internal data class MergeKey(val rect: Rect, val type: KeyType)

    /** Нажатые клавиши и сила нажатия 0..1. */
    val active = mutableStateMapOf<CoordsHolder, Float>()
    private val cached = HashMap<CoordsHolder, Pair<MergeKey, List<MergeKey>>>()

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

    /** Клавиша и её соседи, в dp от угла клавиатуры. Запоминается на время нажатия. */
    fun geometry(holder: CoordsHolder, reach: Float): Pair<MergeKey, List<MergeKey>>? {
        cached[holder]?.let { return it }
        val keys = KeyboardGeometry.visible().filter { it.first.key.type != KeyType.SPACER }
        val self = keys.firstOrNull { it.first.holder === holder }
            ?.let { MergeKey(it.second, it.first.key.type) } ?: return null
        val near = keys.asSequence()
            .filter { it.first.holder !== holder }
            .map { MergeKey(it.second, it.first.key.type) }
            .filter { gapBetween(self.rect, it.rect) <= reach }
            .sortedBy { gapBetween(self.rect, it.rect) }
            .take(MAX_NEIGHBORS).toList()
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
uniform float nearR[8];
uniform float count;
uniform float radius;
uniform float k;
uniform float band;
uniform float night;
uniform float press;

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
        if (float(i) < count) d = min(d, smin(own, box(p, near[i], nearR[i]), k));
    }
    return d;
}

float solid(float2 p) {
    float d = box(p, key, radius);
    for (int i = 0; i < 8; i++) {
        if (float(i) < count) d = min(d, box(p, near[i], nearR[i]));
    }
    return d;
}

half4 main(float2 p) {
    float d = field(p);
    float s = solid(p);
    // 0 внутри самих клавиш, 1 в перемычке между ними
    float between = smoothstep(-0.5, 0.9, s);
    // 1 внутри слипшейся формы, 0 снаружи
    float inside = 1.0 - smoothstep(-0.55, 0.55, d);
    float mask = between * inside;

    // мягкая тень под формой: на светлом фоне без неё перемычку почти не видно
    float shadow = smoothstep(0.0, 1.4, d) * (1.0 - smoothstep(1.8, 5.5, d));
    if (mask <= 0.002) {
        float sh = shadow * 0.14 * (1.0 - night);
        if (sh > 0.002) return half4(0.0, 0.0, 0.0, sh);
        return half4(0.0);
    }

    // наружная нормаль к границе формы — по ней считаем блик
    float2 n = float2(field(p + float2(0.75, 0.0)) - d, field(p + float2(0.0, 0.75)) - d);
    n = n / max(length(n), 0.0001);

    // яркая кромка чуть внутри свободного края: у самой границы она гаснет, чтобы не было резкой линии
    float r = -d;
    float rim = smoothstep(0.0, 0.5, r) * (1.0 - smoothstep(band * 0.5, band, r));
    rim *= rim;
    float2 light = normalize(float2(-0.55, -0.83));
    float spec = pow(clamp(dot(n, light), 0.0, 1.0), 7.0);
    // верхняя кромка ловит больше света, нижняя остаётся темнее
    float sheen = clamp(-n.y, 0.0, 1.0);
    // у самых краёв клавиш — мягкое «сваривание», чтобы не было видно стыка
    float weld = (1.0 - smoothstep(0.0, 1.8, s)) * between;
    // сверху стекло светлее, книзу темнеет
    float grad = 1.0 - 0.35 * clamp((p.y - key.y) / max(key.w - key.y, 1.0), 0.0, 1.0);

    float fill = mix(0.22, 0.12, night) * grad;
    float a = fill * between;
    a += rim * (0.30 + 0.16 * sheen + 0.22 * spec);
    a += weld * 0.08;
    // перемычка натекает вместе с нажатием, а не появляется сразу
    a *= mix(0.55, 1.0, clamp(press, 0.0, 1.0));
    a = clamp(a, 0.0, 0.86);

    // на светлом фоне заливка прохладнее и темнее, на тёмном — почти белая
    float3 tint = mix(float3(0.40, 0.45, 0.56), float3(0.86, 0.92, 1.0), night);
    float3 col = mix(tint, float3(1.0), clamp(rim * 0.9 + spec * 0.55, 0.0, 1.0));
    return half4(col * a, a);
}
"""

/** Радиус клавиши в px: как в drawSkinKey — от типа клавиши, у букв ограничен настройкой скругления. */
private fun mergeRadiusPx(k: GlassMerge.MergeKey, press: Float, cornerDp: Float, density: Float): Float {
    val m = min(k.rect.width, k.rect.height)
    val f = shapeFractions(k.type)
    var idle = f.first * m
    if (k.type == KeyType.CHARACTER) idle = min(idle, cornerDp)
    val peak = f.second * m
    return (idle + (peak - idle) * press.coerceIn(0f, 1f)) * density
}

/** Перемычки под клавишами. enabled — тема со стеклом и включённая настройка. */
@Composable
internal fun BoxScope.GlassMergeLayer(
    enabled: Boolean,
    background: androidx.compose.ui.graphics.Color,
    cornerDp: Float
) {
    if (!enabled || Build.VERSION.SDK_INT < 33 || GlassMerge.active.isEmpty()) return
    val shader = remember { runCatching { RuntimeShader(MERGE_SRC) }.getOrNull() } ?: return
    val night = if (background.luminance() < 0.5f) 1f else 0f
    val nearValues = remember { FloatArray(GlassMerge.MAX_NEIGHBORS * 4) }
    val nearRadii = remember { FloatArray(GlassMerge.MAX_NEIGHBORS) }
    Canvas(Modifier.matchParentSize()) {
        val d = density
        for ((holder, press) in GlassMerge.active) {
            val (self, near) = GlassMerge.geometry(holder, 14f) ?: continue
            val radius = mergeRadiusPx(self, press, cornerDp, d)
            nearValues.fill(0f)
            nearRadii.fill(0f)
            near.forEachIndexed { i, r ->
                nearValues[i * 4] = r.rect.left * d
                nearValues[i * 4 + 1] = r.rect.top * d
                nearValues[i * 4 + 2] = r.rect.right * d
                nearValues[i * 4 + 3] = r.rect.bottom * d
                nearRadii[i] = mergeRadiusPx(r, 0f, cornerDp, d)
            }
            shader.setFloatUniform("key", self.rect.left * d, self.rect.top * d, self.rect.right * d, self.rect.bottom * d)
            shader.setFloatUniform("near", nearValues)
            shader.setFloatUniform("nearR", nearRadii)
            shader.setFloatUniform("count", near.size.toFloat())
            shader.setFloatUniform("radius", radius)
            // перемычка появляется, когда k больше двойного промежутка между клавишами
            shader.setFloatUniform("k", (28f * press).coerceAtLeast(0.5f) * d)
            shader.setFloatUniform("band", 2.6f * d)
            shader.setFloatUniform("night", night)
            shader.setFloatUniform("press", press.coerceIn(0f, 1f))
            // запас на перемычки и мягкую тень вокруг нажатой клавиши
            val pad = 22f * d
            drawRect(
                ShaderBrush(shader),
                Offset(self.rect.left * d - pad, self.rect.top * d - pad),
                Size(self.rect.width * d + pad * 2, self.rect.height * d + pad * 2)
            )
        }
    }
}
