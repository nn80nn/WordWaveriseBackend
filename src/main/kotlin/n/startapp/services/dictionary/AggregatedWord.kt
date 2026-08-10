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
    val perPosPronunciations: Map<String, List<PronunciationEntry>>
)
