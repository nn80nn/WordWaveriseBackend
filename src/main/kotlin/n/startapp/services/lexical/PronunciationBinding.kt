package n.startapp.services.lexical

import n.startapp.models.dictionary.PronunciationEntry
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.PRONUNCIATION_VERSION
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense
import n.startapp.services.dictionary.AggregatedWord
import n.startapp.services.dictionary.PronunciationVariant

/**
 * Attaches pronunciation to a finished article — the one part of it the model never writes.
 *
 * Two levels, because a word can sound two ways for two different reasons:
 *
 *  - **by part of speech** — `suspect` is /səˈspekt/ as a verb and /ˈsʌspekt/ as a noun, and
 *    every part-of-speech group carries its own;
 *  - **by sense** — `lead` is a noun both as /liːd/ and as /led/, and no grammatical label
 *    separates those. The evidence there is where the definition was printed: a sense whose
 *    supporting fragments came out of the /led/ block sounds like /led/.
 *
 * ⚠️ Runs both when an article is written and when an old one is repaired, and it must be the
 * same code in both places. The repair path has nothing but the stored article and a fresh
 * aggregate — no model call — so anything this function needs has to come from those two.
 *
 * ⚠️ Clears as well as sets. A repair that only ever wrote values would leave yesterday's wrong
 * IPA on a sense that no longer has one of its own, which is the failure it exists to fix.
 */
object PronunciationBinding {

    /** A card shows one pronunciation; UK first only so it is the same one every time. */
    fun preferred(entries: List<PronunciationEntry>): PronunciationEntry? =
        listOf("uk", "us").firstNotNullOfOrNull { region ->
            entries.firstOrNull { it.region.equals(region, ignoreCase = true) && !it.ipa.isNullOrBlank() }
        }
            ?: entries.firstOrNull { !it.ipa.isNullOrBlank() }
            ?: entries.firstOrNull { !it.audioMp3Url.isNullOrBlank() }

    fun bind(entry: LexicalEntry, aggregate: AggregatedWord): LexicalEntry {
        val raw = aggregate.response
        val definitionsOfSource = entry.sources.associate { it.index to PronunciationVariant.key(it.definition) }

        return entry.copy(
            pronunciations = raw.pronunciations.ifEmpty { entry.pronunciations },
            phonetic = raw.phonetic ?: entry.phonetic,
            audioUrl = raw.audioUrl ?: entry.audioUrl,
            posGroups = entry.posGroups.map { group ->
                bindGroup(group, aggregate, raw.pronunciations, definitionsOfSource)
            },
            pronunciationVersion = PRONUNCIATION_VERSION
        )
    }

    private fun bindGroup(
        group: PosGroup,
        aggregate: AggregatedWord,
        fallback: List<PronunciationEntry>,
        definitionsOfSource: Map<Int, String>
    ): PosGroup {
        val forPos = aggregate.perPosPronunciations[group.pos.lowercase().trim()]
            ?.takeIf { it.isNotEmpty() }
            ?: fallback
        val groupIpa = preferred(forPos)?.ipa

        return group.copy(
            pronunciations = forPos,
            senses = group.senses.map { sense ->
                bindSense(sense, group.pos, groupIpa, aggregate.pronunciationVariants, definitionsOfSource)
            }
        )
    }

    private fun bindSense(
        sense: Sense,
        pos: String,
        groupIpa: String?,
        variants: List<PronunciationVariant>,
        definitionsOfSource: Map<Int, String>
    ): Sense {
        val cleared = sense.copy(phonetic = null, audioUrl = null)
        if (variants.isEmpty() || sense.sourceRefs.isEmpty()) return cleared

        val keys = sense.sourceRefs.mapNotNull { definitionsOfSource[it] }.toSet()
        if (keys.isEmpty()) return cleared

        val variant = bestVariant(keys, pos, variants) ?: return cleared
        val chosen = preferred(variant.pronunciations)
        val ipa = chosen?.ipa?.takeIf { it.isNotBlank() } ?: return cleared

        // The group already says this. Repeating it on the sense would put an identical
        // transcription under every line of the article for no reason.
        if (ipa == groupIpa) return cleared

        return cleared.copy(phonetic = ipa, audioUrl = chosen.audioMp3Url?.takeIf { it.isNotBlank() })
    }

    /**
     * The block that printed most of this sense's evidence.
     *
     * Part of speech only breaks ties: the definition text is the stronger claim, and a sense
     * the model filed under a different label is still the sense that block described.
     */
    private fun bestVariant(
        keys: Set<String>,
        pos: String,
        variants: List<PronunciationVariant>
    ): PronunciationVariant? = variants
        .mapIndexed { index, variant ->
            Triple(
                variant,
                variant.definitionKeys.count { it in keys },
                if (variant.pos == pos.lowercase().trim()) 1 else 0
            ) to index
        }
        .filter { it.first.second > 0 }
        .minWithOrNull(
            compareByDescending<Pair<Triple<PronunciationVariant, Int, Int>, Int>> { it.first.second }
                .thenByDescending { it.first.third }
                .thenBy { it.second }
        )
        ?.first?.first
}
