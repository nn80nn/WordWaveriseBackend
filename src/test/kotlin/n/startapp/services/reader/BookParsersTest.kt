package n.startapp.services.reader

import n.startapp.models.reader.BlockKind
import n.startapp.services.reader.parse.EpubParser
import n.startapp.services.reader.parse.Fb2Parser
import n.startapp.services.reader.parse.PlainTextParser
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parsers are the only place a book stops being somebody else's file format.
 *
 * What is asserted here is not "it parsed" but the three things the reader is built on: the
 * blocks come out in reading order, the text arrives clean enough for the tokeniser, and the
 * parts of the file that are not the book — footnote bodies, stylesheets, the manifest's own
 * inventory — do not turn into chapters.
 */
class BookParsersTest {

    // ── EPUB ───────────────────────────────────────────────────────────────────────────────

    private fun epub(vararg documents: Pair<String, String>): ByteArray {
        val manifest = documents.mapIndexed { i, (name, _) ->
            """<item id="c$i" href="$name" media-type="application/xhtml+xml"/>"""
        }.joinToString("\n")
        val spine = documents.indices.joinToString("\n") { """<itemref idref="c$it"/>""" }

        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>A Study in Scarlet</dc:title>
                <dc:creator>Arthur Conan Doyle</dc:creator>
                <dc:language>en</dc:language>
              </metadata>
              <manifest>
                $manifest
                <item id="css" href="style.css" media-type="text/css"/>
              </manifest>
              <spine>
                $spine
              </spine>
            </package>
        """.trimIndent()

        val container = """
            <?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", container)
            put("OEBPS/content.opf", opf)
            put("OEBPS/style.css", "p { color: red }")
            for ((name, body) in documents) put("OEBPS/$name", body)
        }
        return out.toByteArray()
    }

    @Test
    fun `epub metadata and reading order survive the round trip`() {
        val book = EpubParser.parse(
            epub(
                "one.xhtml" to "<html><body><h1>Chapter One</h1><p>He was late.</p></body></html>",
                "two.xhtml" to "<html><body><h1>Chapter Two</h1><p>She was not.</p></body></html>"
            )
        )

        assertEquals("A Study in Scarlet", book.title)
        assertEquals("Arthur Conan Doyle", book.author)
        assertEquals("en", book.language)
        assertEquals("EPUB", book.format)
        assertEquals(listOf("Chapter One", "Chapter Two"), book.chapters.map { it.title })
        assertEquals("He was late.", book.chapters[0].blocks[1].text)
    }

    @Test
    fun `the stylesheet in the manifest is not a chapter`() {
        // The manifest is an inventory: covers, styles and back matter live there too. A reader
        // that walked it instead of the spine would open the book on its stylesheet.
        val book = EpubParser.parse(
            epub("one.xhtml" to "<html><body><p>Only this.</p></body></html>")
        )
        assertEquals(1, book.chapters.size)
        assertFalse(book.chapters.first().blocks.any { it.text.contains("color") })
    }

    @Test
    fun `a quote holding paragraphs is emitted once, as its paragraphs`() {
        val book = EpubParser.parse(
            epub(
                "one.xhtml" to
                    "<html><body><blockquote><p>Borrowed words.</p></blockquote></body></html>"
            )
        )
        val blocks = book.chapters.first().blocks
        assertEquals(1, blocks.size)
        assertEquals("Borrowed words.", blocks.first().text)
        assertEquals(BlockKind.QUOTE, blocks.first().kind)
    }

    @Test
    fun `line breaks split verse into lines`() {
        val book = EpubParser.parse(
            epub("one.xhtml" to "<html><body><p>Tyger Tyger<br/>burning bright</p></body></html>")
        )
        assertEquals(
            listOf("Tyger Tyger", "burning bright"),
            book.chapters.first().blocks.map { it.text }
        )
    }

    // ── FB2 ────────────────────────────────────────────────────────────────────────────────

    private fun fb2(body: String, encoding: String = "UTF-8"): ByteArray {
        val xml = """
            <?xml version="1.0" encoding="$encoding"?>
            <FictionBook>
              <description>
                <title-info>
                  <author><first-name>Лев</first-name><last-name>Толстой</last-name></author>
                  <book-title>Война и мир</book-title>
                  <lang>ru</lang>
                </title-info>
              </description>
              $body
            </FictionBook>
        """.trimIndent()
        return xml.toByteArray(charset(encoding))
    }

    @Test
    fun `fb2 metadata and sections become chapters`() {
        val book = Fb2Parser.parse(
            fb2(
                """
                <body>
                  <section><title><p>Часть первая</p></title><p>Всё смешалось.</p></section>
                  <section><title><p>Часть вторая</p></title><p>И снова.</p></section>
                </body>
                """
            )
        )

        assertEquals("Война и мир", book.title)
        assertEquals("Лев Толстой", book.author)
        assertEquals("FB2", book.format)
        assertEquals(listOf("Часть первая", "Часть вторая"), book.chapters.map { it.title })
        assertEquals(BlockKind.HEADING, book.chapters.first().blocks.first().kind)
    }

    @Test
    fun `the notes body is not appended to the book`() {
        // A second <body name="notes"> holds footnotes; read as a chapter it adds a hundred
        // numbered fragments to the end of the novel.
        val book = Fb2Parser.parse(
            fb2(
                """
                <body><section><p>Текст книги.</p></section></body>
                <body name="notes"><section><p>1. Примечание.</p></section></body>
                """
            )
        )
        assertEquals(1, book.chapters.size)
        assertFalse(book.chapters.first().blocks.any { it.text.contains("Примечание") })
    }

    @Test
    fun `a windows-1251 file is not read as replacement characters`() {
        // Decoding this as UTF-8 does not throw — it produces a book of question marks, which
        // reads as a broken parser rather than as a wrong charset.
        val book = Fb2Parser.parse(
            fb2("<body><section><p>Проверка кодировки.</p></section></body>", encoding = "windows-1251")
        )
        assertEquals("Проверка кодировки.", book.chapters.first().blocks.first().text)
    }

    @Test
    fun `verse lines stay separate blocks`() {
        val book = Fb2Parser.parse(
            fb2("<body><section><poem><stanza><v>Мороз и солнце</v><v>день чудесный</v></stanza></poem></section></body>")
        )
        assertEquals(
            listOf("Мороз и солнце", "день чудесный"),
            book.chapters.first().blocks.map { it.text }
        )
    }

    // ── Plain text ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `blank lines separate paragraphs and single newlines do not`() {
        val book = PlainTextParser.parse(
            "He was late\nfor the train.\n\nShe was not.",
            title = "Notes"
        )
        val blocks = book.chapters.flatMap { it.blocks }
        assertEquals(listOf("He was late for the train.", "She was not."), blocks.map { it.text })
    }

    @Test
    fun `a chapter line starts a chapter`() {
        val book = PlainTextParser.parse(
            "Chapter I\n\nHe was late.\n\nChapter II\n\nShe was not.",
            title = "Book"
        )
        assertEquals(2, book.chapters.size)
        assertEquals(listOf("Chapter I", "Chapter II"), book.chapters.map { it.title })
        assertEquals(BlockKind.HEADING, book.chapters.first().blocks.first().kind)
    }

    @Test
    fun `an ordinary paragraph is never mistaken for a heading`() {
        // A missed heading is a paragraph nobody notices; a false one splits the book at a
        // sentence and puts half of it in the table of contents.
        val book = PlainTextParser.parse(
            "The chapter he wanted was missing.\n\nHe looked again.",
            title = "Book"
        )
        assertEquals(1, book.chapters.size)
        assertTrue(book.chapters.first().blocks.none { it.kind == BlockKind.HEADING })
    }

    @Test
    fun `pasted text names itself from its first line`() {
        val book = PlainTextParser.parse("Some English I found somewhere today.", title = null)
        assertTrue(book.title.startsWith("Some English I found"))
    }
}
