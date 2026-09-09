package n.startapp.services.lexical

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import n.startapp.repositories.FlashcardRepository
import n.startapp.repositories.LexicalEntryRepository
import n.startapp.repositories.SavedWordRepository
import n.startapp.services.LookupService
import n.startapp.services.context.ContextAnalysisService
import org.slf4j.LoggerFactory

/**
 * Даёт слову, сохранённому из книги, то самое значение, в котором оно там стояло.
 *
 * Читатель нажимает слово ради перевода, а не ради выбора смысла: статьи у слова может ещё не
 * быть вовсе, а если есть — быстрая подсказка сопоставляет значение по переводу и честно
 * промахивается, когда двум смыслам подходит одно русское слово. Раньше в обоих случаях
 * подставлялось **первое** значение статьи, и в словаре оседали карточки про другой смысл —
 * «лошадка (баскетбольная игра)» вместо лошади.
 *
 * ⚠️ Статья строится **только для сохранённого** слова, а не для каждого нажатого. Нажатий за
 * главу сотни, статья стоит минуту работы модели и живёт в корпусе навсегда: строить их на
 * каждый тап значило бы платить за девяносто девять слов, которые человек прочитал и забыл.
 *
 * ⚠️ Значение выбирает полный разбор (`analyze`), а не быстрая подсказка. Он стоит одного
 * вызова модели — но один раз на сохранённое слово, а не на каждое нажатие, — и сопоставляет
 * смысл по **английскому определению**, то есть тем же способом, каким его выбрал бы человек,
 * открывший статью.
 */
class SavedSenseResolver(
    private val saved: SavedWordRepository,
    private val entries: LexicalEntryRepository,
    private val lookup: LookupService,
    private val context: ContextAnalysisService,
    private val flashcards: FlashcardRepository
) {

    private val logger = LoggerFactory.getLogger(SavedSenseResolver::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * ⚠️ Не больше двух статей одновременно.
     *
     * Каждая — это скрейперы и вызов модели, а очередь у них общая с живым поиском. Читатель,
     * сохранивший десять слов подряд, иначе занял бы её целиком и подвесил бы поиск всем
     * остальным ради своих карточек.
     */
    private val slots = Semaphore(2)

    /** Слово только что сохранили — значение подбирается в фоне, ответ ждать не надо. */
    fun schedule(row: SavedWordRepository.ContextRow) {
        scope.launch {
            runCatching { resolve(row) }
                .onFailure { logger.warn("Не вышло выбрать значение для '${row.word}': ${it.message}") }
        }
    }

    /**
     * Хвост, оставшийся с прошлого запуска.
     *
     * Статья пишется минуту, а деплой — это рестарт: слово, сохранённое перед ним, иначе так и
     * осталось бы без значения навсегда.
     */
    suspend fun sweep(limit: Int = 50): Int {
        val rows = runCatching { saved.rowsAwaitingContextSense(limit) }
            .onFailure { logger.warn("Не удалось прочитать слова, ждущие значения: ${it.message}") }
            .getOrDefault(emptyList())
        var done = 0
        for (row in rows) {
            if (runCatching { resolve(row) }.getOrDefault(false)) done++
        }
        if (done > 0) logger.info("Значение по контексту подобрано для {} слов", done)
        return done
    }

    /** @return проставлено ли значение. */
    suspend fun resolve(row: SavedWordRepository.ContextRow): Boolean = slots.withPermit {
        if (row.sentence.isBlank()) return false

        // Статья нужна целиком: выбирать значение не из чего, пока её нет.
        var entry = entries.findLatestByLemma(row.word)
        if (entry == null) {
            val outcome = runCatching { lookup.warm(row.word) }.getOrNull()
            if (outcome == LookupService.WarmOutcome.NOT_FOUND) {
                // Слова нет ни в одном словаре: имя собственное, опечатка, выдуманное слово.
                // Строка остаётся с переводом из подсказки — это лучше пустой карточки, и
                // повторять поход в словари за ним незачем.
                logger.info("Статьи для '{}' нет ни в одном словаре — оставляем перевод из книги", row.word)
                return false
            }
            entry = entries.findLatestByLemma(row.word)
        }
        if (entry == null) return false

        val senseId = senseFor(row, entry) ?: return false
        val wording = SenseWording.of(entry, senseId) ?: return false

        val pinned = saved.pinSense(
            id = row.id,
            userId = row.userId,
            senseId = senseId,
            translation = wording.translation,
            definition = wording.definition,
            example = wording.example
        )
        if (!pinned) return false

        // Карточку, заведённую вместе со словом, надо переставить туда же: иначе список слов и
        // повторение спорят друг с другом про один и тот же смысл.
        runCatching {
            flashcards.repinToSense(
                userId = row.userId,
                word = row.word,
                senseId = senseId,
                translation = wording.translation,
                definition = wording.definition,
                example = wording.example,
                phonetic = wording.phonetic,
                audioUrl = wording.audioUrl
            )
        }.onFailure { logger.warn("Карточка '${row.word}' осталась на прежнем значении: ${it.message}") }

        return true
    }

    /**
     * Какое значение имелось в виду.
     *
     * Порядок попыток — от точного к дешёвому: полный разбор предложения, потом сопоставление
     * по русскому переводу подсказки, и только потом первое значение **нужной части речи**.
     * ⚠️ Последний шаг не «первое значение статьи»: `lead`-глагол и `lead`-металл — разные
     * слова, и часть речи здесь известна точно.
     */
    private suspend fun senseFor(row: SavedWordRepository.ContextRow, entry: n.startapp.models.lexical.LexicalEntry): String? {
        val analysed = runCatching {
            context.analyze(row.sentence, row.tokenIndex, row.word)
        }.onFailure { logger.info("Разбор для '${row.word}' не удался: ${it.message}") }
            .getOrNull()

        analysed?.senseId?.let { return it }

        val pos = analysed?.pos ?: row.pos
        return SenseBackfill.chooseInPos(entry, pos)
    }
}
