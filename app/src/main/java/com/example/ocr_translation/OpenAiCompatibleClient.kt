package com.example.ocr_translation

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Serves any OpenAI-compatible chat-completions API (DeepSeek, OpenAI, …). */
class OpenAiCompatibleClient(
    private val httpClient: OkHttpClient,
    private val gson: Gson,
    private val apiKey: String,
    private val endpoint: String,
    private val model: String,
    private val maxTokens: Int
) : LlmClient {

    private data class Message(
        @SerializedName("role") val role: String,
        @SerializedName("content") val content: String
    )
    private data class ThinkingConfig(
        @SerializedName("type") val type: String   // "enabled" / "disabled"
    )
    private data class ChatRequest(
        @SerializedName("model") val model: String,
        @SerializedName("messages") val messages: List<Message>,
        @SerializedName("stream") val stream: Boolean = false,
        @SerializedName("temperature") val temperature: Double = 0.2,
        @SerializedName("max_tokens") val maxTokens: Int = 2048,
        @SerializedName("reasoning_effort") val reasoningEffort: String? = null,
        @SerializedName("thinking") val thinking: ThinkingConfig? = null
    )

    private data class CompletionDetails(
        @SerializedName("reasoning_tokens") val reasoningTokens: Int = 0
    )
    private data class Usage(
        @SerializedName("prompt_tokens") val promptTokens: Int = 0,
        @SerializedName("completion_tokens") val completionTokens: Int = 0,
        @SerializedName("total_tokens") val totalTokens: Int = 0,
        @SerializedName("completion_tokens_details") val completionDetails: CompletionDetails? = null
    )
    private data class Choice(@SerializedName("message") val message: Message)
    private data class ChatResponse(
        @SerializedName("choices") val choices: List<Choice>,
        @SerializedName("usage") val usage: Usage? = null
    )

    override suspend fun translate(systemPrompt: String, userPrompt: String): String {
        if (apiKey.isBlank()) {
            throw Exception("API key not set. Open Settings and enter your API key.")
        }

        val messages = mutableListOf<Message>()
        if (systemPrompt.isNotBlank()) messages.add(Message("system", systemPrompt))
        messages.add(Message("user", userPrompt))
        val payload = ChatRequest(
            model = model,
            messages = messages,
            maxTokens = maxTokens,
            reasoningEffort = if (model.startsWith("deepseek")) null else "minimal",
            thinking = ThinkingConfig("disabled")
        )
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw Exception("API request failed: ${response.code} $responseBody")
            }
            val parsed = gson.fromJson(responseBody, ChatResponse::class.java)
            parsed.usage?.let { u ->
                TokenStats.record(
                    model,
                    u.promptTokens,
                    u.completionTokens,
                    u.completionDetails?.reasoningTokens ?: 0
                )
            }
            return parsed.choices.firstOrNull()?.message?.content
                ?: throw Exception("API returned an empty response")
        }
    }
}