package n.startapp

import n.startapp.repositories.LexicalEntryRepository
import n.startapp.repositories.LlmCacheRepository
import n.startapp.services.AiService
import n.startapp.services.DictionaryService
import n.startapp.services.LookupService
import n.startapp.services.PushService
import n.startapp.services.SuggestService
import n.startapp.services.ai.LlmClient
import n.startapp.services.ai.OpenAiCompatibleLlmClient
import n.startapp.services.dictionary.DictionaryAggregationService
import n.startapp.services.exercise.ExerciseService
import n.startapp.services.context.ContextAnalysisService
import n.startapp.services.lexical.LexicalAnnotationService
import n.startapp.services.query.DataMuseWordOracle
import n.startapp.services.query.QueryResolver
import n.startapp.services.query.RuEnTranslationService
import n.startapp.services.warmup.WarmupService
import n.startapp.utils.EnvConfig
import org.slf4j.LoggerFactory

/**
 * Single owner of the application's long-lived services.
 *
 * Previously every service was constructed inline in `configureRouting()` and each built its own
 * Ktor engine that nothing ever closed — including two independent `AiService` instances, so the
 * dictionary and the AI endpoints talked to the provider through separate connection pools.
 * Construct one registry per application and close it on shutdown.
 */
class ServiceRegistry {
    private val logger = LoggerFactory.getLogger(ServiceRegistry::class.java)

    val llmClient: LlmClient = OpenAiCompatibleLlmClient()
    val llmCacheRepository = LlmCacheRepository()

    val lexicalEntryRepository = LexicalEntryRepository()

    val ruEnTranslationService = RuEnTranslationService(llmClient, llmCacheRepository)

    val aiService = AiService(llmClient)

    // Holds no connection of its own: the push client is built per send, because the VAPID
    // keys are read through EnvConfig and the admin panel can change them without a restart.
    val pushService = PushService()

    // Practice sessions read the corpus the warm-up already built; the model is asked for one
    // exercise kind only, and that answer is cached forever.
    val exerciseService = ExerciseService(
        llm = llmClient,
        entries = lexicalEntryRepository,
        cache = llmCacheRepository
    )
    val dictionaryService = DictionaryService(aiService, lexicalEntryRepository)
    val suggestService = SuggestService(ruEnTranslationService)

    private val aggregationService = DictionaryAggregationService()
    private val annotationService = LexicalAnnotationService(llmClient)

    private val oracleHttpClient = DataMuseWordOracle.defaultClient()
    val queryResolver = QueryResolver(
        oracle = DataMuseWordOracle(oracleHttpClient),
        // A form already present in the annotated corpus needs no oracle and no model call.
        knownForm = { form -> lexicalEntryRepository.findLemmaByForm(form) },
        llm = llmClient,
        llmCache = llmCacheRepository
    )

    val contextAnalysisService = ContextAnalysisService(llmClient, lexicalEntryRepository, llmCacheRepository)

    val lookupService = LookupService(
        aggregationService = aggregationService,
        annotationService = annotationService,
        repository = lexicalEntryRepository,
        queryResolver = queryResolver,
        ruEnTranslationService = ruEnTranslationService,
        llmEndpoint = (llmClient as? OpenAiCompatibleLlmClient)?.primary?.endpoint ?: "?"
    )

    /**
     * Corrects pronunciation across the corpus without the model — see the class doc for why a
     * lookup-time repair alone leaves `/word/{lemma}` behind. Never auto-starts: it shares the
     * scraper budget with live lookups, and that is an operator's decision.
     */
    val pronunciationSweepService =
        n.startapp.services.lexical.PronunciationSweepService(lookupService, lexicalEntryRepository)

    private val warmupOracle = DataMuseWordOracle(oracleHttpClient)
    val warmupQueueRepository = n.startapp.repositories.WarmupQueueRepository()
    val warmupService =
        WarmupService(lookupService, warmupOracle, warmupQueueRepository, lexicalEntryRepository)

    init {
        if (EnvConfig.warmupEnabled) {
            // Resumes on its own after a restart: anything already annotated is skipped.
            val started = warmupService.start(EnvConfig.warmupLimit)
            logger.info("Warm-up enabled by configuration, started={}", started)
        }
    }

    fun close() {
        logger.info("Shutting down services")
        runCatching { pronunciationSweepService.close() }
            .onFailure { logger.warn("pronunciationSweepService.close(): ${it.message}") }
        runCatching { warmupService.close() }.onFailure { logger.warn("warmupService.close(): ${it.message}") }
        runCatching { lookupService.close() }.onFailure { logger.warn("lookupService.close(): ${it.message}") }
        runCatching { aggregationService.close() }.onFailure { logger.warn("aggregationService.close(): ${it.message}") }
        runCatching { dictionaryService.close() }.onFailure { logger.warn("dictionaryService.close(): ${it.message}") }
        runCatching { suggestService.close() }.onFailure { logger.warn("suggestService.close(): ${it.message}") }
        runCatching { oracleHttpClient.close() }.onFailure { logger.warn("oracleHttpClient.close(): ${it.message}") }
        runCatching { llmClient.close() }.onFailure { logger.warn("llmClient.close(): ${it.message}") }
    }
}
