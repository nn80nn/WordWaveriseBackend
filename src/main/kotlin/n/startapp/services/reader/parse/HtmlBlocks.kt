package n.startapp.services.reader.parse

import n.startapp.models.reader.BlockKind
import n.startapp.services.reader.ParsedBlock
import org.jsoup.nodes.Element

/**
 * Turns a chunk of XHTML into blocks, keeping only what a reader renders.
 *
 * Everything about the source markup that is not "this is a paragraph, this is a heading" is
 * dropped on purpose. Styling belongs to the reader's theme, and a book that arrives carrying
 * its own fonts and colours is a book that will look wrong in dark mode on somebody's phone.
 */
object HtmlBlocks {

    private val BLOCK_TAGS = listOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li", "pre", "dd", "dt", "figcaption"
    )
    private val BLOCK_SELECTOR = BLOCK_TAGS.joinToString(", ")

    fun extract(root: Element): List<ParsedBlock> {
        root.select("script, style, nav, table").remove()

        val blocks = mutableListOf<ParsedBlock>()
        collect(root, blocks)

        // Some producers wrap every paragraph in a bare <div> and use no <p> at all; others put
        // the whole chapter in one element. Falling back to the root's text keeps both readable.
        if (blocks.isEmpty()) emit(BlockKind.PARAGRAPH, root, blocks)

        return blocks
    }

    private fun collect(element: Element, into: MutableList<ParsedBlock>) {
        for (child in element.children()) {
            val tag = child.tagName().lowercase()
            val isBlock = tag in BLOCK_TAGS
            // A <blockquote> holding <p>s is a container, not a block: the paragraphs win, or
            // the quote would be emitted once whole and then again line by line.
            //
            // ⚠️ `select` starts at the element itself, so a bare <p> matches its own
            // selector. Left unfiltered, every block recursed into itself, emitted nothing, and
            // the whole chapter fell through to the "no blocks found" fallback as one paragraph
            // — headings included, which is how a book arrives with no chapter titles.
            val hasBlockDescendant = child.select(BLOCK_SELECTOR).any { it !== child }

            when {
                isBlock && !hasBlockDescendant -> emit(kindOf(tag, child), child, into)
                isBlock -> collect(child, into)
                child.children().isEmpty() -> emit(BlockKind.PARAGRAPH, child, into)
                else -> collect(child, into)
            }
        }
    }

    private fun kindOf(tag: String, element: Element): BlockKind = when {
        tag.length == 2 && tag[0] == 'h' && tag[1].isDigit() -> BlockKind.HEADING
        tag == "blockquote" -> BlockKind.QUOTE
        tag == "li" -> BlockKind.LIST_ITEM
        element.parent()?.tagName()?.lowercase() == "blockquote" -> BlockKind.QUOTE
        else -> BlockKind.PARAGRAPH
    }

    /**
     * One `<br>`-separated line is one block: verse read as a paragraph is not verse.
     *
     * ⚠️ A block-level `id` (`<p id="note3">`) belongs to the whole element, not to any one
     * `<br>`-split line — it is attached to the first line's unit, since that is the block a
     * jump to it actually lands on.
     */
    private fun emit(kind: BlockKind, element: Element, into: MutableList<ParsedBlock>) {
        val blockAnchor = element.id().takeIf { it.isNotBlank() }
        InlineLinks.walk(element, splitOnBr = true).forEachIndexed { i, unit ->
            val text = TextNormaliser.clean(unit.text)
            if (text.isEmpty()) return@forEachIndexed
            val anchors = if (i == 0 && blockAnchor != null) unit.anchorIds + blockAnchor else unit.anchorIds
            into += ParsedBlock(kind, text, links = InlineLinks.resolve(text, unit.links), anchorIds = anchors)
        }
    }
}
