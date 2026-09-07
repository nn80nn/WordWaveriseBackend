package n.startapp.services.reader.parse

import n.startapp.exceptions.BadRequestException
import n.startapp.models.reader.BlockKind
import n.startapp.services.reader.ParsedBook
import n.startapp.services.reader.ParsedChapter
import org.jsoup.Jsoup

/**
 * A saved web page or a pasted article.
 *
 * The extraction is a heuristic and admits it: pick the element carrying the most paragraph text
 * and read that. Nothing here fetches a URL — the server taking an address from a client and
 * requesting it is a different feature with a different threat model, and the reader does not
 * need it to be useful.
 */
object HtmlDocumentParser {

    fun parse(html: String, fallbackTitle: String? = null): ParsedBook {
        val document = Jsoup.parse(html)
        document.select("script, style, noscript, header, footer, nav, aside, form").remove()

        val root = document.selectFirst("article")
            ?: document.select("main, div, section")
                .maxByOrNull { element -> element.select("p").sumOf { it.text().length } }
            ?: document.body()
            ?: throw BadRequestException("В документе нечего читать")

        val blocks = HtmlBlocks.extract(root)
        if (blocks.isEmpty()) throw BadRequestException("В документе нечего читать")

        val title = document.title().trim().takeIf { it.isNotBlank() }
            ?: fallbackTitle
            ?: blocks.firstOrNull { it.kind == BlockKind.HEADING }?.text

        return ParsedBook(
            title = title?.takeIf { it.isNotBlank() } ?: "Без названия",
            author = null,
            language = document.selectFirst("html")?.attr("lang")?.takeIf { it.isNotBlank() },
            format = "HTML",
            chapters = listOf(ParsedChapter(title = title, blocks = blocks))
        )
    }
}
