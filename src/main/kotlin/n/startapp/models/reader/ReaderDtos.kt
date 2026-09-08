package n.startapp.models.reader

import kotlinx.serialization.Serializable
import n.startapp.services.context.Token

/** What a block is, as far as rendering cares. Anything richer is markup we do not keep. */
enum class BlockKind { HEADING, PARAGRAPH, QUOTE, LIST_ITEM }

/**
 * One sentence of a block, with the tokens a tap can land on.
 *
 * ⚠️ The sentence, not the paragraph, is the unit sent to `/api/v2/context/analyze`, and the
 * indices here are the ones that endpoint will re-derive from the same text with the same
 * [n.startapp.services.context.Tokenizer]. That is the whole reason tokenisation lives on the
 * server: an index minted by a second implementation resolves to a different word, and the
 * reader gets "you tapped 'lead' and got 'the' explained".
 *
 * It is also what makes reading cheap. The analysis cache is keyed by (text, tokenIndex) and
 * knows nothing about who asked, so the second person to tap that line in that book pays
 * nothing — which is an argument for a shared catalogue, not just for a cache.
 */
@Serializable
data class SentenceDTO(
    val index: Int,
    val text: String,
    /** Character offsets into the block, so a client can highlight the sentence in place. */
    val start: Int,
    val end: Int,
    val tokens: List<Token> = emptyList()
)

@Serializable
data class BlockDTO(
    /** 0-based across the whole book — the address a reading position stores. */
    val ordinal: Int,
    val chapterIndex: Int,
    val kind: BlockKind,
    val text: String,
    val sentences: List<SentenceDTO> = emptyList()
)

@Serializable
data class ChapterDTO(
    val index: Int,
    val title: String?,
    val firstOrdinal: Int,
    val blockCount: Int
)

@Serializable
data class ReadingPositionDTO(
    val ordinal: Int,
    val chapterIndex: Int,
    /** 0..1, derived from the block count — never reported by a client. */
    val progress: Double,
    val updatedAt: String
)

@Serializable
data class BookDTO(
    val id: Int,
    val title: String,
    val author: String? = null,
    val language: String? = null,
    val format: String,
    val chapterCount: Int,
    val blockCount: Int,
    val wordCount: Int,
    val createdAt: String,
    val position: ReadingPositionDTO? = null
)

/** A book opened: everything needed to draw the table of contents and resume. */
@Serializable
data class BookDetailDTO(
    val book: BookDTO,
    val chapters: List<ChapterDTO> = emptyList()
)

/**
 * A window of blocks.
 *
 * Paged by block rather than by chapter because chapters are not a size: a novel's chapter is
 * two screens and a treatise's is forty, and a reader that must load the larger one before
 * showing the first line is a reader that stalls exactly where a person is waiting to read.
 */
@Serializable
data class BlockPageDTO(
    val bookId: Int,
    val from: Int,
    val blocks: List<BlockDTO> = emptyList(),
    /** Ordinal to ask for next, or null at the end of the book. */
    val nextOrdinal: Int? = null
)

/**
 * The result of an import.
 *
 * [alreadyExisted] is not a warning. Re-uploading the file you are already reading is a normal
 * thing to do — a new phone, a lost download — and the right answer is the book you already
 * have, still open at the page you left it on, rather than a second copy at page one.
 */
/**
 * Место, которое читатель отметил сам.
 *
 * [preview] — начало отмеченного абзаца: список закладок без него это список чисел, по которому
 * невозможно узнать ни одно из отмеченных мест.
 */
@Serializable
data class BookmarkDTO(
    val ordinal: Int,
    val chapterIndex: Int,
    val preview: String,
    val createdAt: String
)

@Serializable
data class SetBookmarkRequest(val ordinal: Int)

@Serializable
data class ImportResultDTO(
    val book: BookDTO,
    val alreadyExisted: Boolean
)

/** Pasted text, for the shortest path from "I have some English" to reading it. */
@Serializable
data class ImportTextRequest(
    val text: String,
    val title: String? = null,
    val author: String? = null
)

@Serializable
data class SetPositionRequest(val ordinal: Int)
