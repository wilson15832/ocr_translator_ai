package com.example.ocr_translation

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit


class TranslationService private constructor(private val context: Context) {

    // Singleton pattern implementation
    companion object {
        /** A `BLOCK_…:` tag at the start of a line, with whatever spacing the model chose. */
        private val BLOCK_MARKER = Regex("""^\s*BLOCK_\S*\s*:\s*""")

        @Volatile
        private var INSTANCE: TranslationService? = null

        fun getInstance(context: Context): TranslationService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TranslationService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private var apiKey = "" // populated from SecureStorage via PreferencesManager

    // Cache system
    private val translationCache = TranslationCache(context)

    // Configure translation settings
    data class TranslationConfig(
        var sourceLanguage: String = "auto", // Auto-detect
        var targetLanguage: String = "en",   // English default
        var maxTokens: Int = 1024,
        var preserveFormatting: Boolean = true,
        var preferSpeed: Boolean = false,    // Speed vs quality tradeoff
        var modelName: String = "gpt-4-turbo", // Default model
        // Which vendor to send to. Kept separate from modelName because a user-added model code
        // needn't follow the vendor's naming, and routing on the code's prefix would send it to
        // whichever endpoint happened to match — or to the fallback.
        var provider: LlmProvider = LlmProvider.CHATGPT,
        var useLocalModel: Boolean = false,  // Option for on-device models
        var maxCacheSize: Int = 100,         // Max entries in cache
        var systemPrompt: String = "",
        var userPrompt: String = ""
    )

    var config = TranslationConfig()

    // OkHttp client for API calls
    private val client = OkHttpClient.Builder()
        // Fail a blocked/unreachable host fast (e.g. Google endpoints on a restricted network)
        // instead of hanging for 30s; readTimeout still allows time for the model to generate.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)   // hard ceiling for the whole request
        .apply { if (BuildConfig.DEBUG) eventListenerFactory { LlmLatencyListener() } }
        .build()

    // Gson for JSON parsing
    private val gson = Gson()

    fun loadConfig(preferencesManager: PreferencesManager) {
        config.sourceLanguage = preferencesManager.sourceLanguage
        config.targetLanguage = preferencesManager.targetLanguage
        config.maxTokens = preferencesManager.maxTokens
        config.preserveFormatting = preferencesManager.preserveFormatting
        config.preferSpeed = preferencesManager.preferSpeed
        config.modelName = preferencesManager.modelName
        config.provider = preferencesManager.providerFor(preferencesManager.modelName)
        config.useLocalModel = preferencesManager.useLocalModel
        config.maxCacheSize = preferencesManager.maxCacheEntries
        config.systemPrompt = preferencesManager.systemPrompt
        config.userPrompt = preferencesManager.userPrompt
        apiKey = preferencesManager.activeApiKey
        gateway = Gateway.from(preferencesManager)
    }

    /** Cloudflare AI Gateway settings for the current request, or null to go straight to the vendor. */
    data class Gateway(val accountId: String, val name: String, val token: String) {
        /**
         * One endpoint for every vendor. `compat` is the OpenAI-compatible surface, which is what
         * lets a single client serve all four — the vendor is named in the model instead of in the
         * URL.
         */
        val endpoint: String
            get() = "https://gateway.ai.cloudflare.com/v1/$accountId/$name/compat/chat/completions"

        companion object {
            fun from(prefs: PreferencesManager): Gateway? =
                if (!prefs.cloudflareProxyReady) null
                else Gateway(
                    prefs.cloudflareAccountId,
                    prefs.cloudflareGateway,
                    prefs.cloudflareToken
                )
        }
    }

    private var gateway: Gateway? = null

    private fun createLlmClient(): LlmClient =
        buildClient(config.provider, config.modelName, apiKey, config.maxTokens, gateway)

    /**
     * Switched on the provider rather than the model's prefix: the URL and payload format are a
     * property of the vendor, not of the model name, which is exactly why a user can add a new
     * model without the app needing to know about it.
     *
     * Takes everything as arguments rather than reading [config], so the connection test can build
     * a client for settings the user has typed but not yet saved without disturbing the live one.
     */
    private fun buildClient(
        provider: LlmProvider, model: String, key: String, maxTokens: Int, gateway: Gateway?
    ): LlmClient {
        if (gateway != null) return gatewayClient(provider, model, key, maxTokens, gateway)
        return directClient(provider, model, key, maxTokens)
    }

    /**
     * Everything through Cloudflare's OpenAI-compatible endpoint, whichever vendor it is.
     *
     * That endpoint normalises the request shape, so Gemini and Claude — which have their own
     * clients precisely because their wire formats differ — arrive here as ordinary chat
     * completions. The vendor moves out of the URL and into the model, prefixed with its gateway
     * slug, and the gateway's token travels beside the vendor key rather than replacing it.
     *
     * A model the user has already prefixed is left alone, so a code copied straight from
     * Cloudflare's dashboard works as typed.
     */
    private fun gatewayClient(
        provider: LlmProvider, model: String, key: String, maxTokens: Int, gateway: Gateway
    ): LlmClient {
        val routed = if (model.contains('/')) model else "${provider.gatewaySlug}/$model"
        return OpenAiCompatibleClient(
            client, gson, key, gateway.endpoint, routed, maxTokens,
            mapOf("cf-aig-authorization" to "Bearer ${gateway.token}")
        )
    }

    private fun directClient(
        provider: LlmProvider, model: String, key: String, maxTokens: Int
    ): LlmClient = when (provider) {
        LlmProvider.DEEPSEEK ->
            OpenAiCompatibleClient(client, gson, key,
                "https://api.deepseek.com/chat/completions", model, maxTokens)
        LlmProvider.CHATGPT ->
            OpenAiCompatibleClient(client, gson, key,
                "https://api.openai.com/v1/chat/completions", model, maxTokens)
        LlmProvider.GEMINI ->
            GeminiClient(client, gson, key,
                "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", model, maxTokens)
        LlmProvider.CLAUDE ->
            ClaudeClient(client, gson, key,
                "https://api.anthropic.com/v1/messages", model, maxTokens)
    }

    /** Outcome of [testConnection]. */
    sealed class ConnectionTest {
        data class Success(val reply: String, val millis: Long) : ConnectionTest()
        data class Failure(val reason: String) : ConnectionTest()
    }

    /**
     * One real round trip to the configured provider, reporting what came back.
     *
     * Separate from [translateText] on purpose. That one swallows every exception and substitutes
     * "Translation failed", which is the right behaviour mid-game — a failed block shouldn't take
     * the overlay down — but it destroys exactly the information a connectivity check exists to
     * show. Here the provider's own message (401, unknown model, unreachable host) is the result.
     *
     * Everything is passed in rather than read from preferences so the test runs against what is
     * on screen right now: the point is to check a key or a model *before* committing to it.
     */
    suspend fun testConnection(
        provider: LlmProvider,
        model: String,
        key: String,
        maxTokens: Int,
        sample: String,
        sourceLanguage: String,
        targetLanguage: String,
        systemPrompt: String,
        userPrompt: String,
        gateway: Gateway? = null
    ): ConnectionTest = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            // The gateway's token is checked on its own first. Sent with a bad one, the gateway
            // rejects the call before the vendor ever sees it, and the reply says nothing about
            // which of the two credentials was wrong — the whole point of this screen.
            if (gateway != null) {
                verifyGatewayToken(gateway.token)?.let { return@withContext it }
            }
            val prompt = userPrompt
                .replace("{source}", sourceLanguage)
                .replace("{target}", targetLanguage)
                .replace("{text}", sample)
            // Not run through parseTranslationResult: that falls back to the original text on a
            // parse miss, which would report success for a setup that is actually broken. The
            // markers are only stripped off the front, so a reply that came back wrong still shows
            // as wrong.
            val reply = stripBlockMarkers(
                buildClient(provider, model, key, maxTokens, gateway)
                    .translate(systemPrompt, prompt)
            )
            if (reply.isEmpty()) {
                ConnectionTest.Failure("Empty response")
            } else {
                ConnectionTest.Success(reply, System.currentTimeMillis() - started)
            }
        } catch (e: Exception) {
            ConnectionTest.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Asks Cloudflare whether the token is valid at all. Returns a failure to report, or null when
     * the token checks out and the test should carry on to the vendor.
     *
     * A separate endpoint from the gateway, and deliberately so: it answers for the token alone,
     * with no vendor key involved, which is what makes "the gateway token is wrong" distinguishable
     * from "the vendor key is wrong".
     */
    private fun verifyGatewayToken(token: String): ConnectionTest.Failure? {
        val request = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/user/tokens/verify")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) null
                else ConnectionTest.Failure("Cloudflare token: ${response.code} $body")
            }
        } catch (e: Exception) {
            ConnectionTest.Failure("Cloudflare unreachable: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 预热：开始翻译时对当前 provider 的 host 建立一次连接（TLS/HTTP2 握手），
     * 连接进 OkHttp 连接池，首次真实翻译复用、省掉握手延迟。best-effort，失败忽略。
     */
    fun warmUp() {
        loadConfig(PreferencesManager.getInstance(context))
        if (config.useLocalModel || apiKey.isBlank()) return

        // With the proxy on, the vendor's host is no longer the one we connect to — warming it
        // would hold open a connection nothing uses and leave the first real request paying for
        // the handshake this exists to avoid.
        val host = if (gateway != null) "https://gateway.ai.cloudflare.com/" else when (config.provider) {
            LlmProvider.DEEPSEEK -> "https://api.deepseek.com/"
            LlmProvider.CHATGPT  -> "https://api.openai.com/"
            LlmProvider.GEMINI   -> "https://generativelanguage.googleapis.com/"
            LlmProvider.CLAUDE   -> "https://api.anthropic.com/"
        }

        val request = Request.Builder().url(host).head().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { /* 预热失败忽略 */ }
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    // Main translation function.
    // [bypassCache] = true skips the cache lookup AND overwrites any existing cache entry with the
    // fresh result. Used by "re-translate" so the user can get a different LLM output (the model
    // is non-deterministic) instead of the same cached one.
    suspend fun translateText(
        textBlocks: List<OCRProcessor.TextBlock>,
        sourceLanguage: String,
        targetLanguage: String,
        bypassCache: Boolean = false
    ): List<TranslatedBlock> {
        return withContext(Dispatchers.IO) {
            // Check cache first
            loadConfig(PreferencesManager.getInstance(context))

            val cacheKey = generateCacheKey(textBlocks, sourceLanguage, targetLanguage)
            if (!bypassCache) {
                val cachedResult = translationCache.getTranslation(cacheKey)
                if (cachedResult != null) {
                    return@withContext cachedResult
                }
            }

            // Prepare for translation
            val translations = mutableListOf<TranslatedBlock>()

            // Group text blocks for efficient API usage
            val combinedText = textBlocks.joinToString("\n\n") {
                "BLOCK_${it.boundingBox.hashCode()}: ${it.text}"
            }

            // Create prompt for LLM
            val prompt = createTranslationPrompt(combinedText, sourceLanguage, targetLanguage)

            try {
                val result = if (config.useLocalModel) {
                    translateWithLocalModel(prompt)
                } else {
                    createLlmClient().translate(config.systemPrompt, prompt)
                }

                val parsedResults = parseTranslationResult(result, textBlocks)
                translations.addAll(parsedResults)

                // Cache the result
                translationCache.saveTranslation(
                    cacheKey,
                    translations.toList(),
                    config.sourceLanguage,
                    config.targetLanguage
                )

                translations
            } catch (e: Exception) {
                e.printStackTrace()
                // Return fallback translation
                textBlocks.map {
                    TranslatedBlock(
                        originalText = it.text,
                        translatedText = "Translation failed",
                        boundingBox = it.boundingBox,
                        sourceLanguage = config.sourceLanguage,
                        targetLanguage = config.targetLanguage
                    )
                }
            }
        }
    }

    /**
     * Drops the `BLOCK_xxx:` tag from the front of each line.
     *
     * The user prompt tells the model to echo those tags — that is how [parseTranslationResult]
     * puts a translation back on the box it came from. The connection test has no boxes to match,
     * so the tag is just protocol noise in front of the one thing the card is there to show.
     */
    private fun stripBlockMarkers(text: String): String =
        text.lineSequence()
            .map { it.replace(BLOCK_MARKER, "") }
            .joinToString("\n")
            .trim()

    // Create prompt for LLM translation
    private fun createTranslationPrompt(text: String, sourceLanguage: String, targetLanguage: String): String {
        return config.userPrompt
            .replace("{source}", sourceLanguage)
            .replace("{target}", targetLanguage)
            .replace("{text}", text)
    }


    // Translate using local model (on-device)
    private suspend fun translateWithLocalModel(prompt: String): String {
        // Implementation depends on which local model you choose
        // This is a placeholder for integration with a local model
        return "Not implemented" // Replace with actual local model implementation
    }

    // Parse the LLM response back into blocks
    private fun parseTranslationResult(
        result: String,
        originalBlocks: List<OCRProcessor.TextBlock>
    ): List<TranslatedBlock> {
        val translations = mutableListOf<TranslatedBlock>()
        val blockMap = mutableMapOf<String, String>()

        // Parse the result
        val lines = result.split("\n")
        var currentBlockId = ""
        var currentTranslation = StringBuilder()

        for (line in lines) {
            if (line.startsWith("BLOCK_")) {
                // Save previous block if exists
                if (currentBlockId.isNotEmpty()) {
                    blockMap[currentBlockId] = currentTranslation.toString().trim()
                    currentTranslation = StringBuilder()
                }

                // Extract new block ID
                val idEndIndex = line.indexOf(":")
                if (idEndIndex > 0) {
                    currentBlockId = line.substring(0, idEndIndex)
                    currentTranslation.append(line.substring(idEndIndex + 1).trim())
                }
            } else {
                currentTranslation.append("\n").append(line)
            }
        }

        // Save the last block
        if (currentBlockId.isNotEmpty()) {
            blockMap[currentBlockId] = currentTranslation.toString().trim()
        }

        // Match translations with original blocks
        for (block in originalBlocks) {
            val blockId = "BLOCK_${block.boundingBox.hashCode()}"
            val translation = blockMap[blockId] ?: block.text

            translations.add(
                TranslatedBlock(
                    originalText = block.text,
                    translatedText = translation,
                    boundingBox = block.boundingBox,
                    sourceLanguage = config.sourceLanguage,
                    targetLanguage = config.targetLanguage,
                    lineHeight = block.lineHeight
                )
            )
        }

        return translations
    }

    /**
     * Cache key generation.
     * Uses SHA-256 over the concatenated text + language + model triple. The old [String.hashCode]
     * approach collides at ~50% probability around 2^16 distinct entries, which would surface as
     * wrong translations being served from cache.
     */
    private fun generateCacheKey(
        textBlocks: List<OCRProcessor.TextBlock>,
        sourceLanguage: String,
        targetLanguage: String
    ): String {
        val text = textBlocks.joinToString("|") { it.text }
        val seed = "$text|$sourceLanguage|$targetLanguage|${config.modelName}"
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    // Data class for translated blocks
    data class TranslatedBlock(
        val originalText: String,
        val translatedText: String,
        val boundingBox: android.graphics.Rect,
        val sourceLanguage: String,
        val targetLanguage: String,
        val bgColor: Int = 0,  // sampled original background colour (0 = unknown / use configured)
        // Carried through from OCRProcessor.TextBlock; see it for why this is not the bounding
        // box's height. 0 when unknown.
        val lineHeight: Int = 0
    )
}