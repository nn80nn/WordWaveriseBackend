package n.startapp.repositories

import kotlinx.coroutines.runBlocking
import n.startapp.database.TestDatabase
import n.startapp.database.tables.Users
import n.startapp.models.auth.SaveContext
import n.startapp.models.lexical.LexicalEntry
import n.startapp.models.lexical.PosGroup
import n.startapp.models.lexical.Sense
import n.startapp.services.lexical.SenseBackfill
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Слово из книги ждёт **своего** значения, а не первого попавшегося.
 *
 * Читатель нажимает слово ради перевода: статьи может не быть вовсе, а быстрая подсказка честно
 * промахивается там, где двум смыслам подходит один русский перевод. Подставленное первое
 * значение выглядит как выбор, читается как выбор и кладёт в словарь карточку про другой смысл.
 * Поэтому у такой строки значение остаётся пустым, а предложение — сохранённым.
 */
class SavedWordContextTest {

    private val words = SavedWordRepository()

    private fun <T> onFreshDatabase(block: () -> T): T = TestDatabase.fresh("context", block)

    private fun user(): Int = transaction {
        Users.insert {
            it[email] = "c${System.nanoTime()}@t.dev"
            it[passwordHash] = "x"
        }[Users.id]
    }

    @Test
    fun `a word saved from a book keeps the sentence it came from`() = onFreshDatabase {
        val me = user()
        runBlocking {
            words.save(
                userId = me,
                word = "horse",
                translation = "лошадей",
                context = SaveContext(
                    sentence = "After the horses came Muriel, the white goat.",
                    tokenIndex = 2,
                    pos = "noun"
                )
            )
        }

        val waiting = runBlocking { words.rowsAwaitingContextSense() }
        assertEquals(1, waiting.size)
        assertEquals("horse", waiting.first().word)
        assertEquals(2, waiting.first().tokenIndex)
        assertEquals("noun", waiting.first().pos)
    }

    /**
     * ⚠️ Главная граница: общая миграция ставит первое значение статьи, и строку с контекстом
     * она забирать не должна — иначе слово получит смысл раньше, чем его успеют выбрать.
     */
    @Test
    fun `the first-sense migration does not touch a row that has a sentence`() = onFreshDatabase {
        val me = user()
        runBlocking {
            words.save(me, "horse", translation = "лошадей", context = SaveContext("After the horses came Muriel."))
            words.save(me, "settle", translation = "решать")
        }

        val forMigration = runBlocking { words.rowsNeedingSense() }
        assertTrue(forMigration.none { it.word == "horse" }, "слово с предложением ждёт разбора, а не первого значения")
        assertTrue(forMigration.any { it.word == "settle" }, "обычное слово по-прежнему достаётся миграции")
    }

    @Test
    fun `a resolved word leaves the queue`() = onFreshDatabase {
        val me = user()
        val saved = runBlocking {
            words.save(me, "horse", translation = "лошадей", context = SaveContext("After the horses came Muriel."))
        }
        runBlocking {
            words.pinSense(saved!!.id, me, "n1", "лошадь", "a large four-legged mammal", null)
        }

        assertTrue(runBlocking { words.rowsAwaitingContextSense() }.isEmpty())
    }

    /** Часть речи известна точно — и запасное значение обязано браться из её группы. */
    @Test
    fun `the fallback sense comes from the part of speech the sentence named`() {
        val article = LexicalEntry(
            lemma = "lead",
            posGroups = listOf(
                PosGroup(
                    pos = "noun",
                    posRu = "существительное",
                    senses = listOf(Sense(id = "n1", definitionEn = "the heavy grey metal", definitionRu = ""))
                ),
                PosGroup(
                    pos = "verb",
                    posRu = "глагол",
                    senses = listOf(Sense(id = "v1", definitionEn = "to be in charge", definitionRu = ""))
                )
            )
        )

        assertEquals("v1", SenseBackfill.chooseInPos(article, "verb"))
        assertEquals("n1", SenseBackfill.chooseInPos(article, "noun"))
        // Часть речи, которой в статье нет, не повод не сохранить слово вовсе.
        assertEquals("n1", SenseBackfill.chooseInPos(article, "adverb"))
        assertNull(SenseBackfill.chooseInPos(null, "noun"))
    }
}
