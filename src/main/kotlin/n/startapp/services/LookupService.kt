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
import n.startapp.exceptions.NotFoundException
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.LexicalKind
import n.startapp.models.lookup.AnnotationStatus
import n.startapp.models.lookup.LookupNotice
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
    private val queryResolver: QueryResolver,
    private val ruEnTranslationService: n.startapp.services.query.RuEnTranslationService? = null
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
    private val ANNOTATION_DEADLINE_MS = 150_000L

    /** Failures the provider may recover from on its own — mainly rate limiting. */
    private val TRANSIENT_REASONS = setOf("llm_call_failed", "llm_timeout")
    private val TRANSIENT_RETRY_MS = 45_000L

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
     *
     * Entries expire on their own schedule: a rate-limited call is worth retrying in under a
     * minute, whereas a reply the validator rejected will be rejected again, so retrying it
     * quickly just burns the token budget.
     */
    private val degraded = Caffeine.newBuilder()
        .expireAfter(object : com.github.benmanes.caffeine.cache.Expiry<String, AnnotationOutcome> {
            override fun expireAfterCreate(key: String, value: AnnotationOutcome, currentTime: Long): Long =
                TimeUnit.MILLISECONDS.toNanos(
                    if (value.reason in TRANSIENT_REASONS) TRANSIENT_RETRY_MS
                    else TimeUnit.MINUTES.toMillis(10)
                )

            override fun expireAfterUpdate(
                key: String, value: AnnotationOutcome, currentTime: Long, currentDuration: Long
            ): Long = currentDuration

            override fun expireAfterRead(
                key: String, value: AnnotationOutcome, currentTime: Long, currentDuration: Long
            ): Long = currentDuration
        })
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
        val notice = noticeFor(resolution)

        // A sentence has no headword. Return its words instead, so the client can make them
        // tappable and the user can ask about the one they actually stumbled on. Checked before
        // the empty-lemma guard precisely because a sentence always has a null lemma.
        if (resolution.kind == QueryKind.SENTENCE) {
            return LookupResponse(
                resolution = resolution,
                notice = notice,
                annotationStatus = AnnotationStatus.UNAVAILABLE,
                tokenized = n.startapp.services.context.Tokenizer.tokenize(resolution.raw.trim())
            )
        }

        if (lemma.isNullOrBlank()) {
            return LookupResponse(
                resolution = resolution,
                notice = notice,
                annotationStatus = AnnotationStatus.UNAVAILABLE
            )
        }

        // Russian never goes to the dictionary as a headword — it goes to reverse translation,
        // which answers with English options the user can choose between.
        if (resolution.language == "ru") {
            return LookupResponse(
                resolution = resolution,
                notice = notice,
                annotationStatus = AnnotationStatus.UNAVAILABLE,
                ruEn = ruEnTranslationService?.translate(lemma)
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
                notice = notice,
                entry = cached,
                annotationStatus = AnnotationStatus.READY
            )
        }

        repository.find(cacheKey)?.let { stored ->
            hot.put(cacheKey, stored.entry)
            return LookupResponse(
                resolution = resolution,
                notice = notice,
                entry = stored.entry,
                annotationStatus = AnnotationStatus.READY,
                raw = stored.raw
            )
        }

        // Cold path. Fetch fast (API sources only) so the user has something to look at even if
        // annotation overruns the grace period.
        val isPhrase = resolution.kind == QueryKind.PHRASE
        val aggregate = try {
            aggregationService.aggregateDetailed(lemma, skipScrapers = true, isPhrase = isPhrase)
        } catch (e: NotFoundException) {
            // Idioms and newer slang are routinely missing from every source. A written-from-
            // scratch article, clearly labelled as such, beats "not found" for a phrase the user
            // demonstrably encountered somewhere.
            if (!isPhrase) throw e
            return ungroundedLookup(resolution, notice, lemma, cacheKey)
        }

        degraded.getIfPresent(cacheKey)?.let { failed ->
            return LookupResponse(
                resolution = resolution,
                notice = notice,
                entry = failed.entry,
                annotationStatus = AnnotationStatus.DEGRADED,
                annotationNote = failed.reason,
                raw = aggregate.response
            )
        }

        val job = inFlight.computeIfAbsent(cacheKey) {
            scope.async {
                try {
                    // Annotate from the FULL aggregate, not the quick one the response was built
                    // from. The quick path skips the scrapers, and they are the only source of
                    // IPA and of the per-part-of-speech pronunciation split that distinguishes
                    // lead /liːd/ from lead /lɛd/. Since this entry is then stored without a TTL,
                    // annotating the quick aggregate would bake a pronunciation-less article in
                    // permanently. The user is not waiting on this — they already have the raw
                    // response — so the extra seconds cost nothing.
                    val full = runCatching {
                        aggregationService.aggregateDetailed(lemma, skipScrapers = false, isPhrase = isPhrase)
                    }.getOrElse {
                        logger.warn("Full aggregate failed for '{}', annotating quick data: {}", lemma, it.message)
                        aggregate
                    }

                    val result = withTimeoutOrNull(ANNOTATION_DEADLINE_MS) {
                        annotationService.annotate(lemma, resolution.surface, kind, full)
                    } ?: LexicalAnnotationService.AnnotationResult(
                        entry = LexicalEntryFallback.fromRaw(
                            full.response, lemma, resolution.surface, kind,
                            annotationService.buildSources(full.sourceDefinitions)
                        ),
                        reason = "llm_timeout",
                        detail = "annotation exceeded ${ANNOTATION_DEADLINE_MS}ms"
                    )

                    val outcome = AnnotationOutcome(result.entry, result.reason, result.detail)
                    if (result.entry.degraded) {
                        logger.warn("Annotation degraded for '{}': {} — {}", lemma, result.reason, result.detail)
                        degraded.put(cacheKey, outcome)
                    } else {
                        // Store the full aggregate too: it is what the sources view renders on a
                        // warm hit, and it is richer than the quick one the response carried.
                        repository.save(
                            cacheKey = cacheKey,
                            entry = result.entry,
                            raw = full.response,
                            sourceFingerprint = LexicalEntryRepository.fingerprint(
                                full.sourceDefinitions.map { it.definition }
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
                notice = notice,
                annotationStatus = AnnotationStatus.PENDING,
                retryAfterMs = RETRY_AFTER_MS,
                raw = aggregate.response
            )
            outcome.entry.degraded -> LookupResponse(
                resolution = resolution,
                notice = notice,
                entry = outcome.entry,
                annotationStatus = AnnotationStatus.DEGRADED,
                annotationNote = outcome.reason,
                raw = aggregate.response
            )
            else -> LookupResponse(
                resolution = resolution,
                notice = notice,
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

    /** Phrase path when the dictionary sources have nothing: write the article, label it. */
    private suspend fun ungroundedLookup(
        resolution: ResolvedQuery,
        notice: LookupNotice?,
        lemma: String,
        cacheKey: String
    ): LookupResponse {
        val result = withTimeoutOrNull(ANNOTATION_DEADLINE_MS) {
            annotationService.annotateUngrounded(lemma, resolution.surface, LexicalKind.IDIOM)
        }

        if (result == null || result.entry.degraded || result.entry.posGroups.isEmpty()) {
            // The model declined or failed. Better to say nothing than to invent an idiom.
            return LookupResponse(
                resolution = resolution,
                notice = notice,
                annotationStatus = AnnotationStatus.UNAVAILABLE,
                annotationNote = result?.reason
            )
        }

        repository.save(
            cacheKey = cacheKey,
            entry = result.entry,
            raw = n.startapp.models.dictionary.WordDetailResponse(word = lemma, definitions = emptyList()),
            sourceFingerprint = LexicalEntryRepository.fingerprint(emptyList())
        )
        hot.put(cacheKey, result.entry)

        return LookupResponse(
            resolution = resolution,
            notice = notice,
            entry = result.entry,
            annotationStatus = AnnotationStatus.READY
        )
    }

    private fun kindFor(resolution: ResolvedQuery): LexicalKind = when (resolution.kind) {
        QueryKind.PHRASE -> LexicalKind.PHRASE
        else -> LexicalKind.WORD
    }

    /**
     * Explains a silent substitution to the user.
     *
     * A typo used to produce an error screen; it now produces the corrected article plus a line
     * saying what happened, so the correction is visible and reversible rather than a surprise.
     */
    private fun noticeFor(resolution: ResolvedQuery): LookupNotice? {
        val lemma = resolution.lemma ?: return null
        return when {
            resolution.correctionApplied && resolution.resolvedBy == "layout" -> LookupNotice(
                type = "layout_corrected",
                textRu = "Похоже, была не та раскладка. Показано для «$lemma».",
                originalQuery = resolution.correctedFrom ?: resolution.normalized
            )

            resolution.kind == QueryKind.MISSPELLING -> LookupNotice(
                type = "spelling_corrected",
                textRu = "Показано для «$lemma». Вы искали «${resolution.correctedFrom ?: resolution.normalized}»?",
                originalQuery = resolution.correctedFrom ?: resolution.normalized
            )

            resolution.kind == QueryKind.INFLECTION && !lemma.equals(resolution.normalized, true) ->
                LookupNotice(
                    type = "lemma_resolved",
                    textRu = "«${resolution.normalized}» — форма слова «$lemma».",
                    originalQuery = resolution.normalized
                )

            else -> null
        }
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
