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
// 5. ВИБРАЦИЯ И ЗВУК
// ============================================================================

object HapticsEngine {
    @Volatile private var cached: Vibrator? = null
    @Volatile private var hasMotor: Boolean? = null
    @Volatile private var amplitudeControl = false
    @Volatile private var audio: AudioManager? = null
    /** Вибрация — системный вызов; он уходит в свой поток, чтобы не задерживать обработку касания. */
    private val worker: android.os.Handler by lazy {
        android.os.Handler(android.os.HandlerThread("haptics").also { it.start() }.looper)
    }

    private fun vibrator(context: Context): Vibrator? {
        cached?.let { return it }
        val v = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()
        cached = v
        hasMotor = runCatching { v?.hasVibrator() == true }.getOrDefault(false)
        amplitudeControl = runCatching { v?.hasAmplitudeControl() == true }.getOrDefault(false)
        return v
    }

    /** Мотор есть: проверяется один раз, а не на каждое нажатие. */
    private fun motor(context: Context): Vibrator? {
        val v = vibrator(context)
        return if (hasMotor == true) v else null
    }

    private fun play(v: Vibrator, effect: VibrationEffect) {
        worker.post { runCatching { v.vibrate(effect) } }
    }

    // ---------------------------------------------------------------- свой звук нажатия

    private var pool: android.media.SoundPool? = null
    /** Звуки в пуле: [0] — буквы, [1] — пробел, Enter, ⌫ (у своего файла — один и тот же). */
    private var poolSounds = IntArray(0)
    private var poolKey = ""
    private val loaded = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val jitter = java.util.Random()
    /** Звук, который попросили сыграть до окончания загрузки: (id, громкость, когда). */
    @Volatile private var pending: Triple<Int, Float, Long>? = null

    const val SOUND_FILE = "click_sound"

    /** Что звучит сейчас: синтезированный стиль, свой файл или системный щелчок (null). */
    private fun soundFiles(context: Context, s: KeyboardSettings): Pair<String, List<java.io.File>>? {
        val style = KeySoundStyle.fromId(s.soundStyle)
        if (style == KeySoundStyle.CUSTOM) {
            if (s.customSoundVersion == 0L) return null
            val f = java.io.File(context.filesDir, SOUND_FILE)
            return if (f.exists()) "custom:${s.customSoundVersion}" to listOf(f, f) else null
        }
        if (style == KeySoundStyle.SYSTEM) return null
        val files = KeySounds.files(context, style) ?: return null
        return style.id to files
    }

    /** Пул SoundPool под нужные звуки: минимальная задержка; пересоздаётся только при смене звука. */
    private fun ensurePool(context: Context, s: KeyboardSettings): Boolean {
        // ключ считается без обращения к диску: проверка файлов — только когда звук сменили
        val style = KeySoundStyle.fromId(s.soundStyle)
        val quickKey = when (style) {
            KeySoundStyle.SYSTEM -> ""
            KeySoundStyle.CUSTOM -> if (s.customSoundVersion == 0L) "" else "custom:${s.customSoundVersion}"
            else -> style.id
        }
        if (quickKey == poolKey) return pool != null
        val want = soundFiles(context, s)
        poolKey = quickKey
        loaded.clear()
        runCatching { pool?.release() }
        pool = null
        poolSounds = IntArray(0)
        if (want == null) return false
        pool = runCatching {
            android.media.SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build().also { sp ->
                    sp.setOnLoadCompleteListener { pool, id, status ->
                        if (status != 0) return@setOnLoadCompleteListener
                        loaded += id
                        // нажали, пока звук грузился (выбор стиля в настройках, первая буква) — играем его, а не тишину
                        val p = pending
                        if (p != null && p.first == id && android.os.SystemClock.uptimeMillis() - p.third < 600L) {
                            pending = null
                            runCatching { pool.play(id, p.second, p.second, 1, 0, 1f) }
                        }
                    }
                    val ids = want.second.map { it.absolutePath }.distinct().associateWith { sp.load(it, 1) }
                    poolSounds = IntArray(want.second.size) { ids[want.second[it].absolutePath] ?: 0 }
                }
        }.getOrNull()
        return pool != null
    }

    /** Звук без вибрации: при выборе стиля в настройках. */
    fun previewSound(context: Context, s: KeyboardSettings, special: Boolean = false) {
        playSound(context, s.copy(soundEnabled = true), special)
    }

    private fun playSound(context: Context, s: KeyboardSettings, special: Boolean) {
        if (!s.soundEnabled) return
        if (ensurePool(context, s)) {
            val id = poolSounds.getOrElse(if (special) 1 else 0) { 0 }
            if (id != 0 && id in loaded) {
                // чуть разная высота у соседних нажатий — звук не «пулемётный»
                val custom = poolKey.startsWith("custom:")
                val rate = (if (special && custom) 0.92f else 1f) * (0.97f + jitter.nextFloat() * 0.06f)
                runCatching { pool?.play(id, s.soundVolume, s.soundVolume, 1, 0, rate) }
            } else if (id != 0) {
                pending = Triple(id, s.soundVolume, android.os.SystemClock.uptimeMillis())
            }
        } else systemClick(context, s, special)
    }

    private fun systemClick(context: Context, s: KeyboardSettings, special: Boolean) = runCatching {
        val audio = audio ?: (context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager).also { audio = it }
        // системный звук — вызов в другой процесс: в свой поток, чтобы касание не ждало его
        val fx = if (special) AudioManager.FX_KEYPRESS_SPACEBAR else AudioManager.FX_KEYPRESS_STANDARD
        val vol = s.soundVolume
        worker.post { runCatching { audio?.playSoundEffect(fx, vol) } }
    }

    fun keyPress(context: Context, view: View?, s: KeyboardSettings, special: Boolean) {
        playSound(context, s, special)
        if (!s.hapticsEnabled) return
        runCatching {
            val v = motor(context)
            if (v == null) {
                view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                return
            }
            val boost = s.strongSpecialKeys && special
            val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            when (HapticMode.fromId(s.hapticMode)) {
                HapticMode.CLICK -> if (q) play(v,
                    VibrationEffect.createPredefined(if (boost) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK)
                ) else oneShot(v, if (boost) 22L else 12L, if (boost) 200 else 140)
                HapticMode.TICK -> if (q) play(v,
                    VibrationEffect.createPredefined(if (boost) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK)
                ) else oneShot(v, if (boost) 14L else 8L, if (boost) 130 else 80)
                HapticMode.HEAVY -> if (q) play(v,
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                ) else oneShot(v, 28L, 255)
                HapticMode.CUSTOM -> {
                    val d = s.vibrationMs.toLong().coerceIn(1L, 100L)
                    val a = s.vibrationAmplitude.toInt().coerceIn(1, 255)
                    oneShot(v, if (boost) (d * 1.3f).toLong() else d, if (boost) (a * 1.25f).toInt() else a)
                }
            }
        }
    }

    fun tick(context: Context, view: View?, s: KeyboardSettings) {
        if (!s.hapticsEnabled) return
        runCatching {
            val v = motor(context)
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    play(v, VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                } else oneShot(v, 7L, 80)
            } else view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun release(context: Context, s: KeyboardSettings) {
        if (!s.hapticsEnabled || !s.vibrateOnRelease) return
        runCatching {
            val v = motor(context) ?: return
            oneShot(v, (s.vibrationMs * 0.45f).toLong(), (s.vibrationAmplitude * 0.5f).toInt())
        }
    }

    fun preview(context: Context, mode: HapticMode, durationMs: Float, amplitude: Float) {
        keyPress(
            context, null,
            KeyboardSettings(hapticMode = mode.id, vibrationMs = durationMs, vibrationAmplitude = amplitude),
            false
        )
    }

    private fun oneShot(v: Vibrator, duration: Long, amplitude: Int) {
        runCatching {
            val d = duration.coerceIn(1L, 100L)
            val a = if (amplitudeControl) amplitude.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
            play(v, VibrationEffect.createOneShot(d, a))
        }
    }
}
