@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class
)

package com.expressive.keyboard

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface as AndroidTypeface
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
// НАСТРОЙКИ: РЕЗЕРВНАЯ КОПИЯ
// ============================================================================

@Composable
internal fun BackupPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(KeyboardPrefs.exportJson(context).toByteArray()) } }.isSuccess
            }
            Toast.makeText(context, if (ok) tr("Настройки сохранены") else tr("Не удалось сохранить"), Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Файл не открылся")
                    KeyboardPrefs.importJson(context, raw.trimStart { it.code == 0xFEFF || it.isWhitespace() })
                }
            }
            r.onSuccess { Toast.makeText(context, tr("Восстановлено параметров: {0}", it), Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, tr("Ошибка: {0}", it.message), Toast.LENGTH_LONG).show() }
        }
    }
    T9BackupSection()
    SettingsSection("Настройки") {
        ActionItem(Icons.Rounded.Upload, "Экспорт настроек", "Сохранить все параметры в .json", onClick = {
            exportLauncher.launch("expressive-keyboard-settings.json")
        })
        ActionItem(Icons.Rounded.Download, "Импорт настроек", "Восстановить из файла", onClick = {
            // список из нескольких типов вместе с */* на части телефонов (MIUI, One UI) прячет или блокирует файлы
            runCatching { importLauncher.launch(arrayOf("*/*")) }.onFailure {
                Toast.makeText(context, tr("На телефоне нет приложения для выбора файлов"), Toast.LENGTH_LONG).show()
            }
        })
        ActionItem(Icons.Rounded.RestartAlt, "Сбросить настройки", "Словарь, плагины и недавние эмодзи сохранятся", onClick = { confirm = "settings" })
    }
    SettingsSection("Данные") {
        ActionItem(Icons.Rounded.AutoFixHigh, "Очистить выученные слова", "Подсказки забудут ваши слова и пары слов", onClick = { confirm = "dict" })
        ActionItem(Icons.Rounded.History, "Очистить недавние эмодзи", null, onClick = {
            EmojiRecents.clear(context)
            Toast.makeText(context, tr("Готово"), Toast.LENGTH_SHORT).show()
        })
        ActionItem(Icons.Rounded.DeleteForever, "Удалить все личные данные", "Выученные слова, история буфера, недавние эмодзи и ключи API", onClick = { confirm = "all" })
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Rounded.RestartAlt, null) },
            title = { Text(tr(when (what) { "dict" -> "Очистить словарь?"; "all" -> "Удалить личные данные?"; else -> "Сбросить настройки?" })) },
            text = {
                Text(
                    tr(when (what) {
                        "dict" -> "Все выученные слова будут удалены."
                        "all" -> "Клавиатура забудет ваши слова, историю буфера обмена, недавние эмодзи и ключи ИИ-сервисов и GIF. Настройки и темы останутся."
                        else -> "Размеры, цвета, тема, шрифт, фон и вибрация вернутся к значениям по умолчанию."
                    })
                )
            },
            confirmButton = {
                Button(onClick = {
                    when (what) {
                        "dict" -> {
                            WordPredictorEngine.clearUserData()
                            CjkUser.clear()
                        }
                        "all" -> {
                            PrivateData.wipe(context)
                            Toast.makeText(context, tr("Личные данные удалены"), Toast.LENGTH_SHORT).show()
                        }
                        else -> KeyboardPrefs.resetAll(context)
                    }
                    confirm = null
                }) { Text(tr(when (what) { "dict" -> "Очистить"; "all" -> "Удалить"; else -> "Сбросить" })) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Отмена")) } }
        )
    }
}

/** Словарь Т9 переживает переустановку: автокопия в папку пользователя, экспорт и восстановление. */
@Composable
internal fun T9BackupSection(title: String = "Словарь Т9", compact: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(LearnedBackup.folder(context)) }
    var last by remember { mutableStateOf(LearnedBackup.lastSavedMs(context)) }
    var found by remember { mutableStateOf<String?>(null) }
    fun restore(raw: String) = scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { LearnedBackup.restore(context, raw) } }
        r.onSuccess { Toast.makeText(context, tr("Восстановлено: слов {0}, параметров {1}", it.words, it.settings), Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(context, tr("Ошибка: {0}", it.message), Toast.LENGTH_LONG).show() }
    }
    fun saveNow() = scope.launch {
        val ok = withContext(Dispatchers.IO) { LearnedBackup.saveNow(context) }
        last = LearnedBackup.lastSavedMs(context)
        Toast.makeText(context, if (ok) tr("Копия сохранена") else tr("Нет доступа к папке — выберите её заново"), Toast.LENGTH_SHORT).show()
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val old = withContext(Dispatchers.IO) { LearnedBackup.setFolder(context, uri) }
            folder = LearnedBackup.folder(context)
            if (old != null) found = old else saveNow()
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                WordPredictorEngine.flush()
                delay(300)
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(LearnedBackup.build(context).toByteArray()) } }.isSuccess
            }
            Toast.makeText(context, if (ok) tr("Копия сохранена") else tr("Не удалось сохранить"), Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            }
            if (raw != null) restore(raw) else Toast.makeText(context, tr("Файл не открылся"), Toast.LENGTH_SHORT).show()
        }
    }
    SettingsSection(title) {
        val sub = if (folder == null) "Выберите папку (например, «Документы») — словарь и настройки будут сохраняться туда и вернутся после переустановки"
        else if (last > 0) tr("Включена · последняя копия {0}", java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(last)))
        else "Включена"
        ActionItem(Icons.Rounded.FolderOpen, "Автокопия в папку", sub, onClick = {
            runCatching { treeLauncher.launch(null) }.onFailure {
                Toast.makeText(context, tr("На телефоне нет приложения для выбора файлов"), Toast.LENGTH_LONG).show()
            }
        })
        if (folder != null && !compact) {
            ActionItem(Icons.Rounded.Save, "Сохранить копию сейчас", null, onClick = { saveNow() })
            ActionItem(Icons.Rounded.FolderOff, "Отключить автокопию", "Файл в папке останется", onClick = {
                LearnedBackup.clearFolder(context)
                folder = null
                last = 0L
            })
        }
        if (!compact) ActionItem(Icons.Rounded.Upload, "Экспорт словаря и настроек", "Выученные слова, пары слов и «Мой словарь» в одном .json", onClick = {
            exportLauncher.launch(LearnedBackup.FILE_NAME)
        })
        ActionItem(Icons.Rounded.Download, "Восстановить из копии", "Слова добавятся к уже выученным", onClick = {
            runCatching { importLauncher.launch(arrayOf("*/*")) }.onFailure {
                Toast.makeText(context, tr("На телефоне нет приложения для выбора файлов"), Toast.LENGTH_LONG).show()
            }
        })
    }
    found?.let { raw ->
        AlertDialog(
            onDismissRequest = { found = null; saveNow() },
            icon = { Icon(Icons.Rounded.Restore, null) },
            title = { Text(tr("В папке уже есть копия")) },
            text = { Text(tr("Восстановить из неё словарь и настройки? Выученные на этом телефоне слова останутся.")) },
            confirmButton = {
                Button(onClick = {
                    found = null
                    scope.launch {
                        restore(raw).join()
                        saveNow()
                    }
                }) { Text(tr("Восстановить")) }
            },
            dismissButton = { TextButton(onClick = { found = null; saveNow() }) { Text(tr("Заменить новой")) } }
        )
    }
}

// ----------------------------------------------------------------------- о приложении
