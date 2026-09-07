package n.startapp.services.reader.parse

/**
 * One pass over every string that becomes block text, whatever parsed it.
 *
 * The reader's tokeniser splits on whitespace and trims a fixed set of punctuation, so characters
 * that merely *look* like a space, or are invisible altogether, reach it as part of a word: a
 * non-breaking space glues two words into one untappable token, and a soft hyphen left over from
 * a typeset export puts an invisible character inside the lemma we would go on to look up.
 *
 * Written as escapes rather than as the characters themselves on purpose: the whole point of
 * these characters is that they are indistinguishable from ordinary text on screen, and a source
 * file is a screen too.
 */
object TextNormaliser {

    /** Soft hyphen, zero-width space / non-joiner / joiner, byte-order mark. */
    private val INVISIBLE = Regex("[\u00AD\u200B\u200C\u200D\uFEFF]")

    /** Every kind of space collapses to one, including those Java does not call whitespace. */
    private val COLLAPSE = Regex("[\\s\u00A0\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u202F\u205F\u3000]+")

    fun clean(raw: String): String = raw
        .replace(INVISIBLE, "")
        .replace(COLLAPSE, " ")
        .trim()
}
