package com.expressive.keyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ============================================================================
// ОБНОВЛЕНИЯ ПО ВОЗДУХУ (OTA)
// Новая версия — это релиз на GitHub (kotlalipopus/Expressive-Keyboard) с приложенным APK.
// Приложение смотрит последний релиз, сравнивает его номер со своим, скачивает APK и открывает
// системную установку. Обновление встанет поверх, только если APK подписан тем же ключом.
// ============================================================================

data class UpdateInfo(val version: String, val notes: String, val apkUrl: String, val size: Long, val page: String)

object Updater {
    private const val REPO = "kotlalipopus/Expressive-Keyboard"
    private const val PREF_AUTO = "auto_update_check"

    /** Найденное обновление (null — его нет или ещё не проверяли). */
    var available by mutableStateOf<UpdateInfo?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    /** Скачано, 0…1; null — не качаем. */
    var progress by mutableStateOf<Float?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Проверяли и обновлений нет — для кнопки «Проверить обновления». */
    var upToDate by mutableStateOf(false)
        private set
    private var autoChecked = false

    fun autoEnabled(context: Context): Boolean = KeyboardPrefs.prefs(context).getBoolean(PREF_AUTO, true)
    fun setAuto(context: Context, on: Boolean) = KeyboardPrefs.edit(context) { putBoolean(PREF_AUTO, on) }

    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty().substringBefore('-')

    /** «11.1.10» > «11.1.9»: сравниваем числа по порядку. */
    internal fun newer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Один раз за запуск, если не выключено в настройках. */
    suspend fun autoCheck(context: Context) {
        if (autoChecked || !autoEnabled(context)) return
        autoChecked = true
        check(context, quiet = true)
    }

    suspend fun check(context: Context, quiet: Boolean = false) {
        if (checking) return
        checking = true
        error = null
        upToDate = false
        try {
            val info = withContext(Dispatchers.IO) { fetchLatest() }
            val local = currentVersion(context)
            available = info?.takeIf { local.isNotEmpty() && newer(it.version, local) }
            upToDate = available == null
        } catch (e: Exception) {
            if (!quiet) error = tr("Не удалось проверить обновления: нет связи с GitHub")
        } finally {
            checking = false
        }
    }

    private fun fetchLatest(): UpdateInfo? {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ExpressiveKeyboard")
        }
        val body = try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val o = JSONObject(body)
        val tag = o.optString("tag_name").ifEmpty { return null }
        val assets = o.optJSONArray("assets") ?: return null
        var best: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true) || name.contains("debug", ignoreCase = true)) continue
            // APK с понятным именем — в приоритете; остальные — если другого нет
            if (best == null || name.startsWith("ExpressiveKeyboard-")) best = a
        }
        val apk = best ?: return null
        return UpdateInfo(
            version = tag.removePrefix("v"),
            notes = o.optString("body").trim(),
            apkUrl = apk.optString("browser_download_url"),
            size = apk.optLong("size"),
            page = o.optString("html_url")
        )
    }

    /** Скачать APK и открыть установку. Без разрешения «Установка неизвестных приложений» — сначала просим его. */
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        if (progress != null) return
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            error = tr("Разрешите установку обновлений и нажмите ещё раз")
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        error = null
        progress = 0f
        try {
            val file = withContext(Dispatchers.IO) { download(context, info) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            error = tr("Не удалось скачать обновление")
        } finally {
            progress = null
        }
    }

    private fun download(context: Context, info: UpdateInfo): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "ExpressiveKeyboard-${info.version}.apk")
        var url = URL(info.apkUrl)
        // GitHub отдаёт файл через переадресацию на другой адрес — идём по ней сами
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 20000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "ExpressiveKeyboard")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                url = URL(url, next)
                continue
            }
            break
        }
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (info.size > 0 && out.length() != info.size) throw java.io.IOException("incomplete download")
        return out
    }
}

/** Карточка «Доступна новая версия»: что нового и кнопка обновления. */
@Composable
internal fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Updater.autoCheck(context) }
    val info = Updater.available
    val cs = MaterialTheme.colorScheme
    AnimatedVisibility(info != null) {
        val u = info ?: return@AnimatedVisibility
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(cs.tertiaryContainer).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = cs.onTertiaryContainer)
                Text(
                    tr("Доступна версия {0}", u.version), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold, color = cs.onTertiaryContainer
                )
            }
            if (u.notes.isNotEmpty()) {
                Text(
                    u.notes, style = MaterialTheme.typography.bodyMedium, color = cs.onTertiaryContainer.copy(alpha = 0.85f),
                    maxLines = 8, overflow = TextOverflow.Ellipsis
                )
            }
            val p = Updater.progress
            if (p != null) {
                LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Text(tr("Скачиваю… {0}%", (p * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer)
            } else {
                Button(onClick = { scope.launch { Updater.downloadAndInstall(context, u) } }) {
                    Text(tr("Обновить"))
                }
            }
            Updater.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
        }
    }
}
