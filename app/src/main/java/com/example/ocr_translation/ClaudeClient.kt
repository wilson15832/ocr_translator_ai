package com.example.ocr_translation

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Anthropic Claude — Messages API（非 OpenAI 兼容：x-api-key + anthropic-version、system 单独、content[].text）。 */
class ClaudeClient(
    private val httpClient: OkHttpClient,
    private val gson: Gson,
    private val apiKey: String,
    private val endpoint: String,
    private val model: String,
    private val maxTokens: Int
) : LlmClient {
//    private val endpoint = "https://api.anthropic.com/v1/messages"

    private data class Message(
        @SerializedName("role") val role: String,
        @SerializedName("content") val content: String
    )
    private data class ClaudeRequest(
        @SerializedName("model") val model: String,
        @SerializedName("max_tokens") val maxTokens: Int,     // 必填
        @SerializedName("system") val system: String?,        // system 是顶层字段，不进 messages
        @SerializedName("messages") val messages: List<Message>
        // 不传 thinking → Claude 4.x 默认不做扩展思考（翻译无需）
    )
    private data class ContentBlock(
        @SerializedName("type") val type: String,
        @SerializedName("text") val text: String?
    )
    private data class Usage(
        @SerializedName("input_tokens") val inputTokens: Int = 0,
        @SerializedName("output_tokens") val outputTokens: Int = 0
    )
    private data class ClaudeResponse(
        @SerializedName("content") val content: List<ContentBlock>?,
        @SerializedName("usage") val usage: Usage? = null
    )

    override suspend fun translate(systemPrompt: String, userPrompt: String): String {
        if (apiKey.isBlank()) {
            throw Exception("API key not set. Open Settings and enter your API key.")
        }

        val payload = ClaudeRequest(
            model = model,
            maxTokens = maxTokens,
            system = systemPrompt.ifBlank { null },
            messages = listOf(Message("user", userPrompt))
        )
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("X-Api-Key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw Exception("Claude request failed: ${response.code} $responseBody")
            }
            val parsed = gson.fromJson(responseBody, ClaudeResponse::class.java)
            parsed.usage?.let { TokenStats.record(model, it.inputTokens, it.outputTokens, 0) }  // 复用成本日志
            return parsed.content?.firstOrNull { it.type == "text" }?.text
                ?: throw Exception("API returned an empty response")
        }
    }
}