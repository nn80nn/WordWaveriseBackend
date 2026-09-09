package n.startapp.services.reader

import n.startapp.models.reader.BlockKind

/** What every parser produces, so import knows one shape and not four. */
data class ParsedBlock(val kind: BlockKind, val text: String)

data class ParsedChapter(val title: String?, val blocks: List<ParsedBlock>)

data class ParsedBook(
    val title: String,
    val author: String?,
    val language: String?,
    /** "EPUB" | "FB2" | "PDF" | "TXT" | "HTML" | "PASTE" */
    val format: String,
    val chapters: List<ParsedChapter>
)
