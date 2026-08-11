package n.startapp.services.ai

import n.startapp.utils.EnvConfig
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI-compatible providers disagree on parts of the chat-completions body:
 *
 *  - the token limit is `max_completion_tokens` on gpt-5.x and `max_tokens` on most OSS models;
 *  - `temperature` is rejected outright by reasoning models, which only accept the default;
 *  - `response_format: json_schema` is not universally supported.
 *
 * All three are configured via env and, if the config is wrong for the deployed model,
 * corrected at runtime from the provider's own rejection. Without this, pointing AI_MODEL at a
 * different model can make every AI call fail.
 *
 * State is kept **per provider**: the primary and the reserve pool are separate endpoints that
 * may run different software, so a rejection from one must not strip a field the other accepts.
 */
object AiCompat {

    enum class StructuredMode { JSON_SCHEMA, JSON_OBJECT, NONE }

    /**
     * Reasoning models bill hidden reasoning tokens against the completion budget, so a small
     * cap returns an empty message with `finish_reason: "length"`. Anything asking for less
     * than this under the `max_completion_tokens` dialect gets raised to it — it is a ceiling,
     * not a spend, so widening it costs nothing when the answer really is short.
     */
    private const val REASONING_BUDGET_FLOOR = 800

    private class Dialect {
        val tokenParam = AtomicReference(EnvConfig.aiTokenParam)
        val supportsTemperature = AtomicBoolean(EnvConfig.aiSupportsTemperature)
        val structuredMode = AtomicReference(EnvConfig.aiStructuredMode)
    }

    private val dialects = ConcurrentHashMap<String, Dialect>()

    private fun dialect(provider: String) = dialects.computeIfAbsent(provider) { Dialect() }

    /**
     * Forgets what every provider was adapted to, so the next call re-reads the configuration.
     *
     * A dialect is captured on first use and then only ever narrowed by rejections, which is
     * right while the model stays put and wrong the moment it changes: a body adapted to gpt-5.x
     * is exactly the body llama refuses. Without this, editing a model in the admin panel would
     * leave the client using settings the panel no longer shows — the panel would be lying.
     */
    fun reset(logger: Logger? = null) {
        if (dialects.isEmpty()) return
        dialects.clear()
        logger?.info("Provider dialects reset; configuration will be re-read on the next call")
    }

    fun tokenParam(provider: String): String = dialect(provider).tokenParam.get()
    fun supportsTemperature(provider: String): Boolean = dialect(provider).supportsTemperature.get()
    fun structuredMode(provider: String): StructuredMode = dialect(provider).structuredMode.get()

    fun effectiveMaxTokens(provider: String, requested: Int): Int =
        if (tokenParam(provider) == "max_completion_tokens") maxOf(requested, REASONING_BUDGET_FLOOR)
        else requested

    /** Steps the response-format constraint down one level. @return false when already at the bottom. */
    fun downgradeStructuredMode(provider: String, logger: Logger): Boolean {
        val ref = dialect(provider).structuredMode
        val current = ref.get()
        val next = when (current) {
            StructuredMode.JSON_SCHEMA -> StructuredMode.JSON_OBJECT
            StructuredMode.JSON_OBJECT -> StructuredMode.NONE
            StructuredMode.NONE -> return false
        }
        ref.set(next)
        logger.warn(
            "[{}] provider rejected response_format={}; falling back to {} for the rest of this process. Output validation now carries more weight — set AI_STRUCTURED_MODE to silence this.",
            provider, current, next
        )
        return true
    }

    /**
     * Stops sending `temperature` at all. Reasoning models reject any value other than the
     * default, and a gateway usually reports that as a server error rather than passing the
     * upstream 400 through, so it cannot be recognised from the response body.
     */
    fun disableTemperature(provider: String, logger: Logger): Boolean {
        if (!dialect(provider).supportsTemperature.getAndSet(false)) return false
        logger.warn(
            "[{}] provider rejected requests carrying temperature; omitting it from now on. Set AI_SUPPORTS_TEMPERATURE=false to avoid the extra round-trip.",
            provider
        )
        return true
    }

    /**
     * Inspects a provider 400 body and flips whichever field it complains about.
     * @return true when something changed and the call is worth retrying.
     */
    fun adaptTo(provider: String, errorBody: String, logger: Logger): Boolean {
        val body = errorBody.lowercase()
        val d = dialect(provider)
        var changed = false

        if ("max_tokens" in body && "max_completion_tokens" in body && d.tokenParam.get() == "max_tokens") {
            // Typical wording: "Use 'max_completion_tokens' instead of 'max_tokens'".
            d.tokenParam.set("max_completion_tokens")
            changed = true
        } else if ("max_completion_tokens" in body && d.tokenParam.get() == "max_completion_tokens") {
            // Provider does not know the newer field at all.
            d.tokenParam.set("max_tokens")
            changed = true
        }

        if ("temperature" in body && d.supportsTemperature.get()) {
            d.supportsTemperature.set(false)
            changed = true
        }

        if (changed) {
            logger.warn(
                "[{}] adapting request body: tokenParam={} temperature={}. Provider said: {}",
                provider, d.tokenParam.get(), d.supportsTemperature.get(), errorBody.take(300)
            )
        } else {
            logger.error("[{}] provider returned 400 and no known field to adapt: {}", provider, errorBody.take(500))
        }
        return changed
    }
}
