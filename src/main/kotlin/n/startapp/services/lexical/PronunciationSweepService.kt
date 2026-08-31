package n.startapp.services.lexical

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import n.startapp.models.lexical.PRONUNCIATION_VERSION
import n.startapp.repositories.LexicalEntryRepository
import n.startapp.services.LookupService
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class PronunciationSweepStatus(
    val running: Boolean,
    val total: Int,
    val processed: Int,
    /** Articles whose pronunciation actually changed — the point of the run. */
    val rewritten: Int,
    /** Already carried the current binding; skipped without touching a dictionary. */
    val alreadyCurrent: Int,
    val failed: Int,
    val currentWord: String? = null,
    val wordsPerHour: Int,
    val startedAt: Long? = null,
    val lastError: String? = null
)

/**
 * Walks the corpus and re-reads pronunciation for every article, without the model.
 *
 * The lazy repair on lookup fixes whatever people actually open, and that is enough for the app.
 * It is **not** enough for the corpus: `/word/{lemma}` deliberately never triggers outbound work,
 * so a page nobody has opened in the app keeps yesterday's transcription in front of every reader
 * who arrives from a search engine — and those pages are most of what the corpus is for.
 *
 * ⚠️ Slow on purpose, for the same two reasons the warm-up is: the scrapers share one global
 * rate-limited queue with live lookups, and this is N requests each to Cambridge, Oxford and OED
 * from a single IP. LDOCE has already blocked this server; losing the others would cost the
 * grounding the whole annotation layer rests on. The default pace uses a small fraction of that
 * budget and looks like a trickle rather than a crawl.
 *
 * ⚠️ Costs no tokens. Pronunciation is written by the scrapers, never by the model, so a corpus
 * this size can be corrected for the price of the scrapes alone — which is the entire reason
 * `pronunciationVersion` is not part of the cache key.
 */
class PronunciationSweepService(
    private val lookupService: LookupService,
    private val repository: LexicalEntryRepository
) {
    private val logger = LoggerFactory.getLogger(PronunciationSweepService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val job = AtomicReference<Job?>(null)
    private val total = AtomicInteger()
    private val processed = AtomicInteger()
    private val rewritten = AtomicInteger()
    private val alreadyCurrent = AtomicInteger()
    private val failed = AtomicInteger()
    private val current = AtomicReference<String?>(null)
    private val startedAt = AtomicReference<Long?>(null)
    private val lastError = AtomicReference<String?>(null)
    private val pace = AtomicInteger(DEFAULT_WORDS_PER_HOUR)

    fun status() = PronunciationSweepStatus(
        running = job.get()?.isActive == true,
        total = total.get(),
        processed = processed.get(),
        rewritten = rewritten.get(),
        alreadyCurrent = alreadyCurrent.get(),
        failed = failed.get(),
        currentWord = current.get(),
        wordsPerHour = pace.get(),
        startedAt = startedAt.get(),
        lastError = lastError.get()
    )

    /** @return false when a run is already going — a second one would double the scraper load. */
    fun start(limit: Int = 0, wordsPerHour: Int = 0): Boolean {
        if (job.get()?.isActive == true) return false
        pace.set(if (wordsPerHour > 0) wordsPerHour else DEFAULT_WORDS_PER_HOUR)
        processed.set(0); rewritten.set(0); alreadyCurrent.set(0); failed.set(0)
        lastError.set(null)
        startedAt.set(System.currentTimeMillis())
        job.set(scope.launch { run(limit) })
        return true
    }

    fun stop(): Boolean {
        val running = job.get()?.isActive == true
        job.get()?.cancel()
        current.set(null)
        return running
    }

    private suspend fun run(limit: Int) {
        val lemmas = runCatching { repository.publishedLemmas().map { it.lemma } }
            .onFailure { lastError.set("could not list the corpus: ${it.message}") }
            .getOrDefault(emptyList())
            .let { if (limit > 0) it.take(limit) else it }
        total.set(lemmas.size)
        logger.info("Pronunciation sweep: {} lemmas at {} per hour", lemmas.size, pace.get())

        val gap = 3_600_000L / pace.get().coerceAtLeast(1)
        for (lemma in lemmas) {
            if (!scope.isActive || job.get()?.isActive != true) break
            current.set(lemma)
            // The corpus is its own progress record: a word already carrying the current
            // binding is skipped without touching a dictionary, so a restart resumes and a
            // second run over the same corpus costs nothing.
            val stored = runCatching { repository.findLatestByLemma(lemma) }.getOrNull()
            if (stored != null && stored.pronunciationVersion >= PRONUNCIATION_VERSION) {
                alreadyCurrent.incrementAndGet()
                processed.incrementAndGet()
                continue
            }

            runCatching { lookupService.repronounce(lemma) }
                .onSuccess { if (it > 0) rewritten.incrementAndGet() }
                .onFailure {
                    failed.incrementAndGet()
                    lastError.set("$lemma: ${it.message}")
                    logger.warn("Pronunciation sweep failed for '{}': {}", lemma, it.message)
                }
            processed.incrementAndGet()
            // The pause is taken only for a word that actually went to the dictionaries.
            delay(gap)
        }

        current.set(null)
        logger.info(
            "Pronunciation sweep finished: {} processed, {} rewritten, {} already current, {} failed",
            processed.get(), rewritten.get(), alreadyCurrent.get(), failed.get()
        )
    }

    fun close() {
        stop()
        scope.cancel()
    }

    companion object {
        /** One word every two minutes: a trickle beside the live lookups, done in a day. */
        const val DEFAULT_WORDS_PER_HOUR = 30
    }
}
