package n.startapp.services.reader.parse

import n.startapp.exceptions.BadRequestException
import n.startapp.models.reader.BlockKind
import n.startapp.services.reader.ParsedBlock
import n.startapp.services.reader.ParsedBook
import n.startapp.services.reader.ParsedChapter
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.text.PDFTextStripper

/**
 * PDF: страницы с координатами букв, из которых надо собрать обратно абзацы.
 *
 * У остальных форматов абзац написан в разметке — `<p>`, пустая строка, тег FB2. В PDF его нет:
 * есть строки, разложенные по листу, и склеивать их в абзацы приходится по косвенным признакам.
 * Поэтому здесь больше эвристик, чем во всех остальных парсерах вместе, и каждая отвечает за
 * конкретную вещь, которая иначе приезжает в книгу мусором.
 *
 * ⚠️ Сканы не поддерживаются и поддержаны быть не могут: в них нет текста вообще, только
 * картинки страниц. Такой файл отклоняется словами про скан, а не пустой книгой — «не получилось»
 * читается лучше, чем «книга из нуля страниц».
 */
object PdfParser {

    /**
     * Строка короче этой доли обычной — конец абзаца.
     *
     * Единственный признак, который переживает вёрстку: у полного абзаца строки выровнены по
     * правому краю, а последняя обрывается там, где кончился текст. Порог намеренно низкий:
     * лишний разрыв абзаца читается как авторский, а склеенные абзацы — как сломанный парсер.
     */
    private const val SHORT_LINE_RATIO = 0.72

    /**
     * Строка короче этой доли обычной кончает абзац сама по себе.
     *
     * Так стоят заголовки и обрываются последние строки: продолжать абзац после трети строки
     * значит склеить главу с её названием.
     */
    private const val HARD_BREAK_RATIO = 0.45

    /** Чем может кончиться мысль. Строка, оборванная на «к» или «и», не кончилась. */
    private val SENTENCE_END = setOf('.', '!', '?', '…', ':', ';', '»', '"', ')', '’')

    /** На скольких страницах строка должна повториться, чтобы считаться колонтитулом. */
    private const val FURNITURE_SHARE = 0.5

    /** Голый номер страницы. Ни один абзац так не выглядит. */
    private val PAGE_NUMBER = Regex("""^[-–—\s]*\d{1,4}[-–—\s]*$""")

    /** Цифры в колонтитуле меняются от страницы к странице, остальное — нет. */
    private val DIGITS = Regex("""\d+""")

    fun parse(bytes: ByteArray, fallbackTitle: String?): ParsedBook {
        val document = runCatching { Loader.loadPDF(bytes) }
            .getOrElse {
                throw BadRequestException("Не удалось открыть PDF: файл повреждён или защищён паролем")
            }

        document.use { pdf ->
            if (pdf.isEncrypted) {
                throw BadRequestException("PDF защищён паролем — снимите защиту и загрузите снова")
            }

            val pages = readPages(pdf)
            if (pages.all { page -> page.none { it.text.isNotEmpty() } }) {
                throw BadRequestException(
                    "В этом PDF нет текста — похоже, это скан. Распознайте его (OCR) и загрузите снова"
                )
            }

            val furniture = furnitureOf(pages)
            val paragraphs = reflow(pages, furniture)
            if (paragraphs.isEmpty()) throw BadRequestException("В файле нечего читать")

            val outline = outlineOf(pdf)
            return ParsedBook(
                title = title(pdf, fallbackTitle, paragraphs.first().text),
                author = pdf.documentInformation?.author?.trim()?.takeIf { it.isNotBlank() },
                language = null,
                format = "PDF",
                chapters = chapters(paragraphs, outline)
            )
        }
    }

    /** Чем PDFBox помечает начало абзаца: такого символа в тексте книги не бывает. */
    private const val PARAGRAPH_MARK = "\u0000"

    /** Абзац и страница, на которой он начался: по ней потом раскладываются главы. */
    private data class Piece(val text: String, val page: Int)

    /** Строка страницы и то, что PDFBox считает её началом нового абзаца. */
    private data class Line(val text: String, val starts: Boolean)

    private fun readPages(pdf: PDDocument): List<List<Line>> {
        val stripper = PDFTextStripper().apply {
            // ⚠️ Без сортировки по координатам двухколоночная страница приезжает вперемешку:
            // строка левой колонки, строка правой, и так до конца.
            sortByPosition = true
            lineSeparator = "\n"
            // ⚠️ Метка абзаца от самой библиотеки: PDFBox видит отступы и вертикальные пробелы,
            // о которых по одному тексту не догадаться. Её признак — первый и самый надёжный;
            // длина строки остаётся вторым, для файлов, где абзац ничем не размечен.
            paragraphStart = PARAGRAPH_MARK
        }

        return (1..pdf.numberOfPages).map { number ->
            stripper.startPage = number
            stripper.endPage = number
            val raw = runCatching { stripper.getText(pdf) }.getOrDefault("")
            /**
             * ⚠️ Пустая строка **сохраняется**, а не выбрасывается.
             *
             * Вертикальный пробел между абзацами — единственный признак разрыва, который стоит
             * в самом файле, а не выводится из длины строки. Выброшенный вместе с прочим
             * мусором, он оставлял два абзаца склеенными и заставлял догадываться по ширине.
             */
            raw.split('\n')
                // Метка снимается до чистки: `TextNormaliser` выбрасывает невидимые символы,
                // а вместе с ними выбросил бы и её.
                .map { line ->
                    Line(
                        text = TextNormaliser.clean(line.removePrefix(PARAGRAPH_MARK)),
                        starts = line.startsWith(PARAGRAPH_MARK)
                    )
                }
                .dropWhile { it.text.isEmpty() }
                .dropLastWhile { it.text.isEmpty() }
        }
    }

    /**
     * Колонтитулы: то, что стоит на каждой странице и книгой не является.
     *
     * Считаются по первой и последней строке страницы — там они и живут. Сравнение идёт с
     * замазанными цифрами, иначе «Глава 3 · 47» и «Глава 3 · 48» окажутся разными строками и
     * останутся в тексте обе.
     */
    private fun furnitureOf(pages: List<List<Line>>): Set<String> {
        if (pages.size < 4) return emptySet()
        val counts = mutableMapOf<String, Int>()
        for (page in pages) {
            val edges = listOfNotNull(
                page.firstOrNull { it.text.isNotEmpty() }?.text,
                page.lastOrNull { it.text.isNotEmpty() }?.text
            ).distinct()
            for (line in edges) counts.merge(mask(line), 1, Int::plus)
        }
        val threshold = (pages.size * FURNITURE_SHARE).toInt().coerceAtLeast(2)
        return counts.filterValues { it >= threshold }.keys
    }

    private fun mask(line: String): String = DIGITS.replace(line.trim(), "#")

    /**
     * Строки обратно в абзацы.
     *
     * Разрыв ставится там, где предыдущая строка короче обычной: у выровненного абзаца так
     * обрывается только последняя строка. Перенос по слогам склеивается: «прекрас-\nный» — это
     * одно слово, и в словаре его иначе не найти.
     */
    private fun reflow(pages: List<List<Line>>, furniture: Set<String>): List<Piece> {
        val width = medianWidth(pages)
        val out = mutableListOf<Piece>()
        val buffer = StringBuilder()
        var startedOn = 1
        var lastWasShort = false
        var lastEndedSentence = false
        var forcedBreak = false

        fun flush() {
            val text = TextNormaliser.clean(buffer.toString())
            if (text.isNotEmpty()) out += Piece(text, startedOn)
            buffer.setLength(0)
        }

        for ((index, page) in pages.withIndex()) {
            val number = index + 1
            for (entry in page) {
                val line = entry.text
                if (line.isEmpty()) {
                    // Пробел между абзацами: разрыв стоит в файле, гадать по ширине не нужно.
                    forcedBreak = true
                    continue
                }
                if (mask(line) in furniture || PAGE_NUMBER.matches(line)) continue

                /**
                 * ⚠️ Обрыв строки — ещё не конец абзаца.
                 *
                 * Признаки вёрстки (короткая строка, метка PDFBox) в документе с полуторным
                 * интервалом и рваным правым краем срабатывают на каждой второй строке, и
                 * абзац рассыпался на обрывки: «требования к» отдельно, «приложению;»
                 * отдельно. Поэтому они засчитываются, только если предыдущая строка **могла**
                 * кончить мысль — стоит точка, двоеточие, точка с запятой, кавычка. Без этого
                 * рвётся лишь то, что и в файле разорвано пустой строкой, или строка,
                 * оборванная совсем коротко: так кончаются абзацы и стоят заголовки.
                 */
                val veryShort = lastWasShort && buffer.length < width * HARD_BREAK_RATIO
                val looksDone = lastEndedSentence && (lastWasShort || entry.starts)

                if (buffer.isEmpty()) {
                    startedOn = number
                } else if (forcedBreak || looksDone || veryShort) {
                    flush()
                    startedOn = number
                }
                forcedBreak = false

                if (buffer.isNotEmpty()) {
                    val tail = buffer.last()
                    if (tail == '-' || tail == '­') {
                        // Перенос: дефис уходит вместе со швом, но только перед строчной буквой —
                        // «военно-морской» и «Санкт-Петербург» переносятся по-разному.
                        if (line.firstOrNull()?.isLowerCase() == true) {
                            buffer.setLength(buffer.length - 1)
                        } else {
                            buffer.append(' ')
                        }
                    } else {
                        buffer.append(' ')
                    }
                }
                buffer.append(line)
                lastWasShort = line.length < width * SHORT_LINE_RATIO
                lastEndedSentence = line.lastOrNull() in SENTENCE_END
            }
        }
        flush()
        return out
    }

    /**
     * Обычная длина строки в этом файле.
     *
     * Медиана, а не среднее: заголовки и обрывки в конце абзацев тянут среднее вниз ровно там,
     * где от порога и требуется устойчивость.
     */
    private fun medianWidth(pages: List<List<Line>>): Double {
        val lengths = pages.flatten().map { it.text.length }.filter { it > 0 }.sorted()
        if (lengths.isEmpty()) return 1.0
        return lengths[lengths.size / 2].toDouble()
    }

    /** Заголовки оглавления и страницы, с которых они начинаются. */
    private fun outlineOf(pdf: PDDocument): List<Pair<Int, String>> {
        val root = pdf.documentCatalog?.documentOutline ?: return emptyList()
        val found = mutableListOf<Pair<Int, String>>()

        var node: PDOutlineItem? = root.firstChild
        while (node != null) {
            val item = node
            val title = item.title?.let { TextNormaliser.clean(it) }?.takeIf { it.isNotBlank() }
            val page = runCatching { item.findDestinationPage(pdf) }.getOrNull()
                ?.let { pdf.pages.indexOf(it) + 1 }
                ?.takeIf { it > 0 }
            if (title != null && page != null) found += page to title
            // ⚠️ Только верхний уровень: подпункты оглавления PDF — это разделы внутри глав, и
            // списком они превращают оглавление читалки в перечень абзацев.
            node = item.nextSibling
        }

        return found.distinctBy { it.first }.sortedBy { it.first }
    }

    private fun chapters(pieces: List<Piece>, outline: List<Pair<Int, String>>): List<ParsedChapter> {
        if (outline.isEmpty()) {
            // ⚠️ Одна глава, а не «страница 1», «страница 2». Номер страницы PDF — не название
            // главы, и оглавление из четырёхсот таких строк не помогает никому.
            return listOf(ParsedChapter(null, pieces.map { ParsedBlock(BlockKind.PARAGRAPH, it.text) }))
        }

        val chapters = mutableListOf<ParsedChapter>()
        var blocks = mutableListOf<ParsedBlock>()
        var title: String? = null
        var next = 0

        for (piece in pieces) {
            while (next < outline.size && piece.page >= outline[next].first) {
                if (blocks.isNotEmpty()) chapters += ParsedChapter(title, blocks)
                val heading = outline[next].second
                title = heading
                blocks = mutableListOf(ParsedBlock(BlockKind.HEADING, heading))
                next++
            }
            // Заголовок из оглавления уже стоит блоком — второй раз абзацем он не нужен.
            if (piece.text != title) blocks += ParsedBlock(BlockKind.PARAGRAPH, piece.text)
        }
        if (blocks.isNotEmpty()) chapters += ParsedChapter(title, blocks)
        return chapters
    }

    private fun title(pdf: PDDocument, fallback: String?, firstParagraph: String): String {
        pdf.documentInformation?.title?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        fallback?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val words = firstParagraph.split(' ').take(8).joinToString(" ")
        return if (words.length < firstParagraph.length) "$words..." else words
    }
}
