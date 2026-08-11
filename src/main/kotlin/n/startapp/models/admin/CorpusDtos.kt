package n.startapp.models.admin

import kotlinx.serialization.Serializable

/** One row of the corpus as the panel lists it — no article body, just what it cost and yields. */
@Serializable
data class CorpusEntrySummary(
    val lemma: String,
    val kind: String,
    val model: String,
    val posCount: Int,
    val senseCount: Int,
    val sources: List<String>,
    val aiGenerated: Boolean,
    val hitCount: Long,
    val tokens: Int,
    val latencyMs: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class CorpusEntriesPage(
    val entries: List<CorpusEntrySummary>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

@Serializable
data class NamedCount(val name: String, val count: Long)

@Serializable
data class CorpusStats(
    val totalEntries: Long,
    val aiGenerated: Long,
    val withoutSources: Long,
    val added24h: Long,
    val added7d: Long,
    val totalHits: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val avgLatencyMs: Long,
    val byKind: List<NamedCount>,
    val byModel: List<NamedCount>,
    val topByHits: List<NamedCount>,
    /** How much of the built-in warm-up list already has an article. */
    val warmupListSize: Int,
    val warmupListCovered: Int,
    val queueSize: Int,
    val queueCovered: Int
)

/** A queued word plus whether the corpus already answers for it. */
@Serializable
data class WarmupQueueItem(
    val word: String,
    val done: Boolean,
    val addedAt: Long
)

@Serializable
data class WarmupQueueView(
    val items: List<WarmupQueueItem>,
    val pending: Int
)

@Serializable
data class AddQueueWordsRequest(val words: String)

@Serializable
data class AddQueueWordsResult(
    val added: List<String>,
    val alreadyQueued: List<String>,
    val rejected: List<String>
)
