package n.startapp.services.reader.parse

import n.startapp.services.reader.ParsedLink
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Captures `<a href="#x">`/FB2 `<a l:href="#x">` spans and `id` anchors while a parser walks an
 * element's children — the one place both [HtmlBlocks] and [Fb2Parser] need the same thing, so
 * neither has its own copy to drift from the other.
 *
 * Offsets are recovered by search, not carried through cleaning. [TextNormaliser.clean] collapses
 * whitespace and trims, so an offset measured on the raw walk would not point at the same
 * character once the block's final text exists — resolving after the fact avoids maintaining a
 * raw→cleaned index map for a normalisation step this is the only caller of.
 */
object InlineLinks {

    /** A link as met during the walk: the raw text inside it, and where it points. */
    data class RawLink(val text: String, val targetAnchorId: String)

    /** One accumulated stretch of raw text — one per `<br>`-separated line, or the whole element. */
    data class Unit(val text: String, val links: List<RawLink>, val anchorIds: Set<String>)

    /**
     * Walks [element]'s children, splitting into a new [Unit] at each `<br>` when [splitOnBr] is
     * set (HTML verse and line breaks) and producing exactly one otherwise (FB2, whose lines are
     * already separate `<v>` elements). An `id` on any descendant attaches to whichever unit is
     * open when the walk reaches it — a heading split by `<br>` still resolves to its first line,
     * which is the block a jump actually lands on.
     */
    fun walk(element: Element, splitOnBr: Boolean): List<Unit> {
        val units = mutableListOf<Unit>()
        var text = StringBuilder()
        var links = mutableListOf<RawLink>()
        var anchors = mutableSetOf<String>()

        fun flush() {
            units += Unit(text.toString(), links.toList(), anchors.toSet())
            text = StringBuilder()
            links = mutableListOf()
            anchors = mutableSetOf()
        }

        fun walkNode(node: Node) {
            when {
                node is TextNode -> text.append(node.text())
                node is Element && splitOnBr && node.tagName().equals("br", ignoreCase = true) -> flush()
                node is Element -> {
                    node.id().takeIf { it.isNotBlank() }?.let { anchors += it }
                    val target = if (node.tagName().equals("a", ignoreCase = true)) hrefTarget(node) else null
                    if (target != null) {
                        val start = text.length
                        node.childNodes().forEach { walkNode(it) }
                        links += RawLink(text.substring(start), target)
                    } else {
                        node.childNodes().forEach { walkNode(it) }
                    }
                }
            }
        }

        element.childNodes().forEach { walkNode(it) }
        flush()
        return units
    }

    /**
     * The id after the `#` of a link's own href, whatever its href attribute is actually called.
     *
     * Plain HTML/EPUB always uses `href`; FB2 uses the XLink namespace, and different exporters
     * declare its prefix differently (`l:href`, `xlink:href`) — matching by suffix reads all of
     * them without hard-coding one. A link elsewhere in the same book (no `#`) is not an anchor
     * this parser can resolve, so it is not treated as one.
     */
    private fun hrefTarget(anchor: Element): String? {
        val href = anchor.attr("href").ifBlank {
            anchor.attributes().firstOrNull { it.key.substringAfter(':').equals("href", ignoreCase = true) }?.value
        } ?: return null
        return href.takeIf { it.startsWith("#") }?.substring(1)?.takeIf { it.isNotBlank() }
    }

    /**
     * Resolves raw link text onto the block's already-cleaned text, in the order the links were
     * met. A forward-only cursor — rather than a plain `indexOf` from zero each time — is what
     * makes repeated link text (two footnote markers reading the same digit) resolve to their own
     * occurrence instead of both collapsing onto the first.
     */
    fun resolve(cleaned: String, links: List<RawLink>): List<ParsedLink> {
        var cursor = 0
        val resolved = mutableListOf<ParsedLink>()
        for (link in links) {
            val needle = TextNormaliser.clean(link.text)
            if (needle.isEmpty()) continue
            val at = cleaned.indexOf(needle, cursor)
            if (at < 0) continue
            resolved += ParsedLink(at, at + needle.length, link.targetAnchorId)
            cursor = at + needle.length
        }
        return resolved
    }
}
