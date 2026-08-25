package n.startapp.services.warmup

/**
 * The order a warm-up run works through its list.
 *
 * The corpus is the progress record — there is no separate bookkeeping — and that worked
 * perfectly right up until a prompt version changed. The key a lookup checks carries the schema
 * and prompt versions, so a bump makes **every** word a miss at once; the run then walked the
 * list from the top rewriting words that already had articles, while words that had none waited
 * behind them. At thirty words an hour that is days of work spent on pages a reader could
 * already open, to reach the ones where they cannot.
 *
 * So the run asks two questions instead of one:
 *
 *  - is there an article for this word **at all**? If not, this is a gap — a reader who looks
 *    the word up today waits minutes and meanwhile sees raw dictionary fragments;
 *  - is that article the **current** one? If not, it is merely out of date, and the reader
 *    still gets a whole article while the rewrite happens behind them.
 *
 * Gaps first, upgrades after. A version bump then costs nothing in coverage: it adds work to
 * the back of the queue instead of throwing the front of it away.
 *
 * ⚠️ This also fixes what `WARMUP_LIMIT` used to do. The limit is applied **after** this
 * ordering, so "first N" now means "the N most urgent" rather than "the first N lines of the
 * file" — and finishing a word moves it out of the front of the queue, so a restart advances
 * instead of walking the same prefix forever.
 *
 * Pure and side-effect free so the ordering can be tested without a database.
 */
object WarmupOrder {

    /** What the corpus already has for one word. */
    enum class Urgency {
        /** No article at all. A reader who looks this up today waits. */
        MISSING,

        /** An article exists, written by an older schema or prompt. Worth upgrading, not urgently. */
        STALE,

        /** The current article is already there. Nothing to do. */
        CURRENT
    }

    fun urgencyOf(word: String, hasCurrent: Set<String>, hasAny: Set<String>): Urgency = when {
        word in hasCurrent -> Urgency.CURRENT
        word in hasAny -> Urgency.STALE
        else -> Urgency.MISSING
    }

    /**
     * @param words the list to work through, in its own stable order
     * @param hasCurrent words whose current-version article is already stored
     * @param hasAny words with an article of any version
     *
     * Words already current stay in the list rather than being dropped: `warm()` answers for
     * them in one indexed read and the run pays no pacing for it, and keeping them means the
     * reported totals still describe the list the operator asked for.
     *
     * Order within a band is the list's own, so a restart resumes where it left off.
     */
    fun byUrgency(words: List<String>, hasCurrent: Set<String>, hasAny: Set<String>): List<String> {
        val bands = words.groupBy { urgencyOf(it, hasCurrent, hasAny) }
        return bands[Urgency.MISSING].orEmpty() +
            bands[Urgency.STALE].orEmpty() +
            bands[Urgency.CURRENT].orEmpty()
    }
}
