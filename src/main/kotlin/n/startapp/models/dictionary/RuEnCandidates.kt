package n.startapp.models.dictionary

import kotlinx.serialization.Serializable
import n.startapp.models.lexical.Register

/**
 * One English option for a Russian query, with enough context to choose between options.
 *
 * The previous reverse-translation returned bare strings, which left the user guessing which of
 * four words to use. Everything here beyond [en] exists to make that choice informed.
 */
@Serializable
data class RuEnCandidate(
    val en: String,
    val pos: String = "",
    /** Which sense of the Russian word this option covers — "кислый вкус" vs "угрюмый человек". */
    val ruGloss: String = "",
    /** When to reach for this one rather than its neighbours. */
    val whenToUse: String = "",
    val example: String = "",
    val exampleRu: String = "",
    val cefr: String? = null,
    val register: Register = Register.NEUTRAL
)

@Serializable
data class RuEnCandidates(
    val query: String,
    /** The Russian word has several unrelated senses (замок, лук, ключ). */
    val isAmbiguous: Boolean = false,
    val candidates: List<RuEnCandidate> = emptyList(),
    /** Explains the ambiguity when there is one. */
    val note: String? = null,
    /** True when the model was unavailable and these are machine-translation leftovers. */
    val degraded: Boolean = false
)
