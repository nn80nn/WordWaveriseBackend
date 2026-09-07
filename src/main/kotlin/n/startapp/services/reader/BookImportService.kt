package n.startapp.services.reader

import n.startapp.exceptions.BadRequestException
import n.startapp.models.reader.ImportResultDTO
import n.startapp.models.reader.ImportTextRequest
import n.startapp.repositories.BookRepository
import n.startapp.services.reader.parse.EpubParser
import n.startapp.services.reader.parse.Fb2Parser
import n.startapp.services.reader.parse.HtmlDocumentParser
import n.startapp.services.reader.parse.PlainTextParser
import n.startapp.services.reader.parse.ZipArchive
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Takes whatever arrived and turns it into a book in someone's library.
 *
 * The format is worked out from the bytes, not from the file name. A name is a hint people get
 * wrong constantly — `.txt` holding HTML, `.epub` that is really a zipped FB2, no extension at
 * all because it came from a share sheet — and the failure mode of trusting it is a book that
 * imports as one paragraph of angle brackets.
 */
class BookImportService(private val repository: BookRepository) {

    companion object {
        /**
         * Enough for any novel; refuses the scanned-textbook case that would land as one
         * unreadable block anyway.
         */
        const val MAX_UPLOAD_BYTES = 32 * 1024 * 1024

        private const val MAX_BLOCKS = 200_000
    }

    suspend fun importFile(userId: Int, fileName: String?, bytes: ByteArray): ImportResultDTO {
        if (bytes.isEmpty()) throw BadRequestException("Файл пуст")
        if (bytes.size > MAX_UPLOAD_BYTES) throw BadRequestException("Файл слишком большой")

        val parsed = parse(bytes, fileName)
        return store(userId, parsed)
    }

    suspend fun importText(userId: Int, request: ImportTextRequest): ImportResultDTO {
        if (request.text.isBlank()) throw BadRequestException("Текст пуст")
        val parsed = PlainTextParser.parse(
            text = request.text,
            title = request.title,
            author = request.author,
            format = "PASTE"
        )
        return store(userId, parsed)
    }

    private suspend fun store(userId: Int, parsed: ParsedBook): ImportResultDTO {
        val blockCount = parsed.chapters.sumOf { it.blocks.size }
        if (blockCount == 0) throw BadRequestException("В файле нечего читать")
        if (blockCount > MAX_BLOCKS) throw BadRequestException("Книга слишком большая")

        val hash = contentHash(parsed)

        // Re-uploading the book you are already reading is a normal thing to do — a new phone, a
        // lost download. The right answer is the copy you already have, still open where you left
        // it, not a second one at page one.
        repository.findByHash(userId, hash)?.let {
            return ImportResultDTO(book = it, alreadyExisted = true)
        }

        return ImportResultDTO(
            book = repository.insert(userId, parsed, hash),
            alreadyExisted = false
        )
    }

    /**
     * Hashes the normalised text, not the file.
     *
     * Two exports of the same book differ byte for byte — timestamps, cover compression, the
     * reader that produced them — while the text is identical. Hashing the upload would file the
     * same novel twice and split its reading position between the copies.
     */
    private fun contentHash(parsed: ParsedBook): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(parsed.title.toByteArray(Charsets.UTF_8))
        for (chapter in parsed.chapters) {
            for (block in chapter.blocks) {
                digest.update(block.text.toByteArray(Charsets.UTF_8))
                digest.update('\n'.code.toByte())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parse(bytes: ByteArray, fileName: String?): ParsedBook {
        val name = fileName?.lowercase().orEmpty()

        if (ZipArchive.looksLikeZip(bytes)) {
            val entries = ZipArchive.read(bytes)
            // A zipped FB2 is a zip with one .fb2 inside and no EPUB scaffolding — and it is what
            // Russian-language libraries hand out by default, so it is not an edge case here.
            val fb2 = entries.keys.firstOrNull { it.lowercase().endsWith(".fb2") }
            return when {
                entries.containsKey("META-INF/container.xml") -> EpubParser.parse(bytes)
                fb2 != null -> Fb2Parser.parse(entries.getValue(fb2))
                else -> EpubParser.parse(bytes)
            }
        }

        val text = decodeText(bytes)
        val head = text.take(2048)

        return when {
            head.contains("<FictionBook", ignoreCase = true) -> Fb2Parser.parse(bytes)
            head.contains("<html", ignoreCase = true) ||
                head.contains("<!doctype html", ignoreCase = true) ->
                HtmlDocumentParser.parse(text, fallbackTitle = baseName(name))
            else -> PlainTextParser.parse(text, title = baseName(name))
        }
    }

    /**
     * UTF-8 unless the bytes say otherwise.
     *
     * Decoding a windows-1251 file as UTF-8 does not throw by default — it silently produces a
     * book of replacement characters, which reads as a broken parser rather than a wrong charset.
     * Strict decoding is what makes the fallback reachable at all.
     */
    private fun decodeText(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, charset("windows-1251")) }
            .removePrefix("\uFEFF")
    }

    private fun baseName(fileName: String): String? = fileName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringBeforeLast('.')
        .replace('_', ' ')
        .trim()
        .takeIf { it.isNotBlank() }
}
