package n.startapp.services.dictionary

import n.startapp.models.dictionary.DetailedDefinition
import n.startapp.models.dictionary.PronunciationEntry
import n.startapp.models.dictionary.WordDetailResponse

/**
 * Aggregation output including the parts the public response drops.
 *
 * [WordDetailResponse] is the client contract and is deliberately trimmed; annotation needs the
 * wider view, and homograph rendering needs to know which pronunciation belongs to which part
 * of speech. Both were being computed and then discarded.
 */
data class AggregatedWord(
    val response: WordDetailResponse,
    /** Un-truncated per-source definitions used as grounding material for annotation. */
    val sourceDefinitions: List<DetailedDefinition>,
    /** pos (lowercase) → pronunciations, from the sources that tag pronunciation with a POS. */
    val perPosPronunciations: Map<String, List<PronunciationEntry>>,
    /**
     * One headword block of one source: how it sounds, and which definitions stood next to it.
     *
     * Part of speech is the coarse answer and it runs out fast: `lead` is a noun both as /liːd/
     * and as /led/, `bass` as /beɪs/ and /bæs/. The finer answer is where the definition was
     * printed, and that is the only evidence a *sense* can be matched against — see
     * [n.startapp.services.lexical.PronunciationBinding].
     */
    val pronunciationVariants: List<PronunciationVariant> = emptyList()
)

/**
 * A pronunciation together with the definitions that were printed under it.
 *
 * [definitionKeys] are normalised definition texts, which is what makes the match possible at
 * all: the same strings travel into the annotation prompt as source fragments, and a sense
 * points back at them through its `sourceRefs`.
 */
data class PronunciationVariant(
    val source: String,
    val pos: String?,
    val pronunciations: List<PronunciationEntry>,
    val definitionKeys: Set<String>
) {
    companion object {
        /**
         * How a definition is recognised again after it has travelled through the prompt.
         *
         * The text is the only handle there is — the block index does not survive into the
         * annotated article — so it is reduced to the part that cannot drift: letters, digits
         * and single spaces, cut to a length short enough that a trailing "(also …)" cannot
         * make the same definition look like two.
         */
        fun key(definition: String): String =
            definition.lowercase()
                .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
                .trim()
                .take(90)
    }
}
