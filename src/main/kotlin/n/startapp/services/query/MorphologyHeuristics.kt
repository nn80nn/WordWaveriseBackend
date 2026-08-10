package n.startapp.services.query

/**
 * Guesses the dictionary form of a regularly inflected English word.
 *
 * Deliberately over-generates: every candidate is confirmed against a real word list before
 * being used, so a wrong guess costs nothing while a missing one costs an LLM call. English
 * irregulars (geese, went, better) are not handled here — they fall through to the model.
 */
object MorphologyHeuristics {

    private const val VOWELS = "aeiou"

    /** Candidate lemmas, most likely first. */
    fun candidates(word: String): List<String> {
        val w = word.trim().lowercase()
        if (w.length < 3) return emptyList()

        val out = LinkedHashSet<String>()

        fun add(candidate: String) {
            if (candidate.length >= 2 && candidate != w) out += candidate
        }

        when {
            w.endsWith("ies") && w.length > 4 -> { add(w.dropLast(3) + "y"); add(w.dropLast(2)) }
            w.endsWith("ied") && w.length > 4 -> { add(w.dropLast(3) + "y"); add(w.dropLast(1)) }
            w.endsWith("ier") && w.length > 4 -> add(w.dropLast(3) + "y")
            w.endsWith("iest") && w.length > 5 -> add(w.dropLast(4) + "y")
            w.endsWith("ily") && w.length > 4 -> add(w.dropLast(3) + "y")
        }

        if (w.endsWith("es") && w.length > 3) {
            add(w.dropLast(2))          // boxes → box, wishes → wish
            add(w.dropLast(1))          // names → name
        }
        if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && w.length > 3) {
            add(w.dropLast(1))
        }

        if (w.endsWith("ing") && w.length > 5) {
            val stem = w.dropLast(3)
            add(stem)                   // walking → walk
            add(stem + "e")             // making → make
            undoubled(stem)?.let(::add) // running → run
        }

        if (w.endsWith("ed") && w.length > 4) {
            val stem = w.dropLast(2)
            add(stem)                   // walked → walk
            add(w.dropLast(1))          // liked → like
            undoubled(stem)?.let(::add) // stopped → stop
        }

        if (w.endsWith("er") && w.length > 4) {
            add(w.dropLast(2))
            add(w.dropLast(1))
            undoubled(w.dropLast(2))?.let(::add)   // bigger → big
        }
        if (w.endsWith("est") && w.length > 5) {
            add(w.dropLast(3))
            add(w.dropLast(2))
            undoubled(w.dropLast(3))?.let(::add)   // biggest → big
        }
        if (w.endsWith("ly") && w.length > 4) add(w.dropLast(2))

        return out.toList()
    }

    /** "runn" → "run": strips a consonant doubled before a suffix. */
    private fun undoubled(stem: String): String? {
        if (stem.length < 3) return null
        val last = stem.last()
        if (last != stem[stem.length - 2]) return null
        if (last in VOWELS) return null
        return stem.dropLast(1)
    }
}
