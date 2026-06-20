package com.example.ocr_translation

enum class LlmProvider(
    val displayName: String,
    val secureKey: String,      // SecureStorage 里的独立密钥槽
    private val prefix: String  // 模型码前缀
) {
    CHATGPT("Chatgpt", "chatgpt", "gpt"),
    DEEPSEEK("DeepSeek", "api_key_deepseek", "deepseek"),
    GEMINI("Gemini", "api_key_gemini", "gemini"),
    CLAUDE("Claude", "api_key_claude", "claude");

    fun matches(code: String) = code.startsWith(prefix)

    companion object {
        fun fromModel(code: String): LlmProvider =
            values().firstOrNull { it.matches(code) } ?: CHATGPT
    }
}