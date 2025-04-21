package com.example.ocr_translation

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import com.example.ocr_translation.SecureStorage
import android.util.Log


class TranslationService private constructor(private val context: Context) {

    // Singleton pattern implementation
    companion object {
        @Volatile
        private var INSTANCE: TranslationService? = null

        fun getInstance(context: Context): TranslationService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TranslationService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // API Endpoints - can be changed in settings
    private var geminiApiEndpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
    private var apiKey = "" // To be set from secure storage

    // Cache system
    private val translationCache = TranslationCache(context)

    // Configure translation settings
    data class TranslationConfig(
        var sourceLanguage: String = "auto", // Auto-detect
        var targetLanguage: String = "en",   // English default
        var preserveFormatting: Boolean = true,
        var preferSpeed: Boolean = false,    // Speed vs quality tradeoff
        var modelName: String = "gpt-4-turbo", // Default model
        var useLocalModel: Boolean = false,  // Option for on-device models
        var maxCacheSize: Int = 100,          // Max entries in cache
        var instruction: String = ""
    )

    var config = TranslationConfig()

    // OkHttp client for API calls
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Gson for JSON parsing
    private val gson = Gson()

    // Data classes for API request/response
    data class GeminiContent(
        @SerializedName("parts") val parts: List<GeminiPart>
    )

    data class GeminiPart(
        @SerializedName("text") val text: String
    )

    data class GeminiRequest(
        @SerializedName("contents") val contents: List<GeminiContent>,
        @SerializedName("generationConfig") val generationConfig: GenerationConfig = GenerationConfig()
    )

    data class GenerationConfig(
        @SerializedName("temperature") val temperature: Double = 0.2,
        @SerializedName("topK") val topK: Int = 40,
        @SerializedName("topP") val topP: Double = 0.95,
        @SerializedName("maxOutputTokens") val maxOutputTokens: Int = 1024
    )

    data class GeminiResponse(
        @SerializedName("candidates") val candidates: List<GeminiCandidate>
    )

    data class GeminiCandidate(
        @SerializedName("content") val content: GeminiContent
    )

    fun loadConfig(preferencesManager: PreferencesManager) {
        config.sourceLanguage = preferencesManager.sourceLanguage
        config.targetLanguage = preferencesManager.targetLanguage
        config.preserveFormatting = preferencesManager.preserveFormatting
        config.preferSpeed = preferencesManager.preferSpeed
        config.modelName = preferencesManager.modelName
        config.useLocalModel = preferencesManager.useLocalModel
        config.maxCacheSize = preferencesManager.maxCacheEntries
        apiKey = preferencesManager.llmApiKey
        geminiApiEndpoint = preferencesManager.llmApiEndpoint
        config.instruction = preferencesManager.llmInstruction
    }

    enum class LlmProvider {
        GEMINI,
        OPENAI,
        CLAUDE
        // Add others as needed
    }

    var currentProvider: LlmProvider = LlmProvider.GEMINI

    private suspend fun translateWithAPI(prompt: String): String {
        return when(currentProvider) {
            LlmProvider.GEMINI -> translateWithGemini(prompt)
            LlmProvider.OPENAI -> translateWithOpenAI(prompt)
            LlmProvider.CLAUDE -> translateWithClaude(prompt)
            // Add cases for other providers
        }
    }

    private suspend fun translateWithGemini(prompt: String): String {
        // Your existing Gemini implementation
        val content = GeminiContent(parts = listOf(GeminiPart(text = prompt)))

        val geminiRequest = GeminiRequest(
            contents = listOf(content)
        )

        val requestBody = gson.toJson(geminiRequest)
            .toRequestBody("application/json".toMediaTypeOrNull())

        val urlWithKey = "$geminiApiEndpoint?key=$apiKey"

        val request = Request.Builder()
            .url(urlWithKey)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("API 请求失败: ${response.code}")
            }

            val responseBody = response.body?.string() ?: ""
            val geminiResponse = gson.fromJson(responseBody, GeminiResponse::class.java)

            return geminiResponse.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: throw Exception("API 返回了空响应")
        }
    }

    private suspend fun translateWithOpenAI(prompt: String): String {
        // OpenAI implementation
        return "Not implemented"
    }

    private suspend fun translateWithClaude(prompt: String): String {
        // Claude implementation
        return "Not implemented"
    }


    // Main translation function
    suspend fun translateText(
        textBlocks: List<OCRProcessor.TextBlock>,
        instructions: String,
    ): List<TranslatedBlock> {
        return withContext(Dispatchers.IO) {
            // Check cache first
            val cacheKey = generateCacheKey(textBlocks, config.targetLanguage)
            val cachedResult = translationCache.getTranslation(cacheKey)

            if (cachedResult != null) {
                return@withContext cachedResult
            }

            // Prepare for translation
            val translations = mutableListOf<TranslatedBlock>()

            // Group text blocks for efficient API usage
            val combinedText = textBlocks.joinToString("\n\n") {
                "BLOCK_${it.boundingBox.hashCode()}: ${it.text}"
            }

            // Create prompt for LLM
            val prompt = createTranslationPrompt(combinedText, config.instruction)

            try {
                val result = if (config.useLocalModel) {
                    translateWithLocalModel(prompt)
                } else {
                    translateWithAPI(prompt)
                }

                val parsedResults = parseTranslationResult(result, textBlocks)
                translations.addAll(parsedResults)

                // Explicitly typed variable (optional now, but keep for checking)
                val listToSave: List<TranslatedBlock> = translations

                // Cache the result
                translationCache.saveTranslation(
                    cacheKey,
                    listToSave, // Pass the list
                    config.sourceLanguage,
                    config.targetLanguage
                )

                translations // <--- ADD THIS: Make the list the return value of the try block

            } catch (e: Exception) {
                e.printStackTrace()
                // Return fallback translation
                textBlocks.map {
                    TranslatedBlock(
                        originalText = it.text,
                        translatedText = "Translation failed",
                        boundingBox = it.boundingBox,
                        sourceLanguage = config.sourceLanguage, // <-- Pass language from config
                        targetLanguage = config.targetLanguage  // <-- Pass language from config
                    )
                }
            }
        }
    }

    // Create prompt for LLM translation
    private fun createTranslationPrompt(text: String, instructions: String): String {
        Log.d("TranslationService", "Translate from source text: ${text}")
        return """
        Translate the following text from ${config.sourceLanguage} to ${config.targetLanguage}.
        Requirement:
        Maintain the original formatting and layout as much as possible.
        Keep the BLOCK_XXX: prefixes in the output but don't translate them.
        ${instructions}
        
        Text to translate:
        $text
        
        Translation:
        """
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
                    sourceLanguage = config.sourceLanguage, // <-- Pass language from config
                    targetLanguage = config.targetLanguage  // <-- Pass language from config
                )
            )
        }

        return translations
    }

    // Cache key generation
    private fun generateCacheKey(
        textBlocks: List<OCRProcessor.TextBlock>,
        targetLanguage: String
    ): String {
        val text = textBlocks.joinToString("|") { it.text }
        return "$text:$targetLanguage".hashCode().toString()
    }

    // Data class for translated blocks
    data class TranslatedBlock(
        val originalText: String,
        val translatedText: String,
        val boundingBox: android.graphics.Rect,
        val sourceLanguage: String,
        val targetLanguage: String
    )

    private fun getSecureApiKey(): String {
        // Using Android Keystore or other secure storage mechanism
        // This is pseudocode - you'll need to implement proper secure storage
        return SecureStorage.getEncryptedValue(context, "api_key") ?: ""
    }

    fun updateApiKey(newKey: String) {
        apiKey = newKey
    }

    fun updateApiEndpoint(newEndpoint: String) {
        geminiApiEndpoint = newEndpoint
    }

    // Method to set the source language
    fun setSourceLanguage(language: String) {
        Log.d("PreferencesManager", "Setting source language: $language")
        config.sourceLanguage = language
    }

    // Method to set the target language
    fun setTargetLanguage(language: String) {
        Log.d("PreferencesManager", "Setting target language: $language")
        config.targetLanguage = language
    }
}