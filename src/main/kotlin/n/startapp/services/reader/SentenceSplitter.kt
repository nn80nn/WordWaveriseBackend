package n.startapp.services.reader

import n.startapp.models.reader.SentenceDTO

/**
 * Cuts a paragraph into sentences.
 *
 * The reader needs this because a tap asks "what does this word mean *here*", and "here" is a
 * sentence. Sending the whole paragraph would work — the model would even read it better — but
 * the answer is cached under the text it was asked about, so paragraph-sized keys would be hit
 * far less often, and `sentenceRu` would come back as a paragraph translation for a request that
 * was about one line.
 *
 * Heuristic, and deliberately conservative: a missed split leaves two sentences joined, which
 * costs a slightly longer prompt, while a wrong split hands the model half a clause and gets a
 * confident answer about a sentence nobody wrote. When the rules disagree, this one does not cut.
 *
 * ⚠️ Sentences are derived on read, never stored ([n.startapp.database.tables.BookBlocks]), so a
 * fix here reaches books imported months ago. That is only true as long as nothing anchors to a
 * sentence index — positions and highlights anchor to the block.
 */
object SentenceSplitter {

    /**
     * Words whose trailing dot is part of the word.
     *
     * Not an attempt at a complete list — there is no complete list. These are the ones that
     * actually occur mid-sentence in prose, which is the only place a wrong split hurts.
     */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "rev", "hon", "gen", "col", "capt",
        "lt", "sgt", "fr", "mt", "ft", "rd", "ave", "blvd", "dept", "est", "fig", "no", "vol",
        "op", "cf", "ca", "approx", "min", "max", "vs", "etc", "inc", "ltd", "co", "corp",
        "univ", "al", "ed", "eds", "pp", "esp", "e.g", "i.e", "a.m", "p.m", "u.s", "u.k"
    )

    /** Closing marks that belong to the sentence they follow: `"Stop!"` ends after the quote. */
    private const val CLOSERS = "\"'”’»)]}"

    fun split(text: String): List<SentenceDTO> {
        val trimmed = text
        if (trimmed.isBlank()) return emptyList()

        val spans = mutableListOf<Pair<Int, Int>>()
        var start = firstNonSpace(trimmed, 0)
        var i = start

        while (i < trimmed.length) {
            val ch = trimmed[i]
            if (ch != '.' && ch != '!' && ch != '?' && ch != '…') { i++; continue }

            if (ch == '.' && isDecimalPoint(trimmed, i)) { i++; continue }
            if (ch == '.' && endsAbbreviation(trimmed, i)) { i++; continue }

            // "?!", "...", "…" — one terminator, however many characters it took.
            var end = i + 1
            while (end < trimmed.length && trimmed[end] in ".!?…") end++
            while (end < trimmed.length && trimmed[end] in CLOSERS) end++

            val next = firstNonSpace(trimmed, end)
            if (next >= trimmed.length) {
                spans += start to end
                start = next
                i = next
                break
            }

            if (!opensSentence(trimmed, next)) { i = end; continue }

            spans += start to end
            start = next
            i = next
        }

        if (start < trimmed.length) {
            val tail = trimEnd(trimmed, start, trimmed.length)
            if (tail > start) spans += start to tail
        }

        // A paragraph with no terminator at all is still one sentence — headings, verse, dialogue
        // punctuated with dashes. Returning nothing would make its words untappable.
        if (spans.isEmpty()) {
            val end = trimEnd(trimmed, 0, trimmed.length)
            val begin = firstNonSpace(trimmed, 0)
            if (end > begin) spans += begin to end
        }

        return spans.mapIndexed { index, (from, to) ->
            SentenceDTO(index = index, text = trimmed.substring(from, to), start = from, end = to)
        }
    }

    private fun opensSentence(text: String, at: Int): Boolean {
        var i = at
        // An opening quote or bracket is part of the next sentence, not evidence against it.
        while (i < text.length && (text[i] in "\"'“‘«([{" || text[i] == '—' || text[i] == '–')) i++
        if (i >= text.length) return false
        val c = text[i]
        // Lowercase after a terminator is nearly always dialogue attribution — `"Stop!" he said.`
        // Anything that is neither a capital nor a digit is left uncut, on the same principle:
        // a join costs a longer prompt, a bad cut costs a confident answer about half a clause.
        return c.isUpperCase() || c.isDigit()
    }

    private fun isDecimalPoint(text: String, at: Int): Boolean =
        at > 0 && at + 1 < text.length && text[at - 1].isDigit() && text[at + 1].isDigit()

    /**
     * True when the dot closes a word that carries one.
     *
     * A single letter counts, which is what keeps "J. R. R. Tolkien" in one piece; the cost is
     * that a sentence genuinely ending in an initial joins the next one, and that sentence is
     * rare enough to be worth trading for every name in the book.
     */
    private fun endsAbbreviation(text: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && (text[i].isLetter() || text[i] == '.')) i--
        val word = text.substring(i + 1, at).lowercase().trimEnd('.')
        if (word.isEmpty()) return false
        if (word.length == 1 && word[0].isLetter()) return true
        return word in ABBREVIATIONS
    }

    private fun firstNonSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun trimEnd(text: String, from: Int, to: Int): Int {
        var i = to
        while (i > from && text[i - 1].isWhitespace()) i--
        return i
    }
}
