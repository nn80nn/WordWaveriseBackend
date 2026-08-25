package n.startapp.services.lexical

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import n.startapp.models.dictionary.DetailedDefinition
import n.startapp.models.dictionary.WordDetailResponse
import n.startapp.models.lexical.DraftEntry
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.LexicalKind
import n.startapp.models.lexical.SourceRef
import n.startapp.services.ai.LlmClient
import n.startapp.services.ai.LlmJson
import n.startapp.services.ai.LlmRequest
import n.startapp.services.ai.LlmRoute
import n.startapp.services.ai.LlmUsage
import n.startapp.services.ai.ResponseFormat
import n.startapp.services.dictionary.AggregatedWord
import n.startapp.utils.EnvConfig
import org.slf4j.LoggerFactory

/**
 * Converts the noisy multi-source aggregate into one structured, grounded [LexicalEntry].
 *
 * This is the layer the whole overhaul rests on: good dictionaries ship pre-annotated data,
 * we scrape fragments, and this is where the annotation gets reconstructed. Everything else
 * (per-sense Russian, part of speech, register, CEFR) is a consequence of it rather than a
 * separate feature.
 */
class LexicalAnnotationService(private val llm: LlmClient) {
    private val logger = LoggerFactory.getLogger(LexicalAnnotationService::class.java)
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Fan-out ceiling: enough for any real headword, low enough not to trip a rate limit. */
    private val MAX_PARALLEL_POS = 4

    /** Builds the provenance list the model's `sourceRefs` point into. */
    fun buildSources(definitions: List<DetailedDefinition>): List<SourceRef> =
        LexicalPromptBuilder.selectFragments(
            definitions.mapIndexed { i, def ->
                SourceRef(
                    index = i + 1,
                    source = def.source?.uppercase() ?: "UNKNOWN",
                    partOfSpeech = def.partOfSpeech.takeIf { it.isNotBlank() },
                    definition = def.definition,
                    example = def.example
                )
            }
        )

    /**
     * Outcome of an annotation attempt.
     *
     * [reason] and [detail] are only set when the article had to be degraded. They exist because
     * a failing annotation is otherwise invisible from outside the process: the user just sees a
     * dictionary with no Russian, and there is nothing in the response to say why.
     */
    data class AnnotationResult(
        val entry: LexicalEntry,
        /**
         * What producing this article cost. Recorded on the row so the corpus can be asked what
         * it spent — the columns existed from the start and were written as zeros, because
         * nothing carried the number this far.
         */
        val usage: LlmUsage = LlmUsage(),
        /** Stable machine-readable code: llm_call_failed | parse_failed | validation_failed. */
        val reason: String? = null,
        /** Human-readable specifics for logs and the admin diagnose endpoint. */
        val detail: String? = null
    )

    /**
     * Annotates [aggregate]. Never throws for model-side problems: a failure yields a
     * `degraded` entry built from the raw data so the user still gets the dictionary content.
     *
     * Time here is dominated by how much the model has to write, and the parts of speech are
     * independent of one another — so when there is more than one, each is written by its own
     * concurrent call and the slowest term becomes a max instead of a sum. It also means a
     * single failing section costs that section rather than the whole article.
     */
    suspend fun annotate(
        lemma: String,
        queryForm: String,
        kind: LexicalKind,
        aggregate: AggregatedWord,
        /** BULK keeps a warm-up run on the reserve pool, away from the user-facing quota. */
        route: LlmRoute = LlmRoute.LIVE
    ): AnnotationResult {
        val sources = buildSources(aggregate.sourceDefinitions)
        val partsOfSpeech = LexicalPromptBuilder.partsOfSpeech(sources)

        return if (PosGroupMerge.shouldSplitByPartOfSpeech(lemma, kind, partsOfSpeech)) {
            annotateByPartOfSpeech(lemma, queryForm, kind, aggregate, sources, partsOfSpeech, route)
        } else {
            annotateWhole(lemma, queryForm, kind, aggregate, sources, onlyPos = null, route = route)
        }
    }

    /** One section per part of speech, written concurrently and stitched back together. */
    private suspend fun annotateByPartOfSpeech(
        lemma: String,
        queryForm: String,
        kind: LexicalKind,
        aggregate: AggregatedWord,
        sources: List<SourceRef>,
        partsOfSpeech: List<String>,
        route: LlmRoute
    ): AnnotationResult = coroutineScope {
        // Capped so a word with many parts of speech cannot fan out into a rate limit.
        val targets = partsOfSpeech.take(MAX_PARALLEL_POS)

        val results = targets.mapIndexed { index, pos ->
            async {
                annotateWhole(
                    lemma, queryForm, kind, aggregate, sources,
                    onlyPos = pos,
                    route = route,
                    // Etymology and usage notes belong to the word, not to a section: asking
                    // every call for them would duplicate the answer and the tokens.
                    includeEntryLevel = index == 0
                )
            }
        }.awaitAll()

        val usable = results.filter { !it.entry.degraded && it.entry.posGroups.isNotEmpty() }
        if (usable.isEmpty()) {
            logger.error("All {} per-POS annotations failed for '{}'", targets.size, lemma)
            return@coroutineScope results.firstOrNull()
                ?: AnnotationResult(
                    entry = LexicalEntryFallback.fromRaw(aggregate.response, lemma, queryForm, kind, sources),
                    reason = "validation_failed",
                    detail = "no part of speech produced an article"
                )
        }

        if (usable.size < targets.size) {
            logger.warn(
                "Annotation for '{}': {} of {} parts of speech survived",
                lemma, usable.size, targets.size
            )
        }

        val base = usable.first().entry
        AnnotationResult(
            // Tokens add up across sections, but the calls run concurrently, so the time the
            // article actually took is the slowest of them rather than their sum.
            usage = LlmUsage(
                promptTokens = usable.sumOf { it.usage.promptTokens },
                completionTokens = usable.sumOf { it.usage.completionTokens },
                latencyMs = usable.maxOf { it.usage.latencyMs }
            ),
            entry = base.copy(
                // Not a flatMap: the sections each saw every fragment, so two of them can
                // describe the same part of speech — and did, which is how `grow up` came back
                // with its article printed twice under two `phrasal verb` groups carrying the
                // same sense ids. See [PosGroupMerge].
                posGroups = PosGroupMerge.merge(usable.map { it.entry.posGroups }),
                // Whichever section answered them; the first is the one that was asked.
                etymology = usable.firstNotNullOfOrNull { it.entry.etymology },
                usageNotes = usable.firstOrNull { it.entry.usageNotes.isNotEmpty() }?.entry?.usageNotes.orEmpty(),
                frequencyBand = usable.firstNotNullOfOrNull { it.entry.frequencyBand }
            )
        )
    }

    private suspend fun annotateWhole(
        lemma: String,
        queryForm: String,
        kind: LexicalKind,
        aggregate: AggregatedWord,
        sources: List<SourceRef>,
        onlyPos: String?,
        route: LlmRoute = LlmRoute.LIVE,
        includeEntryLevel: Boolean = true
    ): AnnotationResult {
        val raw = aggregate.response
        val grounded = sources.isNotEmpty()

        val system = LexicalPromptBuilder.system(grounded)
        val baseUser = LexicalPromptBuilder.user(
            lemma = lemma,
            queryForm = queryForm,
            kind = kind,
            fragments = sources,
            synonyms = raw.synonyms,
            antonyms = raw.antonyms,
            onlyPos = onlyPos,
            includeEntryLevel = includeEntryLevel
        )

        var user = baseUser
        var lastIssues = emptyList<String>()
        var lastCode = "validation_failed"

        repeat(2) { attempt ->
            when (val outcome = attemptAnnotation(system, user, onlyPos, route, sources, lemma, queryForm, kind, aggregate, grounded)) {
                is AttemptResult.Success -> return AnnotationResult(outcome.entry, outcome.usage)
                is AttemptResult.Retry -> {
                    logger.warn(
                        "Annotation attempt {} for '{}' rejected: {}",
                        attempt + 1, lemma, outcome.issues.joinToString("; ").take(400)
                    )
                    lastIssues = outcome.issues
                    lastCode = outcome.code
                    user = baseUser + LexicalPromptBuilder.repairSuffix(outcome.issues)
                }
                is AttemptResult.Abort -> {
                    logger.error("Annotation for '{}' aborted: {}", lemma, outcome.reason)
                    return AnnotationResult(
                        entry = LexicalEntryFallback.fromRaw(raw, lemma, queryForm, kind, sources),
                        reason = "llm_call_failed",
                        detail = outcome.reason
                    )
                }
            }
        }

        logger.error(
            "Annotation for '{}' failed twice ({}) — serving degraded entry: {}",
            lemma, lastCode, lastIssues.joinToString("; ").take(400)
        )
        return AnnotationResult(
            entry = LexicalEntryFallback.fromRaw(raw, lemma, queryForm, kind, sources),
            reason = lastCode,
            detail = lastIssues.joinToString("; ").take(600)
        )
    }

    /**
     * Writes an article for a headword no source carries — mostly newer idioms and slang, which
     * scrapers reliably miss. Marked `aiGenerated` so both clients can say so plainly; an
     * unlabelled invented entry in a dictionary is worse than no entry.
     */
    suspend fun annotateUngrounded(
        lemma: String,
        queryForm: String,
        kind: LexicalKind,
        route: LlmRoute = LlmRoute.LIVE
    ): AnnotationResult {
        val empty = WordDetailResponse(word = lemma, definitions = emptyList())
        val aggregate = AggregatedWord(empty, emptyList(), emptyMap())
        val result = annotate(lemma, queryForm, kind, aggregate, route)
        return result.copy(entry = result.entry.copy(aiGenerated = true))
    }

    private sealed interface AttemptResult {
        data class Success(val entry: LexicalEntry, val usage: LlmUsage) : AttemptResult
        data class Retry(val issues: List<String>, val code: String) : AttemptResult
        data class Abort(val reason: String) : AttemptResult
    }

    private suspend fun attemptAnnotation(
        system: String,
        user: String,
        onlyPos: String?,
        route: LlmRoute,
        sources: List<SourceRef>,
        lemma: String,
        queryForm: String,
        kind: LexicalKind,
        aggregate: AggregatedWord,
        grounded: Boolean
    ): AttemptResult {
        val result = try {
            llm.complete(
                LlmRequest(
                    task = "annotate",
                    system = system,
                    user = user,
                    // Sized for a whole article; a single part-of-speech section needs far
                    // less, and the client grants one larger budget if the ceiling is hit
                    // anyway — a truncated JSON can never parse, so retrying it is pointless.
                    maxTokens = if (onlyPos != null) 2500 else 4500,
                    temperature = 0.2,
                    responseFormat = ResponseFormat.JsonSchema(
                        name = LEXICAL_ENTRY_SCHEMA_NAME,
                        schema = LEXICAL_ENTRY_JSON_SCHEMA
                    ),
                    // Annotation runs in the background, so it can afford to wait out a
                    // rate limit rather than degrade the article.
                    maxRetries = 3,
                    route = route
                )
            )
        } catch (e: Exception) {
            // Provider unreachable or misconfigured: retrying the prompt will not help.
            return AttemptResult.Abort("LLM call failed: ${e.message}")
        }

        val payload = LlmJson.extract(result.content)
        val draft = try {
            json.decodeFromString<DraftEntry>(payload)
        } catch (e: Exception) {
            return AttemptResult.Retry(
                listOf("ответ не разобрался как JSON по схеме: ${e.message?.take(300)}"),
                "parse_failed"
            )
        }

        val validation = LexicalEntryValidator.validate(draft, sources, lemma, kind, payload)
        if (validation.fatal) {
            return AttemptResult.Retry(validation.issues, "validation_failed:${validation.fatalCode}")
        }

        if (validation.issues.isNotEmpty()) {
            logger.info("Annotation for '{}' repaired: {}", lemma, validation.issues.joinToString("; ").take(400))
        }

        val raw = aggregate.response
        // Assembled here rather than taken from the model: pronunciation, audio and provenance
        // come from the scrapers, and the model's output object has no field for them at all.
        val entry = LexicalEntry(
            lemma = lemma,
            queryForm = queryForm,
            kind = kind,
            pronunciations = raw.pronunciations,
            phonetic = raw.phonetic,
            audioUrl = raw.audioUrl,
            posGroups = validation.posGroups.map { group ->
                group.copy(
                    pronunciations = aggregate.perPosPronunciations[group.pos]
                        ?.takeIf { it.isNotEmpty() }
                        ?: raw.pronunciations
                )
            },
            etymology = draft.etymology?.trim()?.takeIf { it.isNotBlank() },
            usageNotes = draft.usageNotes.map { it.trim() }.filter { it.isNotBlank() },
            frequencyBand = draft.frequencyBand?.trim()?.takeIf { it.isNotBlank() },
            sources = sources,
            aiGenerated = !grounded,
            degraded = false,
            promptVersion = LexicalPromptBuilder.PROMPT_VERSION,
            model = EnvConfig.aiModel,
            generatedAt = System.currentTimeMillis()
        )
        return AttemptResult.Success(entry, result.usage)
    }
}
