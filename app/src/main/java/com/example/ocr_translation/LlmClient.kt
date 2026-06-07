package com.example.ocr_translation

interface LlmClient {
    /** Sends [systemPrompt] (persona/instructions) and [userPrompt] to the provider. */
    suspend fun translate(systemPrompt: String, userPrompt: String): String
}