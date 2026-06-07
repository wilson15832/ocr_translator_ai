package com.example.ocr_translation

import android.content.Context
import android.hardware.usb.UsbEndpoint
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
import androidx.compose.ui.semantics.Role
import com.example.ocr_translation.LlmClient
import com.example.ocr_translation.GeminiClient
import com.example.ocr_translation.OpenAiCompatibleClient


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
        .build()

    // Gson for JSON parsing
    private val gson = Gson()

    fun loadConfig(preferencesManager: PreferencesManager) {
        config.sourceLanguage = preferencesManager.sourceLanguage
        config.targetLanguage = preferencesManager.targetLanguage
        config.preserveFormatting = preferencesManager.preserveFormatting
        config.preferSpeed = preferencesManager.preferSpeed
        config.modelName = preferencesManager.modelName
        config.useLocalModel = preferencesManager.useLocalModel
        config.maxCacheSize = preferencesManager.maxCacheEntries
        config.systemPrompt = preferencesManager.systemPrompt
        config.userPrompt = preferencesManager.userPrompt
        apiKey = preferencesManager.llmApiKey
        }

    private fun createLlmClent(): LlmClient{
        val model = config.modelName
        return when {
            model.startsWith("deepseek") ->
                OpenAiCompatibleClient(client, gson, apiKey,
                    "https://api.deepseek.com/chat/completions", model)
            model.startsWith("gpt") ->
                OpenAiCompatibleClient(client, gson, apiKey,
                    "https://api.openai.com/v1/chat/completions", model)
            else ->  // gemini-* and fallback
                GeminiClient(client, gson, apiKey, model)
        }
    }


    // Main translation function
    suspend fun translateText(
        textBlocks: List<OCRProcessor.TextBlock>,
        sourceLanguage: String,
        targetLanguage: String
    ): List<TranslatedBlock> {
        return withContext(Dispatchers.IO) {
            // Check cache first
            loadConfig(PreferencesManager.getInstance(context))

            val cacheKey = generateCacheKey(textBlocks, sourceLanguage, targetLanguage)
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
            val prompt = createTranslationPrompt(combinedText, sourceLanguage, targetLanguage)

            try {
                val result = if (config.useLocalModel) {
                    translateWithLocalModel(prompt)
                } else {
                    createLlmClent().translate(config.systemPrompt, prompt)
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
        sourceLanguage: String,
        targetLanguage: String
    ): String {
        val text = textBlocks.joinToString("|") { it.text }
        return "$text:$sourceLanguage:$targetLanguage:${config.modelName}".hashCode().toString()
    }

    // Data class for translated blocks
    data class TranslatedBlock(
        val originalText: String,
        val translatedText: String,
        val boundingBox: android.graphics.Rect,
        val sourceLanguage: String,
        val targetLanguage: String,
        val bgColor: Int = 0   // sampled original background colour (0 = unknown / use configured)
    )

    private fun getSecureApiKey(): String {
        // Using Android Keystore or other secure storage mechanism
        // This is pseudocode - you'll need to implement proper secure storage
        return SecureStorage.getEncryptedValue(context, "api_key") ?: ""
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