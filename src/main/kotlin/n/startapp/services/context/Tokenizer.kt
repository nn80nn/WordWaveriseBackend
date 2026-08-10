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
     * Conservative by design: "gave up" is grouped, but so is any word followed by a particle,
     * because deciding whether it is genuinely phrasal needs the sense — which is the analysis
     * step's job. Grouping only affects what gets sent as context, never what gets discarded.
     */
    private fun groupPhrasalVerbs(tokens: List<Token>): List<Token> {
        if (tokens.size < 2) return tokens
        val result = tokens.toMutableList()

        for (i in 0 until tokens.size - 1) {
            val next = tokens[i + 1]
            if (!tokens[i].tappable || !next.tappable) continue
            if (next.text.lowercase() !in PARTICLES) continue
            if (tokens[i].text.lowercase() in PARTICLES) continue

            result[i] = result[i].copy(groupWith = listOf(next.index))
            result[i + 1] = result[i + 1].copy(groupWith = listOf(tokens[i].index))
        }
        return result
    }
}
