package com.expressive.keyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import org.json.JSONObject

// ============================================================================
// РЕЗЕРВНАЯ КОПИЯ СЛОВАРЯ Т9 И НАСТРОЕК
// Данные приложения удаляются вместе с ним, поэтому после переустановки выученные слова пропадали.
// Копия — один .json: настройки (без ключей API) + выученные слова, пары слов и «Мой словарь».
// Автокопия пишется в папку, которую выбрал пользователь (например, «Документы»): эта папка
// не удаляется вместе с приложением, и после переустановки словарь восстанавливается из неё.
// Плюс системная резервная копия Android (data_extraction_rules.xml): при переустановке
// и переезде на новый телефон Android сам возвращает словарь, если копирование включено.
// ============================================================================

object LearnedBackup {
    const val FORMAT = "expressive-keyboard-backup"
    const val FILE_NAME = "ExpressiveKeyboard-backup.json"

    /** Локальное, не попадает ни в копию, ни в экспорт: доступ к папке после переустановки всё равно пропадает. */
    private const val LOCAL_PREFS = "expressive_keyboard_local"
    private const val KEY_TREE = "backup_tree_uri"
    private const val KEY_LAST = "backup_last_ms"
    private const val MIN_INTERVAL_MS = 5 * 60_000L

    private const val CJK = "cjk_user_words"

    @Volatile private var lastAutoSave = 0L

    private fun local(context: Context) = context.applicationContext.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)

    fun folder(context: Context): Uri? = local(context).getString(KEY_TREE, null)?.let(Uri::parse)

    fun lastSavedMs(context: Context): Long = local(context).getLong(KEY_LAST, 0L)

    fun build(context: Context): String {
        val app = context.applicationContext
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", 1)
        root.put("app_version", BuildConfig.VERSION_NAME)
        root.put("created", System.currentTimeMillis())
        root.put("settings", JSONObject(KeyboardPrefs.exportJson(app)))
        val dict = JSONObject()
        WordPredictorEngine.exportUserData(app).forEach { (k, v) -> dict.put(k, v) }
        CjkUser.export(app)?.let { dict.put(CJK, it) }
        root.put("dictionary", dict)
        return root.toString()
    }

    class Restored(val settings: Int, val words: Int)

    /**
     * Понимает и полную копию, и старый файл «Экспорт настроек».
     * Словарь объединяется с текущим, настройки — только если [withSettings].
     */
    fun restore(context: Context, raw: String, withSettings: Boolean = true): Restored {
        val app = context.applicationContext
        val text = raw.trimStart { it.code == 0xFEFF || it.isWhitespace() }
        val root = JSONObject(text)
        if (root.optString("format") == "expressive-keyboard-settings") {
            return Restored(KeyboardPrefs.importJson(app, text), 0)
        }
        require(root.optString("format") == FORMAT) { "Это не резервная копия клавиатуры" }
        val settings = if (withSettings) root.optJSONObject("settings")?.let { KeyboardPrefs.importJson(app, it.toString()) } ?: 0 else 0
        var words = 0
        root.optJSONObject("dictionary")?.let { d ->
            val map = HashMap<String, String>()
            for (k in d.keys()) if (k != CJK) map[k] = d.optString(k)
            words = WordPredictorEngine.mergeUserData(app, map)
            d.optString(CJK).takeIf { it.isNotEmpty() }?.let { CjkUser.merge(app, it) }
        }
        return Restored(settings, words)
    }

    /** Пользователь выбрал папку для автокопии; возвращает найденную в ней старую копию (если есть). */
    fun setFolder(context: Context, tree: Uri): String? {
        val app = context.applicationContext
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        local(app).edit().putString(KEY_TREE, tree.toString()).apply()
        return runCatching {
            findFile(app, tree)?.let { uri -> app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
        }.getOrNull()
    }

    fun clearFolder(context: Context) {
        val app = context.applicationContext
        folder(app)?.let { t ->
            runCatching { app.contentResolver.releasePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        local(app).edit().remove(KEY_TREE).remove(KEY_LAST).apply()
    }

    /** Вызывается после сохранения словаря (в фоне). */
    fun autoSave(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastAutoSave != 0L && now - lastAutoSave < MIN_INTERVAL_MS) return
        if (folder(context) == null) return
        lastAutoSave = now
        saveNow(context)
    }

    /** Записывает копию в выбранную папку; false — папка не выбрана или к ней пропал доступ. */
    fun saveNow(context: Context): Boolean {
        val app = context.applicationContext
        val tree = folder(app) ?: return false
        return runCatching {
            val uri = findFile(app, tree) ?: run {
                val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                DocumentsContract.createDocument(app.contentResolver, dir, "application/json", FILE_NAME)
            } ?: error("no file")
            val bytes = build(app).toByteArray()
            val out = runCatching { app.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: app.contentResolver.openOutputStream(uri, "w")
                ?: error("no stream")
            out.use { it.write(bytes) }
            local(app).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        }.isSuccess
    }

    private fun findFile(context: Context, tree: Uri): Uri? {
        val parent = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == FILE_NAME) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return null
    }
}
