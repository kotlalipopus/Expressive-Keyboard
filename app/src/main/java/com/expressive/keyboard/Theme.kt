@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
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
// 4. ТЕМА MONET (MATERIAL YOU)
// ============================================================================

internal val DarkFallback = darkColorScheme(
    primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
    surfaceBright = Color(0xFF3B383E), surfaceDim = Color(0xFF141218),
    surfaceContainerLowest = Color(0xFF0F0D13), surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainer = Color(0xFF211F26), surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
    onSurface = Color(0xFFE6E0E9), onSurfaceVariant = Color(0xFFCAC4D0), outlineVariant = Color(0xFF49454F)
)

internal val LightFallback = lightColorScheme(
    primary = Color(0xFF6750A4), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4), onTertiaryContainer = Color(0xFF31111D),
    surfaceBright = Color(0xFFFEF7FF), surfaceDim = Color(0xFFDED8E1),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF3EDF7), surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    onSurface = Color(0xFF1D1B20), onSurfaceVariant = Color(0xFF49454F), outlineVariant = Color(0xFFCAC4D0)
)

/** HSL-тон с той же логикой, что у тональных палитр Material: оттенок фиксирован, меняется светлота. */
private fun tone(h: Float, s: Float, l: Float): Color =
    Color.hsl(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))

/** Схема Material 3 из одного «зерна» — когда Monet недоступен или выбран свой цвет. */
fun seedColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(seed.toArgb(), hsl)
    val h = hsl[0]
    val s = hsl[1].coerceIn(0.25f, 0.9f)
    val th = h + 60f
    val n = 0.10f
    return if (!dark) lightColorScheme(
        primary = tone(h, s, 0.40f), onPrimary = Color.White,
        primaryContainer = tone(h, s * 0.95f, 0.88f), onPrimaryContainer = tone(h, s, 0.12f),
        inversePrimary = tone(h, s, 0.80f),
        secondary = tone(h, s * 0.3f, 0.40f), onSecondary = Color.White,
        secondaryContainer = tone(h, s * 0.45f, 0.88f), onSecondaryContainer = tone(h, s * 0.4f, 0.12f),
        tertiary = tone(th, s * 0.55f, 0.40f), onTertiary = Color.White,
        tertiaryContainer = tone(th, s * 0.6f, 0.88f), onTertiaryContainer = tone(th, s * 0.6f, 0.12f),
        background = tone(h, n, 0.975f), onBackground = tone(h, n, 0.11f),
        surface = tone(h, n, 0.975f), onSurface = tone(h, n, 0.11f),
        surfaceVariant = tone(h, n * 1.6f, 0.90f), onSurfaceVariant = tone(h, n * 1.6f, 0.30f),
        surfaceTint = tone(h, s, 0.40f),
        inverseSurface = tone(h, n, 0.19f), inverseOnSurface = tone(h, n, 0.95f),
        outline = tone(h, n, 0.50f), outlineVariant = tone(h, n, 0.80f),
        surfaceBright = tone(h, n, 0.985f), surfaceDim = tone(h, n, 0.86f),
        surfaceContainerLowest = Color.White, surfaceContainerLow = tone(h, n * 1.4f, 0.955f),
        surfaceContainer = tone(h, n * 1.6f, 0.935f), surfaceContainerHigh = tone(h, n * 1.6f, 0.915f),
        surfaceContainerHighest = tone(h, n * 1.6f, 0.895f)
    ) else darkColorScheme(
        primary = tone(h, s * 0.85f, 0.80f), onPrimary = tone(h, s, 0.20f),
        primaryContainer = tone(h, s * 0.8f, 0.30f), onPrimaryContainer = tone(h, s * 0.9f, 0.90f),
        inversePrimary = tone(h, s, 0.40f),
        secondary = tone(h, s * 0.3f, 0.80f), onSecondary = tone(h, s * 0.3f, 0.20f),
        secondaryContainer = tone(h, s * 0.3f, 0.28f), onSecondaryContainer = tone(h, s * 0.35f, 0.90f),
        tertiary = tone(th, s * 0.5f, 0.80f), onTertiary = tone(th, s * 0.5f, 0.20f),
        tertiaryContainer = tone(th, s * 0.45f, 0.30f), onTertiaryContainer = tone(th, s * 0.5f, 0.90f),
        background = tone(h, n, 0.065f), onBackground = tone(h, n, 0.90f),
        surface = tone(h, n, 0.065f), onSurface = tone(h, n, 0.90f),
        surfaceVariant = tone(h, n * 1.5f, 0.28f), onSurfaceVariant = tone(h, n * 1.5f, 0.80f),
        surfaceTint = tone(h, s * 0.85f, 0.80f),
        inverseSurface = tone(h, n, 0.90f), inverseOnSurface = tone(h, n, 0.19f),
        outline = tone(h, n, 0.58f), outlineVariant = tone(h, n, 0.30f),
        surfaceBright = tone(h, n, 0.235f), surfaceDim = tone(h, n, 0.065f),
        surfaceContainerLowest = tone(h, n, 0.04f), surfaceContainerLow = tone(h, n * 1.3f, 0.10f),
        surfaceContainer = tone(h, n * 1.4f, 0.12f), surfaceContainerHigh = tone(h, n * 1.4f, 0.165f),
        surfaceContainerHighest = tone(h, n * 1.4f, 0.21f)
    )
}

fun resolveColorScheme(context: Context, dark: Boolean, amoled: Boolean, settings: KeyboardSettings = KeyboardSettings()): ColorScheme {
    val base = when {
        settings.colorSource == "SEED" -> seedColorScheme(Color(settings.seedColor), dark)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
    return if (!amoled) base else base.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF08080A),
        surfaceContainer = Color.Black,
        surfaceContainerHigh = Color(0xFF1A1A20),
        surfaceContainerHighest = Color(0xFF24242C),
        surfaceBright = Color(0xFF26262E)
    )
}

internal fun typographyWith(f: FontFamily): Typography {
    val t = Typography()
    return Typography(
        displayLarge = t.displayLarge.copy(fontFamily = f),
        displayMedium = t.displayMedium.copy(fontFamily = f),
        displaySmall = t.displaySmall.copy(fontFamily = f),
        headlineLarge = t.headlineLarge.copy(fontFamily = f, fontWeight = FontWeight.ExtraBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = f, fontWeight = FontWeight.ExtraBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = f, fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontFamily = f, fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontFamily = f, fontWeight = FontWeight.Bold),
        titleSmall = t.titleSmall.copy(fontFamily = f, fontWeight = FontWeight.Bold),
        bodyLarge = t.bodyLarge.copy(fontFamily = f),
        bodyMedium = t.bodyMedium.copy(fontFamily = f),
        bodySmall = t.bodySmall.copy(fontFamily = f),
        labelLarge = t.labelLarge.copy(fontFamily = f, fontWeight = FontWeight.Bold),
        labelMedium = t.labelMedium.copy(fontFamily = f, fontWeight = FontWeight.Bold),
        labelSmall = t.labelSmall.copy(fontFamily = f)
    )
}

val LocalKeyWeight = staticCompositionLocalOf { FontWeight.Bold }

@Composable
fun ExpressiveTheme(settings: KeyboardSettings, appUi: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        "DARK", "AMOLED" -> true
        "LIGHT" -> false
        else -> systemDark
    }
    val configuration = LocalConfiguration.current
    val scheme = remember(dark, settings.themeMode, settings.colorSource, settings.seedColor, configuration) {
        resolveColorScheme(context, dark, settings.themeMode == "AMOLED", settings)
    }
    val font = remember(settings.fontStyle, settings.customFontVersion) { FontRepository.resolve(context, settings) }
    // экран настроек не берёт свой/моно/serif шрифт клавиатуры: с ним заголовки и кнопки расползаются
    val uiFont = remember(font, appUi, settings.fontStyle) {
        if (appUi && settings.fontStyle != "ROUNDED" && settings.fontStyle != "SYSTEM") FontRepository.resolve(context, KeyboardSettings()) else font
    }
    val typography = remember(uiFont) { typographyWith(uiFont) }
    CompositionLocalProvider(LocalKeyFont provides font, LocalKeyWeight provides FontWeight(settings.fontWeight)) {
        // настоящая тема M3 Expressive: пружинные анимации MotionScheme.expressive() у всех компонентов material3
        MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), typography = typography, content = content)
    }
}
