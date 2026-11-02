package com.expressive.keyboard

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.proofreading.Proofreader
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// ============================================================================
// ИСПРАВЛЕНИЕ ТЕКСТА
// Кто исправляет — выбирается в настройках:
//  • Gemini Nano прямо на телефоне (AICore): без ключа и интернета, но не на всех телефонах и не для всех языков;
//  • Gemini API (бесплатный ключ из Google AI Studio), Claude API, OpenAI, Groq — по ключу пользователя;
//  • LanguageTool — бесплатно и без ключа, но это не ИИ.
// «Авто»: Nano, если умеет; иначе первый сервис с ключом; иначе LanguageTool.
// ============================================================================

enum class AiProvider(val id: String, val title: String) {
    AUTO("AUTO", "Авто"),
    NANO("NANO", "На телефоне"),
    GEMINI("GEMINI", "Gemini"),
    CLAUDE("CLAUDE", "Claude"),
    OPENAI("OPENAI", "OpenAI"),
    GROQ("GROQ", "Groq"),
    OPENROUTER("OPENROUTER", "OpenRouter"),
    LANGUAGETOOL("LANGUAGETOOL", "LanguageTool"),
    /** Свой сервис из списка пользователя; в настройках хранится как «CUSTOM:<id>». */
    CUSTOM("CUSTOM", "Свой сервис");

    companion object {
        fun from(id: String) = if (id.startsWith("CUSTOM:")) CUSTOM else entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * Свой ИИ-провайдер: любой сервис с API как у OpenAI (DeepSeek, Mistral, xAI, Together, Cerebras, свой сервер,
 * Ollama и LM Studio в домашней сети) или как у Anthropic. Ключ может быть пустым — для локальных серверов.
 */
data class CustomAi(
    val id: String,
    val name: String,
    val url: String,
    val key: String = "",
    val model: String = "",
    /** openai — /chat/completions; anthropic — /messages. */
    val format: String = "openai"
) {
    /** Адрес API без хвоста /chat/completions; голый домен получает /v1. */
    fun base(): String {
        var u = url.trim().trimEnd('/')
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        u = u.removeSuffix("/chat/completions").removeSuffix("/messages").removeSuffix("/models").trimEnd('/')
        val path = u.substringAfter("://").substringAfter('/', "")
        return if (path.isEmpty()) "$u/v1" else u
    }

    val title get() = name.ifBlank { url.substringAfter("://").substringBefore('/') }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url).put("key", key)
        .put("model", model).put("format", format)

    companion object {
        fun parse(json: String): List<CustomAi> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                CustomAi(
                    o.optString("id").ifBlank { return@mapNotNull null }, o.optString("name"), o.optString("url"),
                    o.optString("key"), o.optString("model"), o.optString("format", "openai")
                ).takeIf { it.url.isNotBlank() }
            }
        }.getOrDefault(emptyList())

        fun serialize(list: List<CustomAi>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun newId() = java.lang.Long.toString(System.currentTimeMillis(), 36)

        /** Готовые заготовки: адрес и модель подставляются, остаётся вставить ключ. */
        val PRESETS = listOf(
            CustomAi("", "DeepSeek", "https://api.deepseek.com/v1", model = "deepseek-chat"),
            CustomAi("", "Mistral", "https://api.mistral.ai/v1", model = "mistral-small-latest"),
            CustomAi("", "xAI Grok", "https://api.x.ai/v1"),
            CustomAi("", "Cerebras", "https://api.cerebras.ai/v1"),
            CustomAi("", "Together", "https://api.together.xyz/v1"),
            CustomAi("", "Ollama", "http://192.168.1.2:11434/v1"),
            CustomAi("", "LM Studio", "http://192.168.1.2:1234/v1"),
            CustomAi("", "Anthropic-совместимый", "https://api.anthropic.com/v1", format = "anthropic"),
        )
    }
}

/**
 * Что сделать с текстом. Исправление умеют все сервисы, остальное (тон, сокращение, своё указание, дописать,
 * очистка диктовки) — только ИИ по ключу: Gemini Nano на телефоне и LanguageTool умеют лишь исправлять.
 */
enum class AiTask(val id: String, val title: String, val icon: String, val prompt: String) {
    PROOFREAD("proofread", "Исправить", "✓", ""),
    PROFESSIONAL("professional", "Деловой", "💼",
        "Rewrite the text in a professional, polite and clear business tone. Keep every fact and the meaning."),
    CASUAL("casual", "Дружелюбный", "😊",
        "Rewrite the text in a relaxed, friendly, conversational tone, as if writing to a friend. Keep the meaning."),
    CONCISE("concise", "Короче", "✂️",
        "Make the text noticeably shorter and clearer: remove filler and repetition, keep every important point."),
    EMOTIVE("emotive", "Эмоциональнее", "🔥",
        "Rewrite the text to be warmer, more expressive and emotional, while keeping the meaning."),
    EMOJIFY("emojify", "Эмодзи", "✨",
        "Keep the text as it is (fix obvious typos only) and add fitting emoji in natural places. Do not overdo it."),
    ELABORATE("elaborate", "Подробнее", "📝",
        "Expand the text a little: make it more complete and detailed, keeping the same tone and meaning."),
    CONTINUE("continue", "Дописать", "✍️",
        "Continue the text naturally with the most likely next words: from a few words up to one sentence, in the same " +
            "language, voice and style. Reply with the continuation only, do not repeat the given text."),
    CUSTOM("custom", "Своё указание", "🪄", ""),
    DICTATION("dictation", "Очистить диктовку", "🎙", "");

    companion object {
        fun from(id: String) = entries.firstOrNull { it.id == id } ?: PROOFREAD
        /** Кнопки панели «ИИ» по порядку. */
        val TOOLS = listOf(PROOFREAD, PROFESSIONAL, CASUAL, CONCISE, EMOTIVE, EMOJIFY, ELABORATE, CONTINUE)
    }
}

/** Ключи и выбор сервиса — из настроек. */
data class AiModels(val gemini: String = "", val claude: String = "", val openai: String = "", val groq: String = "", val openrouter: String = "")

data class AiKeys(
    val provider: AiProvider, val gemini: String, val claude: String, val openai: String, val groq: String, val openrouter: String,
    val models: AiModels = AiModels(),
    val custom: List<CustomAi> = emptyList(),
    /** id своего сервиса, если выбран он. */
    val customId: String = ""
) {
    val selectedCustom get() = custom.firstOrNull { it.id == customId }

    companion object {
        fun of(s: KeyboardSettings) = AiKeys(
            AiProvider.from(s.aiProvider), s.geminiKey, s.anthropicKey, s.openaiKey, s.groqKey, s.openrouterKey,
            AiModels(s.geminiModel, s.claudeModel, s.openaiModel, s.groqModel, s.openrouterModel),
            s.aiCustom, s.aiProvider.substringAfter("CUSTOM:", "")
        )
    }
}

/** Кому отправить запрос: встроенный сервис или свой. */
data class AiTarget(val provider: AiProvider, val custom: CustomAi? = null) {
    val title get() = custom?.title ?: provider.title
}

object AiFix {
    const val CLAUDE_MODEL = "claude-opus-5-5"
    const val GEMINI_MODEL = "gemini-flash-latest"
    const val OPENAI_MODEL = "gpt-4.1-mini"
    const val GROQ_MODEL = "llama-3.3-70b-versatile"
    const val OPENROUTER_MODEL = "openrouter/auto"
    private const val MAX_CHARS = 8000
    private val THINK = Regex("(?s)<think>.*?</think>")

    private val SYSTEM = """
        You fix text typed on a phone keyboard. Correct spelling, typos, grammar, punctuation and capitalization.
        Keep the original language, meaning, tone, slang, emoji, line breaks and formatting. Do not translate,
        shorten, expand, explain or add anything. If the text is already correct, return it unchanged.
        Reply with the corrected text only.
    """.trimIndent()

    private val REWRITE = """
        You are a writing assistant built into a phone keyboard. You get text the user typed and a task.
        Always answer in the same language as the text (never translate unless the task explicitly asks).
        Keep names, numbers, links and facts. Fix typos along the way. No quotes around the answer, no comments,
        no explanations, no alternatives. Reply with the resulting text only.
    """.trimIndent()

    private val CUSTOM = """
        You are a writing assistant built into a phone keyboard. Follow the user's instruction. If text is given,
        apply the instruction to that text (edit, rewrite, translate, reply to it, whatever is asked); if no text is
        given, write what the instruction asks for. Use the language of the text, or of the instruction if there is
        no text, unless the instruction asks otherwise. Reply with the resulting text only, without quotes,
        comments or explanations.
    """.trimIndent()

    private val DICTATION = """
        You clean up speech-to-text dictation like a careful human transcriber. Remove filler words and hesitations
        (um, uh, er, like, you know, ну, э, это самое, короче, типа, как бы, вот), stutters, repeated words and false
        starts. When the speaker corrects themselves ("at two, no, three"), keep only the correction ("at three").
        Add proper punctuation, capitalization and paragraph breaks; write numbers, times and dates naturally.
        Keep the speaker's words, language, meaning and style: do not rephrase, summarize, translate or add anything.
        Reply with the cleaned text only.
    """.trimIndent()

    /** Есть ли ключ хоть одного ИИ-сервиса (без него доступно только исправление). */
    fun hasLlm(keys: AiKeys): Boolean =
        keys.custom.isNotEmpty() || listOf(keys.gemini, keys.claude, keys.openai, keys.groq, keys.openrouter).any { it.isNotBlank() }

    private val CLOUD = listOf(AiProvider.GEMINI, AiProvider.CLAUDE, AiProvider.OPENAI, AiProvider.GROQ, AiProvider.OPENROUTER)

    /** ИИ-сервисы по порядку: выбранный в настройках, потом встроенные с ключом, потом свои. */
    fun llmOrder(keys: AiKeys): List<AiTarget> = buildList {
        when (keys.provider) {
            AiProvider.CUSTOM -> keys.selectedCustom?.let { add(AiTarget(AiProvider.CUSTOM, it)) }
            in CLOUD -> add(AiTarget(keys.provider))
            else -> {}
        }
        if (keys.gemini.isNotBlank()) add(AiTarget(AiProvider.GEMINI))
        if (keys.claude.isNotBlank()) add(AiTarget(AiProvider.CLAUDE))
        if (keys.openai.isNotBlank()) add(AiTarget(AiProvider.OPENAI))
        if (keys.groq.isNotBlank()) add(AiTarget(AiProvider.GROQ))
        if (keys.openrouter.isNotBlank()) add(AiTarget(AiProvider.OPENROUTER))
        keys.custom.forEach { add(AiTarget(AiProvider.CUSTOM, it)) }
    }.distinct()

    /**
     * Любая задача панели «ИИ». Для всего, кроме исправления, нужен ИИ по ключу: сначала выбранный в настройках
     * сервис, потом любой другой с ключом.
     */
    suspend fun run(
        context: Context, text: String, keys: AiKeys, lang: String, task: AiTask, instruction: String = ""
    ): Result<Pair<String, AiTarget>> = withContext(Dispatchers.IO) {
        if (task == AiTask.PROOFREAD) return@withContext fix(context, text, keys, lang)
        if (text.isBlank() && task != AiTask.CUSTOM) return@withContext Result.failure(IllegalArgumentException(tr("Нет текста")))
        if (text.length > MAX_CHARS) return@withContext Result.failure(IllegalArgumentException(tr("Слишком длинный текст")))
        val order = llmOrder(keys)
        if (order.isEmpty()) {
            return@withContext Result.failure(IllegalStateException(tr("Нужен ключ ИИ: Настройки → Набор текста → ключ Gemini (бесплатно)")))
        }
        val (system, user) = when (task) {
            AiTask.CUSTOM -> CUSTOM to (if (text.isBlank()) "Instruction: $instruction" else "Instruction: $instruction\n\nText:\n$text")
            AiTask.DICTATION -> DICTATION to text
            else -> REWRITE to "Task: ${task.prompt}\n\nText:\n$text"
        }
        var last: Throwable? = null
        for (p in order) {
            try {
                val out = ask(p, system, user, keys)
                return@withContext Result.success((if (task == AiTask.CONTINUE) out.trimEnd() else out.trim()) to p)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = if (e is Skip || e is IllegalStateException) e else IllegalStateException(e.message ?: e.javaClass.simpleName)
            }
        }
        Result.failure(last ?: IllegalStateException(tr("Не удалось обработать текст")))
    }

    private fun ask(t: AiTarget, system: String, user: String, keys: AiKeys): String = when (t.provider) {
        AiProvider.GEMINI -> gemini(user, keys.gemini.trim(), system, keys.models.gemini.ifBlank { GEMINI_MODEL })
        AiProvider.CLAUDE -> claude(user, keys.claude.trim(), system, keys.models.claude.ifBlank { CLAUDE_MODEL })
        AiProvider.OPENAI -> openAiCompatible("https://api.openai.com/v1", keys.models.openai.ifBlank { OPENAI_MODEL }, user, keys.openai.trim(), "OpenAI", system)
        AiProvider.GROQ -> openAiCompatible("https://api.groq.com/openai/v1", keys.models.groq.ifBlank { GROQ_MODEL }, user, keys.groq.trim(), "Groq", system)
        AiProvider.OPENROUTER -> openAiCompatible("https://openrouter.ai/api/v1", keys.models.openrouter.ifBlank { OPENROUTER_MODEL }, user, keys.openrouter.trim(), "OpenRouter", system)
        AiProvider.CUSTOM -> custom(t.custom ?: throw Skip(tr("Свой сервис не найден")), user, system)
        else -> throw Skip("unsupported")
    }

    private fun custom(c: CustomAi, text: String, system: String): String =
        if (c.format == "anthropic") anthropicHttp(c.base(), c.model.ifBlank { CLAUDE_MODEL }, text, c.key.trim(), c.title, system)
        else openAiCompatible(c.base(), c.model.trim(), text, c.key.trim(), c.title, system)

    /** Anthropic Messages API по HTTP — для своих адресов (прокси, совместимые сервисы). */
    private fun anthropicHttp(base: String, model: String, text: String, key: String, name: String, system: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 8000)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
        val headers = buildMap {
            put("anthropic-version", "2023-06-01")
            if (key.isNotEmpty()) {
                put("x-api-key", key)
                put("Authorization", "Bearer $key")
            }
        }
        val root = try {
            post("$base/messages", body, headers, name)
        } catch (e: ModelNotFound) {
            throw IllegalStateException(e.message)
        }
        val content = root.optJSONArray("content") ?: throw IllegalStateException(tr("Пустой ответ"))
        return (0 until content.length()).mapNotNull { content.optJSONObject(it)?.takeIf { o -> o.optString("type") == "text" }?.optString("text") }
            .joinToString("").trim().ifEmpty { throw IllegalStateException(tr("Пустой ответ")) }
    }

    /** Ошибка, после которой в режиме «Авто» стоит попробовать следующий сервис (а не показывать её сразу). */
    private class Skip(message: String) : Exception(message)

    /** Сервис не знает модель (или ключу она недоступна) — HTTP 404. */
    private class ModelNotFound(message: String) : Exception(message)

    /** Модель, которую сервис сам предложил после 404: база URL → id модели. */
    private val pickedModel = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Проверка подключения: короткий запрос сервису; ответ — исправленная фраза или причина ошибки. */
    suspend fun test(context: Context, keys: AiKeys, target: AiTarget): Result<String> = withContext(Dispatchers.IO) {
        runCatching { run(target, context, "helo wrold", keys, "en") }.recoverCatching { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw IllegalStateException(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Исправленный текст и кто исправлял, или понятная ошибка. lang — код языка клавиатуры (ru, en, …). */
    suspend fun fix(context: Context, text: String, keys: AiKeys, lang: String): Result<Pair<String, AiTarget>> =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext Result.failure(IllegalArgumentException(tr("Нет текста")))
            if (text.length > MAX_CHARS) return@withContext Result.failure(IllegalArgumentException(tr("Слишком длинный текст")))
            val order = when (keys.provider) {
                AiProvider.AUTO -> listOf(AiTarget(AiProvider.NANO)) + llmOrder(keys) + AiTarget(AiProvider.LANGUAGETOOL)
                AiProvider.CUSTOM -> listOf(AiTarget(AiProvider.CUSTOM, keys.selectedCustom))
                else -> listOf(AiTarget(keys.provider))
            }
            var last: Throwable? = null
            for (p in order) {
                try {
                    return@withContext Result.success(run(p, context, text, keys, lang) to p)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // в «Авто» — к следующему сервису (сеть, лимит, ошибка сервера у одного не мешает другим);
                    // при явном выборе — показать причину
                    last = if (e is Skip || e is IllegalStateException) e else IllegalStateException(e.message ?: e.javaClass.simpleName)
                    if (keys.provider != AiProvider.AUTO) break
                }
            }
            Result.failure(last ?: IllegalStateException(tr("Не удалось исправить текст")))
        }

    private fun run(t: AiTarget, context: Context, text: String, keys: AiKeys, lang: String): String = when (val p = t.provider) {
        AiProvider.CUSTOM -> custom(t.custom ?: throw Skip(tr("Свой сервис не найден")), text, SYSTEM)
        AiProvider.NANO -> nano(context, text, lang)
        AiProvider.GEMINI -> gemini(text, keys.gemini.trim().ifBlank { throw Skip(tr("Не задан ключ {0}", "Gemini")) }, SYSTEM, keys.models.gemini.ifBlank { GEMINI_MODEL })
        AiProvider.CLAUDE -> claude(text, keys.claude.trim().ifBlank { throw Skip(tr("Не задан ключ {0}", "Claude")) }, SYSTEM, keys.models.claude.ifBlank { CLAUDE_MODEL })
        AiProvider.OPENAI -> openAiCompatible(
            "https://api.openai.com/v1", keys.models.openai.ifBlank { OPENAI_MODEL }, text,
            keys.openai.trim().ifBlank { throw Skip(tr("Не задан ключ {0}", "OpenAI")) }, "OpenAI"
        )
        AiProvider.GROQ -> openAiCompatible(
            "https://api.groq.com/openai/v1", keys.models.groq.ifBlank { GROQ_MODEL }, text,
            keys.groq.trim().ifBlank { throw Skip(tr("Не задан ключ {0}", "Groq")) }, "Groq"
        )
        AiProvider.OPENROUTER -> openAiCompatible(
            "https://openrouter.ai/api/v1", keys.models.openrouter.ifBlank { OPENROUTER_MODEL }, text,
            keys.openrouter.trim().ifBlank { throw Skip(tr("Не задан ключ {0}", "OpenRouter")) }, "OpenRouter"
        )
        AiProvider.LANGUAGETOOL -> languageTool(text, lang)
        AiProvider.AUTO -> throw IllegalStateException("auto")
    }

    // ------------------------------------------------------------------ Gemini Nano на телефоне

    private val NANO_LANGS = mapOf(
        "en" to ProofreaderOptions.Language.ENGLISH, "ja" to ProofreaderOptions.Language.JAPANESE,
        "de" to ProofreaderOptions.Language.GERMAN, "fr" to ProofreaderOptions.Language.FRENCH,
        "it" to ProofreaderOptions.Language.ITALIAN, "es" to ProofreaderOptions.Language.SPANISH,
        "ko" to ProofreaderOptions.Language.KOREAN
    )

    @Volatile private var nanoClient: Proofreader? = null
    @Volatile private var nanoLang = -1
    /** Языки, для которых исправление на телефоне недоступно: не ждём проверку по 10 секунд при каждом нажатии. */
    private val nanoUnavailable = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** Состояние исправления на телефоне (FeatureStatus) для языка или null, если язык не поддерживается. */
    suspend fun nanoStatus(context: Context, lang: String): Int? = withContext(Dispatchers.IO) {
        val l = NANO_LANGS[lang] ?: return@withContext null
        runCatching { proofreader(context, l).checkFeatureStatus().get(10, TimeUnit.SECONDS) }.getOrNull()
    }

    private fun proofreader(context: Context, l: Int): Proofreader = synchronized(this) {
        nanoClient?.takeIf { nanoLang == l } ?: run {
            runCatching { nanoClient?.close() }
            Proofreading.getClient(
                ProofreaderOptions.builder(context.applicationContext)
                    .setInputType(ProofreaderOptions.InputType.KEYBOARD)
                    .setLanguage(l)
                    .build()
            ).also {
                nanoClient = it
                nanoLang = l
            }
        }
    }

    private fun nano(context: Context, text: String, lang: String): String {
        val l = NANO_LANGS[lang] ?: throw Skip(tr("Исправление на телефоне не поддерживает этот язык"))
        if (l in nanoUnavailable) throw Skip(tr("Исправление на телефоне недоступно"))
        val pr = try {
            proofreader(context, l)
        } catch (e: Throwable) {
            nanoUnavailable += l
            throw Skip(tr("Исправление на телефоне недоступно"))
        }
        val status = runCatching { pr.checkFeatureStatus().get(5, TimeUnit.SECONDS) }.getOrNull()
        if (status == null || status == FeatureStatus.UNAVAILABLE) {
            nanoUnavailable += l
            throw Skip(tr("Исправление на телефоне недоступно"))
        }
        // модель ещё не скачана — первый запрос скачает её сам; ждём дольше
        val wait = if (status == FeatureStatus.AVAILABLE) 30L else 180L
        val result = try {
            pr.runInference(ProofreadingRequest.builder(text).build()).get(wait, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            throw Skip(tr("Исправление на телефоне не сработало"))
        }
        return result.results.firstOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() } ?: text
    }

    // ------------------------------------------------------------------ Claude (SDK Anthropic)

    @Volatile private var claudeClient: AnthropicClient? = null
    @Volatile private var claudeKey = ""

    private fun claudeClient(key: String): AnthropicClient = synchronized(this) {
        // клавиатура не может ждать минутами: короткий таймаут и одна повторная попытка
        claudeClient?.takeIf { claudeKey == key } ?: AnthropicOkHttpClient.builder().apiKey(key)
            .timeout(java.time.Duration.ofSeconds(30)).maxRetries(1).build().also {
                claudeClient = it
                claudeKey = key
            }
    }

    private fun claude(text: String, key: String, system: String = SYSTEM, model: String = CLAUDE_MODEL): String = try {
        fun params(effort: Boolean) = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .apply {
                // простая правка: низкое усилие — быстрее ответ (не все модели принимают этот параметр)
                if (effort) outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            }
            .addUserMessage(text)
            .build()
        val msg = try {
            claudeClient(key).messages().create(params(true))
        } catch (e: com.anthropic.errors.BadRequestException) {
            // модель не знает параметр «усилие» — повторяем без него
            claudeClient(key).messages().create(params(false))
        }
        if (msg.stopReason().map { it.toString() }.orElse("") == "refusal") {
            throw IllegalStateException(tr("Claude отказался обработать этот текст"))
        }
        msg.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("").trim()
            .ifEmpty { throw IllegalStateException(tr("Пустой ответ")) }
    } catch (e: UnauthorizedException) {
        throw IllegalStateException(tr("Неверный ключ {0}", "Claude"))
    } catch (e: PermissionDeniedException) {
        throw IllegalStateException(tr("Ключу {0} не хватает прав", "Claude"))
    } catch (e: RateLimitException) {
        throw IllegalStateException(tr("Слишком много запросов — попробуйте через минуту"))
    } catch (e: AnthropicServiceException) {
        throw IllegalStateException(tr("Ошибка {0}: {1}", "Claude", e.statusCode()) + (e.message?.let { " — " + it.take(160) } ?: ""))
    } catch (e: AnthropicIoException) {
        throw IllegalStateException(tr("Нет соединения с сервером"))
    }

    // ------------------------------------------------------------------ Gemini API, OpenAI, Groq (HTTP)

    private fun post(url: String, body: JSONObject, headers: Map<String, String>, name: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 30000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.readLimited().orEmpty()
            // пояснение от самого сервиса — чтобы по сообщению было видно причину
            val detail = runCatching {
                JSONObject(raw).let { o -> o.optJSONObject("error")?.optString("message") ?: o.optString("message") }
            }.getOrNull().orEmpty().take(160)
            when {
                code == 401 || code == 403 || (code == 400 && raw.contains("API_KEY_INVALID")) ->
                    throw IllegalStateException(tr("Неверный ключ {0}", name))
                code == 429 -> throw IllegalStateException(tr("Слишком много запросов — попробуйте через минуту"))
                code == 404 -> throw ModelNotFound(tr("Ошибка {0}: {1}", name, code) + if (detail.isNotEmpty()) " — $detail" else "")
                code !in 200..299 -> throw IllegalStateException(tr("Ошибка {0}: {1}", name, code) + if (detail.isNotEmpty()) " — $detail" else "")
            }
            return JSONObject(raw)
        } catch (e: java.io.IOException) {
            throw IllegalStateException(tr("Нет соединения с сервером"))
        } finally {
            conn.disconnect()
        }
    }

    private fun gemini(text: String, key: String, system: String = SYSTEM, model: String = GEMINI_MODEL): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
        val root = post(
            "https://generativelanguage.googleapis.com/v1beta/models/${model.removePrefix("models/")}:generateContent",
            body, mapOf("x-goog-api-key" to key), "Gemini"
        )
        val parts = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException(tr("Пустой ответ"))
        return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
            .ifEmpty { throw IllegalStateException(tr("Пустой ответ")) }
    }

    /**
     * OpenAI и всё, что говорит на его языке (Groq): /chat/completions. Если модели нет (404) — спрашиваем
     * у сервиса список моделей, доступных ключу, берём подходящую и запоминаем её.
     */
    private fun openAiCompatible(base: String, model: String, text: String, key: String, name: String, system: String = SYSTEM): String {
        val slot = "$base|$model"
        // модель не указана (свой сервис) — берём первую, что сервис отдаёт в /models
        val current = pickedModel[slot] ?: model.ifBlank {
            availableModel(base, key, name, preferred = emptyList())?.also { pickedModel[slot] = it }
                ?: throw IllegalStateException(tr("{0}: укажите модель", name))
        }
        return try {
            chat(base, current, text, key, name, system)
        } catch (e: ModelNotFound) {
            val other = availableModel(base, key, name, preferred = listOfNotNull(model.ifBlank { null })) ?: throw IllegalStateException(e.message)
            if (other == current) throw IllegalStateException(e.message)
            pickedModel[slot] = other
            try {
                chat(base, other, text, key, name, system)
            } catch (e2: ModelNotFound) {
                throw IllegalStateException(e2.message)
            }
        }
    }

    /** Модели, доступные ключу (GET /models): сначала знакомые, потом любая текстовая. */
    private fun availableModel(base: String, key: String, name: String, preferred: List<String>): String? = runCatching {
        val conn = (URL("$base/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            if (key.isNotEmpty()) setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val data = JSONObject(conn.inputStream.readLimited()).optJSONArray("data") ?: return@runCatching null
            val ids = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }
            // не чат-модели: распознавание речи, озвучка, модерация, эмбеддинги, картинки
            val chat = ids.filter { id ->
                listOf("whisper", "tts", "orpheus", "guard", "embed", "moderation", "dall-e", "image", "audio", "realtime", "transcribe", "search")
                    .none { id.contains(it, ignoreCase = true) }
            }
            val known = preferred + listOf(
                "openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b", "llama-3.1-8b-instant",
                "gpt-4.1-mini", "gpt-4o-mini", "gpt-5-mini", "gpt-4.1", "gpt-4o"
            )
            known.firstOrNull { it in chat } ?: chat.firstOrNull()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun chat(base: String, model: String, text: String, key: String, name: String, system: String = SYSTEM): String {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", text))
            )
        val root = post("$base/chat/completions", body, if (key.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $key"), name)
        // рассуждающие модели (DeepSeek R1, Qwen в Ollama) присылают ход мыслей в <think>…</think> — он не нужен
        return root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?.replace(THINK, "")?.trim()?.ifEmpty { null } ?: throw IllegalStateException(tr("Пустой ответ"))
    }

    // ------------------------------------------------------------------ LanguageTool (бесплатно, не ИИ)

    private fun languageTool(text: String, lang: String): String {
        val l = when (lang) {
            "", "auto" -> "auto"
            "en" -> "en-US"
            "de" -> "de-DE"
            "pt" -> "pt-BR"
            "ru" -> "ru-RU"
            "uk" -> "uk-UA"
            "pl" -> "pl-PL"
            else -> lang
        }
        return try {
            languageTool(text, l, retry = l != "auto")
        } catch (e: UnsupportedLanguage) {
            // язык клавиатуры LanguageTool не знает — пусть определит сам
            languageTool(text, "auto", retry = false)
        }
    }

    private class UnsupportedLanguage : Exception()

    private fun languageTool(text: String, l: String, retry: Boolean): String {
        val conn = (URL("https://api.languagetool.org/v2/check").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        }
        val body = "text=" + URLEncoder.encode(text, "UTF-8") + "&language=" + URLEncoder.encode(l, "UTF-8")
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code == 429) throw IllegalStateException(tr("Слишком много запросов — попробуйте через минуту"))
            if (code == 400 && retry) throw UnsupportedLanguage()
            if (code !in 200..299) throw IllegalStateException(tr("Ошибка {0}: {1}", "LanguageTool", code))
            val root = JSONObject(conn.inputStream.readLimited())
            val matches = root.optJSONArray("matches") ?: return text
            val fixes = (0 until matches.length()).mapNotNull { i ->
                val m = matches.getJSONObject(i)
                val rep = m.optJSONArray("replacements")?.optJSONObject(0)?.optString("value") ?: return@mapNotNull null
                Triple(m.optInt("offset"), m.optInt("length"), rep)
            }.sortedByDescending { it.first }
            val sb = StringBuilder(text)
            var lastStart = Int.MAX_VALUE
            for ((off, len, rep) in fixes) {
                // пересекающиеся правки пропускаем
                if (off < 0 || off + len > sb.length || off + len > lastStart) continue
                sb.replace(off, off + len, rep)
                lastStart = off
            }
            return sb.toString()
        } catch (e: java.io.IOException) {
            throw IllegalStateException(tr("Нет соединения с сервером"))
        } finally {
            conn.disconnect()
        }
    }
}
