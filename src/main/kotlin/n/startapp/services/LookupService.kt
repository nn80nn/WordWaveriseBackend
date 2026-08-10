package n.startapp.services

import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import n.startapp.exceptions.BadRequestException
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.LexicalKind
import n.startapp.models.lookup.AnnotationStatus
import n.startapp.models.lookup.LookupResponse
import n.startapp.models.query.QueryKind
import n.startapp.models.query.ResolvedQuery
import n.startapp.repositories.LexicalEntryRepository
import n.startapp.services.dictionary.AggregatedWord
import n.startapp.services.dictionary.DictionaryAggregationService
import n.startapp.services.lexical.LexicalAnnotationService
import n.startapp.services.lexical.LexicalEntryFallback
import n.startapp.services.lexical.LexicalPromptBuilder
import n.startapp.services.query.QueryResolver
import n.startapp.utils.EnvConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Orchestrates a v2 lookup: resolve the query, fetch raw data, serve or start annotation.
 *
 * Annotation is slow (one large model call) but permanent, so the request does not wait for it
 * indefinitely. A cold lookup returns the raw aggregate with `PENDING` after a short grace
 * period and finishes the annotation in the background; the client re-issues the same request
 * and hits the warm path. Concurrent lookups of the same lemma share one in-flight job, so a
 * word going viral still costs exactly one model call.
 */
class LookupService(
    private val aggregationService: DictionaryAggregationService,
    private val annotationService: LexicalAnnotationService,
    private val repository: LexicalEntryRepository,
    private val queryResolver: QueryResolver
) {
    private val logger = LoggerFactory.getLogger(LookupService::class.java)

    /** How long a request waits for a fresh annotation before falling back to PENDING. */
    private val ANNOTATION_GRACE_MS = 1_200L
    private val RETRY_AFTER_MS = 2_500

    /**
     * Hard ceiling on one annotation. The LLM client already retries with backoff, so an
     * unhealthy provider can otherwise keep a job alive for minutes — during which every
     * request for that word reports PENDING and the user waits on something that will not come.
     */
    private val ANNOTATION_DEADLINE_MS = 90_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<AnnotationOutcome>>()

    /** Short-lived so a hot word skips the DB round-trip; the durable copy lives in Postgres. */
    private val hot = Caffeine.newBuilder()
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .maximumSize(2_000)
        .build<String, LexicalEntry>()

    /**
     * Failed annotations are cached briefly and deliberately.
     *
     * They must not be persisted — the next request should retry the model — but without a
     * short-lived record every retry starts a fresh job, the grace period expires again, and the
     * client polls PENDING forever instead of ever seeing the degraded article it could render.
     */
    private val degraded = Caffeine.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .maximumSize(1_000)
        .build<String, AnnotationOutcome>()

    private data class AnnotationOutcome(
        val entry: LexicalEntry,
        val reason: String? = null,
        val detail: String? = null
    )

    suspend fun lookup(query: String): LookupResponse {
        if (query.isBlank()) throw BadRequestException("Query parameter 'query' cannot be empty")

        val resolution = queryResolver.resolve(query)
        val lemma = resolution.lemma

        if (lemma.isNullOrBlank()) {
            return LookupResponse(
                resolution = resolution,
                annotationStatus = AnnotationStatus.UNAVAILABLE
            )
        }

        // Russian input and free text are handled by pipelines that do not exist yet; until then
        // they resolve to no article rather than being sent to the dictionary as a headword.
        if (resolution.language == "ru" || resolution.kind == QueryKind.SENTENCE) {
            return LookupResponse(
                resolution = resolution,
                annotationStatus = AnnotationStatus.UNAVAILABLE
            )
        }

        val kind = kindFor(resolution)
        val cacheKey = LexicalEntryRepository.cacheKey(
            lemma = lemma,
            kind = kind.name,
            promptVersion = LexicalPromptBuilder.PROMPT_VERSION,
            model = EnvConfig.aiModel
        )

        hot.getIfPresent(cacheKey)?.let { cached ->
            return LookupResponse(
                resolution = resolution,
                entry = cached,
                annotationStatus = AnnotationStatus.READY
            )
        }

        repository.find(cacheKey)?.let { stored ->
            hot.put(cacheKey, stored.entry)
            return LookupResponse(
                resolution = resolution,
                entry = stored.entry,
                annotationStatus = AnnotationStatus.READY,
                raw = stored.raw
            )
        }

        // Cold path. Fetch fast (API sources only) so the user has something to look at even if
        // annotation overruns the grace period.
        val aggregate = aggregationService.aggregateDetailed(
            word = lemma,
            skipScrapers = true,
            isPhrase = resolution.kind == QueryKind.PHRASE
        )

        degraded.getIfPresent(cacheKey)?.let { failed ->
            return LookupResponse(
                resolution = resolution,
                entry = failed.entry,
                annotationStatus = AnnotationStatus.DEGRADED,
                annotationNote = failed.reason,
                raw = aggregate.response
            )
        }

        val job = inFlight.computeIfAbsent(cacheKey) {
            scope.async {
                try {
                    val result = withTimeoutOrNull(ANNOTATION_DEADLINE_MS) {
                        annotationService.annotate(lemma, resolution.surface, kind, aggregate)
                    } ?: LexicalAnnotationService.AnnotationResult(
                        entry = LexicalEntryFallback.fromRaw(
                            aggregate.response, lemma, resolution.surface, kind,
                            annotationService.buildSources(aggregate.sourceDefinitions)
                        ),
                        reason = "llm_timeout",
                        detail = "annotation exceeded ${ANNOTATION_DEADLINE_MS}ms"
                    )

                    val outcome = AnnotationOutcome(result.entry, result.reason, result.detail)
                    if (result.entry.degraded) {
                        logger.warn("Annotation degraded for '{}': {} — {}", lemma, result.reason, result.detail)
                        degraded.put(cacheKey, outcome)
                    } else {
                        repository.save(
                            cacheKey = cacheKey,
                            entry = result.entry,
                            raw = aggregate.response,
                            sourceFingerprint = LexicalEntryRepository.fingerprint(
                                aggregate.sourceDefinitions.map { it.definition }
                            )
                        )
                        hot.put(cacheKey, result.entry)
                    }
                    outcome
                } finally {
                    inFlight.remove(cacheKey)
                }
            }
        }

        val outcome = withTimeoutOrNull(ANNOTATION_GRACE_MS) { job.await() }

        return when {
            outcome == null -> LookupResponse(
                resolution = resolution,
                annotationStatus = AnnotationStatus.PENDING,
                retryAfterMs = RETRY_AFTER_MS,
                raw = aggregate.response
            )
            outcome.entry.degraded -> LookupResponse(
                resolution = resolution,
                entry = outcome.entry,
                annotationStatus = AnnotationStatus.DEGRADED,
                annotationNote = outcome.reason,
                raw = aggregate.response
            )
            else -> LookupResponse(
                resolution = resolution,
                entry = outcome.entry,
                annotationStatus = AnnotationStatus.READY,
                raw = aggregate.response
            )
        }
    }

    /**
     * Runs annotation synchronously and reports exactly what happened. Admin-only: the detail
     * carries provider error text, which has no business in a public response.
     */
    suspend fun diagnose(query: String): Map<String, String> {
        val resolution = queryResolver.resolve(query)
        val lemma = resolution.lemma ?: return mapOf("error" to "query resolved to no lemma")
        val kind = kindFor(resolution)
        val aggregate = aggregationService.aggregateDetailed(lemma, skipScrapers = true)
        val started = System.currentTimeMillis()
        val result = annotationService.annotate(lemma, resolution.surface, kind, aggregate)
        return mapOf(
            "lemma" to lemma,
            "model" to EnvConfig.aiModel,
            "structuredMode" to n.startapp.services.ai.AiCompat.structuredMode.name,
            "tokenParam" to n.startapp.services.ai.AiCompat.tokenParam,
            "sourceFragments" to aggregate.sourceDefinitions.size.toString(),
            "elapsedMs" to (System.currentTimeMillis() - started).toString(),
            "degraded" to result.entry.degraded.toString(),
            "posGroups" to result.entry.posGroups.size.toString(),
            "reason" to (result.reason ?: "-"),
            "detail" to (result.detail ?: "-")
        )
    }

    private fun kindFor(resolution: ResolvedQuery): LexicalKind = when (resolution.kind) {
        QueryKind.PHRASE -> LexicalKind.PHRASE
        else -> LexicalKind.WORD
    }

    /** Drops every cached article for a lemma so the next lookup regenerates it. */
    suspend fun invalidate(lemma: String): Int {
        val normalized = lemma.trim().lowercase()
        hot.asMap().keys.filter { it.contains("|$normalized|") }.forEach { hot.invalidate(it) }
        val removed = repository.deleteByLemma(normalized)
        logger.info("Invalidated {} lexical entr(ies) for '{}'", removed, normalized)
        return removed
    }

    fun close() = scope.cancel()
}
