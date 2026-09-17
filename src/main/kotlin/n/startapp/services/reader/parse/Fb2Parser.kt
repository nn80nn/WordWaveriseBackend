package n.startapp.services.reader.parse

import n.startapp.exceptions.BadRequestException
import n.startapp.models.reader.BlockKind
import n.startapp.services.reader.ParsedBlock
import n.startapp.services.reader.ParsedBook
import n.startapp.services.reader.ParsedChapter
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.nio.charset.Charset

/**
 * FB2 — one XML file, and the format Russian-language libraries actually hand out.
 *
 * Worth supporting for exactly that reason: the reader's audience already has a shelf of these,
 * and "convert it to EPUB first" is a step most people will not take.
 */
object Fb2Parser {

    private val ENCODING = Regex("""encoding\s*=\s*["']([\w-]+)["']""", RegexOption.IGNORE_CASE)

    fun parse(bytes: ByteArray): ParsedBook {
        val xml = decode(bytes)
        val document = Jsoup.parse(xml, "", Parser.xmlParser())

        val info = document.selectFirst("title-info")
        val title = info?.selectFirst("book-title")?.text()?.trim()
        val author = info?.selectFirst("author")?.let { node ->
            listOfNotNull(
                node.selectFirst("first-name")?.text()?.trim(),
                node.selectFirst("middle-name")?.text()?.trim(),
                node.selectFirst("last-name")?.text()?.trim()
            ).filter { it.isNotEmpty() }.joinToString(" ").ifBlank { null }
        }
        val language = info?.selectFirst("lang")?.text()?.trim()

        // A second <body name="notes"> holds footnotes — parsed separately and appended as a
        // trailing chapter (below) rather than mixed into the reading order, so a link to one
        // has somewhere to land without a hundred numbered fragments interrupting the story.
        val body = document.select("body").firstOrNull { !it.hasAttr("name") }
            ?: document.selectFirst("body")
            ?: throw BadRequestException("FB2: в файле нет текста")

        val sections = body.children().filter { it.tagName().equals("section", ignoreCase = true) }
        val chapters = if (sections.isEmpty()) listOf(chapterOf(body)) else sections.map { chapterOf(it) }
        val notesBody = document.select("body").firstOrNull { it.attr("name").equals("notes", ignoreCase = true) }
        val kept = (chapters + listOfNotNull(notesBody?.let { chapterOf(it, defaultTitle = "Примечания") }))
            .filter { it.blocks.isNotEmpty() }

        if (kept.isEmpty()) throw BadRequestException("FB2: не удалось прочитать текст книги")

        return ParsedBook(
            title = title?.takeIf { it.isNotBlank() } ?: "Без названия",
            author = author,
            language = language?.takeIf { it.isNotBlank() },
            format = "FB2",
            chapters = kept
        )
    }

    private fun chapterOf(section: Element, defaultTitle: String? = null): ParsedChapter {
        val blocks = mutableListOf<ParsedBlock>()
        collect(section, blocks, mutableSetOf(), inTitle = false, inQuote = false)
        val title = blocks.firstOrNull { it.kind == BlockKind.HEADING }?.text ?: defaultTitle
        return ParsedChapter(title = title, blocks = blocks)
    }

    /**
     * [pending] carries an ancestor `<section id="…">`'s id down to the first block emitted from
     * inside it — a footnote's id sits on the `<section>` wrapping its `<p>`, not the `<p>`
     * itself, and a link needs the id to resolve to a block that actually exists.
     */
    private fun collect(
        element: Element,
        into: MutableList<ParsedBlock>,
        pending: MutableSet<String>,
        inTitle: Boolean,
        inQuote: Boolean
    ) {
        for (child in element.children()) {
            child.id().takeIf { it.isNotBlank() }?.let { pending += it }
            when (child.tagName().lowercase()) {
                "title" -> collect(child, into, pending, inTitle = true, inQuote = inQuote)
                "subtitle" -> emit(BlockKind.HEADING, child, into, pending)
                "cite", "epigraph" -> collect(child, into, pending, inTitle = inTitle, inQuote = true)
                "p", "text-author" -> emit(
                    when {
                        inTitle -> BlockKind.HEADING
                        inQuote -> BlockKind.QUOTE
                        else -> BlockKind.PARAGRAPH
                    },
                    child, into, pending
                )
                // A line of verse is a block of its own: joined into a paragraph it stops
                // being verse, and the tokeniser stops seeing where a line ends.
                "v" -> emit(if (inQuote) BlockKind.QUOTE else BlockKind.PARAGRAPH, child, into, pending)
                "empty-line", "image", "binary" -> {}
                else -> collect(child, into, pending, inTitle = inTitle, inQuote = inQuote)
            }
        }
    }

    private fun emit(
        kind: BlockKind,
        element: Element,
        into: MutableList<ParsedBlock>,
        pending: MutableSet<String>
    ) {
        val unit = InlineLinks.walk(element, splitOnBr = false).firstOrNull() ?: return
        val text = TextNormaliser.clean(unit.text)
        if (text.isEmpty()) return
        val anchors = unit.anchorIds + pending
        pending.clear()
        into += ParsedBlock(kind, text, links = InlineLinks.resolve(text, unit.links), anchorIds = anchors)
    }

    /**
     * FB2 predates UTF-8 winning, and windows-1251 files are still handed out today. Decoding
     * one as UTF-8 does not fail — it produces a book of replacement characters, which looks
     * like a broken parser rather than a wrong charset.
     */
    private fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1)
        val declared = ENCODING.find(head)?.groupValues?.get(1)
        val charset = declared
            ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
            ?: Charsets.UTF_8
        return String(bytes, charset)
    }
}
