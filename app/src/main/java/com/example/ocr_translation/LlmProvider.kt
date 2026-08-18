package com.example.ocr_translation

enum class LlmProvider(
    val displayName: String,
    val secureKey: String,      // SecureStorage 里的独立密钥槽
    private val prefix: String, // 模型码前缀
    /**
     * How Cloudflare's AI Gateway names this vendor.
     *
     * Its OpenAI-compatible endpoint has no per-vendor URL — one endpoint serves all of them and
     * routes on a `slug/model` prefix in the request body. So this is what decides where a request
     * ends up once the proxy is on, in place of the endpoint the direct path picks.
     */
    val gatewaySlug: String
) {
    CHATGPT("Chatgpt", "chatgpt", "gpt", "openai"),
    DEEPSEEK("DeepSeek", "api_key_deepseek", "deepseek", "deepseek"),
    GEMINI("Gemini", "api_key_gemini", "gemini", "google-ai-studio"),
    CLAUDE("Claude", "api_key_claude", "claude", "anthropic");

    fun matches(code: String) = code.startsWith(prefix)

    companion object {
        fun fromModel(code: String): LlmProvider =
            values().firstOrNull { it.matches(code) } ?: CHATGPT
    }
}