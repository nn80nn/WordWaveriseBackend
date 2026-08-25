package n.startapp.services.warmup

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a version bump must not cost.
 *
 * The scenario in the last two tests is the real one: the prompt version changed, so every
 * stored article stopped matching the key a lookup checks. Nothing was deleted and no reader
 * lost a page — but a run that only asks "is the current article here" sees an empty corpus and
 * starts again from the top.
 */
class WarmupOrderTest {

    private val list = listOf("alpha", "beta", "gamma", "delta")

    @Test
    fun `words with no article at all come first`() {
        val ordered = WarmupOrder.byUrgency(
            list,
            hasCurrent = setOf("alpha"),
            hasAny = setOf("alpha", "beta"),
        )
        // gamma and delta have nothing; beta is out of date; alpha is done.
        assertEquals(listOf("gamma", "delta", "beta", "alpha"), ordered)
    }

    @Test
    fun `order inside a band is the list's own, so a restart resumes`() {
        val ordered = WarmupOrder.byUrgency(list, hasCurrent = emptySet(), hasAny = emptySet())
        assertEquals(list, ordered)
    }

    @Test
    fun `a version bump moves work to the back of the queue instead of to the front`() {
        // Everything has an article; none of them match the new key.
        val ordered = WarmupOrder.byUrgency(
            list,
            hasCurrent = emptySet(),
            hasAny = list.toSet(),
        )
        assertEquals(list, ordered)
        assertEquals(
            List(list.size) { WarmupOrder.Urgency.STALE },
            list.map { WarmupOrder.urgencyOf(it, emptySet(), list.toSet()) },
        )
    }

    @Test
    fun `a genuine gap still beats every stale word after a bump`() {
        val words = list + "epsilon"
        val ordered = WarmupOrder.byUrgency(words, hasCurrent = emptySet(), hasAny = list.toSet())
        assertEquals("epsilon", ordered.first())
    }

    @Test
    fun `the limit now takes the most urgent words, not the first lines of the file`() {
        // "alpha" leads the file and is already current; without ordering a limit of 2 would
        // spend the whole run on it and on a word that merely needs an upgrade.
        val ordered = WarmupOrder.byUrgency(
            list,
            hasCurrent = setOf("alpha", "beta"),
            hasAny = setOf("alpha", "beta", "gamma"),
        )
        assertEquals(listOf("delta", "gamma"), ordered.take(2))
    }
}
