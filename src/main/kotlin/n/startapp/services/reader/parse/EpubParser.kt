package n.startapp.services.reader.parse

import n.startapp.exceptions.BadRequestException
import n.startapp.models.reader.BlockKind
import n.startapp.services.reader.ParsedBook
import n.startapp.services.reader.ParsedChapter
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLDecoder

/**
 * EPUB, read with `java.util.zip` and jsoup and nothing else.
 *
 * An EPUB library would be one line in the build file and a second netty in the fat JAR — the
 * failure this project has already had once, where the server came up and then died on every
 * connection. The format does not earn a dependency: it is a zip holding a manifest that points
 * at XHTML files, and both halves are already on the classpath.
 */
object EpubParser {

    private val XHTML_TYPES = setOf(
        "application/xhtml+xml", "text/html", "application/x-dtbook+xml"
    )

    fun parse(bytes: ByteArray): ParsedBook {
        val entries = ZipArchive.read(bytes)

        val opfPath = rootFilePath(entries)
        val opfBytes = entries[opfPath] ?: throw BadRequestException("EPUB: не найден OPF-файл")
        val opf = Jsoup.parse(String(opfBytes, Charsets.UTF_8), "", Parser.xmlParser())

        val title = opf.selectFirst("metadata > *|title")?.text()?.trim()
            ?: opf.selectFirst("title")?.text()?.trim()
        val author = opf.selectFirst("metadata > *|creator")?.text()?.trim()
            ?: opf.selectFirst("creator")?.text()?.trim()
        val language = opf.selectFirst("metadata > *|language")?.text()?.trim()
            ?: opf.selectFirst("language")?.text()?.trim()

        val baseDir = opfPath.substringBeforeLast('/', "")
        val manifest = opf.select("manifest > item").associate { item ->
            item.attr("id") to Pair(item.attr("href"), item.attr("media-type").lowercase())
        }

        // The spine is the reading order the publisher chose. The manifest is only an inventory:
        // covers, styles and back-matter live there too, and a reader that walked it would open
        // the book on its stylesheet.
        val spine = opf.select("spine > itemref").mapNotNull { manifest[it.attr("idref")] }
            .filter { (_, mediaType) -> mediaType in XHTML_TYPES }

        val chapters = spine.mapNotNull { (href, _) ->
            val path = resolve(baseDir, href)
            val data = entries[path] ?: entries[path.removePrefix("/")] ?: return@mapNotNull null
            chapterOf(String(data, Charsets.UTF_8))
        }.filter { it.blocks.isNotEmpty() }

        if (chapters.isEmpty()) throw BadRequestException("EPUB: не удалось прочитать текст книги")

        return ParsedBook(
            title = title?.takeIf { it.isNotBlank() } ?: "Без названия",
            author = author?.takeIf { it.isNotBlank() },
            language = language?.takeIf { it.isNotBlank() },
            format = "EPUB",
            chapters = chapters
        )
    }

    private fun chapterOf(html: String): ParsedChapter {
        val document = Jsoup.parse(html)
        val body = document.body()
        val blocks = HtmlBlocks.extract(body)

        // The chapter's own first heading is its name. A table of contents would be more
        // authoritative, but plenty of EPUBs ship one that disagrees with the text, and the
        // heading the reader is about to see is the one they will recognise in a list.
        val title = blocks.firstOrNull { it.kind == BlockKind.HEADING }?.text
            ?: document.title().takeIf { it.isNotBlank() }

        return ParsedChapter(title = title, blocks = blocks)
    }

    private fun rootFilePath(entries: Map<String, ByteArray>): String {
        val container = entries["META-INF/container.xml"]
            ?: throw BadRequestException("EPUB: нет META-INF/container.xml")
        val path = Jsoup.parse(String(container, Charsets.UTF_8), "", Parser.xmlParser())
            .selectFirst("rootfile")?.attr("full-path")
            ?.takeIf { it.isNotBlank() }
            ?: entries.keys.firstOrNull { it.endsWith(".opf") }
            ?: throw BadRequestException("EPUB: не найден OPF-файл")
        return decode(path)
    }

    /** Manifest hrefs are relative to the OPF, URL-encoded, and may climb with `../`. */
    private fun resolve(baseDir: String, href: String): String {
        val clean = decode(href.substringBefore('#'))
        val parts = ArrayDeque<String>()
        if (baseDir.isNotEmpty()) parts.addAll(baseDir.split('/'))
        for (segment in clean.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
