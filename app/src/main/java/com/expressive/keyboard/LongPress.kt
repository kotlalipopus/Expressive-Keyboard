package com.expressive.keyboard

import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LongPressPage(settings: KeyboardSettings) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val current = remember(settings.keyVariants) { UserVariants.parse(settings.keyVariants) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    SettingsSection(null) {
        GroupItem {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("Свои символы"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                FilledTonalButton(onClick = { editing = "" }) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Добавить"))
                }
            }
        }
        if (current.isEmpty()) {
            GroupItem {
                Text(tr("Пока своих символов нет — стоят стандартные"), Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        current.entries.sortedBy { it.key }.forEach { (key, variants) ->
            GroupItem(onClick = { editing = key }) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = RoundedCornerShape(16.dp), color = colors.primaryContainer) {
                        Box(Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).widthIn(max = 72.dp)
                            .padding(6.dp), contentAlignment = Alignment.Center) {
                            Text(key, style = MaterialTheme.typography.titleLarge, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        variants.forEachIndexed { index, variant ->
                            Surface(shape = RoundedCornerShape(10.dp),
                                color = if (index == 0) colors.secondaryContainer else colors.surfaceContainerHighest) {
                                Text(variant, Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    Icon(Icons.Rounded.Edit, tr("Изменить"), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
    if (current.isNotEmpty()) {
        SettingsSection(null) {
            ActionItem(Icons.Rounded.RestartAlt, "Вернуть стандартные символы", null,
                onClick = { confirmReset = true })
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(tr("Вернуть стандартные символы")) },
            text = { Text(tr("Удалить все свои наборы")) },
            confirmButton = {
                TextButton(onClick = {
                    UserVariants.save(context, emptyMap())
                    confirmReset = false
                }) { Text(tr("Сброс")) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(tr("Отмена")) } }
        )
    }
    editing?.let { original ->
        var label by rememberSaveable(original) { mutableStateOf(original) }
        val saved = current[original].orEmpty()
        val initialSymbols = if (saved == listOf(",")) "," else saved.joinToString(" ")
        var symbols by rememberSaveable(original) { mutableStateOf(initialSymbols) }
        val variants = if (symbols == initialSymbols) saved else UserVariants.split(symbols)
        val key = label.lowercase()
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(tr(if (original.isEmpty()) "Добавить" else "Изменить")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(label, { label = it.trim().take(4) }, Modifier.fillMaxWidth(),
                        label = { Text(tr("Клавиша")) }, singleLine = true, placeholder = { Text("г") })
                    OutlinedTextField(symbols, { symbols = it.take(80) }, Modifier.fillMaxWidth(),
                        label = { Text(tr("Символы")) }, placeholder = { Text("ғ г") })
                    Text(tr("Первый символ вводится при удержании без сдвига."),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    if (original.isNotEmpty()) {
                        TextButton(onClick = {
                            UserVariants.save(context, current - original)
                            editing = null
                        }) { Text(tr("Удалить"), color = colors.error) }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    UserVariants.save(context, current - original + (key to variants))
                    editing = null
                }, enabled = key.isNotEmpty() && variants.isNotEmpty()) { Text(tr("Сохранить")) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("Отмена")) } }
        )
    }
}
