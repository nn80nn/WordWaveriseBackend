package n.startapp.services.lexical

import n.startapp.models.lexical.LexicalEntry

/**
 * Which sense a word saved *without* one should be pinned to.
 *
 * Saving a bare headword used to be a second way to save, and it was the weaker one: the row
 * carried no choice, so the next save of that word could fill the choice in — a silent rewrite
 * of a thing the user had never decided. The clients no longer offer it, and everything that
 * still arrives without a sense (an older app, a queued offline save, a row from before this
 * change) is given one here instead.
 *
 * ⚠️ The **first** sense, because that is precisely what such a row has been showing all along:
 * `SenseWording.of(entry, null)` reads the first sense of the first part of speech, so pinning
 * it changes nothing on screen — it only makes the choice explicit and therefore safe from
 * being rewritten later.
 *
 * ⚠️ Never a sense the same user already holds for the same word. Uniqueness of
 * `(user, word, sense)` is kept by code rather than by an index, so a collision here would not
 * fail loudly — it would quietly produce the same meaning twice in one vocabulary.
 */
object SenseBackfill {

    /**
     * @param taken sense ids this user already has saved for this word.
     * @return the sense to pin, or null when the article cannot name one — a word whose entry
     *   is still being written keeps its null, and a later read assigns it.
     */
    fun choose(entry: LexicalEntry?, taken: Set<String> = emptySet()): String? =
        entry?.posGroups
            ?.asSequence()
            ?.flatMap { it.senses.asSequence() }
            ?.map { it.id }
            ?.firstOrNull { it.isNotBlank() && it !in taken }
}
