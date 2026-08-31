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
 * The budget is therefore spent round-robin across parts of speech. Order inside one part of
 * speech is untouched: dictionaries list their most common sense first, and that is information.
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
            .flatMap { perSource -> roundRobinByPartOfSpeech(perSource).take(perSourceLimit) }
    }

    /** noun, verb, adjective, noun, verb, … — one from each in turn. */
    private fun roundRobinByPartOfSpeech(defs: List<DetailedDefinition>): List<DetailedDefinition> {
        val buckets = defs
            .groupBy { it.partOfSpeech.lowercase().trim() }
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
