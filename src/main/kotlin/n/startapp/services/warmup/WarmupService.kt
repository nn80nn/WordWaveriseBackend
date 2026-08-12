package n.startapp.services.warmup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import n.startapp.services.LookupService
import n.startapp.services.query.WordOracle
import n.startapp.utils.EnvConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

@Serializable
data class WarmupStatus(
    val running: Boolean,
    val total: Int,
    val processed: Int,
    val written: Int,
    val alreadyPresent: Int,
    val skipped: Int,
    val notFound: Int,
    val failed: Int,
    val currentWord: String? = null,
    val wordsPerHour: Int,
    val startedAt: Long? = null,
    val lastError: String? = null,
    val poolConfigured: Boolean
)

/**
 * Reply to a start request.
 *
 * A declared type rather than a map: `ApiResponse` is serialized by kotlinx, which cannot write a
 * `Map<String, Any>` and fails at render time — after the job has already been launched. That
 * combination reports a 500 for a run that is in fact happily running.
 */
@Serializable
data class WarmupStartResponse(
    val started: Boolean,
    val message: String,
    val status: WarmupStatus
)

/**
 * Builds the corpus ahead of demand, one word at a time.
 *
 * Two constraints shape this, and both point the same way — go slowly:
 *
 *  - the scrapers share one global rate-limited queue with live lookups, so a fast bulk run
 *    would put every real search behind it;
 *  - warming means N requests each to Cambridge, Oxford and OED from a single IP, and LDOCE
 *    has already blocked this server. Losing the others would cost the grounding the whole
 *    annotation layer depends on.
 *
 * At the default 30 words/hour the job uses under 2% of the scraper budget and looks like a
 * trickle rather than a crawl. The corpus itself is the progress record: anything already
 * annotated is skipped, so a restart resumes without bookkeeping.
 */
class WarmupService(
    private val lookupService: LookupService,
    private val oracle: WordOracle? = null,
    private val queue: n.startapp.repositories.WarmupQueueRepository? = null
) {
    private val logger = LoggerFactory.getLogger(WarmupService::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val processed = AtomicInteger()
    private val written = AtomicInteger()
    private val alreadyPresent = AtomicInteger()
    private val skipped = AtomicInteger()
    private val notFound = AtomicInteger()
    private val failed = AtomicInteger()
    private val current = AtomicReference<String?>(null)
    private val lastError = AtomicReference<String?>(null)
    private val startedAt = AtomicReference<Long?>(null)
    private val total = AtomicInteger()

    /**
     * The rate the current run is actually using, which is not always the configured one.
     *
     * Pacing is the knob worth turning while watching a run — it trades throughput against
     * getting blocked by the dictionaries — and turning it through the environment costs a
     * redeploy, which restarts the very run being tuned. Reported rather than assumed, so status
     * never quotes a default the run is not obeying.
     */
    private val perHourInUse = AtomicInteger(EnvConfig.warmupWordsPerHour)

    companion object {
        private val WORD_LIST_RESOURCES = listOf(
            "/wordlists/b2c1.txt",
            "/wordlists/b2c1_nouns.txt"
        )

        /**
         * Occurrences per million, outside which a word is not worth an article.
         *
         * Above the ceiling a learner already knows it — nobody looks up "value" or "will".
         * Below the floor it is too marginal to spend tokens on. The gate exists so the
         * hand-assembled list can be slightly wrong in either direction without costing
         * anything.
         */
        private const val MAX_FREQUENCY_PER_MILLION = 60.0
        private const val MIN_FREQUENCY_PER_MILLION = 0.3

        /**
         * Pause after a word that cost the dictionaries nothing. Not zero: skipping still asks
         * the frequency oracle once per word, and a couple of thousand of those back to back is
         * a burst worth spreading out.
         */
        private const val SKIP_STEP_MS = 200L
    }

    /** The bundled band, deduplicated and in a stable order so a restart resumes where it left off. */
    fun bundledWords(): List<String> = WORD_LIST_RESOURCES
        .flatMap { resource ->
            javaClass.getResourceAsStream(resource)?.bufferedReader()?.readLines().orEmpty()
        }
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() && !it.startsWith("#") && it.all { c -> c.isLetter() } }
        .distinct()

    /**
     * Everything to warm: hand-queued words first, then the bundled band.
     *
     * Queued words lead because queueing one is an explicit request for that word — putting it
     * behind two thousand others would make the button pointless. `distinct` keeps the first
     * occurrence, so queueing a word that is also in the band promotes it rather than doubling it.
     */
    suspend fun words(): List<String> =
        (queue?.words().orEmpty() + bundledWords()).distinct()

    fun status() = WarmupStatus(
        running = job?.isActive == true,
        total = total.get(),
        processed = processed.get(),
        written = written.get(),
        alreadyPresent = alreadyPresent.get(),
        skipped = skipped.get(),
        notFound = notFound.get(),
        failed = failed.get(),
        currentWord = current.get(),
        wordsPerHour = perHourInUse.get(),
        startedAt = startedAt.get(),
        lastError = lastError.get(),
        poolConfigured = n.startapp.services.ai.LlmProvider.pool().isConfigured
    )

    /**
     * @param limit how many words of the list to take; 0 means all of it.
     * @param perHour pace override; 0 means whatever the environment configures.
     * @return false when a run is already in progress.
     */
    fun start(limit: Int = 0, perHour: Int = 0): Boolean {
        if (job?.isActive == true) return false

        processed.set(0); written.set(0); alreadyPresent.set(0)
        skipped.set(0); notFound.set(0); failed.set(0)
        lastError.set(null)
        total.set(0)
        startedAt.set(System.currentTimeMillis())

        val effectivePerHour = (if (perHour > 0) perHour else EnvConfig.warmupWordsPerHour)
            .coerceIn(1, 240)
        perHourInUse.set(effectivePerHour)
        val spacingMs = 3_600_000L / effectivePerHour

        job = scope.launch {
            // Composed inside the job because the queue lives in the database: computing it
            // before launching would mean blocking the caller of an admin endpoint on a query.
            val all = words()
            val slice = if (limit > 0) all.take(limit) else all
            total.set(slice.size)

            logger.info(
                "Warm-up starting: {} words at {}/hour (~{} min between words)",
                slice.size, effectivePerHour, spacingMs / 60_000
            )

            for (word in slice) {
                if (!isActive) break
                current.set(word)
                val startedWordAt = System.currentTimeMillis()
                // Whether this word actually cost the dictionaries anything. The pacing exists to
                // protect them, so a word that never reached them must not be paced: a resumed
                // run walks the entire already-built prefix before it reaches new work, and at
                // one word every two minutes that prefix alone is hours of doing nothing.
                var reachedTheSources = true
                try {
                    if (isTooCommonOrTooRare(word)) {
                        skipped.incrementAndGet()
                        reachedTheSources = false
                    } else {
                        when (lookupService.warm(word)) {
                            LookupService.WarmOutcome.WRITTEN -> written.incrementAndGet()
                            LookupService.WarmOutcome.NOT_FOUND -> notFound.incrementAndGet()
                            LookupService.WarmOutcome.FAILED -> failed.incrementAndGet()
                            LookupService.WarmOutcome.ALREADY_PRESENT -> {
                                alreadyPresent.incrementAndGet()
                                reachedTheSources = false
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Stopping is not a failure. Swallowed by the catch below, it charged the
                    // operator an error and left "StandaloneCoroutine was cancelled" sitting in
                    // the panel as the last thing that went wrong — every single time the stop
                    // button was pressed.
                    throw e
                } catch (e: Exception) {
                    // One bad word must never end the run; there are two thousand more.
                    failed.incrementAndGet()
                    lastError.set("${word}: ${e.message?.take(200)}")
                    logger.warn("Warm-up failed on '{}': {}", word, e.message)
                }
                processed.incrementAndGet()
                current.set(null)

                if (isActive) {
                    if (reachedTheSources) {
                        // Spacing is measured between words, not added to them: building an
                        // article takes 20-30s, so sleeping a full interval on top of that would
                        // deliver about three quarters of the requested rate and make the
                        // setting mean something other than what it says. Jittered so the
                        // traffic does not arrive on a metronome.
                        val target = spacingMs + Random.nextLong(-spacingMs / 5, spacingMs / 5)
                        val spent = System.currentTimeMillis() - startedWordAt
                        delay((target - spent).coerceAtLeast(0))
                    } else {
                        delay(SKIP_STEP_MS)
                    }
                }
            }
            logger.info(
                "Warm-up finished: {} written, {} already present, {} skipped, {} not found, {} failed",
                written.get(), alreadyPresent.get(), skipped.get(), notFound.get(), failed.get()
            )
            current.set(null)
        }
        return true
    }

    fun stop() {
        job?.cancel()
        current.set(null)
        logger.info("Warm-up stopped after {} words", processed.get())
    }

    /** A missing or unreachable oracle must not block warming — assume the word is worth it. */
    private suspend fun isTooCommonOrTooRare(word: String): Boolean {
        val frequency = runCatching { oracle?.frequency(word) }.getOrNull() ?: return false
        val outside = frequency > MAX_FREQUENCY_PER_MILLION || frequency < MIN_FREQUENCY_PER_MILLION
        if (outside) logger.debug("Warm-up skipping '{}' (frequency {}/M)", word, frequency)
        return outside
    }

    fun close() = job?.cancel()
}
