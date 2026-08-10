package n.startapp

import n.startapp.repositories.LexicalEntryRepository
import n.startapp.repositories.LlmCacheRepository
import n.startapp.services.AiService
import n.startapp.services.DictionaryService
import n.startapp.services.LookupService
import n.startapp.services.SuggestService
import n.startapp.services.ai.LlmClient
import n.startapp.services.ai.OpenAiCompatibleLlmClient
import n.startapp.services.dictionary.DictionaryAggregationService
import n.startapp.services.context.ContextAnalysisService
import n.startapp.services.lexical.LexicalAnnotationService
import n.startapp.services.query.DataMuseWordOracle
import n.startapp.services.query.QueryResolver
import n.startapp.services.query.RuEnTranslationService
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
        ruEnTranslationService = ruEnTranslationService
    )

    fun close() {
        logger.info("Shutting down services")
        runCatching { lookupService.close() }.onFailure { logger.warn("lookupService.close(): ${it.message}") }
        runCatching { aggregationService.close() }.onFailure { logger.warn("aggregationService.close(): ${it.message}") }
        runCatching { dictionaryService.close() }.onFailure { logger.warn("dictionaryService.close(): ${it.message}") }
        runCatching { suggestService.close() }.onFailure { logger.warn("suggestService.close(): ${it.message}") }
        runCatching { oracleHttpClient.close() }.onFailure { logger.warn("oracleHttpClient.close(): ${it.message}") }
        runCatching { llmClient.close() }.onFailure { logger.warn("llmClient.close(): ${it.message}") }
    }
}
