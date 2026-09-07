package n.startapp.services.reader.parse

import n.startapp.exceptions.BadRequestException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Reads a zip fully into memory, with the limits an upload endpoint has to have.
 *
 * EPUB and zipped FB2 both need access by entry name, and the whole point is a few hundred
 * kilobytes of text; holding that is cheaper than a temp file with a lifetime to manage. The
 * caps are not tuning — they are what stops a 200-byte upload from asking for 40 GB of heap.
 */
object ZipArchive {

    private const val MAX_ENTRIES = 5_000
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 24L * 1024 * 1024

    fun read(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (entries.size >= MAX_ENTRIES) throw BadRequestException("Архив слишком большой")

                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var entrySize = 0L
                while (true) {
                    val read = zip.read(buffer)
                    if (read <= 0) break
                    entrySize += read
                    total += read
                    if (entrySize > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                        throw BadRequestException("Архив слишком большой")
                    }
                    out.write(buffer, 0, read)
                }

                // Nothing here is ever written to disk, so a climbing name cannot escape onto
                // the filesystem — but it is still a signal we want nothing to do with.
                val name = entry.name.replace('\\', '/')
                if (name.split('/').any { it == ".." }) continue
                entries[name] = out.toByteArray()
            }
        }

        if (entries.isEmpty()) throw BadRequestException("Архив пуст или повреждён")
        return entries
    }

    fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
}
