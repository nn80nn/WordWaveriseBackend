package n.startapp.services.reader

import n.startapp.models.reader.BlockKind

/**
 * An in-text link, offsets into [ParsedBlock.text], not yet resolved to a block.
 *
 * [targetAnchorId] is whatever the source markup called it (`href="#note3"` → `"note3"`) — a
 * string meaningful only within one book's parse, matched against [ParsedBlock.anchorIds]
 * collected from the very same pass. [BookRepository] resolves it to the target's ordinal once
 * every block has one; a target that never turns up (an anchor outside the book, or one this
 * parser did not capture) simply drops the link rather than pointing it nowhere.
 */
data class ParsedLink(val start: Int, val end: Int, val targetAnchorId: String)

/**
 * What every parser produces, so import knows one shape and not four.
 *
 * [anchorIds] names every jump target that lands on this block — usually none, sometimes one
 * (a footnote's own `id`), and is carried alongside the block rather than as a separate map
 * because a block is the unit [BookRepository] assigns an ordinal to.
 */
data class ParsedBlock(
    val kind: BlockKind,
    val text: String,
    val links: List<ParsedLink> = emptyList(),
    val anchorIds: Set<String> = emptySet()
)

data class ParsedChapter(val title: String?, val blocks: List<ParsedBlock>)

data class ParsedBook(
    val title: String,
    val author: String?,
    val language: String?,
    /** "EPUB" | "FB2" | "PDF" | "TXT" | "HTML" | "PASTE" */
    val format: String,
    val chapters: List<ParsedChapter>
)
