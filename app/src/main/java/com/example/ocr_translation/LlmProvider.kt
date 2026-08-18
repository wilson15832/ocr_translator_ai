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
    val gatewaySlug: String,
    /**
     * `reasoning_effort` to send, or null to leave it out. An OpenAI extension, so only the
     * vendors that implement it may see it.
     */
    val reasoningEffort: String? = null,
    /**
     * Whether to send `thinking: {type: disabled}` — DeepSeek's switch for turning reasoning off,
     * which is what took its latency from a 13s tail to about a second. Nobody else knows the
     * field.
     */
    val disablesThinking: Boolean = false
) {
    CHATGPT("Chatgpt", "chatgpt", "gpt", "openai", reasoningEffort = "minimal"),
    DEEPSEEK("DeepSeek", "api_key_deepseek", "deepseek", "deepseek", disablesThinking = true),
    GEMINI("Gemini", "api_key_gemini", "gemini", "google-ai-studio"),
    CLAUDE("Claude", "api_key_claude", "claude", "anthropic");

    fun matches(code: String) = code.startsWith(prefix)

    companion object {
        fun fromModel(code: String): LlmProvider =
            values().firstOrNull { it.matches(code) } ?: CHATGPT
    }
}