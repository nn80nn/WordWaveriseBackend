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
import n.startapp.repositories.StoredEntry
import n.startapp.services.dictionary.AggregatedWord
import n.startapp.services.dictionary.DictionaryAggregationService
import n.startapp.services.dictionary.SpellingGloss
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
    private val ruEnTranslationService: n.startapp.services.query.RuEnTranslationService? = null,
    /** Resolved chat-completions URL, reported by diagnose so a misconfigured base URL is visible. */
    private val llmEndpoint: String = "?"
) {
    private val logger = LoggerFactory.getLogger(LookupService::class.java)

    /** How long a request waits for a fresh annotation before falling back to PENDING. */
    private val ANNOTATION_GRACE_MS = 1_200L

    /**
     * How long the client should wait before asking again.
     *
     * An article takes one to three minutes, so a 2.5s hint invited a poll storm for something
     * that could not possibly be ready yet.
     */
    private val RETRY_AFTER_MS = 5_000

    /**
     * Hard ceiling on one annotation, sized above the client's own retry budget so it catches a
     * wedged job rather than a merely slow one.
     *
     * A common word yields two dozen source fragments across three dictionaries, and the article
     * written from them takes minutes — cutting that off produced a degraded entry for exactly
     * the words that most deserve a good one. Nobody is waiting on this: the raw response has
     * already gone out.
     */
    private val ANNOTATION_DEADLINE_MS = 420_000L

    /** Failures the provider may recover from on its own — mainly rate limiting. */
    private val TRANSIENT_REASONS = setOf("llm_call_failed", "llm_timeout")
    private val TRANSIENT_RETRY_MS = 45_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<AnnotationOutcome>>()

    /**
     * Short-lived so a hot word skips the DB round-trip; the durable copy lives in Postgres.
     *
     * Holds the raw aggregate alongside the article, not just the article: they are two halves
     * of one answer, and caching only the entry made the sources view empty at the exact moment
     * the article arrived.
     */
    private val hot = Caffeine.newBuilder()
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .maximumSize(2_000)
        .build<String, StoredEntry>()

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

    /**
     * The quick aggregate served while annotation runs.
     *
     * Polling is the normal case now — an article takes minutes — and without this every poll
     * re-ran four upstream API calls for data that had not changed. Short-lived because it is a
     * stand-in, not the real answer.
     */
    private val quickAggregates = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .maximumSize(500)
        .build<String, AggregatedWord>()

    private data class AnnotationOutcome(
        val entry: LexicalEntry,
        /**
         * The aggregate the article was actually built from — wider than the quick one the
         * cold-path response carried, because the job runs the scrapers. Kept here so the
         * response that finally reports READY ships the sources behind the article, rather
         * than the stand-in the request happened to start with.
         */
        val raw: n.startapp.models.dictionary.WordDetailResponse? = null,
        val reason: String? = null,
        val detail: String? = null
    )

    /**
     * Resolves the query, then looks it up — retrying once on the resolver's fallback.
     *
     * The retry is what lets the resolver stop overriding real words. It hands back what the
     * user typed plus, where a speller had an opinion, the word it would have substituted; that
     * substitution now happens only here, after every dictionary has said it has no entry for
     * the literal query. "missive" has an entry and is served as itself; "teh" has none, so the
     * second pass answers with "the". Frequency could never draw that line — the populations
     * overlap — but having an article is exactly the evidence needed.
     */
    /**
     * @param exact look the query up as typed, with no correction, lemmatisation or fallback.
     *   This is what «Искать точно» sends, so it must not second-guess the user in any way —
     *   including the fallback retry below, which is a substitution made after the fact.
     */
    suspend fun lookup(query: String, exact: Boolean = false): LookupResponse {
        if (query.isBlank()) throw BadRequestException("Query parameter 'query' cannot be empty")

        if (exact) return lookupResolved(queryResolver.resolveExact(query))

        val resolution = queryResolver.resolve(query)
        return try {
            lookupResolved(resolution)
        } catch (e: NotFoundException) {
            val fallback = resolution.fallback ?: throw e
            logger.info(
                "No dictionary entry for '{}'; retrying as '{}'", resolution.normalized, fallback.form
            )
            lookupResolved(
                resolution.copy(
                    kind = fallback.kind,
                    lemma = fallback.form,
                    correctionApplied = fallback.kind == QueryKind.MISSPELLING,
                    correctedFrom = resolution.normalized
                        .takeIf { fallback.kind == QueryKind.MISSPELLING },
                    fallback = null
                )
            )
        }
    }

    private suspend fun lookupResolved(resolution: ResolvedQuery): LookupResponse {
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
                entry = cached.entry,
                annotationStatus = AnnotationStatus.READY,
                raw = cached.raw
            )
        }

        repository.find(cacheKey)?.let { stored ->
            hot.put(cacheKey, stored)
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
            quickAggregates.getIfPresent(cacheKey)
                ?: aggregationService.aggregateDetailed(lemma, skipScrapers = true, isPhrase = isPhrase)
                    .also { quickAggregates.put(cacheKey, it) }
        } catch (e: NotFoundException) {
            // Idioms and newer slang are routinely missing from every source. A written-from-
            // scratch article, clearly labelled as such, beats "not found" for a phrase the user
            // demonstrably encountered somewhere.
            if (!isPhrase) throw e
            return ungroundedLookup(resolution, notice, lemma, cacheKey)
        }

        // The form has an entry, but the entry may say it is not a word. Wiktionary documents
        // misspellings — "occured" comes back as "Misspelling of occurred" — so having sources
        // is not the same as being a headword, and this is the one reading of the evidence that
        // is not a guess: the dictionary names the target itself. Checked before annotation so a
        // typo never costs a model call.
        // …unless the user has already said they meant this string. «Искать точно» exists to
        // overrule exactly this kind of correction, and the gloss is still a correction even
        // though the dictionary rather than a speller proposed it.
        SpellingGloss.redirectTarget(aggregate.response.definitions.map { it.definition })
            ?.takeIf { !it.equals(lemma, true) && resolution.correctedFrom == null && !resolution.isExact }
            ?.let { target ->
                logger.info("'{}' is glossed only as a pointer to '{}'", lemma, target)
                return lookupResolved(
                    resolution.copy(
                        kind = QueryKind.MISSPELLING,
                        lemma = target,
                        correctionApplied = true,
                        correctedFrom = resolution.normalized,
                        fallback = null
                    )
                )
            }

        // The article this word had before the current schema and prompt existed.
        //
        // ⚠️ Read by lemma, not by cache key — that is the point. A version bump makes every
        // stored article a miss, and without this the reader of a word the corpus has served
        // for months suddenly gets raw dictionary fragments back and waits three minutes for
        // an article to be rewritten. What they had was not wrong, only out of date, and an
        // out-of-date article beats no article by a distance that is not close.
        //
        // Deliberately NOT put in the hot cache under the new key: it would satisfy the very
        // lookup that is supposed to replace it, and the rewrite would never happen.
        val superseded = runCatching { repository.findLatestByLemma(lemma) }
            .onFailure { logger.warn("Could not read the previous article for '{}': {}", lemma, it.message) }
            .getOrNull()

        degraded.getIfPresent(cacheKey)?.let { failed ->
            return LookupResponse(
                resolution = resolution,
                notice = notice,
                // A real article the model wrote beats one derived mechanically from raw data,
                // even an older one. `degraded` still describes what just happened, so the
                // clients keep retrying on the codes that are worth retrying.
                entry = superseded ?: failed.entry,
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

                    val outcome = AnnotationOutcome(result.entry, full.response, result.reason, result.detail)
                    if (result.entry.degraded) {
                        logger.warn("Annotation degraded for '{}': {} — {}", lemma, result.reason, result.detail)
                        degraded.put(cacheKey, outcome)
                    } else if (resolution.isExact) {
                        // An exact lookup is one person overruling the resolver for one query.
                        // Persisting its article would turn that override into everyone's
                        // default: rung 3 resolves a form by looking for an entry under that
                        // headword, so a stored article for "recieve" switches the correction
                        // off for every later user — permanently, these rows having no TTL.
                        // In memory for the polls that follow, and no further.
                        hot.put(cacheKey, StoredEntry(result.entry, full.response, sourceFingerprint = ""))
                        logger.info("Exact lookup for '{}' answered without writing to the corpus", lemma)
                    } else {
                        // Store the full aggregate too: it is what the sources view renders on a
                        // warm hit, and it is richer than the quick one the response carried.
                        repository.save(
                            cacheKey = cacheKey,
                            entry = result.entry,
                            raw = full.response,
                            sourceFingerprint = LexicalEntryRepository.fingerprint(
                                full.sourceDefinitions.map { it.definition }
                            ),
                            usage = result.usage
                        )
                        hot.put(
                            cacheKey,
                            StoredEntry(result.entry, full.response, sourceFingerprint = "")
                        )
                    }
                    outcome
                } finally {
                    inFlight.remove(cacheKey)
                }
            }
        }

        val outcome = withTimeoutOrNull(ANNOTATION_GRACE_MS) { job.await() }

        return when {
            // Still being written. PENDING keeps the clients polling, so the fresh article
            // replaces this one on screen the moment it lands.
            outcome == null -> LookupResponse(
                resolution = resolution,
                notice = notice,
                entry = superseded,
                annotationStatus = AnnotationStatus.PENDING,
                annotationNote = if (superseded != null) "superseded_article" else null,
                retryAfterMs = RETRY_AFTER_MS,
                raw = aggregate.response
            )
            // Same reasoning as the cached-degraded branch above: the previous article was
            // written by the model and validated, this one was derived from raw data because
            // the model call failed. Older beats mechanical.
            outcome.entry.degraded -> LookupResponse(
                resolution = resolution,
                notice = notice,
                entry = superseded ?: outcome.entry,
                annotationStatus = AnnotationStatus.DEGRADED,
                annotationNote = outcome.reason,
                raw = outcome.raw ?: aggregate.response
            )
            else -> LookupResponse(
                resolution = resolution,
                notice = notice,
                entry = outcome.entry,
                annotationStatus = AnnotationStatus.READY,
                raw = outcome.raw ?: aggregate.response
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
            "endpoint" to llmEndpoint,
            "structuredMode" to n.startapp.services.ai.AiCompat.structuredMode("primary").name,
            "tokenParam" to n.startapp.services.ai.AiCompat.tokenParam("primary"),
            "sourceFragments" to aggregate.sourceDefinitions.size.toString(),
            "elapsedMs" to (System.currentTimeMillis() - started).toString(),
            "degraded" to result.entry.degraded.toString(),
            "posGroups" to result.entry.posGroups.size.toString(),
            "reason" to (result.reason ?: "-"),
            "detail" to (result.detail ?: "-").take(600)
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
            sourceFingerprint = LexicalEntryRepository.fingerprint(emptyList()),
            usage = result.usage
        )
        hot.put(cacheKey, StoredEntry(result.entry, null, sourceFingerprint = ""))

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

            // The article below is for what was typed; the neighbour is merely offered. No
            // `originalQuery`, because there is nothing to undo — the clients turn that field
            // into an "Искать точно" button, and here the exact search is already what is shown.
            //
            // Only a speller's suggestion is worth showing. The morphology rung's fallback is a
            // mechanical stem guess that exists to be tried, not read: it offered "wav" for
            // "waver", which is a plausible thing to look up next and an absurd thing to ask
            // someone whether they meant.
            resolution.fallback?.kind == QueryKind.MISSPELLING -> LookupNotice(
                type = "did_you_mean",
                textRu = "Возможно, вы искали «${resolution.fallback.form}»?"
            )

            else -> null
        }
    }

    /** What happened to one warm-up word. */
    enum class WarmOutcome { ALREADY_PRESENT, WRITTEN, NOT_FOUND, FAILED }

    /**
     * Builds and stores the article for one word ahead of anyone asking for it.
     *
     * Deliberately the slow path in full — scrapers included — because the whole point is that
     * the result is the same artefact a real lookup would have produced, only paid for in
     * advance. Runs on the reserve pool so the bulk job cannot spend the user-facing quota.
     */
    suspend fun warm(word: String): WarmOutcome {
        val lemma = word.trim().lowercase()
        if (lemma.isBlank()) return WarmOutcome.NOT_FOUND

        val cacheKey = LexicalEntryRepository.cacheKey(
            lemma = lemma,
            kind = LexicalKind.WORD.name,
            promptVersion = LexicalPromptBuilder.PROMPT_VERSION,
            model = EnvConfig.aiModel
        )
        if (repository.find(cacheKey) != null) return WarmOutcome.ALREADY_PRESENT

        val aggregate = try {
            aggregationService.aggregateDetailed(lemma, skipScrapers = false)
        } catch (e: NotFoundException) {
            logger.info("Warm-up: '{}' is in no dictionary, skipping", lemma)
            return WarmOutcome.NOT_FOUND
        }

        val result = withTimeoutOrNull(ANNOTATION_DEADLINE_MS) {
            annotationService.annotate(
                lemma, lemma, LexicalKind.WORD, aggregate,
                route = n.startapp.services.ai.LlmRoute.BULK
            )
        } ?: return WarmOutcome.FAILED

        if (result.entry.degraded) {
            logger.warn("Warm-up: '{}' degraded ({})", lemma, result.reason)
            return WarmOutcome.FAILED
        }

        repository.save(
            cacheKey = cacheKey,
            entry = result.entry,
            raw = aggregate.response,
            sourceFingerprint = LexicalEntryRepository.fingerprint(
                aggregate.sourceDefinitions.map { it.definition }
            ),
            usage = result.usage
        )
        return WarmOutcome.WRITTEN
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
