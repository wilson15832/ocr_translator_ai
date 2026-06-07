package com.example.ocr_translation

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiClient(
    private val httpClient: OkHttpClient,
    private val gson: Gson,
    private val apiKey: String,
    private val model: String
) : LlmClient {
    private val endpoint =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

    private data class Content(@SerializedName("parts") val parts: List<Part>)
    private data class Part(@SerializedName("text") val text: String)
    private data class GenerationConfig(
        @SerializedName("temperature") val temperature: Double = 0.2,
        @SerializedName("topK") val topK: Int = 40,
        @SerializedName("topP") val topP: Double = 0.95,
        @SerializedName("maxOutputTokens") val maxOutputTokens: Int = 1024
    )
    private data class GeminiRequest(
        @SerializedName("contents") val contents: List<Content>,
        @SerializedName("generationConfig") val generationConfig: GenerationConfig = GenerationConfig()
    )
    private data class GeminiResponse(@SerializedName("candidates") val candidates: List<Candidate>)
    private data class Candidate(@SerializedName("content") val content: Content)

    override suspend fun translate(systemPrompt: String, userPrompt: String): String {
        if (apiKey.isBlank()){
            throw Exception("API key not set. Open Settings and enter your API key.")
        }

        // Gemini's simple request has no system role here, so prepend it to the prompt
        val text = if (systemPrompt.isBlank()) userPrompt else "$systemPrompt\n\n$userPrompt"
        val payload = GeminiRequest(contents = listOf(Content(listOf(Part(text)))))
        val Body = gson.toJson(payload).toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("$endpoint?key=$apiKey")
            .post(Body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw Exception("Gemini request failed: ${response.code} $responseBody")
            }
            val parsed = gson.fromJson(responseBody, GeminiResponse::class.java)
            return parsed.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: throw Exception("API returned an empty response")
        }
    }
}