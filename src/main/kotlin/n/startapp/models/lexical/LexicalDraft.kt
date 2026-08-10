package n.startapp.models.lexical

import kotlinx.serialization.Serializable

/**
 * Exactly what the model is allowed to return — the deserialisation target for the annotation
 * call, mirroring `LexicalEntrySchema`.
 *
 * Deliberately narrower than [LexicalEntry]: there is no field here for pronunciation, audio,
 * URLs, sense ids, or provenance metadata, so the model has nowhere to put invented ones.
 * Everything absent from this shape is filled server-side.
 *
 * Every field has a default: a provider that drops one under a downgraded response format
 * should degrade a single sense, not fail the whole parse.
 */
@Serializable
data class DraftEntry(
    val lemma: String = "",
    val kind: String = "WORD",
    val etymology: String? = null,
    val frequencyBand: String? = null,
    val usageNotes: List<String> = emptyList(),
    val posGroups: List<DraftPosGroup> = emptyList()
)

@Serializable
data class DraftPosGroup(
    val pos: String = "",
    val posRu: String = "",
    val forms: DraftForms? = null,
    val senses: List<DraftSense> = emptyList()
)

@Serializable
data class DraftForms(
    val plural: String? = null,
    val past: String? = null,
    val pastParticiple: String? = null,
    val presentParticiple: String? = null,
    val thirdPerson: String? = null,
    val comparative: String? = null,
    val superlative: String? = null
) {
    fun toModel() = InflectedForms(
        plural = plural?.takeIf { it.isNotBlank() },
        past = past?.takeIf { it.isNotBlank() },
        pastParticiple = pastParticiple?.takeIf { it.isNotBlank() },
        presentParticiple = presentParticiple?.takeIf { it.isNotBlank() },
        thirdPerson = thirdPerson?.takeIf { it.isNotBlank() },
        comparative = comparative?.takeIf { it.isNotBlank() },
        superlative = superlative?.takeIf { it.isNotBlank() }
    )
}

@Serializable
data class DraftSense(
    val definitionEn: String = "",
    val definitionRu: String = "",
    val translationsRu: List<String> = emptyList(),
    val register: String = "neutral",
    val cefr: String? = null,
    val domain: String? = null,
    val examples: List<DraftExample> = emptyList(),
    val collocations: List<DraftCollocation> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val sourceRefs: List<Int> = emptyList(),
    val generated: Boolean = false,
    val usageNote: String? = null
)

@Serializable
data class DraftExample(
    val en: String = "",
    val ru: String = "",
    val sourceRef: Int? = null
)

@Serializable
data class DraftCollocation(
    val pattern: String = "",
    val ru: String? = null
)

/** Unknown values fall back to NEUTRAL rather than failing the parse. */
fun parseRegister(raw: String?): Register =
    Register.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: Register.NEUTRAL

fun parseLexicalKind(raw: String?): LexicalKind =
    LexicalKind.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: LexicalKind.WORD
