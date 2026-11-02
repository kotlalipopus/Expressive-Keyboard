@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.expressive.keyboard

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ============================================================================
// СВОИ ИИ-СЕРВИСЫ: список в настройках и окно добавления/правки
// ============================================================================

private fun saveCustom(context: Context, list: List<CustomAi>) {
    KeyboardPrefs.edit(context) { putString("ai_custom", SecretBox.encrypt(CustomAi.serialize(list))) }
}

@Composable
internal fun CustomAiItems(settings: KeyboardSettings) {
    val context = LocalContext.current
    // null — окно закрыто; id пустой — новый сервис
    var editing by remember { mutableStateOf<CustomAi?>(null) }
    settings.aiCustom.forEach { c ->
        val selected = settings.aiProvider == "CUSTOM:${c.id}"
        ActionItem(
            Icons.Rounded.Hub, c.title,
            listOf(
                c.base().substringAfter("://"),
                c.model.ifBlank { tr("модель — первая доступная") },
                if (c.key.isBlank()) tr("без ключа") else null,
                if (selected) tr("выбран") else null
            ).filterNotNull().joinToString(" · "),
            onClick = { editing = c }
        )
    }
    ActionItem(
        Icons.Rounded.AddCircle, "Добавить свой сервис",
        "DeepSeek, Mistral, xAI, Ollama, LM Studio или любой API как у OpenAI или Anthropic",
        onClick = { editing = CustomAi("", "", "") }
    )
    editing?.let { e ->
        CustomAiDialog(
            initial = e,
            onDismiss = { editing = null },
            onSave = { c ->
                val list = settings.aiCustom
                val saved = if (c.id.isEmpty()) c.copy(id = CustomAi.newId()) else c
                saveCustom(context, if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it } else list + saved)
                // первый свой сервис сразу становится выбранным, если раньше было «Авто»
                if (c.id.isEmpty() && settings.aiProvider == AiProvider.AUTO.id && list.isEmpty()) {
                    KeyboardPrefs.edit(context) { putString("ai_provider", "CUSTOM:${saved.id}") }
                }
                editing = null
            },
            onDelete = if (e.id.isEmpty()) null else ({
                saveCustom(context, settings.aiCustom.filterNot { it.id == e.id })
                if (settings.aiProvider == "CUSTOM:${e.id}") KeyboardPrefs.edit(context) { putString("ai_provider", AiProvider.AUTO.id) }
                editing = null
            })
        )
    }
}

@Composable
private fun CustomAiDialog(initial: CustomAi, onDismiss: () -> Unit, onSave: (CustomAi) -> Unit, onDelete: (() -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var key by remember { mutableStateOf(initial.key) }
    var model by remember { mutableStateOf(initial.model) }
    var format by remember { mutableStateOf(initial.format) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }
    val current = CustomAi(initial.id, name.trim(), url.trim(), key.trim(), model.trim(), format)
    val plainHttp = url.trim().startsWith("http://") && key.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { androidx.compose.material3.Icon(Icons.Rounded.Hub, null) },
        title = { Text(tr(if (initial.id.isEmpty()) "Свой ИИ-сервис" else "Изменить сервис")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (initial.id.isEmpty()) {
                    Text(tr("Заготовки"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        CustomAi.PRESETS.forEach { p ->
                            FilterChip(
                                selected = url == p.url && name == p.name,
                                onClick = {
                                    name = p.name; url = p.url; model = p.model; format = p.format; result = null
                                },
                                label = { Text(tr(p.name)) },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    name, { name = it }, singleLine = true, label = { Text(tr("Название")) },
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    url, { url = it; result = null }, singleLine = true, label = { Text(tr("Адрес API")) },
                    placeholder = { Text("https://api.example.com/v1") },
                    // как адрес поймётся — показываем, только если он отличается от введённого
                    supportingText = when {
                        url.isBlank() -> ({ Text(tr("Базовый адрес: …/v1, без /chat/completions")) })
                        current.base() != url.trim().trimEnd('/') -> ({ Text("→ ${current.base()}") })
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    key, { key = it; result = null }, singleLine = true, label = { Text(tr("Ключ API")) },
                    supportingText = { Text(tr("Можно оставить пустым для Ollama и LM Studio")) },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            androidx.compose.material3.Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, tr("Показать ключ"))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    model, { model = it; result = null }, singleLine = true, label = { Text(tr("Модель")) },
                    supportingText = { Text(tr("Пусто — первая модель из списка сервиса")) },
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                )
                Text(tr("Формат API"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                ButtonGroup(listOf("openai" to "OpenAI", "anthropic" to "Anthropic"), format) { format = it; result = null }
                AnimatedVisibility(plainHttp) {
                    InfoCard(Icons.Rounded.Warning, tr("Адрес без https: ключ и текст пойдут по сети открыто. Так можно только в своей домашней сети"))
                }
                FilledTonalButton(
                    onClick = {
                        if (testing) return@FilledTonalButton
                        testing = true
                        result = null
                        scope.launch {
                            val keys = AiKeys(AiProvider.CUSTOM, "", "", "", "", "", custom = listOf(current), customId = current.id)
                            val r = AiFix.test(context, keys, AiTarget(AiProvider.CUSTOM, current))
                            testing = false
                            result = r.fold({ resultOk = true; it }, { resultOk = false; it.message })
                        }
                    },
                    enabled = url.isNotBlank() && !testing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else androidx.compose.material3.Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr(if (testing) "Проверяю…" else "Проверить подключение"))
                }
                result?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodyMedium,
                        color = if (resultOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(current) }, enabled = url.isNotBlank()) { Text(tr("Сохранить")) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(tr("Удалить"), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(tr("Отмена")) }
            }
        }
    )
}
