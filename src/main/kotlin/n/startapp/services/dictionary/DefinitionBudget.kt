package n.startapp.services.dictionary

import n.startapp.models.dictionary.DetailedDefinition

/**
 * How many definitions each source gets to contribute, and which ones.
 *
 * ⚠️ This looks like a truncation limit and is really a decision about which meanings of a
 * homograph the article can have at all. Taking the first N per source gave every slot to
 * whichever part of speech the dictionary printed first: Cambridge lists `lead` the verb before
 * `lead` the noun and gives the verb more than ten senses, so the metal, the pencil and the
 * dog's lead never reached the aggregate — and with them went the only block pronounced /led/.
 *
 * The budget is therefore spent round-robin across headword blocks. Blocks, not parts of speech:
 * `lead` the metal and `lead` the front are both nouns, printed under different pronunciations,
 * and a budget spread only by part of speech still gave the noun's whole share to whichever of
 * them came first. Order inside one block is untouched: dictionaries list their most common
 * sense first, and that is information.
 */
object DefinitionBudget {

    fun apply(defs: List<DetailedDefinition>, perSourceLimit: Int): List<DetailedDefinition> {
        val seenPerSource = mutableMapOf<String, MutableSet<String>>()
        val unique = defs.filter { def ->
            val source = def.source?.uppercase() ?: "UNKNOWN"
            val key = def.definition.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim().take(60)
            seenPerSource.getOrPut(source) { mutableSetOf() }.add(key)
        }

        return unique
            .groupBy { it.source?.uppercase() ?: "UNKNOWN" }
            .values
            .flatMap { perSource -> roundRobinByHeadword(perSource).take(perSourceLimit) }
    }

    /** One from each headword block in turn: verb, noun, adjective, verb, noun, … */
    private fun roundRobinByHeadword(defs: List<DetailedDefinition>): List<DetailedDefinition> {
        val buckets = defs
            // A source without blocks (the APIs) falls back to part of speech, which is all it
            // knows — there the two questions are the same question.
            .groupBy { it.partOfSpeech.lowercase().trim() to it.entryIndex }
            .values
            .map { it.toMutableList() }
        if (buckets.size <= 1) return defs

        val result = mutableListOf<DetailedDefinition>()
        while (result.size < defs.size) {
            for (bucket in buckets) if (bucket.isNotEmpty()) result += bucket.removeAt(0)
        }
        return result
    }
}
