package com.expressive.keyboard

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal val sensitivePluginPermissions = setOf("read_text", "clipboard", "microphone", "sensors", "media", "app_info")

internal data class PluginAccess(val name: String, val live: Boolean, val microphone: Boolean, val network: Boolean)

@Composable
internal fun BoxScope.PluginAccessIndicator(settings: KeyboardSettings, palette: KeyPalette) {
    val plugins = PluginRepository.active
    if (plugins.none { p -> !p.builtIn && p.permissions.any { it in sensitivePluginPermissions } }) return
    var current by remember { mutableStateOf(emptyList<PluginAccess>()) }
    var showReminder by remember { mutableStateOf(false) }
    LaunchedEffect(plugins, settings.pluginAccessReminder) {
        var next = SystemClock.elapsedRealtime() + 240_000L
        var until = 0L
        while (true) {
            current = ScriptHost.recentAccess()
            val now = SystemClock.elapsedRealtime()
            if (settings.pluginAccessReminder && now >= next && current.isNotEmpty()) {
                next = now + 240_000L
                until = now + 6500L
            }
            showReminder = now < until
            delay(1500L)
        }
    }
    if (!ScriptHost.privateMode && current.any { it.live && (settings.pluginAccessReminder || it.microphone || it.network) }) {
        val label = tr("Мод использует чувствительное разрешение")
        Box(Modifier.align(Alignment.TopStart).padding(3.dp).size(8.dp)
            .background(Color(0xFFE53935), CircleShape).semantics { contentDescription = label })
    }
    if (!ScriptHost.privateMode && showReminder && current.isNotEmpty()) {
        Text(
            tr("Моды с чувствительными разрешениями") + "\n" + current.take(3).joinToString(", ") { it.name },
            Modifier.align(Alignment.TopCenter).padding(top = 4.dp, start = 20.dp, end = 20.dp)
                .background(palette.func, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .padding(horizontal = 9.dp, vertical = 5.dp),
            color = palette.onFunc, fontSize = 12.sp, maxLines = 3
        )
    }
}
