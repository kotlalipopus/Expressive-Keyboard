package com.expressive.keyboard

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ============================================================================
// КЛЮЧИ API В ЗАШИФРОВАННОМ ВИДЕ
// Ключи ИИ-сервисов и GIF хранятся в настройках зашифрованными (AES-GCM). Сам ключ шифрования живёт
// в Android Keystore и не покидает телефон: даже прочитав файл настроек, ключи API не достать.
// Если Keystore на устройстве не работает, значение остаётся как есть — клавиатура не ломается.
// ============================================================================

object SecretBox {
    private const val ALIAS = "expressive_keyboard_secrets"
    private const val PREFIX = "enc1:"
    /** Ключи настроек, которые храним зашифрованными и не выгружаем в резервную копию. */
    val SECRET_PREFS = setOf("anthropic_key", "gemini_key", "openai_key", "groq_key", "openrouter_key", "giphy_key", "klipy_key", "ai_custom")

    private val plainCache = ConcurrentHashMap<String, String>()

    /**
     * Ключ из Android Keystore нужен на каждый ключ API при чтении настроек. Раньше Keystore открывался
     * заново для каждого секрета — на холодном запуске это заметная пауза перед первым кадром клавиатуры.
     */
    @Volatile private var cachedKey: SecretKey? = null

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val k = (ks.getKey(ALIAS, null) as? SecretKey) ?: run {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                gen.init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                gen.generateKey()
            }
            cachedKey = k
            return k
        }
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
            (PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)).also { plainCache[it] = plain }
        }.getOrDefault(plain)
    }

    /** Зашифрованное — расшифровать (с кэшем: настройки перечитываются часто), старое открытое — как есть. */
    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        plainCache[stored]?.let { return it }
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8).also { plainCache[stored] = it }
        }.getOrDefault("")
    }

    /** Ключи, сохранённые раньше открытым текстом, шифруются один раз при запуске. */
    fun migrate(prefs: SharedPreferences) {
        val e = prefs.edit()
        var changed = false
        for (k in SECRET_PREFS) {
            val v = prefs.getString(k, null) ?: continue
            if (v.isNotEmpty() && !v.startsWith(PREFIX)) {
                val enc = encrypt(v)
                if (enc != v) {
                    e.putString(k, enc)
                    changed = true
                }
            }
        }
        if (changed) e.apply()
    }
}

/** «Удалить все личные данные»: выученные слова, история буфера, недавние эмодзи и ключи API. */
object PrivateData {
    /** Клавиатура очистит историю буфера в памяти при следующем показе. */
    @Volatile var wipeClipboard = false

    fun wipe(context: android.content.Context) {
        WordPredictorEngine.clearUserData()
        CjkUser.clear()
        EmojiRecents.clear(context)
        context.getSharedPreferences(WordPredictorEngine.DATA_PREFS, android.content.Context.MODE_PRIVATE).edit().remove("pinned_clips").apply()
        runCatching { java.io.File(context.filesDir, "clips").deleteRecursively() }
        ClipStore.clear(context)
        KeyboardPrefs.edit(context) { SecretBox.SECRET_PREFS.forEach { remove(it) } }
        wipeClipboard = true
    }
}
