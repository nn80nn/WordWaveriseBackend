package n.startapp.services.reader

import n.startapp.exceptions.BadRequestException
import n.startapp.models.reader.BlockKind
import n.startapp.services.reader.parse.PdfParser
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PDF — единственный формат, где абзаца в файле нет.
 *
 * EPUB, FB2 и HTML говорят про абзац прямо, тегом. В PDF есть только строки, разложенные по
 * листу, и всё, что здесь проверяется, — это склейка их обратно: без колонтитулов, без номеров
 * страниц, без разрыва слова, перенесённого по слогам. Ошибка в любом из этих мест не роняет
 * импорт, а тихо портит книгу — читатель видит мусор в тексте и думает, что сломан файл.
 */
class PdfImportTest {

    /** Страница, набранная строка за строкой, — ровно то, что отдаёт вёрстка PDF. */
    private fun pdfOf(vararg pages: List<String>): ByteArray {
        val document = PDDocument()
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        for (lines in pages) {
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(font, 11f)
                content.setLeading(14f)
                content.newLineAtOffset(50f, 750f)
                for (line in lines) {
                    content.showText(line)
                    content.newLine()
                }
                content.endText()
            }
        }
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    private fun textOf(book: ParsedBook): List<String> =
        book.chapters.flatMap { chapter -> chapter.blocks.map { it.text } }

    @Test
    fun `lines of one paragraph come back as one paragraph`() {
        val book = PdfParser.parse(
            pdfOf(
                listOf(
                    "It was a bright cold day in April, and the clocks were",
                    "striking thirteen. Winston Smith, his chin nuzzled into",
                    "his breast, slipped quickly through the glass doors.",
                    "",
                    "The hallway smelt of boiled cabbage and old rag mats."
                )
            ),
            fallbackTitle = "1984"
        )

        val paragraphs = textOf(book)
        assertTrue(
            paragraphs.first().startsWith("It was a bright cold day") &&
                paragraphs.first().endsWith("through the glass doors."),
            "строки одного абзаца обязаны склеиться: $paragraphs"
        )
        // Короткая строка кончает абзац — следующий начинается своим предложением.
        assertTrue(paragraphs.any { it.startsWith("The hallway smelt") }, "второй абзац: $paragraphs")
    }

    /** ⚠️ Перенос по слогам: «prekras-\nный» ищется в словаре как одно слово или никак. */
    @Test
    fun `a word broken across lines is put back together`() {
        val book = PdfParser.parse(
            pdfOf(
                listOf(
                    "The identification existed and was entirely unmis-",
                    "takable to him, whatever the circumstances hap-",
                    "pened to be at that particular moment in time."
                )
            ),
            fallbackTitle = null
        )

        val text = textOf(book).joinToString(" ")
        assertTrue(text.contains("unmistakable"), "слово со швом: $text")
        assertTrue(text.contains("happened"), "второе слово со швом: $text")
        assertTrue(!text.contains("- "), "дефис переноса остался в тексте: $text")
    }

    /**
     * ⚠️ Колонтитул и номер страницы — не текст книги. Пропущенные, они врезаются в середину
     * абзаца на каждой странице, и читается это как испорченный файл.
     */
    @Test
    fun `running heads and page numbers do not get into the text`() {
        val body = { n: Int ->
            listOf(
                "ANIMAL FARM",
                "Mr. Jones of the Manor Farm had locked the hen-houses for",
                "the night, but was too drunk to remember the popholes $n.",
                "$n"
            )
        }
        val book = PdfParser.parse(pdfOf(body(1), body(2), body(3), body(4)), fallbackTitle = null)

        val paragraphs = textOf(book)
        assertTrue(paragraphs.none { it.contains("ANIMAL FARM") }, "колонтитул попал в текст: $paragraphs")
        assertTrue(paragraphs.none { it.trim().matches(Regex("\\d+")) }, "номер страницы попал в текст: $paragraphs")
        assertTrue(paragraphs.any { it.contains("Mr. Jones") }, "текст книги пропал: $paragraphs")
    }

    /** Скан — это картинки, а не книга: отказ словами лучше книги из нуля страниц. */
    @Test
    fun `a pdf without text is refused by name`() {
        val document = PDDocument()
        document.addPage(PDPage())
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()

        val failure = assertFailsWith<BadRequestException> {
            PdfParser.parse(out.toByteArray(), fallbackTitle = null)
        }
        assertTrue(failure.message!!.contains("скан"), "ошибка обязана объяснять причину: ${failure.message}")
    }

    @Test
    fun `a file that is not a pdf at all is refused rather than parsed`() {
        assertFailsWith<BadRequestException> {
            PdfParser.parse("это просто текст, а не PDF".toByteArray(), fallbackTitle = null)
        }
    }

    /** Название из метаданных важнее имени файла: файл называют как попало, книгу — нет. */
    @Test
    fun `the title comes from the document itself when it has one`() {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        PDPageContentStream(document, page).use { content ->
            content.beginText()
            content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
            content.newLineAtOffset(50f, 750f)
            content.showText("Somewhere far away a rocket bomb exploded.")
            content.endText()
        }
        document.documentInformation.title = "Nineteen Eighty-Four"
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()

        val book = PdfParser.parse(out.toByteArray(), fallbackTitle = "scan_0001")
        assertEquals("Nineteen Eighty-Four", book.title)
        assertEquals("PDF", book.format)
        assertEquals(BlockKind.PARAGRAPH, book.chapters.first().blocks.first().kind)
    }
}
