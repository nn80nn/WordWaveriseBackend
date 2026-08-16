package n.startapp.services

import n.startapp.models.auth.SavedWord
import n.startapp.models.lexical.LexicalEntry
import n.startapp.services.lexical.SenseWording

/**
 * Fills in what a saved word is missing, from the annotated corpus.
 *
 * A word could always be saved before its article existed — the raw aggregate is what the save
 * button had to hand, and for an already-annotated word that aggregate can come back empty — so
 * rows landed with a null definition and the card showed nothing but the headword. The
 * dictionary knows the answer by the time the list is opened, so the gap is closed on read.
 *
 * Only blanks are filled. Saved words carry no `customized` flag the way flashcards do, so
 * "never overwrite" is the entire guarantee that anything a user put there survives.
 */
object SavedWordEnrichment {

    /** What should be written to the row. */
    data class Wording(val translation: String?, val definition: String?, val example: String?)

    /**
     * The wording a saved word should end up with, or null when there is nothing to change —
     * either it is already complete, or the corpus has nothing to add.
     *
     * A word pinned to a sense is filled from *that* sense. An unknown pin fills nothing at
     * all: quietly falling back to the first sense would hand the user a meaning they did not
     * choose, and the row would look filled in, so nothing would ever correct it.
     */
    fun fill(saved: SavedWord, entry: LexicalEntry?): Wording? {
        val definitionMissing = saved.definition.isNullOrBlank()
        val translationMissing = saved.translation.isNullOrBlank()
        val exampleMissing = saved.example.isNullOrBlank()
        if (!definitionMissing && !translationMissing && !exampleMissing) return null
        if (entry == null) return null

        val sense = SenseWording.of(entry, saved.senseId) ?: return null

        val definition = saved.definition?.takeIf { it.isNotBlank() } ?: sense.definition
        val translation = saved.translation?.takeIf { it.isNotBlank() }
            ?: sense.translation.takeIf { it.isNotBlank() }
        val example = saved.example?.takeIf { it.isNotBlank() } ?: sense.example

        if (definition == saved.definition && translation == saved.translation &&
            example == saved.example
        ) return null
        return Wording(translation = translation, definition = definition, example = example)
    }
}
