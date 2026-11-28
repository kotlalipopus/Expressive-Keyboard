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
    // Индикатор доступа модов убран по запросу пользователя.
}
