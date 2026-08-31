package n.startapp.repositories

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import n.startapp.database.DatabaseFactory.dbQuery
import n.startapp.database.tables.LexicalEntries
import n.startapp.models.admin.CorpusEntriesPage
import n.startapp.models.admin.CorpusEntrySummary
import n.startapp.models.admin.CorpusStats
import n.startapp.models.admin.NamedCount
import n.startapp.models.dictionary.WordDetailResponse
import n.startapp.models.lexical.LEXICAL_SCHEMA_VERSION
import n.startapp.models.lexical.LexicalEntry
import n.startapp.services.ai.LlmUsage
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.avg
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/** A headword the corpus can already answer for, with the timestamp of its newest article. */
data class CorpusLemma(val lemma: String, val updatedAt: Long)

/** An entry plus the raw aggregate it was built from. */
data class StoredEntry(
    val entry: LexicalEntry,
    val raw: WordDetailResponse?,
    val sourceFingerprint: String
)

class LexicalEntryRepository {
    private val logger = LoggerFactory.getLogger(LexicalEntryRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun find(cacheKey: String): StoredEntry? = dbQuery {
        val row = LexicalEntries.selectAll().where { LexicalEntries.cacheKey eq cacheKey }
            .firstOrNull() ?: return@dbQuery null

        LexicalEntries.update({ LexicalEntries.cacheKey eq cacheKey }) {
            it[hitCount] = row[LexicalEntries.hitCount] + 1
        }

        try {
            StoredEntry(
                entry = json.decodeFromString<LexicalEntry>(row[LexicalEntries.entryJson]),
                raw = row[LexicalEntries.rawJson].takeIf { it.isNotBlank() }
                    ?.let { runCatching { json.decodeFromString<WordDetailResponse>(it) }.getOrNull() },
                sourceFingerprint = row[LexicalEntries.sourceFingerprint]
            )
        } catch (e: Exception) {
            // A row that cannot be read is worse than no row: drop it so the next request rebuilds.
            logger.warn("Unreadable lexical entry '$cacheKey', discarding: ${e.message}")
            LexicalEntries.deleteWhere { LexicalEntries.cacheKey eq cacheKey }
            null
        }
    }

    /**
     * Rewrites the stored articles for one lemma in place, without touching the model.
     *
     * ⚠️ By lemma, so it reaches **every** version of the article — including the one an older
     * prompt wrote, which is still served to readers while a new one is being generated. A
     * repair that only fixed the current row would leave the copy people actually see wrong.
     *
     * `updatedAt` is deliberately left alone: this is not a new article, and moving the
     * timestamp would tell the warm-up that a stale entry had just been rewritten.
     */
    suspend fun rewriteByLemma(lemma: String, transform: (StoredEntry) -> StoredEntry?): Int = dbQuery {
        val rows = LexicalEntries.selectAll().where { LexicalEntries.lemma eq lemma }.toList()
        var changed = 0
        for (row in rows) {
            val key = row[LexicalEntries.cacheKey]
            val stored = runCatching {
                StoredEntry(
                    entry = json.decodeFromString<LexicalEntry>(row[LexicalEntries.entryJson]),
                    raw = row[LexicalEntries.rawJson].takeIf { it.isNotBlank() }
                        ?.let { runCatching { json.decodeFromString<WordDetailResponse>(it) }.getOrNull() },
                    sourceFingerprint = row[LexicalEntries.sourceFingerprint]
                )
            }.getOrElse {
                logger.warn("Unreadable lexical entry '$key' during rewrite: ${it.message}")
                null
            } ?: continue

            val updated = transform(stored) ?: continue
            if (updated.entry == stored.entry && updated.raw == stored.raw) continue

            val entryJson = json.encodeToString(updated.entry)
            val rawJson = updated.raw?.let { json.encodeToString(it) }
            LexicalEntries.update({ LexicalEntries.cacheKey eq key }) {
                it[LexicalEntries.entryJson] = entryJson
                if (rawJson != null) it[LexicalEntries.rawJson] = rawJson
                it[formsIndex] = updated.entry.formsIndex()
            }
            changed++
        }
        changed
    }

    /**
     * Persists an annotated entry. Never persists a degraded one — that would pin a failed
     * annotation forever, when the whole point is that the next request retries the model.
     */
    suspend fun save(
        cacheKey: String,
        entry: LexicalEntry,
        raw: WordDetailResponse,
        sourceFingerprint: String,
        usage: LlmUsage = LlmUsage()
    ) {
        if (entry.degraded) {
            logger.debug("Not persisting degraded entry for '${entry.lemma}'")
            return
        }
        runCatching {
            dbQuery {
                val now = System.currentTimeMillis()
                val entryJson = json.encodeToString(entry)
                val rawJson = json.encodeToString(raw)
                val exists = LexicalEntries.selectAll()
                    .where { LexicalEntries.cacheKey eq cacheKey }.any()

                if (exists) {
                    LexicalEntries.update({ LexicalEntries.cacheKey eq cacheKey }) {
                        it[LexicalEntries.entryJson] = entryJson
                        it[LexicalEntries.rawJson] = rawJson
                        it[LexicalEntries.sourceFingerprint] = sourceFingerprint
                        it[formsIndex] = entry.formsIndex()
                        it[aiGenerated] = entry.aiGenerated
                        it[promptTokens] = usage.promptTokens
                        it[completionTokens] = usage.completionTokens
                        it[latencyMs] = usage.latencyMs.toInt()
                        it[updatedAt] = now
                    }
                } else {
                    LexicalEntries.insert {
                        it[LexicalEntries.cacheKey] = cacheKey
                        it[lemma] = entry.lemma.take(255)
                        it[lang] = entry.language
                        it[kind] = entry.kind.name
                        it[schemaVersion] = entry.schemaVersion
                        it[promptVersion] = entry.promptVersion
                        it[model] = entry.model.take(96)
                        it[LexicalEntries.sourceFingerprint] = sourceFingerprint
                        it[LexicalEntries.entryJson] = entryJson
                        it[LexicalEntries.rawJson] = rawJson
                        it[formsIndex] = entry.formsIndex()
                        it[aiGenerated] = entry.aiGenerated
                        it[promptTokens] = usage.promptTokens
                        it[completionTokens] = usage.completionTokens
                        it[latencyMs] = usage.latencyMs.toInt()
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }
            }
        }.onFailure { logger.warn("Failed to persist lexical entry '$cacheKey': ${it.message}") }
    }

    /**
     * Which of [keys] the corpus already holds — "is this article the current one".
     *
     * The bulk form of [find], for the warm-up, which has to answer the same question for a
     * couple of thousand words before it decides what to do first. Chunked because the list is
     * the whole word list and a single `IN` of that size is a statement nobody wants to debug.
     */
    suspend fun existingKeys(keys: Collection<String>): Set<String> = dbQuery {
        if (keys.isEmpty()) return@dbQuery emptySet()
        keys.chunked(500).flatMapTo(mutableSetOf()) { chunk ->
            LexicalEntries
                .select(LexicalEntries.cacheKey)
                .where { LexicalEntries.cacheKey inList chunk }
                .map { it[LexicalEntries.cacheKey] }
        }
    }

    /**
     * Which of [lemmas] have **any** article at all, whatever version wrote it.
     *
     * ⚠️ A different question from [existingKeys], and keeping them apart is the whole point.
     * The cache key carries the schema and prompt versions, so after a bump every word looks
     * unannotated — which turned "warm the corpus" into "build the corpus again from scratch",
     * and turned a warm word into one the reader waits three minutes for. A word with an older
     * article is warm: it has something to show. It is merely out of date.
     */
    suspend fun lemmasWithAnyEntry(lemmas: Collection<String>): Set<String> = dbQuery {
        if (lemmas.isEmpty()) return@dbQuery emptySet()
        val wanted = lemmas.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        wanted.chunked(500).flatMapTo(mutableSetOf()) { chunk ->
            LexicalEntries
                .select(LexicalEntries.lemma)
                .where { LexicalEntries.lemma inList chunk }
                .map { it[LexicalEntries.lemma] }
        }
    }

    /**
     * Finds the lemma an already-annotated entry files this surface form under.
     *
     * Matches the headword first, then the recorded inflected forms, which is what lets
     * "running" resolve to "run" without a spelling oracle or a model call once "run" is known.
     */
    suspend fun findLemmaByForm(form: String): String? = dbQuery {
        val needle = form.trim().lowercase()
        if (needle.isBlank()) return@dbQuery null

        LexicalEntries.selectAll().where { LexicalEntries.lemma eq needle }
            .firstOrNull()?.let { return@dbQuery it[LexicalEntries.lemma] }

        LexicalEntries
            .selectAll()
            .where { LexicalEntries.formsIndex like "%$needle%" }
            .limit(50)
            // `like` is a coarse prefilter; the real decision is made in Kotlin.
            .firstOrNull { formsContain(it[LexicalEntries.lemma], it[LexicalEntries.formsIndex], needle) }
            ?.get(LexicalEntries.lemma)
    }

    /**
     * Most recently written article for a lemma, whatever schema or prompt version produced it.
     *
     * Used to enrich the legacy endpoints, where any annotated Russian beats the context-free
     * word-level translation they would otherwise generate — so being strict about versions
     * would only mean falling back to the worse answer.
     */
    suspend fun findLatestByLemma(lemma: String): LexicalEntry? = dbQuery {
        LexicalEntries.selectAll()
            .where { LexicalEntries.lemma eq lemma.trim().lowercase() }
            .orderBy(LexicalEntries.updatedAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.let { row ->
                runCatching { json.decodeFromString<LexicalEntry>(row[LexicalEntries.entryJson]) }.getOrNull()
            }
    }

    /**
     * The newest stored article for a lemma **with the aggregate it was built from**.
     *
     * ⚠️ The pair is the point. An article and a raw aggregate are two descriptions of one word,
     * and the clients read pronunciation and audio from the raw one while reading the senses
     * from the article. Serving an old article beside a freshly fetched aggregate mixes them:
     * the fresh one is the *quick* aggregate — API sources only, no scrapers — so it carries no
     * uk/us split, and the header fell back to FreeDictionary's `/tɑem/` while the entry right
     * below it said `/taɪm/`. Whatever is shown, both halves have to come from the same reading
     * of the word.
     */
    suspend fun findLatestStoredByLemma(lemma: String): StoredEntry? = dbQuery {
        LexicalEntries.selectAll()
            .where { LexicalEntries.lemma eq lemma.trim().lowercase() }
            .orderBy(LexicalEntries.updatedAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.let { row ->
                val entry = runCatching {
                    json.decodeFromString<LexicalEntry>(row[LexicalEntries.entryJson])
                }.getOrNull() ?: return@let null
                StoredEntry(
                    entry = entry,
                    raw = row[LexicalEntries.rawJson].takeIf { it.isNotBlank() }
                        ?.let { runCatching { json.decodeFromString<WordDetailResponse>(it) }.getOrNull() },
                    sourceFingerprint = row[LexicalEntries.sourceFingerprint]
                )
            }
    }

    /**
     * The same lookup for a set of lemmas in one round trip. Refreshing a card
     * list one query at a time would put a query per card on a request that is
     * already reading every card.
     *
     * Newest wins: rows arrive newest-first and the first one seen for a lemma
     * is kept, which mirrors [findLatestByLemma] for a single word.
     */
    suspend fun findLatestByLemmas(lemmas: Collection<String>): Map<String, LexicalEntry> = dbQuery {
        val wanted = lemmas.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return@dbQuery emptyMap()

        val out = LinkedHashMap<String, LexicalEntry>()
        LexicalEntries.selectAll()
            .where { LexicalEntries.lemma inList wanted }
            .orderBy(LexicalEntries.updatedAt to SortOrder.DESC)
            .forEach { row ->
                val lemma = row[LexicalEntries.lemma]
                if (lemma in out) return@forEach
                runCatching { json.decodeFromString<LexicalEntry>(row[LexicalEntries.entryJson]) }
                    .getOrNull()
                    ?.let { out[lemma] = it }
            }
        out
    }

    suspend fun deleteByLemma(lemma: String): Int = dbQuery {
        LexicalEntries.deleteWhere { LexicalEntries.lemma eq lemma.trim().lowercase() }
    }

    // ── Public corpus index (crawlable pages) ───────────────────────────────

    /**
     * Every headword that has a page, newest write per lemma.
     *
     * Deliberately reads the table rather than the word list: the sitemap must promise only
     * URLs that render, and an article that has not been generated yet renders as a 404.
     * A lemma appears under several cache keys as versions move, so the rows are collapsed
     * on lemma and the newest `updated_at` becomes the `<lastmod>`.
     */
    suspend fun publishedLemmas(): List<CorpusLemma> = dbQuery {
        val newest = LexicalEntries.updatedAt.max()
        LexicalEntries
            .select(LexicalEntries.lemma, newest)
            .groupBy(LexicalEntries.lemma)
            .orderBy(LexicalEntries.lemma to SortOrder.ASC)
            .map { CorpusLemma(it[LexicalEntries.lemma], it[newest] ?: 0L) }
    }

    /** Headwords under one initial, for the A–Z index that gives crawlers a path to them. */
    suspend fun lemmasStartingWith(prefix: String, limit: Int = 5000): List<String> = dbQuery {
        val needle = prefix.trim().lowercase()
        if (needle.isBlank()) return@dbQuery emptyList()
        LexicalEntries
            .select(LexicalEntries.lemma)
            .where { LexicalEntries.lemma like "$needle%" }
            .withDistinct()
            .orderBy(LexicalEntries.lemma to SortOrder.ASC)
            .limit(limit)
            .map { it[LexicalEntries.lemma] }
    }

    // ── Admin views ─────────────────────────────────────────────────────────

    /** Every headword the corpus answers for, for set arithmetic against a word list. */
    suspend fun allLemmas(): Set<String> = dbQuery {
        LexicalEntries.select(LexicalEntries.lemma).withDistinct()
            .map { it[LexicalEntries.lemma] }
            .toSet()
    }

    suspend fun browse(search: String?, page: Int, pageSize: Int): CorpusEntriesPage = dbQuery {
        val needle = search?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val condition: Op<Boolean> =
            if (needle == null) Op.TRUE else (LexicalEntries.lemma like "%$needle%")

        val total = LexicalEntries.selectAll().where(condition).count()
        val rows = LexicalEntries
            .selectAll()
            .where(condition)
            .orderBy(LexicalEntries.updatedAt to SortOrder.DESC)
            .limit(pageSize, offset = ((page - 1).toLong() * pageSize))
            .map { row ->
                // The body is decoded only for the rows on screen; the table itself stores no
                // sense count, and denormalising one would be a second thing to keep in sync.
                val entry = runCatching {
                    json.decodeFromString<LexicalEntry>(row[LexicalEntries.entryJson])
                }.getOrNull()

                CorpusEntrySummary(
                    lemma = row[LexicalEntries.lemma],
                    kind = row[LexicalEntries.kind],
                    model = row[LexicalEntries.model],
                    posCount = entry?.posGroups?.size ?: 0,
                    senseCount = entry?.posGroups?.sumOf { it.senses.size } ?: 0,
                    sources = entry?.sources?.map { it.source }?.distinct()?.sorted().orEmpty(),
                    aiGenerated = row[LexicalEntries.aiGenerated],
                    hitCount = row[LexicalEntries.hitCount],
                    tokens = row[LexicalEntries.promptTokens] + row[LexicalEntries.completionTokens],
                    latencyMs = row[LexicalEntries.latencyMs],
                    createdAt = row[LexicalEntries.createdAt],
                    updatedAt = row[LexicalEntries.updatedAt]
                )
            }

        CorpusEntriesPage(entries = rows, total = total, page = page, pageSize = pageSize)
    }

    suspend fun stats(warmupWords: List<String>, queueWords: List<String>): CorpusStats = dbQuery {
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L

        val idCount = LexicalEntries.id.count()
        val total = LexicalEntries.selectAll().count()

        val byKind = LexicalEntries.select(LexicalEntries.kind, idCount)
            .groupBy(LexicalEntries.kind)
            .map { NamedCount(it[LexicalEntries.kind], it[idCount]) }
            .sortedByDescending { it.count }

        val byModel = LexicalEntries.select(LexicalEntries.model, idCount)
            .groupBy(LexicalEntries.model)
            .map { NamedCount(it[LexicalEntries.model].ifBlank { "—" }, it[idCount]) }
            .sortedByDescending { it.count }

        val topByHits = LexicalEntries
            .select(LexicalEntries.lemma, LexicalEntries.hitCount)
            .orderBy(LexicalEntries.hitCount to SortOrder.DESC)
            .limit(15)
            .map { NamedCount(it[LexicalEntries.lemma], it[LexicalEntries.hitCount]) }
            .filter { it.count > 0 }

        val promptSum = LexicalEntries.promptTokens.sum()
        val completionSum = LexicalEntries.completionTokens.sum()
        val hitSum = LexicalEntries.hitCount.sum()
        val latencyAvg = LexicalEntries.latencyMs.avg()
        val totals = LexicalEntries.select(promptSum, completionSum, hitSum, latencyAvg).first()

        val lemmas = LexicalEntries.select(LexicalEntries.lemma).withDistinct()
            .map { it[LexicalEntries.lemma] }
            .toSet()

        CorpusStats(
            totalEntries = total,
            aiGenerated = LexicalEntries.selectAll()
                .where { LexicalEntries.aiGenerated eq true }.count(),
            // An article whose grounding is empty is one the sources never actually supported.
            withoutSources = LexicalEntries.selectAll()
                .where { LexicalEntries.formsIndex eq "" }.count(),
            added24h = LexicalEntries.selectAll()
                .where { LexicalEntries.createdAt greater (now - day) }.count(),
            added7d = LexicalEntries.selectAll()
                .where { LexicalEntries.createdAt greater (now - 7 * day) }.count(),
            totalHits = totals[hitSum] ?: 0L,
            promptTokens = totals[promptSum]?.toLong() ?: 0L,
            completionTokens = totals[completionSum]?.toLong() ?: 0L,
            avgLatencyMs = totals[latencyAvg]?.toLong() ?: 0L,
            byKind = byKind,
            byModel = byModel,
            topByHits = topByHits,
            warmupListSize = warmupWords.size,
            warmupListCovered = warmupWords.count { it in lemmas },
            queueSize = queueWords.size,
            queueCovered = queueWords.count { it in lemmas }
        )
    }

    companion object {
        /**
         * Whether an entry files [needle] as one of its own forms.
         *
         * The index joins every form with a space, so a multi-word headword is stored as tokens
         * indistinguishable from a list of one-word forms — "take up" indexes as
         * `take up took up taking up` — and matching a bare token let the query "take" come back
         * as the phrasal verb. A form always has as many words as the headword it belongs to,
         * which is what tells the two apart; the match is then made on windows of that width.
         */
        internal fun formsContain(lemma: String, formsIndex: String, needle: String): Boolean {
            val width = needle.split(' ').filter { it.isNotBlank() }.size
            if (width == 0) return false
            if (lemma.split(' ').filter { it.isNotBlank() }.size != width) return false

            return formsIndex
                .split(' ')
                .filter { it.isNotBlank() }
                .windowed(width, 1, partialWindows = false)
                .any { it.joinToString(" ") == needle }
        }

        fun cacheKey(lemma: String, kind: String, promptVersion: Int, model: String, lang: String = "en"): String =
            "$lang|${lemma.trim().lowercase()}|$kind|s$LEXICAL_SCHEMA_VERSION|p$promptVersion|$model"

        /** Identifies the grounding material, so a changed scrape can be detected later. */
        fun fingerprint(fragments: List<String>): String =
            sha256(fragments.joinToString("\n"))

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
