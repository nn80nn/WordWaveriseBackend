package n.startapp.services

import n.startapp.models.auth.SavedWord
import n.startapp.models.lexical.LexicalEntry

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
    data class Wording(val translation: String?, val definition: String?)

    /**
     * The wording a saved word should end up with, or null when there is nothing to change —
     * either it is already complete, or the corpus has nothing to add.
     */
    fun fill(saved: SavedWord, entry: LexicalEntry?): Wording? {
        val definitionMissing = saved.definition.isNullOrBlank()
        val translationMissing = saved.translation.isNullOrBlank()
        if (!definitionMissing && !translationMissing) return null

        val sense = entry?.posGroups?.firstOrNull()?.senses?.firstOrNull() ?: return null

        // Русское объяснение — запасной вариант: карточки этого списка исторически показывают
        // английское определение, и смешивать языки в одной колонке хуже, чем оставить как есть.
        val definition = saved.definition?.takeIf { it.isNotBlank() }
            ?: sense.definitionEn.takeIf { it.isNotBlank() }
            ?: sense.definitionRu.takeIf { it.isNotBlank() }
        val translation = saved.translation?.takeIf { it.isNotBlank() }
            ?: sense.translationsRu.joinToString(", ").takeIf { it.isNotBlank() }

        if (definition == saved.definition && translation == saved.translation) return null
        return Wording(translation = translation, definition = definition)
    }
}
