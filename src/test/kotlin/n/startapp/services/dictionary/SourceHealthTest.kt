package n.startapp.services.dictionary

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that matters here is which signals count.
 *
 * The clients swallow their own errors and hand back null for "no entry for this word" exactly
 * as they do for "the request failed", so an empty answer can never be evidence of an outage —
 * counting it would take a healthy dictionary out of service the moment somebody looked up a few
 * rare words. A timeout is the only signal that cannot also mean "I don't have that word".
 */
class SourceHealthTest {

    @Test
    fun `a source is asked until it has timed out three times in a row`() {
        val health = SourceHealth()

        health.recordTimeout("FreeDictionary")
        assertFalse(health.isOpen("FreeDictionary"), "one timeout is weather, not an outage")

        health.recordTimeout("FreeDictionary")
        assertFalse(health.isOpen("FreeDictionary"))

        assertTrue(health.recordTimeout("FreeDictionary"), "the third one opens the breaker")
        assertTrue(health.isOpen("FreeDictionary"))
    }

    @Test
    fun `an answer resets the run, so scattered timeouts never add up`() {
        val health = SourceHealth()

        repeat(2) { health.recordTimeout("Wiktionary") }
        health.recordAnswer("Wiktionary")
        repeat(2) { health.recordTimeout("Wiktionary") }

        assertFalse(
            health.isOpen("Wiktionary"),
            "four timeouts, never three in a row — a busy afternoon is not an outage"
        )
    }

    @Test
    fun `one answer is enough to bring a source back`() {
        val health = SourceHealth()

        repeat(SourceHealth.BREAKER_THRESHOLD) { health.recordTimeout("FreeDictionary") }
        assertTrue(health.isOpen("FreeDictionary"))

        assertTrue(health.recordAnswer("FreeDictionary"), "the recovery is worth a log line")
        assertFalse(health.isOpen("FreeDictionary"))
    }

    @Test
    fun `a source with nothing to report is not a source in trouble`() {
        val health = SourceHealth()

        // The null answer case: the dictionary replied, it simply has no entry for this word.
        repeat(10) { health.recordAnswer("DataMuse") }

        assertFalse(health.isOpen("DataMuse"))
    }

    @Test
    fun `sources are judged one at a time`() {
        val health = SourceHealth()

        repeat(SourceHealth.BREAKER_THRESHOLD) { health.recordTimeout("FreeDictionary") }

        assertTrue(health.isOpen("FreeDictionary"))
        assertFalse(health.isOpen("Wiktionary"), "one dead upstream must not silence the rest")
    }

    @Test
    fun `re-opening an already open breaker is not reported twice`() {
        val health = SourceHealth()

        repeat(SourceHealth.BREAKER_THRESHOLD) { health.recordTimeout("FreeDictionary") }
        assertFalse(
            health.recordTimeout("FreeDictionary"),
            "the breaker is already open; a log line per lookup would be the noise it prevents"
        )
    }
}
