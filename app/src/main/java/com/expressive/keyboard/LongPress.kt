package com.expressive.keyboard

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
// СВОИ СИМВОЛЫ ПРИ УДЕРЖАНИИ
// Для любой клавиши (в любой раскладке) можно задать свой набор символов при удержании: «г → ғ»,
// «d → $ ₽ €». Первый символ — главный: он стоит посередине (выбирается удержанием без сдвига) и
// подписан мелко на клавише. Свой набор важнее и каталога, и режима «как в Gboard».
// ============================================================================

object UserVariants {
    const val PREF = "key_variants"

    /** Подпись клавиши в нижнем регистре → символы; первый — главный. */
    @Volatile var map: Map<String, List<String>> = emptyMap()
        private set
    private var raw: String? = null

    fun parse(s: String?): Map<String, List<String>> = runCatching {
        if (s.isNullOrBlank()) return emptyMap()
        val o = JSONObject(s)
        buildMap {
            for (k in o.keys()) {
                val a = o.optJSONArray(k) ?: continue
                val list = (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
                if (k.isNotEmpty() && list.isNotEmpty()) put(k, list)
            }
        }
    }.getOrDefault(emptyMap())

    /** Вызывается при чтении настроек: строка не поменялась — разбирать заново не нужно. */
    fun update(s: String?) {
        if (s == raw) return
        raw = s
        map = parse(s)
    }

    fun has(label: String): Boolean = map.containsKey(label.lowercase())

    /** Главный символ (подпись на клавише). */
    fun primary(label: String): String? = map[label.lowercase()]?.firstOrNull()

    /** Варианты для окна удержания: главный — посередине, как у цифр верхнего ряда. */
    fun variants(label: String, upper: Boolean): List<String>? {
        val list = map[label.lowercase()] ?: return null
        val primary = list.first()
        val rest = list.drop(1).toMutableList()
        rest.add((rest.size + 1) / 2, primary)
        return if (upper) rest.map { if (it.length == 1 && it[0].isLetter()) it.uppercase() else it } else rest
    }

    fun save(context: Context, m: Map<String, List<String>>) {
        val o = JSONObject()
        for ((k, v) in m) if (v.isNotEmpty()) o.put(k, JSONArray(v))
        KeyboardPrefs.edit(context) { putString(PREF, o.toString()) }
    }

    /** «$ ₽ €», «$₽€» или «$, ₽, €» → символы. Слитная строка без пробелов — по одному символу (с учётом эмодзи). */
    fun split(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val parts = t.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (parts.size > 1) return parts.distinct().take(12)
        val out = ArrayList<String>()
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(t)
        var start = it.first()
        var end = it.next()
        while (end != java.text.BreakIterator.DONE) {
            out += t.substring(start, end)
            start = end
            end = it.next()
        }
        return out.distinct().take(12)
    }
}

@Composable
internal fun LongPressPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val current = remember(settings.keyVariants) { UserVariants.parse(settings.keyVariants) }
    var key by remember { mutableStateOf("") }
    var symbols by remember { mutableStateOf("") }
    SettingsSection("Свои символы") {
        InfoCard(
            Icons.Rounded.TouchApp,
            "Задайте, что всплывает при удержании любой клавиши. Первый символ — главный: он подписан на клавише и вводится удержанием без сдвига. Работает во всех раскладках"
        )
        if (current.isEmpty()) {
            GroupItem {
                Text(
                    tr("Пока своих символов нет — стоят стандартные"), Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant
                )
            }
        }
        current.forEach { (k, v) ->
            GroupItem {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(k, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = cs.primary, modifier = Modifier.widthIn(min = 32.dp))
                    Text(v.joinToString("  "), fontSize = 20.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { key = k; symbols = v.joinToString(" ") }) { Icon(Icons.Rounded.Edit, tr("Изменить")) }
                    IconButton(onClick = { UserVariants.save(context, current - k) }) {
                        Icon(Icons.Rounded.DeleteOutline, tr("Удалить"), tint = cs.error)
                    }
                }
            }
        }
    }
    SettingsSection("Добавить или изменить") {
        GroupItem {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it.trim().take(4) },
                        modifier = Modifier.width(96.dp), shape = RoundedCornerShape(20.dp),
                        label = { Text(tr("Клавиша")) }, placeholder = { Text("г") }, singleLine = true
                    )
                    OutlinedTextField(
                        value = symbols, onValueChange = { symbols = it.take(80) },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp),
                        label = { Text(tr("Символы")) }, placeholder = { Text("ғ г") }, singleLine = true
                    )
                }
                val list = UserVariants.split(symbols)
                val k = key.lowercase()
                Button(
                    onClick = {
                        UserVariants.save(context, current + (k to list))
                        key = ""
                        symbols = ""
                    },
                    enabled = k.isNotEmpty() && list.isNotEmpty()
                ) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Сохранить"))
                }
            }
        }
        InfoCard(Icons.Rounded.Lightbulb, "Символы можно писать через пробел («$ ₽ €») или слитно («$₽€»). Казахский: клавиша «г», символы «ғ»")
    }
    if (current.isNotEmpty()) {
        SettingsSection("Сброс") {
            ActionItem(Icons.Rounded.RestartAlt, "Вернуть стандартные символы", "Удалить все свои наборы", onClick = {
                UserVariants.save(context, emptyMap())
            })
        }
    }
}
