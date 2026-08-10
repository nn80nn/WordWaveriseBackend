package n.startapp.services.ai

import n.startapp.utils.EnvConfig
import org.slf4j.Logger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI-compatible providers disagree on two fields of the chat-completions body:
 *
 *  - the token limit is `max_completion_tokens` on gpt-5.x and `max_tokens` on most OSS models;
 *  - `temperature` is rejected outright by gpt-5.x (only the default 1 is allowed).
 *
 * Both are configured via env (`AI_TOKEN_PARAM`, `AI_SUPPORTS_TEMPERATURE`) and, if the config
 * is wrong for the deployed model, corrected once at runtime from the provider's own 400 body.
 * Without this, pointing `AI_MODEL` at gpt-5.2 makes every AI call fail with a 400.
 */
object AiCompat {
    /**
     * Reasoning models bill hidden reasoning tokens against the completion budget, so a small
     * cap returns an empty message with `finish_reason: "length"`. Anything asking for less
     * than this under the `max_completion_tokens` dialect gets raised to it — it is a ceiling,
     * not a spend, so widening it costs nothing when the answer really is short.
     */
    private const val REASONING_BUDGET_FLOOR = 800

    /** Strongest response constraint the provider has been observed to accept. */
    enum class StructuredMode { JSON_SCHEMA, JSON_OBJECT, NONE }

    private val tokenParamRef = AtomicReference(EnvConfig.aiTokenParam)
    private val supportsTemperatureRef = AtomicBoolean(EnvConfig.aiSupportsTemperature)
    private val structuredModeRef = AtomicReference(EnvConfig.aiStructuredMode)

    val tokenParam: String get() = tokenParamRef.get()
    val supportsTemperature: Boolean get() = supportsTemperatureRef.get()
    val structuredMode: StructuredMode get() = structuredModeRef.get()

    /**
     * Steps the response-format constraint down one level after the provider rejected it.
     * @return true when there was somewhere left to step down to.
     */
    fun downgradeStructuredMode(logger: Logger): Boolean {
        val current = structuredModeRef.get()
        val next = when (current) {
            StructuredMode.JSON_SCHEMA -> StructuredMode.JSON_OBJECT
            StructuredMode.JSON_OBJECT -> StructuredMode.NONE
            StructuredMode.NONE -> return false
        }
        structuredModeRef.set(next)
        logger.warn(
            "AI provider rejected response_format={}; falling back to {} for the rest of this process. Output validation now carries more weight — set AI_STRUCTURED_MODE to silence this.",
            current, next
        )
        return true
    }

    /**
     * Stops sending `temperature` at all.
     *
     * Reasoning models reject any value other than the default — Claude with extended thinking
     * and gpt-5.x both do — and a gateway usually reports that as a server error rather than
     * passing the upstream 400 through, so it cannot be recognised from the response body.
     *
     * @return true when the field was being sent and now is not.
     */
    fun disableTemperature(logger: Logger): Boolean {
        if (!supportsTemperatureRef.getAndSet(false)) return false
        logger.warn(
            "AI provider rejected requests carrying temperature; omitting it for the rest of this process. Set AI_SUPPORTS_TEMPERATURE=false to avoid the extra round-trip."
        )
        return true
    }

    fun effectiveMaxTokens(requested: Int): Int =
        if (tokenParam == "max_completion_tokens") maxOf(requested, REASONING_BUDGET_FLOOR)
        else requested

    /**
     * Inspects a provider 400 body and flips whichever flag it complains about.
     * @return true when something changed and the call is worth retrying.
     */
    fun adaptTo(errorBody: String, logger: Logger): Boolean {
        val body = errorBody.lowercase()
        var changed = false

        if ("max_tokens" in body && "max_completion_tokens" in body && tokenParam == "max_tokens") {
            // Typical wording: "Use 'max_completion_tokens' instead of 'max_tokens'".
            tokenParamRef.set("max_completion_tokens")
            changed = true
        } else if ("max_completion_tokens" in body && tokenParam == "max_completion_tokens") {
            // Provider does not know the newer field at all.
            tokenParamRef.set("max_tokens")
            changed = true
        }

        if ("temperature" in body && supportsTemperatureRef.get()) {
            supportsTemperatureRef.set(false)
            changed = true
        }

        if (changed) {
            logger.warn(
                "AI provider rejected the request body; adapting to tokenParam={} temperature={}. Set AI_TOKEN_PARAM / AI_SUPPORTS_TEMPERATURE to avoid the extra round-trip. Provider said: {}",
                tokenParam, supportsTemperature, errorBody.take(300)
            )
        } else {
            logger.error("AI provider returned 400 and no known field to adapt: {}", errorBody.take(500))
        }
        return changed
    }
}
