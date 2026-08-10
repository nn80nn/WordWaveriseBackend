package n.startapp.services.ai

import n.startapp.utils.EnvConfig

/**
 * One place the model can be reached: endpoint, key and model name travel together.
 *
 * They have to be a unit rather than three globals, because the reserve pool is a different
 * host with a different key — mixing one provider's key with another's URL fails in a way that
 * looks exactly like an outage.
 */
data class LlmProvider(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val fastModel: String
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    /**
     * The chat-completions URL.
     *
     * AI_DOMEN is written either as a bare host or with the API prefix already on it — both are
     * natural to paste from a provider's docs — so a trailing `/v1` is stripped before the path
     * is added. Appending unconditionally produced `/v1/v1/chat/completions`, which gateways
     * answer with a bare 502 and no explanation.
     */
    val endpoint: String
        get() {
            var raw = baseUrl.trim().trimEnd('/')
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) raw = "https://$raw"
            if (raw.endsWith("/chat/completions")) raw = raw.removeSuffix("/chat/completions").trimEnd('/')
            if (raw.endsWith("/v1")) raw = raw.removeSuffix("/v1")
            return "$raw/v1/chat/completions"
        }

    fun modelFor(tier: LlmModelTier): String =
        if (tier == LlmModelTier.FAST) fastModel.ifBlank { model } else model

    companion object {
        /** Serves user-facing requests. */
        fun primary() = LlmProvider(
            name = "primary",
            baseUrl = EnvConfig.aiDomen,
            apiKey = EnvConfig.aiApiKey,
            model = EnvConfig.aiModel,
            fastModel = EnvConfig.aiModelFast
        )

        /**
         * Separate quota, used for the corpus warm-up so a bulk job cannot spend the budget
         * real lookups depend on — and as the fallback when the primary is rate limited.
         */
        fun pool() = LlmProvider(
            name = "pool",
            baseUrl = EnvConfig.aiDomenPool,
            apiKey = EnvConfig.aiApiKeyPool,
            model = EnvConfig.aiModelPool,
            fastModel = EnvConfig.aiModelPool
        )
    }
}

/** Which provider a request prefers, and whether it may spill over to the other one. */
enum class LlmRoute {
    /** User-facing: primary first, pool as a safety net when the primary runs out. */
    LIVE,

    /**
     * Bulk warm-up: pool only. Never spills over — the point of the separate quota is that a
     * background job cannot degrade the experience of someone actually waiting.
     */
    BULK
}
