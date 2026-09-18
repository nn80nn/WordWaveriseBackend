package n.startapp.services.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import n.startapp.models.reader.OfflineBundlePageDto
import n.startapp.models.reader.OfflineHintDto
import n.startapp.repositories.BookRepository
import n.startapp.repositories.LlmCacheRepository
import n.startapp.services.ai.LlmRoute
import n.startapp.services.context.ContextAnalysisService
import n.startapp.utils.EnvConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Snapshot of one book's warm-up — everything [BookOfflineService.status] can answer without a user in mind. */
data class BookOfflineSnapshot(
    val running: Boolean,
    val totalTokens: Int,
    val processedTokens: Int,
    val failed: Int,
    val startedAt: Long?,
    val finishedAt: Long?
)

private class BookOfflineJob {
    val total = AtomicInteger()
    val processed = AtomicInteger()
    val failed = AtomicInteger()
    @Volatile var running = false
    @Volatile var startedAt: Long? = null
    @Volatile var finishedAt: Long? = null
    var coroutine: Job? = null
}

/**
 * Warms a book's tap targets ahead of an offline download — the reader-side twin of
 * [n.startapp.services.warmup.WarmupService].
 *
 * The unit of work is not the book. `context_hint` is cached on the server by (sentence text,
 * token index) alone — [ContextAnalysisService] — so two readers with the identical novel in two
 * separate `books` rows (upload is deduplicated per user, not globally) warm the *same* cache
 * entries the moment their sentences match, and the second one to start pays almost nothing. The
 * job below just walks one book's tokens and asks for each; whether that costs a model call or a
 * free cache hit is decided underneath it, per token.
 *
 * ⚠️ Runs on [LlmRoute.BULK] — the same reserve pool the corpus warm-up uses, and for the same
 * reason: a background pass over a whole novel is thousands of calls, and it must never queue
 * ahead of, or spend the quota of, someone tapping a word right now.
 */
class BookOfflineService(
    private val bookRepository: BookRepository,
    private val contextService: ContextAnalysisService,
    private val llmCache: LlmCacheRepository
) {
    private val logger = LoggerFactory.getLogger(BookOfflineService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, BookOfflineJob>()
    private val concurrency = Semaphore(EnvConfig.bookOfflineConcurrency.coerceAtLeast(1))

    /** @return false only when the caller should not have asked — the book does not exist. */
    suspend fun start(bookId: Int): Boolean {
        if (bookRepository.blocksNoOwnerCheck(bookId, 0, 1) == null) return false

        val job = jobs.getOrPut(bookId) { BookOfflineJob() }
        // Already warming (or a previous run's numbers are still on screen while a resume ticks
        // over the same, now-cached, prefix) — the caller just wants the status, not a duplicate.
        if (job.running) return true

        job.running = true
        job.total.set(0); job.processed.set(0); job.failed.set(0)
        job.startedAt = System.currentTimeMillis()
        job.finishedAt = null

        job.coroutine = scope.launch {
            try {
                warm(bookId, job)
            } catch (e: Exception) {
                logger.warn("Offline warm-up failed for book {}: {}", bookId, e.message)
            } finally {
                job.running = false
                job.finishedAt = System.currentTimeMillis()
            }
        }
        return true
    }

    private suspend fun warm(bookId: Int, job: BookOfflineJob) {
        // The whole tap list is collected before any request fires — the run is about to spend
        // real time, and "how much" has to be known before the first word, not discovered as it
        // goes (the same reason `WarmupService` orders its list up front).
        val targets = mutableListOf<Pair<String, Int>>()
        var from = 0
        while (true) {
            val page = bookRepository.blocksNoOwnerCheck(bookId, from, 200) ?: break
            for (block in page.blocks) {
                for (sentence in block.sentences) {
                    for (token in sentence.tokens) {
                        if (token.tappable) targets += sentence.text to token.index
                    }
                }
            }
            from = page.nextOrdinal ?: break
        }
        job.total.set(targets.size)
        logger.info("Offline warm-up starting for book {}: {} tap targets", bookId, targets.size)

        coroutineScope {
            targets.map { (text, tokenIndex) ->
                async {
                    if (!isActive) return@async
                    concurrency.withPermit {
                        try {
                            contextService.hint(text, tokenIndex, null, null, route = LlmRoute.BULK)
                        } catch (e: Exception) {
                            job.failed.incrementAndGet()
                        } finally {
                            job.processed.incrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }
        logger.info(
            "Offline warm-up finished for book {}: {} processed, {} failed",
            bookId, job.processed.get(), job.failed.get()
        )
    }

    fun status(bookId: Int): BookOfflineSnapshot? {
        val job = jobs[bookId] ?: return null
        return BookOfflineSnapshot(
            running = job.running,
            totalTokens = job.total.get(),
            processedTokens = job.processed.get(),
            failed = job.failed.get(),
            startedAt = job.startedAt,
            finishedAt = job.finishedAt
        )
    }

    /**
     * The hints already warm, one page of blocks at a time.
     *
     * ⚠️ Never calls the model. A cache miss here means the word has not been warmed yet — the
     * token is simply absent from the page, not retried live: a background sync must not spend
     * quota a live tap would otherwise pay for, and the offline screen already knows how to say
     * "not ready yet" for a token it cannot find.
     */
    suspend fun bundle(bookId: Int, from: Int, limit: Int): OfflineBundlePageDto? {
        val page = bookRepository.blocksNoOwnerCheck(bookId, from, limit) ?: return null
        val hints = mutableListOf<OfflineHintDto>()

        for (block in page.blocks) {
            for (sentence in block.sentences) {
                for (token in sentence.tokens) {
                    if (!token.tappable) continue
                    val cacheKey = LlmCacheRepository.key(
                        "context_hint",
                        ContextAnalysisService.PROMPT_VERSION_HINT,
                        "${sentence.text.trim()}#${token.index}"
                    )
                    if (llmCache.get(cacheKey) == null) continue

                    // A cache hit costs a corpus read, never a model call — this is the same
                    // function a live tap uses, just guaranteed not to reach the LLM by the check
                    // above.
                    val hint = contextService.hint(sentence.text, token.index, null, null)
                    hints += OfflineHintDto(
                        blockOrdinal = block.ordinal,
                        sentenceIndex = sentence.index,
                        tokenIndex = token.index,
                        lemma = hint.lemma,
                        pos = hint.pos,
                        translationRu = hint.translationRu,
                        senseId = hint.senseId,
                        senseMatched = hint.senseMatched,
                        senseDefinitionEn = hint.senseDefinitionEn,
                        phonetic = hint.phonetic,
                        audioUrl = hint.audioUrl,
                        translationsRu = hint.translationsRu,
                        cefr = hint.cefr,
                        register = hint.register,
                        countability = hint.countability,
                        entryAvailable = hint.entryAvailable
                    )
                }
            }
        }

        return OfflineBundlePageDto(
            bookId = bookId,
            from = page.from,
            hints = hints,
            nextOrdinal = page.nextOrdinal
        )
    }

    fun close() {
        jobs.values.forEach { it.coroutine?.cancel() }
    }
}
