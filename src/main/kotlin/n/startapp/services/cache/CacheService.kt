package n.startapp.services.cache

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Cache
import n.startapp.models.dictionary.WordDetailResponse
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * In-memory cache service using Caffeine
 * Caches word lookup results to reduce load on external APIs
 */
class CacheService {
    private val logger = LoggerFactory.getLogger(CacheService::class.java)

    // Cache for word definitions with 24-hour TTL
    private val wordCache: Cache<String, WordDetailResponse> = Caffeine.newBuilder()
        .expireAfterWrite(24, TimeUnit.HOURS)
        .maximumSize(10_000) // Store up to 10,000 words
        .recordStats() // Enable statistics for monitoring
        .build()

    /**
     * Get word data from cache
     * @param word The word to look up (case-insensitive)
     * @return Cached WordDetailResponse or null if not found
     */
    fun getWord(word: String): WordDetailResponse? {
        val key = word.trim().lowercase()
        val cached = wordCache.getIfPresent(key)

        if (cached != null) {
            logger.debug("Cache HIT for word: '$word'")
        } else {
            logger.debug("Cache MISS for word: '$word'")
        }

        return cached
    }

    /**
     * Store word data in cache
     * @param word The word (will be normalized to lowercase)
     * @param data The word data to cache
     */
    fun putWord(word: String, data: WordDetailResponse) {
        val key = word.trim().lowercase()
        wordCache.put(key, data)
        logger.debug("Cached word: '$word'")
    }

    /**
     * Invalidate a specific word from cache
     * @param word The word to invalidate
     */
    fun invalidateWord(word: String) {
        val key = word.trim().lowercase()
        wordCache.invalidate(key)
        logger.debug("Invalidated cache for word: '$word'")
    }

    /**
     * Clear all cached entries
     */
    fun clearAll() {
        wordCache.invalidateAll()
        logger.info("Cleared all cache entries")
    }

    /**
     * Get cache statistics
     */
    fun getStats(): CacheStats {
        val stats = wordCache.stats()
        return CacheStats(
            hitCount = stats.hitCount(),
            missCount = stats.missCount(),
            hitRate = stats.hitRate(),
            size = wordCache.estimatedSize()
        )
    }
}

/**
 * Cache statistics data class
 */
data class CacheStats(
    val hitCount: Long,
    val missCount: Long,
    val hitRate: Double,
    val size: Long
)
