package com.expressive.keyboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ModImageOption(plugin: KeyboardPlugin, option: ModOption, selected: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val currentChange by rememberUpdatedState(onChange)
    val preview by produceState<ImageBitmap?>(null, plugin.id, option.id, selected) {
        value = if (selected.isEmpty()) null else withContext(Dispatchers.IO) {
            UserPictures.thumbnail(context, plugin.id, option.id)?.asImageBitmap()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            failed = false
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { UserPictures.save(context, plugin.id, option.id, uri) }
                }
                result.onSuccess { currentChange(it) }.onFailure { failed = true }
            } finally {
                busy = false
            }
        }
    }
    Text(tr(option.title), style = MaterialTheme.typography.bodyMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        preview?.let {
            Image(it, tr(option.title), Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        }
        Column {
            OutlinedButton(enabled = !busy, onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(tr(if (selected.isEmpty()) "Выбрать изображение" else "Сменить изображение")) }
            if (selected.isNotEmpty()) TextButton(enabled = !busy, onClick = {
                UserPictures.clear(context, plugin.id, option.id)
                failed = false
                currentChange("")
            }) { Text(tr("Убрать изображение")) }
        }
    }
    if (failed) Text(tr("Не удалось прочитать изображение"), color = MaterialTheme.colorScheme.error)
}
