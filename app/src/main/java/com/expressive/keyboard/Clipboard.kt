package com.expressive.keyboard

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ============================================================================
// КАРТИНКИ В БУФЕРЕ ОБМЕНА
// Запись истории — строка. Картинка хранится файлом в files/clips, а в историю
// попадает строка-ссылка с невидимым префиксом: так текст и картинки живут
// в одном списке, закрепляются и удаляются одинаково.
// ============================================================================

object ClipImages {
    private const val PREFIX = "\u0000img:"
    private const val MAX_BYTES = 256L * 1024 * 1024

    fun isImage(entry: String) = entry.startsWith(PREFIX)

    fun file(entry: String): File = File(entry.removePrefix(PREFIX).substringBefore('\u0000'))

    fun mime(entry: String): String = entry.substringAfterLast('\u0000', "image/png").ifEmpty { "image/png" }

    private fun dir(context: Context) = File(context.filesDir, "clips").apply { mkdirs() }

    /** Копирует картинку из буфера к себе: доступ к чужому uri пропадает, а история должна жить дальше. */
    suspend fun save(context: Context, uri: Uri, mime: String, stamp: Long): String? = withContext(Dispatchers.IO) {
        runCatching {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
            val out = File(dir(context), "clip_$stamp.$ext")
            if (!out.exists() || out.length() == 0L) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IllegalStateException("too big")
                            o.write(buf, 0, n)
                        }
                    }
                } ?: return@runCatching null
            }
            PREFIX + out.absolutePath + "\u0000" + mime
        }.getOrElse { null }
    }

    fun delete(entry: String) {
        if (isImage(entry)) runCatching { file(entry).delete() }
    }

    /** Удалить файлы, на которые больше ничто не ссылается. */
    fun cleanup(context: Context, keep: Collection<String>) {
        val used = keep.filter { isImage(it) }.map { file(it).absolutePath }.toSet()
        runCatching { dir(context).listFiles()?.forEach { if (it.absolutePath !in used) it.delete() } }
    }

    /** Миниатюры уже открытых картинок: панель буфера не декодирует их заново при каждом открытии. */
    private val thumbs = object : android.util.LruCache<String, ImageBitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun cachedThumbnail(entry: String, maxPx: Int): ImageBitmap? = thumbs.get("$maxPx|$entry")

    /** Уменьшенная копия для плитки: не больше maxPx по большей стороне. */
    fun thumbnail(entry: String, maxPx: Int): ImageBitmap? = cachedThumbnail(entry, maxPx) ?: runCatching {
        val f = file(entry)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxPx || o.outHeight / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()?.also { thumbs.put("$maxPx|$entry", it) }
}

/**
 * История буфера на диске: раньше она жила только в памяти и пропадала, как только система закрывала
 * клавиатуру. Запись — текст или ссылка на картинку и время, когда её последний раз копировали или вставляли.
 */
object ClipStore {
    private const val FILE = "clip_history.json"
    private val saver = CoroutineScope(SupervisorJob() + Dispatchers.IO + BackgroundErrors)
    private var saveJob: Job? = null
    /** Когда запись последний раз копировали или вставляли. */
    private val times = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** Сколько живёт запись: из настройки «Хранить» (0 — всегда). */
    fun keepMs(s: KeyboardSettings): Long = when (s.clipboardKeep) {
        "1H" -> 3_600_000L
        "1D" -> 86_400_000L
        "7D" -> 7 * 86_400_000L
        "30D" -> 30 * 86_400_000L
        else -> 0L
    }

    fun touch(entry: String, at: Long = System.currentTimeMillis()) {
        times[entry] = at
    }

    fun forget(entry: String) {
        times.remove(entry)
    }

    /** Записи по порядку (новые сверху) без устаревших. */
    fun load(context: Context, s: KeyboardSettings): List<String> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        val keep = keepMs(s)
        val now = System.currentTimeMillis()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("t")
            val at = o.optLong("at", now)
            if (t.isEmpty() || (keep > 0 && now - at > keep)) continue
            if (ClipImages.isImage(t) && !ClipImages.file(t).exists()) continue
            times[t] = at
            out += t
        }
        out
    }.getOrDefault(emptyList())

    /** Убрать устаревшие записи из списка (настройка «Хранить»); возвращает удалённые. */
    fun expired(list: List<String>, s: KeyboardSettings): List<String> {
        val keep = keepMs(s)
        if (keep <= 0) return emptyList()
        val now = System.currentTimeMillis()
        return list.filter { now - (times[it] ?: now) > keep }
    }

    /** Сохранить в фоне (несколько изменений подряд — одна запись на диск). */
    fun save(context: Context, list: List<String>) {
        val app = context.applicationContext
        val snapshot = list.toList()
        synchronized(this) {
            saveJob?.cancel()
            saveJob = saver.launch {
                delay(400L)
                val arr = JSONArray()
                val now = System.currentTimeMillis()
                for (t in snapshot) arr.put(JSONObject().put("t", t).put("at", times[t] ?: now))
                val f = file(app)
                val tmp = File(f.parentFile, "$FILE.tmp")
                tmp.writeText(arr.toString())
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            }
        }
    }

    fun clear(context: Context) {
        times.clear()
        runCatching { file(context).delete() }
    }

    // ------------------------------------------------------------------ скриншоты

    private const val PREF_LAST_ID = "shot_last_id"
    private var observer: android.database.ContentObserver? = null
    private var scanJob: Job? = null

    fun hasPhotoAccess(context: Context): Boolean {
        fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            granted(android.Manifest.permission.READ_MEDIA_IMAGES) ||
                (android.os.Build.VERSION.SDK_INT >= 34 && granted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))
        } else granted(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Разрешения для «скриншотов в буфере» — их просит экран настроек. */
    fun photoPermissions(): Array<String> = when {
        android.os.Build.VERSION.SDK_INT >= 34 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        android.os.Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * Следить за новыми скриншотами, пока клавиатура жива; onFound — в главном потоке.
     * Без разрешения или с выключенной настройкой — ничего не делает.
     */
    fun watch(context: Context, s: KeyboardSettings, onFound: (Uri, String, Long) -> Unit) {
        val app = context.applicationContext
        val on = s.clipScreenshots && s.clipboardHistory && !s.incognito && hasPhotoAccess(app)
        if (!on) {
            observer?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
            observer = null
            return
        }
        if (observer != null) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val o = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // скриншот пишется в несколько шагов: ждём, пока система его допишет
                scanJob?.cancel()
                scanJob = saver.launch {
                    delay(700L)
                    scan(app, onFound)
                }
            }
        }
        runCatching {
            app.contentResolver.registerContentObserver(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
            observer = o
        }
    }

    fun stop(context: Context) {
        observer?.let { runCatching { context.applicationContext.contentResolver.unregisterContentObserver(it) } }
        observer = null
        scanJob?.cancel()
    }

    /** Скриншоты, сделанные, пока клавиатура была закрыта (не старше суток), — при её появлении. */
    fun scanScreenshots(context: Context, s: KeyboardSettings, onFound: (Uri, String, Long) -> Unit) {
        watch(context, s, onFound)
        if (observer == null) return
        val app = context.applicationContext
        scanJob?.cancel()
        scanJob = saver.launch { scan(app, onFound) }
    }

    private suspend fun scan(app: Context, onFound: (Uri, String, Long) -> Unit) {
        val prefs = app.getSharedPreferences(WordPredictorEngine.DATA_PREFS, Context.MODE_PRIVATE)
        val images = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val found = ArrayList<Triple<Uri, String, Long>>()
        runCatching {
            val last = prefs.getLong(PREF_LAST_ID, -1L)
            val cols = arrayOf(
                android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                android.provider.MediaStore.Images.Media.MIME_TYPE,
                android.provider.MediaStore.Images.Media.DATE_ADDED,
                if (android.os.Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.Images.Media.RELATIVE_PATH
                else @Suppress("DEPRECATION") android.provider.MediaStore.Images.Media.DATA
            )
            app.contentResolver.query(images, cols, null, null, "${android.provider.MediaStore.Images.Media._ID} DESC")?.use { c ->
                var maxId = last
                var n = 0
                while (c.moveToNext() && n < 40) {
                    n++
                    val id = c.getLong(0)
                    if (id > maxId) maxId = id
                    // первый запуск: старые скриншоты не тащим, только запоминаем, докуда видели
                    if (last < 0 || id <= last) break
                    val name = c.getString(1).orEmpty()
                    val path = c.getString(4).orEmpty()
                    val shot = "screenshot" in path.lowercase() || "screenshot" in name.lowercase() || "скриншот" in path.lowercase()
                    if (!shot) continue
                    val added = c.getLong(3) * 1000L
                    if (System.currentTimeMillis() - added > 86_400_000L) continue
                    found += Triple(android.content.ContentUris.withAppendedId(images, id), c.getString(2) ?: "image/png", added)
                    if (found.size >= 5) break
                }
                if (maxId != last) prefs.edit().putLong(PREF_LAST_ID, maxId).apply()
            }
        }
        if (found.isEmpty()) return
        withContext(Dispatchers.Main) {
            // старые первыми: самый новый скриншот окажется наверху
            for ((uri, mime, at) in found.reversed()) onFound(uri, mime, at)
        }
    }
}

/** Миниатюра картинки из буфера; пока грузится — пустая подложка. */
@Composable
internal fun ClipThumb(entry: String, modifier: Modifier, placeholder: Color, maxPx: Int = 360) {
    val bmp = produceState(ClipImages.cachedThumbnail(entry, maxPx), entry) {
        if (value == null) value = withContext(Dispatchers.IO) { ClipImages.thumbnail(entry, maxPx) }
    }.value
    if (bmp != null) Image(bmp, null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(placeholder))
}
