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
import n.startapp.services.lexical.LexicalAnnotationService
import n.startapp.services.query.QueryResolver
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

    val aiService = AiService(llmClient)
    val dictionaryService = DictionaryService(aiService)
    val suggestService = SuggestService()

    private val aggregationService = DictionaryAggregationService()
    private val annotationService = LexicalAnnotationService(llmClient)
    val lexicalEntryRepository = LexicalEntryRepository()
    val queryResolver = QueryResolver()

    val lookupService = LookupService(
        aggregationService = aggregationService,
        annotationService = annotationService,
        repository = lexicalEntryRepository,
        queryResolver = queryResolver
    )

    fun close() {
        logger.info("Shutting down services")
        runCatching { lookupService.close() }.onFailure { logger.warn("lookupService.close(): ${it.message}") }
        runCatching { aggregationService.close() }.onFailure { logger.warn("aggregationService.close(): ${it.message}") }
        runCatching { dictionaryService.close() }.onFailure { logger.warn("dictionaryService.close(): ${it.message}") }
        runCatching { suggestService.close() }.onFailure { logger.warn("suggestService.close(): ${it.message}") }
        runCatching { llmClient.close() }.onFailure { logger.warn("llmClient.close(): ${it.message}") }
    }
}
