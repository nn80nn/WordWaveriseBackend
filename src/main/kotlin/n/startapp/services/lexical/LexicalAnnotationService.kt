package n.startapp.services.lexical

import kotlinx.serialization.json.Json
import n.startapp.models.dictionary.DetailedDefinition
import n.startapp.models.lexical.DraftEntry
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.LexicalKind
import n.startapp.models.lexical.SourceRef
import n.startapp.services.ai.LlmClient
import n.startapp.services.ai.LlmJson
import n.startapp.services.ai.LlmRequest
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
     * Annotates [aggregate]. Never throws for model-side problems: a failure yields a
     * `degraded` entry built from the raw data so the user still gets the dictionary content.
     */
    suspend fun annotate(
        lemma: String,
        queryForm: String,
        kind: LexicalKind,
        aggregate: AggregatedWord
    ): LexicalEntry {
        val raw = aggregate.response
        val sources = buildSources(aggregate.sourceDefinitions)
        val grounded = sources.isNotEmpty()

        val system = LexicalPromptBuilder.system(grounded)
        val baseUser = LexicalPromptBuilder.user(
            lemma = lemma,
            queryForm = queryForm,
            kind = kind,
            fragments = sources,
            synonyms = raw.synonyms,
            antonyms = raw.antonyms
        )

        var user = baseUser
        repeat(2) { attempt ->
            val outcome = attemptAnnotation(system, user, sources, lemma, queryForm, kind, aggregate, grounded)
            when (outcome) {
                is AttemptResult.Success -> return outcome.entry
                is AttemptResult.Retry -> {
                    logger.warn(
                        "Annotation attempt {} for '{}' rejected: {}",
                        attempt + 1, lemma, outcome.issues.joinToString("; ").take(400)
                    )
                    user = baseUser + LexicalPromptBuilder.repairSuffix(outcome.issues)
                }
                is AttemptResult.Abort -> {
                    logger.error("Annotation for '{}' aborted: {}", lemma, outcome.reason)
                    return LexicalEntryFallback.fromRaw(raw, lemma, queryForm, kind, sources)
                }
            }
        }

        logger.error("Annotation for '{}' failed validation twice — serving degraded entry", lemma)
        return LexicalEntryFallback.fromRaw(raw, lemma, queryForm, kind, sources)
    }

    private sealed interface AttemptResult {
        data class Success(val entry: LexicalEntry) : AttemptResult
        data class Retry(val issues: List<String>) : AttemptResult
        data class Abort(val reason: String) : AttemptResult
    }

    private suspend fun attemptAnnotation(
        system: String,
        user: String,
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
                    maxTokens = 4000,
                    temperature = 0.2,
                    responseFormat = ResponseFormat.JsonSchema(
                        name = LEXICAL_ENTRY_SCHEMA_NAME,
                        schema = LEXICAL_ENTRY_JSON_SCHEMA
                    ),
                    maxRetries = 1
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
            return AttemptResult.Retry(listOf("ответ не разобрался как JSON по схеме: ${e.message}"))
        }

        val validation = LexicalEntryValidator.validate(draft, sources, lemma, kind, payload)
        if (validation.fatal) return AttemptResult.Retry(validation.issues)

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
        return AttemptResult.Success(entry)
    }
}
