package n.startapp.services.reader.parse

import n.startapp.exceptions.BadRequestException
import n.startapp.models.reader.BlockKind
import n.startapp.services.reader.ParsedBlock
import n.startapp.services.reader.ParsedBook
import n.startapp.services.reader.ParsedChapter

/**
 * Plain text: a pasted passage, or the .txt half of Project Gutenberg.
 *
 * This is also the shortest path the product has from "I have some English in front of me" to
 * reading it with the dictionary attached — no file, no format, no library. Everything else in
 * this package exists to reach the same place from a book someone already owns.
 */
object PlainTextParser {

    /** A blank line is a paragraph break; a single newline inside a paragraph is a wrap. */
    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n+")

    /**
     * Lines that announce a chapter, in the two languages this reader serves.
     *
     * Deliberately narrow. A heading missed is a paragraph in bold that nobody notices; a
     * paragraph mistaken for a heading splits the book at a sentence and puts half of it in
     * the table of contents.
     */
    private val CHAPTER_LINE = Regex(
        """^\s*(chapter|part|book|глава|часть|книга)\s+([\dIVXLCM]+|[a-zа-я]+)\s*\.?\s*$""",
        setOf(RegexOption.IGNORE_CASE)
    )

    fun parse(text: String, title: String?, author: String? = null, format: String = "TXT"): ParsedBook {
        val normalised = text.replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = normalised.split(PARAGRAPH_BREAK)
            .map { TextNormaliser.clean(it) }
            .filter { it.isNotEmpty() }

        if (paragraphs.isEmpty()) throw BadRequestException("В тексте нечего читать")

        val chapters = mutableListOf<ParsedChapter>()
        var current = mutableListOf<ParsedBlock>()
        var currentTitle: String? = null

        for (paragraph in paragraphs) {
            if (CHAPTER_LINE.matches(paragraph)) {
                if (current.isNotEmpty()) chapters += ParsedChapter(currentTitle, current)
                current = mutableListOf(ParsedBlock(BlockKind.HEADING, paragraph))
                currentTitle = paragraph
            } else {
                current += ParsedBlock(BlockKind.PARAGRAPH, paragraph)
            }
        }
        if (current.isNotEmpty()) chapters += ParsedChapter(currentTitle, current)

        return ParsedBook(
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: firstLineAsTitle(paragraphs.first()),
            author = author?.trim()?.takeIf { it.isNotBlank() },
            language = null,
            format = format,
            chapters = chapters
        )
    }

    /**
     * Pasted text has no title, and demanding one before reading would put a form in front of
     * the one flow that is supposed to have none. The opening words are a good enough name for
     * something the reader will recognise by its first line anyway.
     */
    private fun firstLineAsTitle(first: String): String {
        val words = first.split(' ').take(8).joinToString(" ")
        return if (words.length < first.length) "$words..." else words
    }
}
