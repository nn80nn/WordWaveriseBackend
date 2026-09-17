package n.startapp.services.context

import kotlinx.serialization.Serializable

@Serializable
data class Token(
    val index: Int,
    /** Surface form with original casing, punctuation stripped. */
    val text: String,
    /** Character offsets into the original string, so the client can highlight in place. */
    val start: Int,
    val end: Int,
    /** False for punctuation and numerals — nothing to look up. */
    val tappable: Boolean,
    /** Indices this token forms a single lexical unit with, e.g. "gave" + "up". */
    val groupWith: List<Int> = emptyList()
)

@Serializable
data class TokenizedText(
    val text: String,
    val tokens: List<Token> = emptyList()
)

/**
 * Splits a sentence into tappable words.
 *
 * Server-side on purpose. The token index the client sends back has to refer to the same
 * tokenisation the server used to build the analysis prompt; two implementations (Kotlin and
 * TypeScript) would drift, and the drift shows up as "you tapped 'lead' and got 'the' explained".
 * It also costs nothing, since the result rides along inside the lookup response.
 */
object Tokenizer {

    /** Particles that turn a preceding verb into a phrasal verb worth looking up as one unit. */
    private val PARTICLES = setOf(
        "up", "down", "out", "off", "in", "on", "away", "back", "over",
        "through", "along", "around", "apart", "aside", "forward"
    )

    /**
     * Base-form "verb particle" pairs, loaded once from `resources/wordlists/phrasal_verbs.txt`.
     *
     * Several of these particles double as ordinary prepositions — "on", "in", "over" follow a
     * bare noun constantly ("book **on** the table"), not just a phrasal verb. A whitelist is
     * what actually decides "is this the object of a preposition or a phrasal verb", something
     * [PARTICLES] alone cannot: it only knows the word is particle-shaped.
     */
    private val PHRASAL_VERBS: Set<String> =
        javaClass.getResourceAsStream("/wordlists/phrasal_verbs.txt")
            ?.bufferedReader()
            ?.readLines()
            .orEmpty()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toSet()

    /**
     * The handful of irregular verbs common enough in phrasal verbs to be worth naming outright.
     *
     * Regular endings (`-s`, `-ed`, `-ing`) are handled by stripping and re-trying below; an
     * irregular past tense ("gave", "came", "took") is not derivable that way, and these are
     * exactly the verbs a reader runs into constantly ("gave up", "came back", "took off").
     */
    private val IRREGULAR_BASE = mapOf(
        "gave" to "give", "given" to "give",
        "came" to "come",
        "went" to "go", "gone" to "go",
        "took" to "take", "taken" to "take",
        "brought" to "bring",
        "caught" to "catch",
        "sat" to "sit",
        "stood" to "stand",
        "held" to "hold",
        "kept" to "keep",
        "spoke" to "speak", "spoken" to "speak",
        "broke" to "break", "broken" to "break",
        "began" to "begin", "begun" to "begin",
        "grew" to "grow", "grown" to "grow",
        "woke" to "wake", "woken" to "wake",
        "threw" to "throw", "thrown" to "throw",
        "wrote" to "write", "written" to "write",
        "fell" to "fall", "fallen" to "fall",
        "got" to "get", "gotten" to "get",
        "did" to "do", "done" to "do",
        "ran" to "run",
        "won" to "win",
        "fought" to "fight",
        "tore" to "tear", "torn" to "tear",
        "wore" to "wear", "worn" to "wear",
        "drove" to "drive", "driven" to "drive",
        "rang" to "ring", "rung" to "ring",
        "sold" to "sell",
        "told" to "tell",
        "found" to "find",
        "hung" to "hang",
        "knew" to "know", "known" to "know",
        "blew" to "blow", "blown" to "blow"
    )

    private const val TRIM_CHARS = ".,!?;:\"'()[]{}—–…«»“”„‘’"

    fun tokenize(text: String): TokenizedText {
        val tokens = mutableListOf<Token>()
        var cursor = 0
        var index = 0

        for (chunk in text.split(Regex("\\s+"))) {
            if (chunk.isEmpty()) continue
            val chunkStart = text.indexOf(chunk, cursor)
            if (chunkStart < 0) continue
            cursor = chunkStart + chunk.length

            // Keep internal apostrophes (don't) and hyphens (well-known) — they are part of the word.
            val core = chunk.trim { it in TRIM_CHARS }
            if (core.isEmpty()) continue

            val offset = chunk.indexOf(core)
            val start = chunkStart + offset

            tokens += Token(
                index = index++,
                text = core,
                start = start,
                end = start + core.length,
                tappable = core.any { it.isLetter() }
            )
        }

        return TokenizedText(text = text, tokens = groupPhrasalVerbs(tokens))
    }

    /**
     * Links a verb to a following particle so both highlight and resolve together.
     *
     * Whitelisted, not merely particle-shaped: "on", "in" and "over" introduce an ordinary noun's
     * prepositional phrase at least as often as they complete a phrasal verb ("book **on** the
     * table" vs. "look **on**"), and nothing about the tokens alone tells them apart. The
     * whitelist is what does — it only fires for pairs that are actually phrasal verbs.
     */
    private fun groupPhrasalVerbs(tokens: List<Token>): List<Token> {
        if (tokens.size < 2) return tokens
        val result = tokens.toMutableList()

        for (i in 0 until tokens.size - 1) {
            val next = tokens[i + 1]
            if (!tokens[i].tappable || !next.tappable) continue
            val particle = next.text.lowercase()
            if (particle !in PARTICLES) continue
            if (tokens[i].text.lowercase() in PARTICLES) continue
            if (!isPhrasalVerb(tokens[i].text, particle)) continue

            result[i] = result[i].copy(groupWith = listOf(next.index))
            result[i + 1] = result[i + 1].copy(groupWith = listOf(tokens[i].index))
        }
        return result
    }

    private fun isPhrasalVerb(verb: String, particle: String): Boolean =
        baseForms(verb).any { "$it $particle" in PHRASAL_VERBS }

    /**
     * Every base form [word] could plausibly stem from, loosest first.
     *
     * Not a real lemmatiser — just enough to match "gives"/"giving"/"gave" back to "give"
     * against a whitelist of known bases. Over-generating candidates is harmless here: a wrong
     * guess that happens to collide with an unrelated whitelist entry would still have to pair
     * with the exact particle that follows it in the text, which is what actually gates grouping.
     */
    private fun baseForms(word: String): List<String> {
        val lower = word.lowercase()
        IRREGULAR_BASE[lower]?.let { return listOf(it) }

        val forms = mutableListOf(lower)
        for (suffix in listOf("ing", "ed", "es", "s")) {
            if (lower.length <= suffix.length + 1 || !lower.endsWith(suffix)) continue
            val stripped = lower.dropLast(suffix.length)
            forms += stripped
            forms += "${stripped}e" // giving -> giv -> give
            if (stripped.length >= 2 && stripped.last() == stripped[stripped.length - 2]) {
                forms += stripped.dropLast(1) // running -> runn -> run
            }
        }
        return forms
    }
}
