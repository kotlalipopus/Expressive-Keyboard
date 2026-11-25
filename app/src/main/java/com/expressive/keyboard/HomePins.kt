package com.expressive.keyboard

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal val homePinChoices = SettingsPage.entries.filter { it !in setOf(SettingsPage.HOME, SettingsPage.THEME_EDITOR) }

internal fun homePins(raw: String): List<SettingsPage> = raw.split(',').mapNotNull { name ->
    homePinChoices.firstOrNull { it.name == name }
}.distinct()

@Composable
internal fun HomePinEditor(saved: List<SettingsPage>, done: () -> Unit) {
    val context = LocalContext.current
    var order by remember { mutableStateOf(saved) }
    fun save() = KeyboardPrefs.edit(context) { putString("home_pins", order.joinToString(",") { it.name }) }
    fun move(page: SettingsPage, by: Int) {
        val from = order.indexOf(page)
        val to = from + by
        if (from >= 0 && to in order.indices) {
            order = order.toMutableList().also { java.util.Collections.swap(it, from, to) }
        }
    }
    SettingsSection("Закреплённые разделы") {
        for (page in order) key(page) {
            var height by remember { mutableIntStateOf(1) }
            GroupItem {
                Row(Modifier.fillMaxWidth().onSizeChanged { height = it.height }.padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(page.title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { move(page, -1); save() }, enabled = order.firstOrNull() != page) {
                        Icon(Icons.Rounded.ArrowUpward, tr("Выше"))
                    }
                    IconButton(onClick = { move(page, 1); save() }, enabled = order.lastOrNull() != page) {
                        Icon(Icons.Rounded.ArrowDownward, tr("Ниже"))
                    }
                    IconButton(onClick = { order = order - page; save() }) {
                        Icon(Icons.Rounded.Star, tr("Открепить"))
                    }
                    Box(Modifier.size(48.dp).pointerInput(page) {
                        var offset = 0f
                        var before = emptyList<SettingsPage>()
                        detectDragGestures(
                            onDragStart = { offset = 0f; before = order },
                            onDragEnd = { save() },
                            onDragCancel = { order = before }
                        ) { change, delta ->
                            change.consume()
                            offset += delta.y
                            if (kotlin.math.abs(offset) >= height) {
                                val step = if (offset > 0) 1 else -1
                                move(page, step)
                                offset -= step * height
                            }
                        }
                    }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.DragIndicator, tr("Перетащить для перестановки"))
                    }
                }
            }
        }
    }
    SettingsSection("Другие разделы") {
        for (page in homePinChoices.filterNot { it in order }) {
            ActionItem(page.icon, tr(page.title), null, onClick = { order = order + page; save() },
                trailing = { Icon(Icons.Rounded.StarBorder, tr("Закрепить")) })
        }
    }
    SettingsSection(null) { ActionItem(Icons.Rounded.Check, "Готово", null, onClick = done) }
}
